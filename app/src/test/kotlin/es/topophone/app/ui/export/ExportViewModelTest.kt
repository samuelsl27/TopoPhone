package es.topophone.app.ui.export

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.app.log.RecordingLog
import es.topophone.app.ui.map.FakePointRepository
import es.topophone.app.ui.points.PointFixtures.manual
import es.topophone.app.ui.points.PointFixtures.measured
import es.topophone.core.formats.csv.ColumnSeparator
import es.topophone.core.formats.csv.CsvOptions
import es.topophone.core.formats.csv.DecimalSeparator
import es.topophone.core.formats.csv.ExportColumn
import es.topophone.core.formats.csv.ExportFormat
import es.topophone.core.model.log.LogCategory
import es.topophone.core.model.log.LogLevel
import es.topophone.core.model.point.BaseSource
import es.topophone.core.model.point.GeodeticPosition
import es.topophone.core.model.point.PointBase
import es.topophone.core.model.project.ProjectCrs
import es.topophone.core.model.project.ProjectDefaults
import es.topophone.core.survey.ProjectPositionCalculator
import es.topophone.data.db.TopoPhoneDatabase
import es.topophone.data.export.ExportPreferences
import es.topophone.data.project.RoomProjectRepository
import es.topophone.data.project.SaveResult
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.util.Locale
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

/** Exportación: qué puntos, ajustes recordados, validez y escritura del fichero (S012/RF-1 a RF-4, RF-10, RF-13, RF-14, RF-16, RF-20, RF-21). */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class ExportViewModelTest {

    /** Ajustes de la exportación en memoria. */
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
    private val repository = FakePointRepository(
        listOf(
            measured("2", projectId = projectId, description = "Peña", createdAtMs = 2_000),
            measured("1", projectId = projectId, createdAtMs = 1_000).copy(hidden = true),
            manual("E1", projectId = projectId, createdAtMs = 3_000),
            measured("borrado", projectId = projectId, createdAtMs = 4_000).copy(deletedAtMs = 5_000),
        ),
    )
    private val preferences = FakeExportPreferences()
    private val selection = ExportSelection()

    private fun newProject(crs: ProjectCrs): String = runBlocking {
        projects.create(
            ProjectDefaults().toDraft().copy(name = "Proyecto ${crs.name}", crs = crs),
        ).shouldBeInstanceOf<SaveResult.Saved>().id
    }

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        db.close()
    }

    private val log = RecordingLog()

    private fun viewModel(project: String = projectId, selectionOnly: Boolean = false): ExportViewModel {
        val vm = ExportViewModel(repository, projects, preferences, selection, log)
        CoroutineScope(Dispatchers.Unconfined).launch { vm.state.collect {} }
        vm.start(project, selectionOnly)
        return vm
    }

    private fun ExportViewModel.await(predicate: (ExportUiState) -> Boolean): ExportUiState =
        runBlocking { withTimeout(5_000) { state.first(predicate) } }

    @Test
    fun `S012 RF-1 y RF-3 desde el proyecto se exportan todos los activos, ocultos incluidos, y nunca la papelera`() {
        val state = viewModel().await { it.loaded }

        state.pointCount shouldBe 3
        state.projectName shouldBe "Proyecto ETRS89_UTM30"
        state.canExport shouldBe true
    }

    @Test
    fun `S012 RF-2 desde la seleccion solo se exportan los elegidos`() {
        selection.ids = setOf("id-E1", "id-2", "id-borrado")

        viewModel(selectionOnly = true).await { it.loaded }.pointCount shouldBe 2
    }

    @Test
    fun `S012 RF-4 sin puntos que exportar no se puede exportar`() {
        selection.ids = emptySet()

        val state = viewModel(selectionOnly = true).await { it.loaded }

        state.pointCount shouldBe 0
        state.canExport shouldBe false
    }

    @Test
    fun `S012 RF-13, RF-14 y RF-21 lo elegido se recuerda y coma con coma no deja exportar`() {
        val vm = viewModel()
        vm.await { it.loaded }

        vm.setSeparator(ColumnSeparator.COMMA)
        vm.setDecimal(DecimalSeparator.COMMA)
        vm.await { it.options.decimal == DecimalSeparator.COMMA }.canExport shouldBe false
        vm.setSeparator(ColumnSeparator.SEMICOLON)
        vm.setFormat(ExportFormat.PENZD)

        vm.await { it.options.separator == ColumnSeparator.SEMICOLON && it.options.format == ExportFormat.PENZD }.canExport shouldBe true
        preferences.options.value shouldBe
            CsvOptions(format = ExportFormat.PENZD, separator = ColumnSeparator.SEMICOLON, decimal = DecimalSeparator.COMMA)
    }

    @Test
    fun `S012 RF-8 el personalizado sin ninguna columna no deja exportar`() {
        val vm = viewModel()
        vm.await { it.loaded }
        vm.setFormat(ExportFormat.CUSTOM)

        ExportColumn.entries.forEach { vm.toggleColumn(it) }

        vm.await { it.options.customColumns.isEmpty() }.canExport shouldBe false
        vm.toggleColumn(ExportColumn.NAME)
        vm.await { it.options.customColumns == setOf(ExportColumn.NAME) }.canExport shouldBe true
    }

    @Test
    fun `S012 RF-10 en un proyecto en geograficas no hay PENZD aunque fuera lo ultimo elegido`() {
        preferences.options.value = CsvOptions(format = ExportFormat.PENZD)

        val state = viewModel(newProject(ProjectCrs.ETRS89_GEOGRAPHIC)).await { it.loaded }

        state.formats shouldBe listOf(ExportFormat.ALL, ExportFormat.CUSTOM)
        state.options.format shouldBe ExportFormat.ALL
    }

    @Test
    fun `S012 RF-16 el fichero se escribe en UTF-8 sin BOM, en orden de creacion`() {
        preferences.options.value = CsvOptions(format = ExportFormat.PENZD)
        val vm = viewModel()
        vm.await { it.loaded }
        val out = ByteArrayOutputStream()

        runBlocking { vm.writeTo(out) } shouldBe true

        val bytes = out.toByteArray()
        bytes.take(3) shouldBe "Nam".toByteArray().toList()
        val text = bytes.toString(Charsets.UTF_8)
        text.split("\r\n").drop(1).map { it.substringBefore(',') } shouldBe listOf("1", "2", "E1", "")
        text.contains("Peña") shouldBe true
    }

    @Test
    fun `S012 RF-20 si no se puede escribir se dice que no se ha exportado`() {
        val vm = viewModel()
        vm.await { it.loaded }
        val broken = object : OutputStream() {
            override fun write(b: Int): Unit = throw IOException("sin espacio")
        }

        runBlocking { vm.writeTo(broken) } shouldBe false
    }

    @Test
    fun `S014 RF-5 una exportacion fallida queda en el log`() {
        val vm = viewModel()
        vm.await { it.loaded }

        vm.report(ExportResult.FAILED)

        log.entries.single() shouldBe Triple(LogLevel.ERROR, LogCategory.APP, "No se pudo exportar el CSV de «Proyecto ETRS89_UTM30»")
    }

    @Test
    fun `S015 RF-22 el CSV lleva la base en el SC del proyecto`() {
        val avl13m = PointBase(GeodeticPosition(43.5578666032, -5.9130704113, 78.4338), BaseSource.CORRECTIONS, 6, 0.0, 382_123.4567)
        val withBase = measured("B", projectId = projectId, createdAtMs = 500).let {
            it.copy(measurement = it.measurement!!.copy(base = avl13m))
        }
        val vm = ExportViewModel(FakePointRepository(listOf(withBase)), projects, preferences, selection, log)
        CoroutineScope(Dispatchers.Unconfined).launch { vm.state.collect {} }
        vm.start(projectId, selectionOnly = false)
        vm.await { it.loaded }
        val out = ByteArrayOutputStream()

        runBlocking { vm.writeTo(out) } shouldBe true

        val (header, row) = out.toString(Charsets.UTF_8.name()).trimEnd().split("\r\n").map { it.split(",") }
        val project = runBlocking { projects.project(projectId) }!!
        val expected = ProjectPositionCalculator().fromGround(avl13m.position, project.crs, project.heightSystem)
        row[header.indexOf("Base easting")] shouldBe String.format(Locale.ROOT, "%.4f", expected.easting!!)
        row[header.indexOf("Base northing")] shouldBe String.format(Locale.ROOT, "%.4f", expected.northing!!)
        row[header.indexOf("Base elevation")] shouldBe String.format(Locale.ROOT, "%.4f", expected.height!!)
        row[header.indexOf("Baseline")] shouldBe "382123.4567"
    }
}
