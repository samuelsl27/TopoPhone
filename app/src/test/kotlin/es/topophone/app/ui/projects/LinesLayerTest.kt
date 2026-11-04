package es.topophone.app.ui.projects

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.app.ui.map.FakeMapController
import es.topophone.app.ui.map.FakePointRepository
import es.topophone.app.ui.map.LineScene
import es.topophone.app.ui.map.fakeMap
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
import es.topophone.core.model.project.AngularUnit
import es.topophone.core.model.project.CorrectionsDatum
import es.topophone.core.model.project.LinearUnit
import es.topophone.core.model.project.Project
import es.topophone.core.model.project.ProjectCrs
import es.topophone.core.model.project.ProjectDefaults
import es.topophone.core.ntrip.NtripConnector
import es.topophone.core.ntrip.NtripFailure
import es.topophone.data.db.TopoPhoneDatabase
import es.topophone.data.project.RoomProjectRepository
import es.topophone.data.project.SaveResult
import es.topophone.receiver.session.ReceiverSession
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Las líneas y los polígonos del proyecto en el mapa: se pintan, se ocultan y al tocarlos se abre su ficha (S025/RF-22, RF-23). */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class LinesLayerTest {

    @get:Rule
    val compose = createComposeRule()

    private val project = Project(
        id = "p1",
        name = "Calle Mayor 12",
        author = "",
        description = "",
        crs = ProjectCrs.ETRS89_UTM30,
        linearUnit = LinearUnit.METRE,
        angularUnit = AngularUnit.DMS,
        correctionsDatum = CorrectionsDatum.ETRS89,
        folderId = null,
        createdAtMs = 1_000,
        modifiedAtMs = 1_000,
        openedAtMs = null,
        deletedAtMs = null,
    )
    private val map = FakeMapController()
    private val sessionScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val session = ReceiverSession(sessionScope, NtripConnector { _, _, _, _ -> NtripFailure.UNREACHABLE })

    @After
    fun closeSession() {
        session.disconnect()
        sessionScope.cancel()
    }

    @Test
    fun `S025 RF-22 la vista del proyecto pinta las lineas y las quita cuando ya no hay`() {
        var scene by mutableStateOf<LineScene?>(LineScene("{}", 1, 0))
        compose.setContent {
            TopoPhoneTheme(EffectiveTheme.LIGHT) {
                ProjectViewScreen(ThemeState.DEFAULT, {}, ProjectViewUiState(project, lineScene = scene), onBack = {}, map = fakeMap(map))
            }
        }
        compose.waitForIdle()
        map.lines.shouldNotBeNull().lines shouldBe 1

        scene = null
        compose.waitForIdle()
        map.lines.shouldBeNull()
    }

    @Test
    fun `S025 RF-22 tocar una linea en el mapa abre su ficha`() {
        var opened: Pair<String, String>? = null
        compose.setContent {
            TopoPhoneTheme(EffectiveTheme.LIGHT) {
                ProjectViewScreen(
                    ThemeState.DEFAULT,
                    {},
                    ProjectViewUiState(project),
                    onBack = {},
                    map = fakeMap(map),
                    onOpenLine = { projectId, lineId -> opened = projectId to lineId },
                )
            }
        }
        compose.waitForIdle()

        map.clickLine("l1")
        compose.waitForIdle()

        opened shouldBe ("p1" to "l1")
    }

    private fun line(id: String, closed: Boolean, hidden: Boolean = false, vararg points: String) = ProjectLine(
        id = id,
        projectId = "p1",
        name = id,
        code = PointCode("BORD", "", 0x1E88E5, CodeSymbol.CIRCLE),
        geometry = if (closed) CodeGeometry.POLYGON else CodeGeometry.LINE,
        closed = closed,
        hidden = hidden,
        active = false,
        vertices = points.map { LineVertex("id-$it") },
        createdAtMs = 0,
    )

    @Test
    fun `S025 RF-22 y RF-23 el ViewModel da el GeoJSON de las lineas visibles y oculta las que se ocultan`() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), TopoPhoneDatabase::class.java).build()
        try {
            val repository = RoomProjectRepository(db)
            val id = runBlocking { (repository.create(ProjectDefaults().toDraft().copy(name = "Obra")) as SaveResult.Saved).id }
            val points =
                FakePointRepository(listOf(measured("1", projectId = id), measured("2", projectId = id), measured("3", projectId = id)))
            val lines =
                FakeObjectLines(listOf(line("l1", closed = false, points = arrayOf("1", "2")), line("l2", true, false, "1", "2", "3")))
            val vm = projectViewModel(repository, session, points = points, lines = lines)
            vm.start(id)
            val collector = CoroutineScope(Dispatchers.Unconfined).launch { vm.state.collect {} }
            try {
                val scene = runBlocking { withTimeout(5_000) { vm.state.first { it.lineScene != null } } }.lineScene.shouldNotBeNull()
                scene.lines shouldBe 1
                scene.polygons shouldBe 1

                runBlocking { lines.setHidden("l1", true) }

                runBlocking {
                    withTimeout(5_000) { vm.state.first { it.lineScene?.lines == 0 } }
                }.lineScene.shouldNotBeNull().polygons shouldBe
                    1
            } finally {
                collector.cancel()
            }
        } finally {
            db.close()
            Dispatchers.resetMain()
        }
    }
}
