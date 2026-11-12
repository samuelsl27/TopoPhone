package es.topophone.app.ui.export

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.app.log.RecordingLog
import es.topophone.app.ui.map.FakePointRepository
import es.topophone.app.ui.points.FakeObjectLines
import es.topophone.app.ui.points.PointFixtures.manual
import es.topophone.app.ui.points.PointFixtures.measured
import es.topophone.core.formats.shp.ShpByteSource
import es.topophone.core.formats.shp.ShpReader
import es.topophone.core.formats.shp.ShpShape
import es.topophone.core.formats.shp.export.ShpCoordinates
import es.topophone.core.model.codes.CodeGeometry
import es.topophone.core.model.codes.CodeSymbol
import es.topophone.core.model.codes.PointCode
import es.topophone.core.model.line.LineVertex
import es.topophone.core.model.line.ProjectLine
import es.topophone.core.model.project.HeightSystem
import es.topophone.core.model.project.ProjectCrs
import es.topophone.core.model.project.ProjectDefaults
import es.topophone.data.db.TopoPhoneDatabase
import es.topophone.data.export.ShpExportPreferences
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
import java.util.Locale
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

private class FakeShpPreferences(initial: ShpCoordinates = ShpCoordinates.PROJECTED) : ShpExportPreferences {
    val current = MutableStateFlow(initial)
    var saved = 0

    override val coordinates: Flow<ShpCoordinates> = current

    override suspend fun save(coordinates: ShpCoordinates) {
        saved++
        current.value = coordinates
    }
}

/** Exportar a Shapefile: qué se exporta, el sistema, el nombre, escribir entero o nada y el resumen (S055/RF-1, RF-2, RF-12, RF-13, RF-17 a RF-23). */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class ShpExportViewModelTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val db = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext<Context>(),
        TopoPhoneDatabase::class.java,
    ).build()
    private val projects = RoomProjectRepository(db)
    private val projectId = newProject(ProjectCrs.ETRS89_UTM30)
    private val points = listOf(
        measured("2", projectId = projectId, description = "Peña", createdAtMs = 2_000),
        measured("1", projectId = projectId, createdAtMs = 1_000).copy(hidden = true),
        manual("E1", projectId = projectId, createdAtMs = 3_000),
        measured("borrado", projectId = projectId, createdAtMs = 4_000).copy(deletedAtMs = 5_000),
    )
    private val repository = FakePointRepository(points)
    private val selection = ExportSelection()
    private val preferences = FakeShpPreferences()
    private val log = RecordingLog()
    private val bord = PointCode("BORD", "Bordillo", 0x1E88E5, CodeSymbol.CIRCLE)
    private val lines = FakeObjectLines(listOf(line("l1", false, "1", "2"), line("l2", true, "1", "2", "E1")))
    private val previousLocale = Locale.getDefault()

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

    private fun newProject(crs: ProjectCrs, height: HeightSystem = HeightSystem.ORTHOMETRIC): String = runBlocking {
        projects.create(ProjectDefaults().toDraft().copy(name = "Obra ${crs.name}", crs = crs, heightSystem = height))
            .shouldBeInstanceOf<SaveResult.Saved>().id
    }

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() {
        Locale.setDefault(previousLocale)
        Dispatchers.resetMain()
        db.close()
    }

    private fun viewModel(project: String = projectId, selectionOnly: Boolean = false, withLines: Boolean = true): ShpExportViewModel {
        val vm = ShpExportViewModel(
            repository,
            projects,
            selection,
            preferences,
            log,
            if (withLines) lines else es.topophone.app.ui.points.ObjectLines.NONE,
        )
        CoroutineScope(Dispatchers.Unconfined).launch { vm.state.collect {} }
        vm.start(project, selectionOnly)
        return vm
    }

    private fun ShpExportViewModel.await(predicate: (ShpExportUiState) -> Boolean): ShpExportUiState =
        runBlocking { withTimeout(5_000) { state.first(predicate) } }

    private fun work() = folder.newFolder()

    private fun zip(vm: ShpExportViewModel): Map<String, ByteArray> {
        val out = ByteArrayOutputStream()
        runBlocking { vm.writeTo(out, work()) } shouldBe true
        val files = LinkedHashMap<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(out.toByteArray())).use { zip ->
            generateSequence { zip.nextEntry }.forEach { files[it.name] = zip.readBytes() }
        }
        return files
    }

    /** Los nombres de las columnas del `.dbf`, a partir de sus descriptores. */
    private fun columnNames(dbf: ByteArray): List<String> = generateSequence(32) { it + 32 }.takeWhile { dbf[it].toInt() != 0x0D }
        .map { String(dbf, it, 11, Charsets.US_ASCII).trimEnd('\u0000') }.toList()

    @Test
    fun `S055 RF-1 desde el proyecto se exportan todos los activos, ocultos incluidos, y nunca la papelera`() {
        val state = viewModel().await { it.loaded }

        state.pointCount shouldBe 3
        state.projectName shouldBe "Obra ETRS89_UTM30"
        state.canExport shouldBe true
    }

    @Test
    fun `S055 RF-2 desde la seleccion solo se exportan los puntos elegidos`() {
        selection.ids = setOf("id-E1", "id-2", "id-borrado")

        viewModel(selectionOnly = true).await { it.loaded }.pointCount shouldBe 2
    }

    @Test
    fun `S055 RF-2 con una seleccion solo van las lineas y poligonos con todos sus vertices elegidos`() {
        selection.ids = setOf("id-1", "id-2")

        val state = viewModel(selectionOnly = true).await { it.loaded }

        state.pointCount shouldBe 2
        state.lineCount shouldBe 1 // l1 tiene los vértices 1 y 2
        state.polygonCount shouldBe 0 // l2 necesita también E1
    }

    @Test
    fun `S055 RF-3 con todo el proyecto van todas las lineas y poligonos y sin lineas no va ninguno`() {
        val all = viewModel().await { it.loaded }
        val none = viewModel(withLines = false).await { it.loaded }

        listOf(all.lineCount, all.polygonCount) shouldBe listOf(1, 1)
        listOf(none.lineCount, none.polygonCount) shouldBe listOf(0, 0)
    }

    @Test
    fun `S055 RF-19 sin puntos que exportar se dice y no se escribe nada`() {
        selection.ids = emptySet()
        val vm = viewModel(selectionOnly = true)
        val state = vm.await { it.loaded }
        val out = ByteArrayOutputStream()
        val dir = work()

        state.canExport shouldBe false
        runBlocking { vm.writeTo(out, dir) } shouldBe false

        out.size() shouldBe 0
        dir.listFiles().orEmpty().toList() shouldBe emptyList()
    }

    @Test
    fun `S055 RF-12 por defecto se escriben las coordenadas del proyecto y se ve su codigo`() {
        val state = viewModel().await { it.loaded }

        state.coordinates shouldBe ShpCoordinates.PROJECTED
        state.epsg shouldBe 25830
        state.projectEpsg shouldBe 25830
        state.geographicEpsg shouldBe 4258
        state.canChooseCoordinates shouldBe true
    }

    @Test
    fun `S055 RF-13 se pueden elegir las geograficas del datum y el codigo cambia`() {
        val vm = viewModel()
        vm.await { it.loaded }

        vm.setCoordinates(ShpCoordinates.GEOGRAPHIC)

        vm.await { it.coordinates == ShpCoordinates.GEOGRAPHIC }.epsg shouldBe 4258
        vm.setCoordinates(ShpCoordinates.PROJECTED)
        vm.await { it.epsg == 25830 }
    }

    @Test
    fun `S055 RF-12 un proyecto en geograficas no ofrece elegir porque ya lo esta`() {
        val geographic = newProject(ProjectCrs.ETRS89_GEOGRAPHIC)

        val state = viewModel(geographic).await { it.loaded }

        state.canChooseCoordinates shouldBe false
        state.coordinates shouldBe ShpCoordinates.GEOGRAPHIC
        state.epsg shouldBe 4258
    }

    @Test
    fun `S055 RF-13 un proyecto de Canarias en geograficas declara REGCAN95`() {
        val canarias = newProject(ProjectCrs.REGCAN95_UTM28)
        val vm = viewModel(canarias)
        vm.await { it.epsg == 4083 }

        vm.setCoordinates(ShpCoordinates.GEOGRAPHIC)

        vm.await { it.epsg == 4081 }
    }

    @Test
    fun `S055 RF-23 el sistema elegido se recuerda y se recupera al abrir otro proyecto`() {
        val vm = viewModel()
        vm.await { it.loaded }

        vm.setCoordinates(ShpCoordinates.GEOGRAPHIC)

        preferences.current.value shouldBe ShpCoordinates.GEOGRAPHIC
        preferences.saved shouldBe 1
        val other = newProject(ProjectCrs.ETRS89_UTM29)
        viewModel(other).await { it.loaded && it.projectName == "Obra ETRS89_UTM29" }.coordinates shouldBe ShpCoordinates.GEOGRAPHIC
    }

    @Test
    fun `S055 RF-17 el nombre propuesto es el del proyecto, shapefile y la fecha y hora`() {
        val vm = viewModel()
        vm.await { it.loaded }

        vm.fileName(Instant.parse("2026-10-28T10:30:00Z"), ZoneOffset.UTC) shouldBe "Obra ETRS89_UTM30_shapefile_20261028-1030.zip"
    }

    @Test
    fun `S055 RF-18 escribir en el destino da un ZIP completo y no deja nada en la carpeta de trabajo`() {
        val vm = viewModel()
        vm.await { it.loaded }
        val out = ByteArrayOutputStream()
        val dir = work()

        runBlocking { vm.writeTo(out, dir) } shouldBe true

        out.toByteArray().take(2) shouldBe listOf('P'.code.toByte(), 'K'.code.toByte())
        dir.listFiles().orEmpty().toList() shouldBe emptyList() // el temporal se borra
    }

    @Test
    fun `S055 RF-3 el ZIP lleva los Shapefiles de puntos, lineas y poligonos con el nombre del proyecto limpiado`() {
        val vm = viewModel()
        vm.await { it.loaded }

        val files = zip(vm)

        files.keys.map { it.substringBeforeLast('.') }.distinct() shouldBe
            listOf("Obra_ETRS89_UTM30_puntos", "Obra_ETRS89_UTM30_lineas", "Obra_ETRS89_UTM30_poligonos")
        files.size shouldBe 15
    }

    @Test
    fun `S055 RF-12 el ZIP lleva las coordenadas del proyecto y con geograficas la longitud y la latitud`() {
        val vm = viewModel()
        vm.await { it.loaded }
        val projected = zip(vm).getValue("Obra_ETRS89_UTM30_puntos.shp")
        vm.setCoordinates(ShpCoordinates.GEOGRAPHIC)
        vm.await { it.coordinates == ShpCoordinates.GEOGRAPHIC }
        val geographic = zip(vm).getValue("Obra_ETRS89_UTM30_puntos.shp")

        fun coordinates(bytes: ByteArray): List<Pair<Double, Double>> {
            val found = ArrayList<Pair<Double, Double>>()
            ShpReader.open(ShpByteSource.of(bytes))!!.forEachRecord { record ->
                (record.shape as? ShpShape.Parts)?.parts?.single()?.let { found += it.x.single() to it.y.single() }
            }
            return found
        }
        coordinates(projected).map { it.first }.contains(440_310.0) shouldBe true // el Este del punto manual E1
        val degrees = coordinates(geographic)
        degrees.size shouldBe 3
        degrees.all { it.first in -4.0..-3.0 && it.second in 40.0..41.0 } shouldBe true // longitud y latitud
    }

    @Test
    fun `S055 RF-8 los nombres de las columnas van en espanol, o en ingles si la interfaz esta en ingles`() {
        Locale.setDefault(Locale.forLanguageTag("es-ES"))
        val es = viewModel().also { it.await { s -> s.loaded } }.let(::zip)
        Locale.setDefault(Locale.forLanguageTag("en-GB"))
        val en = viewModel().also { it.await { s -> s.loaded } }.let(::zip)

        columnNames(es.getValue("Obra_ETRS89_UTM30_puntos.dbf")).take(3) shouldBe listOf("NOMBRE", "DESCRIP", "ORIGEN")
        columnNames(en.getValue("Obra_ETRS89_UTM30_puntos.dbf")).take(3) shouldBe listOf("NAME", "DESCR", "ORIGIN")
    }

    @Test
    fun `S055 RF-18 para compartir se escribe el ZIP entero en la ruta dada`() {
        val vm = viewModel()
        vm.await { it.loaded }
        val shared = File(work(), "Obra.zip")

        runBlocking { vm.writeFile(shared) } shouldBe true

        ZipInputStream(ByteArrayInputStream(shared.readBytes())).use { zip -> generateSequence { zip.nextEntry }.count() } shouldBe 15
    }

    @Test
    fun `S055 RF-20 si el destino falla a medias se dice que no se ha exportado y no queda temporal`() {
        val vm = viewModel()
        vm.await { it.loaded }
        val failing = object : OutputStream() {
            override fun write(b: Int) = throw IOException("sin espacio")

            override fun write(b: ByteArray, off: Int, len: Int) = throw IOException("sin espacio")
        }
        val dir = work()

        runBlocking { vm.writeTo(failing, dir) } shouldBe false

        dir.listFiles().orEmpty().toList() shouldBe emptyList()
        vm.await { it.loaded }.summary.shouldBeNull()
    }

    @Test
    fun `S055 RF-20 si no se puede escribir el fichero no se toca el destino ni queda un fichero a medias`() {
        val vm = viewModel()
        vm.await { it.loaded }
        val notADirectory = folder.newFile("no-es-carpeta")
        val out = ByteArrayOutputStream()

        runBlocking { vm.writeTo(out, notADirectory) } shouldBe false

        out.size() shouldBe 0
        val target = File(notADirectory, "x.zip")
        runBlocking { vm.writeFile(target) } shouldBe false
        target.exists() shouldBe false
    }

    @Test
    fun `S055 RF-22 tras exportar el estado lleva el resumen con lo escrito, el sistema y el sistema de alturas`() {
        val vm = viewModel()
        vm.await { it.loaded }

        runBlocking { vm.writeTo(ByteArrayOutputStream(), work()) } shouldBe true

        val summary = vm.await { it.summary != null }.summary.shouldNotBeNull()
        listOf(summary.points, summary.lines, summary.polygons) shouldBe listOf(3, 1, 1)
        summary.epsg shouldBe 25830
        summary.heightSystem shouldBe HeightSystem.ORTHOMETRIC
        summary.geographic shouldBe false
    }

    @Test
    fun `S055 RF-22 cambiar el sistema borra el resumen de la exportacion anterior`() {
        val vm = viewModel()
        vm.await { it.loaded }
        runBlocking { vm.writeTo(ByteArrayOutputStream(), work()) } shouldBe true
        vm.await { it.summary != null }

        vm.setCoordinates(ShpCoordinates.GEOGRAPHIC)

        vm.await { it.coordinates == ShpCoordinates.GEOGRAPHIC }.summary.shouldBeNull()
    }

    @Test
    fun `S055 RF-18 cambiar el sistema borra tambien el Fichero guardado de la exportacion anterior`() {
        val vm = viewModel()
        vm.await { it.loaded }
        runBlocking { vm.writeTo(ByteArrayOutputStream(), work()) } shouldBe true
        vm.report(ExportResult.SAVED)
        vm.await { it.result == ExportResult.SAVED && it.summary != null }

        vm.setCoordinates(ShpCoordinates.GEOGRAPHIC)

        val state = vm.await { it.coordinates == ShpCoordinates.GEOGRAPHIC }
        state.result.shouldBeNull()
        state.summary.shouldBeNull()
    }

    @Test
    fun `S055 RF-21 exportar no modifica ningun punto del proyecto`() {
        val vm = viewModel()
        vm.await { it.loaded }
        val before = runBlocking { repository.pointsForExport(projectId) }

        runBlocking { vm.writeTo(ByteArrayOutputStream(), work()) }

        runBlocking { repository.pointsForExport(projectId) } shouldBe before
        runBlocking { repository.points(projectId).first() }.size shouldBe 3
    }

    @Test
    fun `S055 RF-20 el resultado se muestra y queda en el registro sin los puntos`() {
        val vm = viewModel()
        vm.await { it.loaded }

        vm.report(ExportResult.SAVED)
        vm.await { it.result == ExportResult.SAVED }
        vm.report(ExportResult.FAILED)

        vm.await { it.result == ExportResult.FAILED }
        log.messages.any { it.contains("Shapefile exportado") } shouldBe true
        log.messages.any { it.contains("No se pudo exportar el Shapefile") } shouldBe true
        log.messages.none { it.contains("Peña") } shouldBe true
        vm.report(null)
        vm.await { it.result == null }.result.shouldBeNull()
    }
}
