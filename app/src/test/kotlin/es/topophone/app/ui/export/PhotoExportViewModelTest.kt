package es.topophone.app.ui.export

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.app.log.RecordingLog
import es.topophone.app.ui.map.FakePointRepository
import es.topophone.app.ui.points.PointFixtures.manual
import es.topophone.app.ui.points.PointFixtures.measured
import es.topophone.core.model.point.PhotoOrigin
import es.topophone.core.model.point.PointPhoto
import es.topophone.core.model.project.ProjectCrs
import es.topophone.core.model.project.ProjectDefaults
import es.topophone.data.db.TopoPhoneDatabase
import es.topophone.data.photo.PhotoExporter
import es.topophone.data.photo.PhotoRepository
import es.topophone.data.project.RoomProjectRepository
import es.topophone.data.project.SaveResult
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.time.Instant
import java.time.ZoneOffset
import java.util.zip.ZipInputStream
import kotlin.random.Random
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

/** Las fotos de unos puntos y de dónde se leen sus ficheros; [files] sin la foto es un fichero que falta. */
private class ZipFakePhotos(private val byPoint: Map<String, List<PointPhoto>>, val files: Map<String, File>) :
    PhotoRepository by PhotoRepository.NONE {
    override suspend fun photosForExport(pointIds: List<String>): Map<String, List<PointPhoto>> = byPoint.filterKeys { it in pointIds }

    override fun file(photoId: String): File? = files[photoId]
}

/** Exportar las fotos: qué fotos, recuento y tamaño, sin fotos, aviso de 1 GB, nombre, escribir entero o nada y resumen (S056). */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class PhotoExportViewModelTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val db = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext<Context>(),
        TopoPhoneDatabase::class.java,
    ).build()
    private val projects = RoomProjectRepository(db)
    private val projectId = newProject()
    private val points = listOf(
        measured("2", projectId = projectId, description = "Peña", createdAtMs = 2_000),
        measured("1", projectId = projectId, createdAtMs = 1_000).copy(hidden = true),
        manual("E1", projectId = projectId, createdAtMs = 3_000),
        measured("borrado", projectId = projectId, createdAtMs = 4_000).copy(deletedAtMs = 5_000),
    )
    private val repository = FakePointRepository(points)
    private val selection = ExportSelection()
    private val log = RecordingLog()
    private val contents = HashMap<String, ByteArray>()

    private fun newProject(): String = runBlocking {
        projects.create(ProjectDefaults().toDraft().copy(name = "Obra", crs = ProjectCrs.ETRS89_UTM30))
            .shouldBeInstanceOf<SaveResult.Saved>().id
    }

    /** Una foto con su fichero en disco (salvo [onDisk] falso); el contenido queda en [contents]. */
    private fun photo(id: String, pointId: String, size: Int = 500, onDisk: Boolean = true, declared: Long = size.toLong()): PointPhoto {
        val bytes = Random(id.hashCode()).nextBytes(size)
        contents[id] = bytes
        if (onDisk) disk[id] = folder.newFile("$id.jpg").also { it.writeBytes(bytes) }
        return PointPhoto(id, pointId, id, PhotoOrigin.CAMERA, 1_794_471_330_000, 1_000, declared, 640, 480)
    }

    private val disk = HashMap<String, File>()

    private fun photos(vararg byPoint: Pair<String, List<PointPhoto>>) = ZipFakePhotos(byPoint.toMap(), disk)

    /** Fotos de ejemplo: 2 en «2», 1 en el oculto «1», ninguna en E1 y 1 en el borrado, que no debe contar. */
    private fun example() = photos(
        "id-2" to listOf(photo("a", "id-2", 600), photo("b", "id-2", 400)),
        "id-1" to listOf(photo("c", "id-1", 300)),
        "id-borrado" to listOf(photo("d", "id-borrado", 100)),
    )

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        db.close()
    }

    private fun viewModel(
        selectionOnly: Boolean = false,
        fakes: PhotoRepository = example(),
        freeSpace: (File) -> Long = { Long.MAX_VALUE },
    ): PhotoExportViewModel {
        val vm = PhotoExportViewModel(repository, projects, selection, fakes, log)
        vm.exporter = PhotoExporter(fakes, freeSpace)
        CoroutineScope(Dispatchers.Unconfined).launch { vm.state.collect {} }
        vm.start(projectId, selectionOnly)
        return vm
    }

    private fun PhotoExportViewModel.await(predicate: (PhotoExportUiState) -> Boolean): PhotoExportUiState =
        runBlocking { withTimeout(5_000) { state.first(predicate) } }

    private fun work() = folder.newFolder()

    private fun entries(zip: ByteArray): Map<String, ByteArray> {
        val files = LinkedHashMap<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(zip)).use { stream ->
            generateSequence { stream.nextEntry }.forEach { files[it.name] = stream.readBytes() }
        }
        return files
    }

    @Test
    fun `S056 RF-1 desde el proyecto van las fotos de los activos y no las de la papelera`() {
        val state = viewModel().await { it.loaded }

        state.photoCount shouldBe 3 // a y b del punto 2 y c del oculto 1; la d es de la papelera
        state.pointCount shouldBe 2
        state.projectName shouldBe "Obra"
        state.canExport shouldBe true
    }

    @Test
    fun `S056 RF-2 desde la seleccion solo van las fotos de los puntos elegidos`() {
        selection.ids = setOf("id-2", "id-E1", "id-borrado")

        val state = viewModel(selectionOnly = true).await { it.loaded }

        state.photoCount shouldBe 2
        state.pointCount shouldBe 1
    }

    @Test
    fun `S056 RF-3 la pantalla da las fotos, los puntos y lo que ocupan`() {
        val state = viewModel().await { it.loaded }

        state.photoBytes shouldBe 1_300L
        (state.estimatedBytes > state.photoBytes) shouldBe true
    }

    @Test
    fun `S056 RF-8 sin fotos se dice y no se escribe nada`() {
        val vm = viewModel(fakes = photos()).also { it.await { s -> s.loaded } }
        val out = ByteArrayOutputStream()
        val dir = work()

        vm.state.value.canExport shouldBe false
        runBlocking { vm.writeTo(out, dir) } shouldBe false
        runBlocking { vm.writeFile(File(dir, "x.zip")) } shouldBe false

        out.size() shouldBe 0
        dir.listFiles().orEmpty().toList() shouldBe emptyList()
    }

    @Test
    fun `S056 RF-8 una seleccion sin fotos tampoco exporta`() {
        selection.ids = setOf("id-E1")

        viewModel(selectionOnly = true).await { it.loaded }.canExport shouldBe false
    }

    @Test
    fun `S056 RF-10 con mas de 1 GB se pide confirmar y con menos no`() {
        val big = photos("id-2" to listOf(photo("big", "id-2", 100, declared = 2L shl 30)))
        val small = photos("id-2" to listOf(photo("small", "id-2", 100, declared = 1L shl 20)))

        viewModel(fakes = big).await { it.loaded }.needsSizeConfirmation shouldBe true
        viewModel(fakes = small).await { it.loaded }.needsSizeConfirmation shouldBe false
    }

    @Test
    fun `S056 RF-11 sin sitio no se escribe nada y se dice`() {
        val vm = viewModel(freeSpace = { 0L })
        vm.await { it.loaded }
        val out = ByteArrayOutputStream()
        val dir = work()

        runBlocking { vm.writeTo(out, dir) } shouldBe false

        out.size() shouldBe 0
        dir.listFiles().orEmpty().toList() shouldBe emptyList()
        vm.await { it.noSpace }.noSpace shouldBe true
        vm.report(null)
        vm.await { !it.noSpace }.noSpace shouldBe false
    }

    @Test
    fun `S056 RF-4 el ZIP guardado lleva las fotos como estan y el indice al final`() {
        val vm = viewModel()
        vm.await { it.loaded }
        val out = ByteArrayOutputStream()

        runBlocking { vm.writeTo(out, work()) } shouldBe true

        val files = entries(out.toByteArray())
        files.keys.toList() shouldBe listOf("1_1.jpg", "2_1.jpg", "2_2.jpg", "fotos.csv")
        files.getValue("1_1.jpg").contentEquals(contents.getValue("c")) shouldBe true // los puntos van en orden de creación
        files.getValue("2_1.jpg").contentEquals(contents.getValue("a")) shouldBe true
        files.getValue("2_2.jpg").contentEquals(contents.getValue("b")) shouldBe true
    }

    @Test
    fun `S056 RF-12 el nombre propuesto es proyecto, fotos y fecha con hora local`() {
        val vm = viewModel()
        vm.await { it.loaded }

        vm.fileName(Instant.parse("2026-10-28T10:30:00Z"), ZoneOffset.UTC) shouldBe "Obra_fotos_20261028-1030.zip"
    }

    @Test
    fun `S056 RF-12 compartir escribe el ZIP entero en el fichero que se le da`() {
        val vm = viewModel()
        vm.await { it.loaded }
        val target = File(work(), "fotos.zip")

        runBlocking { vm.writeFile(target) } shouldBe true

        entries(target.readBytes()).keys.toList() shouldBe listOf("1_1.jpg", "2_1.jpg", "2_2.jpg", "fotos.csv")
    }

    @Test
    fun `S056 RF-13 el resumen lleva fotos, puntos, omitidas y el peso del ZIP`() {
        val vm = viewModel()
        vm.await { it.loaded }
        val out = ByteArrayOutputStream()

        runBlocking { vm.writeTo(out, work()) } shouldBe true

        val report = vm.await { it.report != null }.report.shouldNotBeNull()
        listOf(report.photos, report.points, report.omitted) shouldBe listOf(3, 2, 0)
        report.zipBytes shouldBe out.size().toLong()
    }

    @Test
    fun `S056 RF-9 la foto que falta no entra y se cuenta`() {
        val vm = viewModel(fakes = photos("id-2" to listOf(photo("a", "id-2"), photo("lost", "id-2", onDisk = false), photo("c", "id-2"))))
        vm.await { it.loaded }
        val out = ByteArrayOutputStream()

        runBlocking { vm.writeTo(out, work()) } shouldBe true

        entries(out.toByteArray()).keys.toList() shouldBe listOf("2_1.jpg", "2_3.jpg", "fotos.csv")
        val report = vm.await { it.report != null }.report.shouldNotBeNull()
        listOf(report.photos, report.omitted) shouldBe listOf(2, 1)
    }

    @Test
    fun `S056 RF-9 si ninguna se puede leer no hay ZIP y se cuentan`() {
        val vm = viewModel(fakes = photos("id-2" to listOf(photo("x", "id-2", onDisk = false), photo("y", "id-2", onDisk = false))))
        vm.await { it.loaded }
        val out = ByteArrayOutputStream()
        val dir = work()

        runBlocking { vm.writeTo(out, dir) } shouldBe false

        out.size() shouldBe 0
        dir.listFiles().orEmpty().toList() shouldBe emptyList()
        val report = vm.await { it.report != null }.report.shouldNotBeNull()
        listOf(report.photos, report.omitted) shouldBe listOf(0, 2)
    }

    @Test
    fun `S056 RF-11 si el destino falla no queda nada en la carpeta de trabajo`() {
        val vm = viewModel()
        vm.await { it.loaded }
        val failing = object : OutputStream() {
            override fun write(b: Int) = throw IOException("sin espacio")

            override fun write(b: ByteArray, off: Int, len: Int) = throw IOException("sin espacio")
        }
        val dir = work()

        runBlocking { vm.writeTo(failing, dir) } shouldBe false

        dir.listFiles().orEmpty().toList() shouldBe emptyList()
        vm.await { it.loaded }.report.shouldBeNull()
    }

    @Test
    fun `S056 RF-14 exportar no cambia el proyecto ni las fotos`() {
        val vm = viewModel()
        vm.await { it.loaded }
        val before = disk.mapValues { it.value.readBytes().toList() to it.value.lastModified() }
        val pointsBefore = runBlocking { repository.pointsForExport(projectId) }

        runBlocking { vm.writeTo(ByteArrayOutputStream(), work()) }

        disk.mapValues { it.value.readBytes().toList() to it.value.lastModified() } shouldBe before
        runBlocking { repository.pointsForExport(projectId) } shouldBe pointsBefore
    }

    @Test
    fun `S056 RF-12 el resultado se muestra y el registro no lleva nombres ni rutas`() {
        val vm = viewModel()
        vm.await { it.loaded }
        runBlocking { vm.writeTo(ByteArrayOutputStream(), work()) }

        vm.report(ExportResult.SAVED)
        vm.await { it.result == ExportResult.SAVED }
        vm.report(ExportResult.FAILED)

        vm.await { it.result == ExportResult.FAILED }
        log.messages.any { it.contains("Fotos exportadas: 3 fotos de 2 puntos") } shouldBe true
        log.messages.any { it.contains("No se pudieron exportar las fotos") } shouldBe true
        log.messages.none { it.contains("Peña") || it.contains(folder.root.name) || it.contains(".jpg") } shouldBe true
    }

    @Test
    fun `S056 RF-12 pedir otro guardado borra el resumen del anterior`() {
        val vm = viewModel()
        vm.await { it.loaded }
        runBlocking { vm.writeTo(ByteArrayOutputStream(), work()) } shouldBe true
        vm.report(ExportResult.SAVED)
        vm.await { it.result == ExportResult.SAVED && it.report != null }

        vm.report(null) // lo que hace la pantalla al pulsar Guardar; si el usuario cancela el selector no llega nada más

        val state = vm.await { it.result == null && it.report == null }
        state.canExport shouldBe true
    }
}
