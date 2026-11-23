package es.topophone.app.ui.projects

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.core.designsystem.theme.TopoPhoneTheme
import es.topophone.core.model.preferences.EffectiveTheme
import es.topophone.core.model.preferences.ThemeState
import es.topophone.core.model.project.HeightSystem
import es.topophone.core.model.project.ProjectDefaults
import es.topophone.core.model.project.SpainCrs
import es.topophone.core.model.project.TerrainPlane
import es.topophone.data.crs.RoomProjectCrsHistoryRepository
import es.topophone.data.crs.TerrainChange
import es.topophone.data.db.TopoPhoneDatabase
import es.topophone.data.project.RoomProjectRepository
import es.topophone.data.project.SaveResult
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
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

/** El historial del proyecto muestra el paso a terreno y el cambio de factor, y «Volver al SC anterior» los deshace (S058/RF-19). Interfaz en es-ES. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "es-rES")
class TerrainHistoryScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val db = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext<Context>(),
        TopoPhoneDatabase::class.java,
    ).build()
    private val projects = RoomProjectRepository(db)
    private var now = 1_000L
    private val history = RoomProjectCrsHistoryRepository(db, clock = { now++ })
    private val first = TerrainPlane(40.4, -3.7, 1.0004)
    private val second = TerrainPlane(40.4, -3.7, 1.0008)

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() {
        db.close()
        Dispatchers.resetMain()
    }

    private fun ProjectCrsHistoryViewModel.await(predicate: (ProjectCrsHistoryUiState) -> Boolean): ProjectCrsHistoryUiState = runBlocking {
        val collector = CoroutineScope(Dispatchers.Unconfined).launch { state.collect {} }
        try {
            withTimeout(5_000) { state.first(predicate) }
        } finally {
            collector.cancel()
        }
    }

    private fun setUpHistory(): Pair<String, ProjectCrsHistoryViewModel> {
        val id = runBlocking {
            projects.create(
                ProjectDefaults().toDraft().copy(name = "Obra", crs = SpainCrs.ETRS89_UTM30, heightSystem = HeightSystem.ELLIPSOIDAL),
            )
                .shouldBeInstanceOf<SaveResult.Saved>().id
        }
        runBlocking {
            history.apply(id, SpainCrs.ETRS89_UTM30, HeightSystem.ELLIPSOIDAL, TerrainChange.Set(first))
            history.apply(id, SpainCrs.ETRS89_UTM30, HeightSystem.ELLIPSOIDAL, TerrainChange.Set(second))
        }
        val vm = ProjectCrsHistoryViewModel(history, projects)
        vm.start(id)
        return id to vm
    }

    @Test
    fun `S058 RF-19 el historial muestra el paso a terreno y el cambio de factor`() {
        val (_, vm) = setUpHistory()
        val state = vm.await { it.changes.size == 2 }

        compose.setContent {
            TopoPhoneTheme(EffectiveTheme.LIGHT) { ProjectCrsHistoryScreen(ThemeState.DEFAULT, {}, state, {}, {}) }
        }

        compose.onNodeWithTag(crsHistoryTerrainTag(state.changes[1].id), useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("Plano: cuadrícula → terreno (factor 1.00040000)", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Plano: terreno (factor 1.00040000) → terreno (factor 1.00080000)", substring = true).assertIsDisplayed()
    }

    @Test
    fun `S058 RF-19 volver al SC anterior deshace el cambio de factor y volver otra vez lo rehace`() {
        val (id, vm) = setUpHistory()
        vm.await { it.canUndo }

        vm.undo()
        vm.await { it.changes.size == 3 }
        runBlocking { projects.project(id) }.shouldNotBeNull().terrain shouldBe first

        vm.await { it.canUndo }
        vm.undo()
        vm.await { it.changes.size == 4 }
        // Deshacer lo ya deshecho vuelve al plano de antes (S035/RF-21): es una vuelta atrás más, no una segunda.
        runBlocking { projects.project(id) }.shouldNotBeNull().terrain shouldBe second
    }

    @Test
    fun `S058 RF-19 el boton de volver de la pantalla llama a deshacer`() {
        val (_, vm) = setUpHistory()
        val state = vm.await { it.changes.size == 2 && it.canUndo }
        var undone = 0

        compose.setContent {
            TopoPhoneTheme(EffectiveTheme.LIGHT) { ProjectCrsHistoryScreen(ThemeState.DEFAULT, {}, state, { undone++ }, {}) }
        }
        compose.onNodeWithTag(CRS_HISTORY_UNDO_TAG).performClick()

        undone shouldBe 1
    }
}
