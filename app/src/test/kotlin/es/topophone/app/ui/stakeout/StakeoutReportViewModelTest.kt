package es.topophone.app.ui.stakeout

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.app.log.RecordingLog
import es.topophone.app.ui.export.ExportResult
import es.topophone.app.ui.export.ExportSelection
import es.topophone.app.ui.map.FakePointRepository
import es.topophone.app.ui.points.PointFixtures.manual
import es.topophone.app.ui.points.PointFixtures.measured
import es.topophone.app.ui.points.PointFixtures.staked
import es.topophone.core.formats.csv.ColumnSeparator
import es.topophone.core.formats.csv.CsvOptions
import es.topophone.core.formats.csv.DecimalSeparator
import es.topophone.core.model.log.LogCategory
import es.topophone.core.model.log.LogLevel
import es.topophone.core.model.point.Point
import es.topophone.core.model.project.ProjectCrs
import es.topophone.core.model.project.ProjectDefaults
import es.topophone.data.db.TopoPhoneDatabase
import es.topophone.data.export.ExportPreferences
import es.topophone.data.project.RoomProjectRepository
import es.topophone.data.project.SaveResult
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import io.kotest.matchers.types.shouldBeInstanceOf
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
import org.junit.Test
import org.junit.runner.RunWith

/**
 * El informe de replanteo de un proyecto o de una selección: qué replanteos entran, el resumen, el vacío y la escritura del CSV
 * (S019/RF-17 a RF-20, RF-24).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class StakeoutReportViewModelTest {

    private class FakeExportPreferences(initial: CsvOptions = CsvOptions()) : ExportPreferences {
        override val options = MutableStateFlow(initial)

        override suspend fun save(options: CsvOptions) {
            this.options.value = options
        }
    }

    private val db = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext<Context>(),
        TopoPhoneDatabase::class.java,
    ).build()
    private val projects = RoomProjectRepository(db)
    private val projectId = newProject(ProjectCrs.ETRS89_UTM30)
    private val preferences = FakeExportPreferences()
    private val selection = ExportSelection()
    private val log = RecordingLog()

    private fun newProject(crs: ProjectCrs): String = runBlocking {
        projects.create(ProjectDefaults().toDraft().copy(name = "Obra ${crs.name}", crs = crs)).shouldBeInstanceOf<SaveResult.Saved>().id
    }

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        db.close()
    }

    /** Dos replanteos del diseño 12, uno del 13 y un punto medido, uno manual y uno replanteado en la papelera. */
    private fun points(project: String = projectId): List<Point> = listOf(
        staked("12_stkd", project, designElevationM = 648.19, toleranceHM = 0.025, toleranceVM = 0.03, withinTolerance = false)
            .copy(createdAtMs = 1_000),
        staked("12_stkd2", project, designElevationM = 648.19, toleranceHM = 0.025, toleranceVM = 0.03, withinTolerance = true)
            .copy(createdAtMs = 2_000),
        staked(
            "13_stkd",
            project,
        ).copy(staked = staked("13_stkd", project).staked!!.copy(designId = "id-13", designName = "13"), createdAtMs = 3_000),
        measured("20", projectId = project, createdAtMs = 4_000),
        manual("E1", projectId = project, createdAtMs = 5_000),
        staked("14_stkd", project).copy(deletedAtMs = 9_000, createdAtMs = 6_000),
    )

    private fun viewModel(
        all: List<Point> = points(),
        project: String = projectId,
        selectionOnly: Boolean = false,
    ): StakeoutReportViewModel {
        val vm = StakeoutReportViewModel(FakePointRepository(all), projects, preferences, selection, log)
        CoroutineScope(Dispatchers.Unconfined).launch { vm.state.collect {} }
        vm.start(project, selectionOnly)
        return vm
    }

    private fun StakeoutReportViewModel.await(predicate: (StakeoutReportUiState) -> Boolean): StakeoutReportUiState =
        runBlocking { withTimeout(5_000) { state.first(predicate) } }

    private var bytes: List<Byte> = emptyList()

    private fun StakeoutReportViewModel.written(): String {
        val out = ByteArrayOutputStream()
        runBlocking { writeTo(out) } shouldBe true
        bytes = out.toByteArray().toList()
        return out.toString(Charsets.UTF_8)
    }

    @Test
    fun `S019 RF-17 desde el proyecto entran todos los replanteos activos y nada mas`() {
        val state = viewModel().await { it.loaded }

        state.report.rows.map { it.point.name } shouldBe listOf("12_stkd", "12_stkd2", "13_stkd")
        state.projectName shouldBe "Obra ETRS89_UTM30"
        state.canExport shouldBe true
    }

    @Test
    fun `S019 RF-17 el resumen cuenta el ultimo replanteo de cada punto de diseno`() {
        val summary = viewModel().await { it.loaded }.report.summary

        summary.designPoints shouldBe 2
        summary.within shouldBe 1 // 12_stkd2
        summary.notEvaluated shouldBe 1 // 13_stkd, sin tolerancias
        summary.outside shouldBe 0
    }

    @Test
    fun `S019 RF-18 desde la seleccion solo entran los replanteos elegidos`() {
        selection.ids = setOf("id-12_stkd2", "id-13_stkd")

        val state = viewModel(selectionOnly = true).await { it.loaded }

        state.report.rows.map { it.point.name } shouldBe listOf("12_stkd2", "13_stkd")
        state.report.summary.designPoints shouldBe 2
    }

    @Test
    fun `S019 RF-18 un punto seleccionado que no es replanteado se ignora`() {
        selection.ids = setOf("id-20", "id-E1", "id-13_stkd")

        viewModel(selectionOnly = true).await { it.loaded }.report.rows.map { it.point.name } shouldBe listOf("13_stkd")
    }

    @Test
    fun `S019 RF-19 un replanteo en la papelera no entra, ni elegido en la seleccion`() {
        selection.ids = setOf("id-14_stkd", "id-12_stkd")

        viewModel(selectionOnly = true).await { it.loaded }.report.rows.map { it.point.name } shouldBe listOf("12_stkd")
        viewModel().await { it.loaded }.report.rows.none { it.point.name == "14_stkd" } shouldBe true
    }

    @Test
    fun `S019 RF-20 sin ningun punto replanteado no se puede exportar`() {
        val state = viewModel(all = listOf(measured("1", projectId = projectId), manual("E1", projectId = projectId))).await { it.loaded }

        state.report.isEmpty shouldBe true
        state.canExport shouldBe false
    }

    @Test
    fun `S019 RF-20 una seleccion sin replanteos tampoco se puede exportar`() {
        selection.ids = setOf("id-20")

        viewModel(selectionOnly = true).await { it.loaded }.canExport shouldBe false
    }

    @Test
    fun `S019 RF-20 sin nada que informar no se escribe ningun fichero`() {
        val vm = viewModel(all = emptyList())
        vm.await { it.loaded }
        val out = ByteArrayOutputStream()

        runBlocking { vm.writeTo(out) } shouldBe false

        out.size() shouldBe 0
    }

    @Test
    fun `S019 RF-21 y RF-23 el CSV lleva la cabecera y una fila por replanteo en UTF-8 sin BOM`() {
        val vm = viewModel()
        vm.await { it.loaded }

        val text = vm.written()

        text shouldStartWith "Design name,Stakeout number,Staked name,Description,"
        text.removeSuffix("\r\n").split("\r\n").size shouldBe 4
        bytes.take(3) shouldNotBe listOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) // sin BOM
    }

    @Test
    fun `S019 RF-23 el CSV usa el separador y el signo decimal elegidos en la exportacion de puntos`() {
        preferences.options.value = CsvOptions(separator = ColumnSeparator.SEMICOLON, decimal = DecimalSeparator.COMMA)
        val vm = viewModel()
        vm.await { it.loaded }

        val header = vm.written().lineSequence().first()

        header shouldStartWith "Design name;Stakeout number;"
    }

    @Test
    fun `S019 RF-23 con la coma de separador y de decimal no se puede exportar`() {
        preferences.options.value = CsvOptions(separator = ColumnSeparator.COMMA, decimal = DecimalSeparator.COMMA)
        val vm = viewModel()
        val state = vm.await { it.loaded }
        val out = ByteArrayOutputStream()

        state.canExport shouldBe false
        runBlocking { vm.writeTo(out) } shouldBe false
        out.size() shouldBe 0
    }

    @Test
    fun `S019 RF-22 en un proyecto geografico el CSV lleva latitud y longitud`() {
        val geographic = newProject(ProjectCrs.ETRS89_GEOGRAPHIC)
        val vm = viewModel(all = points(geographic), project = geographic)
        vm.await { it.loaded }

        vm.written().lineSequence().first() shouldContain "Design latitude,Design longitude"
    }

    @Test
    fun `S019 RF-24 si no se puede escribir el fichero no se exporta`() {
        val vm = viewModel()
        vm.await { it.loaded }
        val broken = object : OutputStream() {
            override fun write(b: Int) = throw IOException("sin espacio")
        }

        runBlocking { vm.writeTo(broken) } shouldBe false
    }

    @Test
    fun `S019 RF-24 el nombre propuesto es el del proyecto, replanteo y la fecha y hora`() {
        val vm = viewModel()
        vm.await { it.loaded }

        val name = vm.fileName("replanteo", Instant.parse("2026-10-24T14:35:00Z"), ZoneId.of("UTC"))

        name shouldBe "Obra ETRS89_UTM30_replanteo_20261024-1435.csv"
    }

    @Test
    fun `S019 RF-24 guardar y fallar se anota en el log, sin rutas ni coordenadas`() {
        val vm = viewModel()
        vm.await { it.loaded }

        vm.report(ExportResult.SAVED)
        vm.report(ExportResult.FAILED)

        log.entries.map { it.first } shouldBe listOf(LogLevel.INFO, LogLevel.ERROR)
        log.entries.all { it.second == LogCategory.APP } shouldBe true
        log.messages[0] shouldContain "Informe de replanteo exportado"
        log.messages[0] shouldContain "Obra ETRS89_UTM30"
        log.messages[1] shouldContain "No se pudo exportar el informe de replanteo"
        vm.state.value.result shouldBe ExportResult.FAILED
    }

    @Test
    fun `S019 RF-24 report con null borra el resultado anterior`() {
        val vm = viewModel()
        vm.await { it.loaded }
        vm.report(ExportResult.SAVED)

        vm.report(null)

        vm.state.value.result shouldBe null
    }
}
