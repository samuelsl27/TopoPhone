package es.topophone.app.ui.projects

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.core.designsystem.theme.TopoPhoneTheme
import es.topophone.core.geodesy.GeoPoint
import es.topophone.core.geodesy.catalog.CrsBuildResult
import es.topophone.core.geodesy.catalog.CrsCatalog
import es.topophone.core.geodesy.catalog.build
import es.topophone.core.model.preferences.EffectiveTheme
import es.topophone.core.model.preferences.ThemeState
import es.topophone.core.model.project.CorrectionsDatum
import es.topophone.core.model.project.CrsChangeKind
import es.topophone.core.model.project.HeightSystem
import es.topophone.core.model.project.LibraryCrs
import es.topophone.core.model.project.Project
import es.topophone.core.model.project.ProjectCrsChange
import es.topophone.core.model.project.ProjectDefaults
import es.topophone.core.model.project.SpainCrs
import es.topophone.data.crs.RoomProjectCrsHistoryRepository
import es.topophone.data.db.TopoPhoneDatabase
import es.topophone.data.project.RoomProjectRepository
import es.topophone.data.project.SaveResult
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
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
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** El historial de cambios de SC del proyecto y «Volver al SC anterior» (S035/RF-20, RF-21). Interfaz en es-ES. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "es-rES")
class ProjectHistoryScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val db = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext<Context>(),
        TopoPhoneDatabase::class.java,
    ).build()
    private val projects = RoomProjectRepository(db)
    private var now = 1_000L
    private val history = RoomProjectCrsHistoryRepository(db, clock = { now++ })

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() {
        db.close()
        Dispatchers.resetMain()
    }

    private val lambert: LibraryCrs = (
        CrsCatalog.bundled.build(
            25831,
            CorrectionsDatum.ETRS89,
            GeoPoint(41.4, 2.2),
        ) as CrsBuildResult.Ready
        )
        .definition.let { LibraryCrs(it, CorrectionsDatum.ETRS89) }

    private fun change(id: String, kind: CrsChangeKind = CrsChangeKind.CHANGE, atMs: Long = 1_800_000_000_000) = ProjectCrsChange(
        id,
        "p1",
        atMs,
        SpainCrs.ETRS89_UTM30,
        HeightSystem.ORTHOMETRIC,
        lambert,
        HeightSystem.ELLIPSOIDAL,
        kind,
    )

    private fun show(state: ProjectCrsHistoryUiState, onUndo: () -> Unit = {}) {
        compose.setContent {
            TopoPhoneTheme(EffectiveTheme.LIGHT) { ProjectCrsHistoryScreen(ThemeState.DEFAULT, {}, state, onUndo, {}) }
        }
    }

    @Test
    fun `S035 RF-20 sin cambios el historial lo dice`() {
        show(ProjectCrsHistoryUiState())

        compose.onNodeWithTag(CRS_HISTORY_EMPTY_TAG).assertIsDisplayed()
    }

    @Test
    fun `S035 RF-20 cada cambio muestra el SC anterior y el nuevo, la fecha y el cambio de altura`() {
        show(ProjectCrsHistoryUiState(listOf(change("c1")), canUndo = true))

        compose.onNodeWithTag(crsHistoryRowTag("c1")).assertIsDisplayed()
        compose.onNodeWithText("ETRS89 / UTM zone 31N", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Altura: Altitud (EGM08-REDNAP) → Altura elipsoidal", substring = true).assertExists()
    }

    @Test
    fun `S035 RF-21 el boton de volver aparece solo en el ultimo cambio y solo si se puede deshacer`() {
        var undone = 0
        show(ProjectCrsHistoryUiState(listOf(change("nuevo"), change("viejo")), canUndo = true)) { undone++ }

        compose.onNodeWithTag(CRS_HISTORY_UNDO_TAG).assertIsDisplayed()
        compose.onNodeWithTag(CRS_HISTORY_UNDO_TAG).performClick()

        undone shouldBe 1
    }

    @Test
    fun `S035 RF-21 sin poder deshacer no hay boton`() {
        show(ProjectCrsHistoryUiState(listOf(change("c1")), canUndo = false))

        compose.onNodeWithTag(CRS_HISTORY_UNDO_TAG).assertDoesNotExist()
    }

    @Test
    fun `S035 RF-21 una vuelta atras se distingue de un cambio`() {
        show(ProjectCrsHistoryUiState(listOf(change("u1", CrsChangeKind.UNDO)), canUndo = true))

        compose.onNodeWithText("Vuelta al SC anterior", substring = true).assertIsDisplayed()
    }

    private fun createProject(): String = runBlocking {
        projects.create(
            ProjectDefaults().toDraft().copy(name = "Obra", crs = SpainCrs.ETRS89_UTM30, heightSystem = HeightSystem.ELLIPSOIDAL),
        )
            .shouldBeInstanceOf<SaveResult.Saved>().id
    }

    private fun ProjectCrsHistoryViewModel.await(predicate: (ProjectCrsHistoryUiState) -> Boolean): ProjectCrsHistoryUiState = runBlocking {
        val collector = CoroutineScope(Dispatchers.Unconfined).launch { state.collect {} }
        try {
            withTimeout(5_000) { state.first(predicate) }
        } finally {
            collector.cancel()
        }
    }

    @Test
    fun `S035 RF-21 el ViewModel deshace el ultimo cambio, lo dice y se puede volver a deshacer`() {
        val id = createProject()
        runBlocking { history.apply(id, lambert, HeightSystem.ELLIPSOIDAL) }
        val vm = ProjectCrsHistoryViewModel(history, projects)
        vm.start(id)

        val first = vm.await { it.changes.size == 1 }
        first.canUndo shouldBe true

        vm.undo()
        // Las dos fuentes (historial y proyecto) se actualizan por separado: se espera a que cuadren.
        val undone = vm.await { it.changes.size == 2 && it.canUndo }

        undone.changes.first().kind shouldBe CrsChangeKind.UNDO
        undone.undoneTo shouldBe SpainCrs.ETRS89_UTM30
        undone.canUndo shouldBe true
        runBlocking { projects.project(id) }.shouldNotBeNull().crs shouldBe SpainCrs.ETRS89_UTM30
    }

    @Test
    fun `S035 RF-21 si el SC se cambio por otro camino ya no se puede deshacer`() {
        val id = createProject()
        runBlocking { history.apply(id, lambert, HeightSystem.ELLIPSOIDAL) }
        runBlocking {
            val project: Project = projects.project(id)!!
            projects.update(id, project.toDraft().copy(crs = SpainCrs.ETRS89_UTM29))
        }
        val vm = ProjectCrsHistoryViewModel(history, projects)
        vm.start(id)

        vm.await { it.changes.size == 1 }.canUndo shouldBe false
        vm.undo()

        vm.await { it.cannotUndo }.changes.size shouldBe 1
    }

    @Test
    fun `S035 RF-20 los datos del proyecto ofrecen abrir el historial`() {
        var opened = 0
        val project = runBlocking { projects.project(createProject()) }.shouldNotBeNull()
        compose.setContent {
            TopoPhoneTheme(EffectiveTheme.LIGHT) {
                ProjectInfoScreen(ThemeState.DEFAULT, {}, ProjectInfoUiState(project), {}, onOpenCrsHistory = { opened++ })
            }
        }

        compose.onNodeWithTag(CRS_HISTORY_OPEN_TAG).performScrollTo().performClick()

        opened shouldBe 1
    }
}
