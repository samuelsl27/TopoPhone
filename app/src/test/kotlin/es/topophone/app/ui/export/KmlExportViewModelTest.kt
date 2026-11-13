package es.topophone.app.ui.export

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.app.log.RecordingLog
import es.topophone.app.ui.map.FakePointRepository
import es.topophone.app.ui.points.FakeObjectLines
import es.topophone.app.ui.points.ObjectLines
import es.topophone.app.ui.points.PointFixtures.manual
import es.topophone.app.ui.points.PointFixtures.measured
import es.topophone.core.formats.kml.export.KmlFormat
import es.topophone.core.model.codes.CodeGeometry
import es.topophone.core.model.codes.CodeSymbol
import es.topophone.core.model.codes.PointCode
import es.topophone.core.model.line.LineVertex
import es.topophone.core.model.line.ProjectLine
import es.topophone.core.model.point.PhotoOrigin
import es.topophone.core.model.point.PointPhoto
import es.topophone.core.model.project.ProjectCrs
import es.topophone.core.model.project.ProjectDefaults
import es.topophone.data.db.TopoPhoneDatabase
import es.topophone.data.export.KmlExportPreferences
import es.topophone.data.export.KmlExportSettings
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
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

private class FakeKmlPreferences(initial: KmlExportSettings = KmlExportSettings()) : KmlExportPreferences {
    val current = MutableStateFlow(initial)
    var saved = 0

    override val settings: Flow<KmlExportSettings> = current

    override suspend fun save(settings: KmlExportSettings) {
        saved++
        current.value = settings
    }
}

/** Las fotos de unos puntos y de dónde se leen sus ficheros; [files] sin la foto es un fichero que falta. */
private class FakePhotos(private val byPoint: Map<String, List<PointPhoto>>, private val files: Map<String, File>) :
    PhotoRepository by PhotoRepository.NONE {
    override suspend fun photosForExport(pointIds: List<String>): Map<String, List<PointPhoto>> = byPoint.filterKeys { it in pointIds }

    override fun file(photoId: String): File? = files[photoId]
}

/** Exportar a KML o KMZ: qué se exporta, formato, altura, fotos, nombre, escribir entero o nada y resumen (S031/RF-20, RF-28 a RF-32). */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class KmlExportViewModelTest {

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
    private val preferences = FakeKmlPreferences()
    private val log = RecordingLog()
    private val bord = PointCode("BORD", "Bordillo", 0x1E88E5, CodeSymbol.CIRCLE)
    private val lines = FakeObjectLines(listOf(line("l1", false, "1", "2"), line("l2", true, "1", "2", "E1")))

    private fun line(id: String, closed: Boolean, vararg ids: String) = ProjectLine(
        id = id,
        projectId = projectId,
        name = id.uppercase(),
        code = bord,
        geometry = if (closed) CodeGeometry.POLYGON else CodeGeometry.LINE,
        closed = closed,
        hidden = false,
        active = false,
        vertices = ids.map { LineVertex("id-$it") },
        createdAtMs = 0,
    )

    private fun newProject(): String = runBlocking {
        projects.create(ProjectDefaults().toDraft().copy(name = "Obra", crs = ProjectCrs.ETRS89_UTM30))
            .shouldBeInstanceOf<SaveResult.Saved>().id
    }

    private fun photo(id: String, pointId: String, size: Long = 100) =
        PointPhoto(id, pointId, id, PhotoOrigin.CAMERA, 1_000, 1_000, size, 640, 480)

    private fun file(content: ByteArray) = folder.newFile().also { it.writeBytes(content) }

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        db.close()
    }

    private fun viewModel(
        selectionOnly: Boolean = false,
        withLines: Boolean = true,
        photos: PhotoRepository = PhotoRepository.NONE,
        prefs: KmlExportPreferences = preferences,
        freeSpace: (File) -> Long = { Long.MAX_VALUE },
    ): KmlExportViewModel {
        val vm = KmlExportViewModel(repository, projects, selection, prefs, photos, log, if (withLines) lines else ObjectLines.NONE)
        vm.freeSpace = freeSpace
        CoroutineScope(Dispatchers.Unconfined).launch { vm.state.collect {} }
        vm.start(projectId, selectionOnly)
        return vm
    }

    private fun KmlExportViewModel.await(predicate: (KmlExportUiState) -> Boolean): KmlExportUiState =
        runBlocking { withTimeout(5_000) { state.first(predicate) } }

    private fun work() = folder.newFolder()

    private fun written(vm: KmlExportViewModel): ByteArray {
        val out = ByteArrayOutputStream()
        runBlocking { vm.writeTo(out, work()) } shouldBe true
        return out.toByteArray()
    }

    private fun text(vm: KmlExportViewModel) = String(written(vm), Charsets.UTF_8)

    private fun entries(kmz: ByteArray): Map<String, ByteArray> {
        val files = LinkedHashMap<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(kmz)).use { zip ->
            generateSequence { zip.nextEntry }.forEach { files[it.name] = zip.readBytes() }
        }
        return files
    }

    private fun marks(text: String) = Regex("<Placemark>").findAll(text).count()

    @Test
    fun `S031 RF-20 desde el proyecto se exportan todos los activos, ocultos incluidos, y nunca la papelera`() {
        val state = viewModel().await { it.loaded }

        state.pointCount shouldBe 3
        state.projectName shouldBe "Obra"
        state.canExport shouldBe true
    }

    @Test
    fun `S031 RF-20 desde la seleccion solo se exportan los puntos elegidos`() {
        selection.ids = setOf("id-E1", "id-2", "id-borrado")

        viewModel(selectionOnly = true).await { it.loaded }.pointCount shouldBe 2
    }

    @Test
    fun `S031 RF-20 con una seleccion solo van las lineas y poligonos con todos sus vertices elegidos`() {
        selection.ids = setOf("id-1", "id-2")

        val state = viewModel(selectionOnly = true).await { it.loaded }

        state.pointCount shouldBe 2
        state.lineCount shouldBe 1 // l1 tiene los vértices 1 y 2
        state.polygonCount shouldBe 0 // l2 necesita también E1
    }

    @Test
    fun `S031 RF-22 con todo el proyecto van todas las lineas y poligonos y sin lineas no va ninguno`() {
        val all = viewModel().await { it.loaded }
        val none = viewModel(withLines = false).await { it.loaded }

        listOf(all.lineCount, all.polygonCount) shouldBe listOf(1, 1)
        listOf(none.lineCount, none.polygonCount) shouldBe listOf(0, 0)
    }

    @Test
    fun `S031 RF-31 sin puntos que exportar se dice y no se escribe nada`() {
        selection.ids = emptySet()
        val vm = viewModel(selectionOnly = true)
        val state = vm.await { it.loaded }
        val out = ByteArrayOutputStream()
        val dir = work()

        state.canExport shouldBe false
        runBlocking { vm.writeTo(out, dir) } shouldBe false

        out.size() shouldBe 0
        dir.listFiles().orEmpty().toList() shouldBe emptyList()
        File(dir, "x.kml").also { runBlocking { vm.writeFile(it) } shouldBe false }.exists() shouldBe false
    }

    @Test
    fun `S031 RF-32 de fabrica es KML sin altura y sin fotos`() {
        val state = viewModel().await { it.loaded }

        listOf(state.format, state.includeHeight, state.includePhotos) shouldBe listOf(KmlFormat.KML, false, false)
        state.withPhotos shouldBe false
    }

    @Test
    fun `S031 RF-32 el formato y las dos opciones se recuerdan para todos los proyectos`() {
        val vm = viewModel()
        vm.await { it.loaded }

        vm.setFormat(KmlFormat.KMZ)
        vm.setIncludeHeight(true)
        vm.setIncludePhotos(true)

        preferences.current.value shouldBe KmlExportSettings(KmlFormat.KMZ, includeHeight = true, includePhotos = true)
        preferences.saved shouldBe 3
        val again = viewModel().await { it.loaded && it.format == KmlFormat.KMZ }
        listOf(again.includeHeight, again.includePhotos) shouldBe listOf(true, true)
    }

    @Test
    fun `S031 RF-31 el nombre propuesto es el del proyecto, kml y la fecha y hora, con la extension del formato`() {
        val vm = viewModel()
        vm.await { it.loaded }
        val at = Instant.parse("2026-10-28T10:30:00Z")

        vm.fileName(at, ZoneOffset.UTC) shouldBe "Obra_kml_20261028-1030.kml"
        vm.setFormat(KmlFormat.KMZ)
        vm.await { it.format == KmlFormat.KMZ }
        vm.fileName(at, ZoneOffset.UTC) shouldBe "Obra_kml_20261028-1030.kmz"
    }

    @Test
    fun `S031 RF-31 escribir un KML en el destino da el documento entero y no deja nada en la carpeta de trabajo`() {
        val vm = viewModel()
        vm.await { it.loaded }
        val out = ByteArrayOutputStream()
        val dir = work()

        runBlocking { vm.writeTo(out, dir) } shouldBe true

        val text = String(out.toByteArray(), Charsets.UTF_8)
        text.startsWith("<?xml") shouldBe true
        text.trimEnd().endsWith("</kml>") shouldBe true
        marks(text) shouldBe 5 // tres puntos, una línea y un polígono
        dir.listFiles().orEmpty().toList() shouldBe emptyList()
    }

    @Test
    fun `S031 RF-26 sin Incluir altura ninguna marca la lleva y con ella van en modo absoluto`() {
        val vm = viewModel()
        vm.await { it.loaded }

        text(vm).contains("<altitudeMode>") shouldBe false
        vm.setIncludeHeight(true)
        vm.await { it.includeHeight }
        text(vm).contains("<altitudeMode>absolute</altitudeMode>") shouldBe true
    }

    @Test
    fun `S031 RF-31 para compartir se escribe el fichero entero en la ruta dada`() {
        val vm = viewModel()
        vm.await { it.loaded }
        val shared = File(work(), "Obra.kml")

        runBlocking { vm.writeFile(shared) } shouldBe true

        marks(shared.readText()) shouldBe 5
    }

    @Test
    fun `S031 RF-28 con KMZ e Incluir fotos el KMZ lleva doc kml primero y las fotos byte a byte con su nombre`() {
        val first = ByteArray(300) { it.toByte() }
        val second = ByteArray(5_000) { (it * 7).toByte() }
        val photos = FakePhotos(
            mapOf("id-2" to listOf(photo("a", "id-2"), photo("b", "id-2"))),
            mapOf("a" to file(first), "b" to file(second)),
        )
        val vm = viewModel(photos = photos)
        vm.await { it.loaded }
        vm.setFormat(KmlFormat.KMZ)
        vm.setIncludePhotos(true)
        vm.await { it.withPhotos }

        val kmz = entries(written(vm))

        kmz.keys.toList() shouldBe listOf("doc.kml", "files/2_1.jpg", "files/2_2.jpg")
        (kmz.getValue("files/2_1.jpg") contentEquals first) shouldBe true
        (kmz.getValue("files/2_2.jpg") contentEquals second) shouldBe true
        val doc = String(kmz.getValue("doc.kml"), Charsets.UTF_8)
        doc.contains("<img src=\"files/2_1.jpg\" width=\"400\"/>") shouldBe true
        doc.contains("<img src=\"files/2_2.jpg\" width=\"400\"/>") shouldBe true
    }

    @Test
    fun `S031 RF-28 sin Incluir fotos el KMZ solo lleva doc kml`() {
        val photos = FakePhotos(mapOf("id-2" to listOf(photo("a", "id-2"))), mapOf("a" to file(ByteArray(10))))
        val vm = viewModel(photos = photos)
        vm.await { it.loaded }
        vm.setFormat(KmlFormat.KMZ)
        vm.await { it.format == KmlFormat.KMZ }

        entries(written(vm)).keys.toList() shouldBe listOf("doc.kml")
    }

    @Test
    fun `S031 RF-28 con KML las fotos no van aunque Incluir fotos este marcada`() {
        val photos = FakePhotos(mapOf("id-2" to listOf(photo("a", "id-2"))), mapOf("a" to file(ByteArray(10))))
        val vm = viewModel(photos = photos)
        vm.await { it.loaded }
        vm.setIncludePhotos(true)
        vm.await { it.includePhotos }

        val state = vm.await { it.loaded }
        val text = text(vm)

        state.withPhotos shouldBe false
        text.startsWith("<?xml") shouldBe true
        text.contains("<img") shouldBe false
    }

    @Test
    fun `S031 RF-30 una foto cuyo fichero falta no entra, se cuenta y su marca no lleva la imagen`() {
        val photos = FakePhotos(
            mapOf("id-2" to listOf(photo("a", "id-2"), photo("b", "id-2"))),
            mapOf("a" to file(ByteArray(10))), // «b» no tiene fichero
        )
        val vm = viewModel(photos = photos)
        vm.await { it.loaded }
        vm.setFormat(KmlFormat.KMZ)
        vm.setIncludePhotos(true)
        vm.await { it.withPhotos }

        val kmz = entries(written(vm))

        kmz.keys.toList() shouldBe listOf("doc.kml", "files/2_1.jpg")
        String(kmz.getValue("doc.kml"), Charsets.UTF_8).contains("2_2.jpg") shouldBe false
        val report = vm.await { it.report != null }.report.shouldNotBeNull()
        listOf(report.photosIncluded, report.photosOmitted) shouldBe listOf(1, 1)
    }

    @Test
    fun `S031 RF-30 el KMZ de mas de 1 GB pide confirmar y solo con KMZ y con fotos`() {
        val big = 600L * 1024 * 1024
        val photos = FakePhotos(
            mapOf("id-2" to listOf(photo("a", "id-2", big), photo("b", "id-2", big))),
            emptyMap(),
        )
        val vm = viewModel(photos = photos)
        vm.await { it.loaded }.needsSizeConfirmation shouldBe false // KML

        vm.setFormat(KmlFormat.KMZ)
        vm.await { it.format == KmlFormat.KMZ }.needsSizeConfirmation shouldBe false // sin fotos

        vm.setIncludePhotos(true)
        val state = vm.await { it.withPhotos }
        state.needsSizeConfirmation shouldBe true
        (state.estimatedBytes > 2 * big) shouldBe true
        state.photoCount shouldBe 2
    }

    @Test
    fun `S031 RF-30 el KMZ que cabe en 1 GB no pide confirmar`() {
        val photos = FakePhotos(mapOf("id-2" to listOf(photo("a", "id-2", 100L * 1024 * 1024))), emptyMap())
        val vm = viewModel(photos = photos)
        vm.await { it.loaded }
        vm.setFormat(KmlFormat.KMZ)
        vm.setIncludePhotos(true)

        vm.await { it.withPhotos }.needsSizeConfirmation shouldBe false
    }

    @Test
    fun `S031 RF-30 sin espacio libre no se escribe nada y se dice`() {
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
    fun `S031 RF-30 el espacio libre que hace falta es el tamano previsto mas un 5 por ciento`() {
        val photos = FakePhotos(mapOf("id-2" to listOf(photo("a", "id-2", 1_000_000))), emptyMap())
        val vm = viewModel(photos = photos)
        vm.await { it.loaded }
        vm.setFormat(KmlFormat.KMZ)
        vm.setIncludePhotos(true)
        val state = vm.await { it.withPhotos }
        val needed = state.estimatedBytes + state.estimatedBytes / 20

        vm.freeSpace = { needed - 1 }
        runBlocking { vm.writeTo(ByteArrayOutputStream(), work()) } shouldBe false
        vm.freeSpace = { needed }
        runBlocking { vm.writeTo(ByteArrayOutputStream(), work()) } shouldBe true
    }

    @Test
    fun `S031 RF-31 si el destino falla a medias se dice que no se ha exportado y no queda temporal`() {
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
    fun `S031 RF-31 si no se puede escribir el fichero no se toca el destino ni queda un fichero a medias`() {
        val vm = viewModel()
        vm.await { it.loaded }
        val notADirectory = folder.newFile("no-es-carpeta")
        val out = ByteArrayOutputStream()

        runBlocking { vm.writeTo(out, notADirectory) } shouldBe false

        out.size() shouldBe 0
        val target = File(notADirectory, "x.kml")
        runBlocking { vm.writeFile(target) } shouldBe false
        target.exists() shouldBe false
    }

    @Test
    fun `S031 RF-32 tras exportar el estado lleva el resumen con lo escrito`() {
        val vm = viewModel()
        vm.await { it.loaded }

        runBlocking { vm.writeTo(ByteArrayOutputStream(), work()) } shouldBe true

        val report = vm.await { it.report != null }.report.shouldNotBeNull()
        listOf(report.summary.points, report.summary.lines, report.summary.polygons) shouldBe listOf(3, 1, 1)
        report.summary.folders shouldBe 2 // BORD y la del proyecto
        listOf(report.format, report.withPhotos, report.photosIncluded) shouldBe listOf(KmlFormat.KML, false, 0)
    }

    @Test
    fun `S031 RF-32 cambiar una opcion borra el resumen y el Fichero guardado de la exportacion anterior`() {
        val vm = viewModel()
        vm.await { it.loaded }
        runBlocking { vm.writeTo(ByteArrayOutputStream(), work()) } shouldBe true
        vm.report(ExportResult.SAVED)
        vm.await { it.result == ExportResult.SAVED && it.report != null }

        vm.setIncludeHeight(true)

        val state = vm.await { it.includeHeight }
        state.result.shouldBeNull()
        state.report.shouldBeNull()
    }

    @Test
    fun `S031 RF-31 exportar no modifica ningun punto del proyecto`() {
        val vm = viewModel()
        vm.await { it.loaded }
        val before = runBlocking { repository.pointsForExport(projectId) }

        runBlocking { vm.writeTo(ByteArrayOutputStream(), work()) }

        runBlocking { repository.pointsForExport(projectId) } shouldBe before
        runBlocking { repository.points(projectId).first() }.size shouldBe 3
    }

    @Test
    fun `S031 RF-31 el resultado se muestra y queda en el registro sin los puntos`() {
        val vm = viewModel()
        vm.await { it.loaded }

        vm.report(ExportResult.SAVED)
        vm.await { it.result == ExportResult.SAVED }
        vm.report(ExportResult.FAILED)

        vm.await { it.result == ExportResult.FAILED }
        log.messages.any { it.contains("KML exportado") } shouldBe true
        log.messages.any { it.contains("No se pudo exportar el KML") } shouldBe true
        log.messages.none { it.contains("Peña") } shouldBe true
        vm.report(null)
        vm.await { it.result == null }.result.shouldBeNull()
    }
}
