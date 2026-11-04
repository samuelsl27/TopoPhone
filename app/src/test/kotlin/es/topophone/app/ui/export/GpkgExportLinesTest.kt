package es.topophone.app.ui.export

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.app.log.RecordingLog
import es.topophone.app.ui.map.FakePointRepository
import es.topophone.app.ui.points.FakeObjectLines
import es.topophone.app.ui.points.PointFixtures.measured
import es.topophone.core.designsystem.theme.TopoPhoneTheme
import es.topophone.core.model.codes.CodeGeometry
import es.topophone.core.model.codes.CodeSymbol
import es.topophone.core.model.codes.PointCode
import es.topophone.core.model.line.LineVertex
import es.topophone.core.model.line.ProjectLine
import es.topophone.core.model.preferences.EffectiveTheme
import es.topophone.core.model.preferences.ThemeState
import es.topophone.core.model.project.ProjectCrs
import es.topophone.core.model.project.ProjectDefaults
import es.topophone.data.db.TopoPhoneDatabase
import es.topophone.data.project.RoomProjectRepository
import es.topophone.data.project.SaveResult
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.io.ByteArrayOutputStream
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
import org.robolectric.annotation.Config

/** Las líneas y los polígonos del proyecto van al GeoPackage con sus capas, o no van si no hay (S025/RF-26, S053/RF-3). */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "es-rES")
class GpkgExportLinesTest {

    @get:Rule
    val folder = TemporaryFolder()

    @get:Rule
    val compose = createComposeRule()

    private val db = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext<Context>(),
        TopoPhoneDatabase::class.java,
    ).build()
    private val projects = RoomProjectRepository(db)
    private val projectId = runBlocking {
        projects.create(
            ProjectDefaults().toDraft().copy(name = "Obra", crs = ProjectCrs.ETRS89_UTM30),
        ).shouldBeInstanceOf<SaveResult.Saved>().id
    }
    private val points = FakePointRepository(listOf("1", "2", "3", "4").map { measured(it, projectId = projectId) })
    private val bord = PointCode("BORD", "Bordillo", 0x1E88E5, CodeSymbol.CIRCLE)

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

    private val lines = FakeObjectLines(listOf(line("l1", false, "1", "2"), line("l2", true, "1", "2", "3")))
    private val selection = ExportSelection()

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        db.close()
    }

    private fun viewModel(withLines: Boolean = true, selectionOnly: Boolean = false): GpkgExportViewModel {
        val vm =
            GpkgExportViewModel(
                points,
                projects,
                selection,
                RecordingLog(),
                if (withLines) lines else es.topophone.app.ui.points.ObjectLines.NONE,
            )
        CoroutineScope(Dispatchers.Unconfined).launch { vm.state.collect {} }
        vm.start(projectId, selectionOnly)
        return vm
    }

    private fun GpkgExportViewModel.await(predicate: (GpkgExportUiState) -> Boolean): GpkgExportUiState =
        runBlocking { withTimeout(5_000) { state.first(predicate) } }

    private fun layers(vm: GpkgExportViewModel): List<String> {
        val out = ByteArrayOutputStream()
        runBlocking { vm.writeTo(out, folder.newFolder()) } shouldBe true
        val file = folder.newFile().apply { writeBytes(out.toByteArray()) }
        return SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use { database ->
            database.rawQuery("SELECT table_name FROM gpkg_contents ORDER BY table_name", null).use { c ->
                buildList { while (c.moveToNext()) add(c.getString(0)) }
            }
        }
    }

    @Test
    fun `S025 RF-26 el proyecto con lineas y poligonos las exporta en las capas lines y polygons`() {
        val vm = viewModel()
        val state = vm.await { it.loaded }

        state.lineCount shouldBe 1
        state.polygonCount shouldBe 1
        layers(vm) shouldBe listOf("lines", "points", "polygons")
    }

    @Test
    fun `S025 RF-26 un proyecto sin lineas exporta el fichero de siempre`() {
        val vm = viewModel(withLines = false)
        val state = vm.await { it.loaded }

        state.lineCount shouldBe 0
        state.polygonCount shouldBe 0
        layers(vm) shouldBe listOf("points")
    }

    @Test
    fun `S025 RF-26 con una seleccion solo van las lineas cuyos vertices estan todos en ella`() {
        selection.ids = setOf("id-1", "id-2")
        val vm = viewModel(selectionOnly = true)
        val state = vm.await { it.loaded }

        state.lineCount shouldBe 1
        state.polygonCount shouldBe 0
        layers(vm) shouldBe listOf("lines", "points")
    }

    @Test
    fun `S025 RF-26 exportar no modifica las lineas ni los puntos`() {
        val vm = viewModel()
        vm.await { it.loaded }
        val before = lines.lines.value to points.points.value

        layers(vm)

        (lines.lines.value to points.points.value) shouldBe before
    }

    @Test
    fun `S025 RF-26 la pantalla dice cuantas lineas y poligonos van al fichero`() {
        compose.setContent {
            TopoPhoneTheme(EffectiveTheme.LIGHT) {
                GpkgExportScreen(
                    ThemeState.DEFAULT,
                    {},
                    GpkgExportUiState(loaded = true, projectName = "Obra", pointCount = 4, srsId = 25830, lineCount = 1, polygonCount = 2),
                    GpkgExportActions(),
                    onBack = {},
                )
            }
        }

        compose.onNodeWithTag(GPKG_LINES_TAG).assertIsDisplayed()
        compose.onNodeWithText("1 línea · 2 polígonos").assertExists()
    }
}
