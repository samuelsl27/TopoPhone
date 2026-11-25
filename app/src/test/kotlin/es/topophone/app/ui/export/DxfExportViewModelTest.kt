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
import es.topophone.core.formats.dxf.export.DxfCoordinates
import es.topophone.core.formats.dxf.export.DxfExportOptions
import es.topophone.core.formats.dxf.export.DxfLabel
import es.topophone.core.formats.dxf.export.DxfUnit
import es.topophone.core.model.codes.CodeGeometry
import es.topophone.core.model.codes.CodeSymbol
import es.topophone.core.model.codes.PointCode
import es.topophone.core.model.line.LineVertex
import es.topophone.core.model.line.ProjectLine
import es.topophone.core.model.project.LinearUnit
import es.topophone.core.model.project.ProjectCrs
import es.topophone.core.model.project.ProjectDefaults
import es.topophone.data.db.TopoPhoneDatabase
import es.topophone.data.export.DxfExportPreferences
import es.topophone.data.project.RoomProjectRepository
import es.topophone.data.project.SaveResult
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.time.Instant
import java.time.ZoneOffset
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

private class FakeDxfPreferences(initial: DxfExportOptions = DxfExportOptions()) : DxfExportPreferences {
    val current = MutableStateFlow(initial)
    var saved = 0

    override val options: Flow<DxfExportOptions> = current

    override suspend fun save(options: DxfExportOptions) {
        saved++
        current.value = options
    }
}

/** Exportar a DXF: qué se exporta, las opciones, el nombre, escribir entero o nada y el resumen (S029/RF-1, RF-2, RF-8, RF-13, RF-16 a RF-21). */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class DxfExportViewModelTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val db = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext<Context>(),
        TopoPhoneDatabase::class.java,
    ).build()
    private val projects = RoomProjectRepository(db)
    private val projectId = newProject(ProjectCrs.ETRS89_UTM30)
    private val bord = PointCode("BORD", "Bordillo", 0x1E88E5, CodeSymbol.CIRCLE)
    private val points = listOf(
        measured("2", projectId = projectId, description = "Peña", createdAtMs = 2_000),
        measured("1", projectId = projectId, createdAtMs = 1_000).copy(hidden = true),
        manual("E1", projectId = projectId, createdAtMs = 3_000),
        measured("borrado", projectId = projectId, createdAtMs = 4_000).copy(deletedAtMs = 5_000),
    )
    private val repository = FakePointRepository(points)
    private val selection = ExportSelection()
    private val log = RecordingLog()
    private val preferences = FakeDxfPreferences()

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

    private val lines = FakeObjectLines(listOf(line("l1", false, "1", "2"), line("l2", true, "1", "2", "E1")))

    private fun newProject(crs: ProjectCrs, unit: LinearUnit = LinearUnit.METRE): String = runBlocking {
        projects.create(
            ProjectDefaults().toDraft().copy(
                name = "Obra ${crs.name}" + if (unit ==
                    LinearUnit.METRE
                ) {
                    ""
                } else {
                    " $unit"
                },
                crs = crs,
                linearUnit = unit,
            ),
        )
            .shouldBeInstanceOf<SaveResult.Saved>().id
    }

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        db.close()
    }

    private fun viewModel(project: String = projectId, selectionOnly: Boolean = false, withLines: Boolean = true): DxfExportViewModel {
        val vm = DxfExportViewModel(
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

    private fun DxfExportViewModel.await(predicate: (DxfExportUiState) -> Boolean): DxfExportUiState =
        runBlocking { withTimeout(5_000) { state.first(predicate) } }

    private fun work() = folder.newFolder()

    private fun String.pairs(): List<Pair<String, String>> = split("\r\n").dropLast(1).chunked(2).map { it[0].trim() to it[1] }

    private fun dxf(vm: DxfExportViewModel): String {
        val out = ByteArrayOutputStream()
        runBlocking { vm.writeTo(out, work()) } shouldBe true
        return String(out.toByteArray(), Charsets.ISO_8859_1)
    }

    private fun String.count(type: String) = pairs().count { it == "0" to type }

    @Test
    fun `S029 RF-1 desde el proyecto se exportan todos los puntos activos, ocultos incluidos, y nunca la papelera`() {
        val state = viewModel().await { it.loaded }

        state.pointCount shouldBe 3
        state.projectName shouldBe "Obra ETRS89_UTM30"
        state.canExport shouldBe true
        state.lineCount shouldBe 1
        state.polygonCount shouldBe 1
    }

    @Test
    fun `S029 RF-2 desde la seleccion solo van los puntos elegidos y las lineas con todos sus vertices entre ellos`() {
        selection.ids = setOf("id-1", "id-2", "id-borrado")

        val state = viewModel(selectionOnly = true).await { it.loaded }

        state.pointCount shouldBe 2
        state.lineCount shouldBe 1 // l1 usa 1 y 2
        state.polygonCount shouldBe 0 // l2 necesita E1
    }

    @Test
    fun `S029 RF-18 sin puntos que exportar se dice y no se escribe nada`() {
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
    fun `S029 RF-7 por defecto se parte de Nombre y Cota y de las ultimas opciones recordadas`() {
        viewModel().await { it.loaded }.options shouldBe DxfExportOptions()

        val remembered = DxfExportOptions(setOf(DxfLabel.DESCRIPTION), 2.0, DxfCoordinates.GLOBAL)
        preferences.current.value = remembered
        val vm2 = viewModel()
        vm2.await { it.loaded }.options shouldBe remembered
    }

    @Test
    fun `S029 RF-20 lo que se elige se recuerda para todos los proyectos`() {
        val vm = viewModel()
        vm.await { it.loaded }

        vm.setLabel(DxfLabel.DESCRIPTION, true)
        vm.setCoordinates(DxfCoordinates.GLOBAL)
        vm.setTextHeight("2,5")

        preferences.current.value shouldBe
            DxfExportOptions(setOf(DxfLabel.NAME, DxfLabel.ELEVATION, DxfLabel.DESCRIPTION), 2.5, DxfCoordinates.GLOBAL)
        vm.setLabel(DxfLabel.NAME, false)
        preferences.current.value.labels shouldBe setOf(DxfLabel.ELEVATION, DxfLabel.DESCRIPTION)
    }

    @Test
    fun `S029 RF-8 la altura del texto va de 0,01 a 100 m y fuera de ese rango no se puede exportar`() {
        val vm = viewModel()
        vm.await { it.loaded }
        val savedBefore = preferences.saved

        vm.setTextHeight("0,005")
        vm.await { !it.heightValid }.canExport shouldBe false
        vm.setTextHeight("100,5")
        vm.await { !it.heightValid }.canExport shouldBe false
        vm.setTextHeight("abc")
        vm.await { !it.heightValid }.canExport shouldBe false
        vm.setTextHeight("")
        vm.await { !it.heightValid }.canExport shouldBe false
        preferences.saved shouldBe savedBefore // un valor no válido no se recuerda

        vm.setTextHeight("0.01")
        vm.await { it.heightValid }.canExport shouldBe true
        vm.setTextHeight("100")
        vm.await { it.heightValid && it.options.textHeightM == 100.0 }.canExport shouldBe true
    }

    @Test
    fun `S029 RF-8 con la altura invalida no se escribe el fichero`() {
        val vm = viewModel()
        vm.await { it.loaded }
        vm.setTextHeight("0")
        vm.await { !it.heightValid } // `writeTo` mira el estado, que llega un poco después
        val out = ByteArrayOutputStream()

        runBlocking { vm.writeTo(out, work()) } shouldBe false

        out.size() shouldBe 0
    }

    @Test
    fun `S029 RF-13 un proyecto geografico solo ofrece globales`() {
        val geographic = newProject(ProjectCrs.ETRS89_GEOGRAPHIC)

        val state = viewModel(geographic).await { it.loaded }

        state.canChooseCoordinates shouldBe false
        state.coordinates shouldBe DxfCoordinates.GLOBAL
        state.unit shouldBe DxfUnit.DEGREE
        (viewModel().await { it.loaded }.canChooseCoordinates) shouldBe true
    }

    @Test
    fun `S029 RF-14 la unidad que se ve es la de las coordenadas elegidas`() {
        val feet = newProject(ProjectCrs.ETRS89_UTM30, LinearUnit.US_SURVEY_FOOT)
        val vm = viewModel(feet)

        vm.await { it.loaded }.unit shouldBe DxfUnit.US_SURVEY_FOOT
        vm.setCoordinates(DxfCoordinates.GLOBAL)
        vm.await { it.coordinates == DxfCoordinates.GLOBAL }.unit shouldBe DxfUnit.DEGREE
        vm.setCoordinates(DxfCoordinates.LOCAL)
        vm.await { it.coordinates == DxfCoordinates.LOCAL }.unit shouldBe DxfUnit.US_SURVEY_FOOT
    }

    @Test
    fun `S029 RF-16 el nombre propuesto es el del proyecto, dxf y la fecha y hora`() {
        val vm = viewModel()
        vm.await { it.loaded }

        vm.fileName(Instant.parse("2026-11-06T10:30:00Z"), ZoneOffset.UTC) shouldBe "Obra ETRS89_UTM30_dxf_20261106-1030.dxf"
    }

    @Test
    fun `S029 RF-17 escribir en el destino da un DXF completo y no deja nada en la carpeta de trabajo`() {
        val vm = viewModel()
        vm.await { it.loaded }
        val out = ByteArrayOutputStream()
        val dir = work()

        runBlocking { vm.writeTo(out, dir) } shouldBe true

        val text = String(out.toByteArray(), Charsets.ISO_8859_1)
        text.startsWith("  0\r\nSECTION\r\n  2\r\nHEADER") shouldBe true
        text.count("POINT") shouldBe 3
        text.count("POLYLINE") shouldBe 2
        text.endsWith("  0\r\nEOF\r\n") shouldBe true
        dir.listFiles().orEmpty().toList() shouldBe emptyList()
    }

    @Test
    fun `S029 RF-17 para compartir se escribe el fichero entero en la ruta dada`() {
        val vm = viewModel()
        vm.await { it.loaded }
        val shared = File(work(), "Obra.dxf")

        runBlocking { vm.writeFile(shared) } shouldBe true

        String(shared.readBytes(), Charsets.ISO_8859_1).count("POINT") shouldBe 3
    }

    @Test
    fun `S029 RF-7 las casillas cambian las etiquetas del fichero`() {
        val vm = viewModel()
        vm.await { it.loaded }
        vm.setLabel(DxfLabel.NAME, false)
        vm.setLabel(DxfLabel.ELEVATION, false)

        dxf(vm).count("TEXT") shouldBe 0

        vm.setLabel(DxfLabel.NAME, true)
        dxf(vm).count("TEXT") shouldBe 3
    }

    @Test
    fun `S029 RF-11 RF-12 el sistema elegido cambia las coordenadas del fichero`() {
        val vm = viewModel()
        vm.await { it.loaded }
        vm.setLabel(DxfLabel.NAME, false)
        vm.setLabel(DxfLabel.ELEVATION, false)

        val local = dxf(vm).pairs().first { it.first == "10" }.second.toDouble()
        vm.setCoordinates(DxfCoordinates.GLOBAL)
        val global = dxf(vm).pairs().first { it.first == "10" }.second.toDouble()

        (local > 100_000.0) shouldBe true
        (global in -10.0..10.0) shouldBe true
    }

    @Test
    fun `S029 RF-19 si el destino falla a medias se dice que no se ha exportado y no queda temporal`() {
        val vm = viewModel()
        vm.await { it.loaded }
        val failing = object : OutputStream() {
            override fun write(b: Int) = throw IOException("sin espacio")

            override fun write(b: ByteArray, off: Int, len: Int) = throw IOException("sin espacio")
        }
        val dir = work()

        runBlocking { vm.writeTo(failing, dir) } shouldBe false

        dir.listFiles().orEmpty().toList() shouldBe emptyList()
    }

    @Test
    fun `S029 RF-19 si no se puede escribir el fichero no se toca el destino`() {
        val vm = viewModel()
        vm.await { it.loaded }
        val notADirectory = folder.newFile("no-es-carpeta")
        val out = ByteArrayOutputStream()

        runBlocking { vm.writeTo(out, notADirectory) } shouldBe false

        out.size() shouldBe 0
        runBlocking { vm.writeFile(File(notADirectory, "x.dxf")) } shouldBe false
    }

    @Test
    fun `S029 RF-20 tras exportar el estado lleva el resumen con lo escrito`() {
        val vm = viewModel()
        vm.await { it.loaded }

        runBlocking { vm.writeTo(ByteArrayOutputStream(), work()) } shouldBe true

        val summary = vm.await { it.summary != null }.summary.shouldNotBeNull()
        summary.points shouldBe 3
        summary.lines shouldBe 1
        summary.polygons shouldBe 1
        summary.unit shouldBe DxfUnit.METRE
    }

    @Test
    fun `S029 RF-15 RF-20 los caracteres que no caben en Windows-1252 se sustituyen y se cuentan en el resumen`() {
        repository.points.value = repository.points.value.map { if (it.name == "E1") it.copy(description = "日本") else it }
        val vm = viewModel()
        vm.await { it.loaded }
        vm.setLabel(DxfLabel.DESCRIPTION, true)

        runBlocking { vm.writeTo(ByteArrayOutputStream(), work()) } shouldBe true

        vm.await { it.summary != null }.summary.shouldNotBeNull().replacedChars shouldBe 2
    }

    @Test
    fun `S029 RF-20 cambiar una opcion despues de exportar borra el resumen anterior`() {
        val vm = viewModel()
        vm.await { it.loaded }
        runBlocking { vm.writeTo(ByteArrayOutputStream(), work()) }
        vm.await { it.summary != null }

        vm.setCoordinates(DxfCoordinates.GLOBAL)

        vm.await { it.summary == null }.summary.shouldBeNull()
    }

    @Test
    fun `S029 RF-21 exportar no modifica ningun punto del proyecto`() {
        val vm = viewModel()
        vm.await { it.loaded }
        val before = runBlocking { repository.pointsForExport(projectId) }
        val linesBefore = lines.lines.value

        runBlocking { vm.writeTo(ByteArrayOutputStream(), work()) }

        runBlocking { repository.pointsForExport(projectId) } shouldBe before
        lines.lines.value shouldBe linesBefore
        runBlocking { repository.points(projectId).first() }.size shouldBe 3
    }

    @Test
    fun `S029 RF-19 el resultado se muestra y queda en el registro sin los puntos`() {
        val vm = viewModel()
        vm.await { it.loaded }

        vm.report(ExportResult.SAVED)
        vm.await { it.result == ExportResult.SAVED }
        vm.report(ExportResult.FAILED)

        vm.await { it.result == ExportResult.FAILED }
        log.messages.any { it.contains("DXF exportado") } shouldBe true
        log.messages.any { it.contains("No se pudo exportar el DXF") } shouldBe true
        vm.report(null)
        vm.await { it.result == null }.result.shouldBeNull()
    }
}
