package es.topophone.app.ui.cogo

import android.content.Context
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.core.designsystem.theme.TopoPhoneTheme
import es.topophone.core.model.preferences.EffectiveTheme
import es.topophone.core.model.preferences.ThemeState
import es.topophone.core.model.project.ProjectCrs
import es.topophone.core.model.project.ProjectDefaults
import es.topophone.data.db.TopoPhoneDatabase
import es.topophone.data.project.RoomProjectRepository
import es.topophone.data.project.SaveResult
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** La pantalla de elección de COGO: tres operaciones, y nada con un SC geográfico (S038/RF-1, RF-2). */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "es-rES")
class CogoMenuTest {

    @get:Rule
    val compose = createComposeRule()

    private val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), TopoPhoneDatabase::class.java)
        .allowMainThreadQueries()
        .build()
    private val projects = RoomProjectRepository(db)
    private val viewModels = mutableListOf<CogoMenuViewModel>()

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() {
        viewModels.forEach { it.viewModelScope.cancel() }
        runBlocking { viewModels.forEach { it.viewModelScope.coroutineContext[Job]?.join() } }
        db.close()
        Dispatchers.resetMain()
    }

    private fun project(crs: ProjectCrs): String = runBlocking {
        (projects.create(ProjectDefaults().toDraft().copy(name = "Parcela", crs = crs)) as SaveResult.Saved).id
    }

    private fun state(crs: ProjectCrs): CogoMenuUi {
        val viewModel = CogoMenuViewModel(projects).also { viewModels += it }
        viewModel.start(project(crs))
        return runBlocking { viewModel.state.first { it.loaded } }
    }

    private fun show(state: CogoMenuUi, actions: CogoMenuActions = CogoMenuActions()) {
        compose.setContent {
            TopoPhoneTheme(EffectiveTheme.LIGHT) { CogoMenuScreen(ThemeState.DEFAULT, {}, state, actions, onBack = {}) }
        }
    }

    private fun count(tag: String) = compose.onAllNodesWithTag(tag).fetchSemanticsNodes().size

    @Test
    fun `S038 RF-1 con un SC proyectado ofrece Inversa, Poligonal e Interseccion`() {
        val calls = mutableListOf<String>()
        show(
            state(ProjectCrs.ETRS89_UTM30),
            CogoMenuActions(onInverse = { calls += "inverse" }, onTraverse = { calls += "traverse" }, onIntersection = {
                calls +=
                    "intersection"
            }),
        )

        compose.onNodeWithTag(COGO_INVERSE_TAG).assertIsDisplayed().performClick()
        compose.onNodeWithTag(COGO_TRAVERSE_TAG).assertIsDisplayed().performClick()
        compose.onNodeWithTag(COGO_INTERSECTION_TAG).assertIsDisplayed().performClick()

        calls shouldBe listOf("inverse", "traverse", "intersection")
    }

    @Test
    fun `S038 RF-1 las opciones tienen zona tactil de 56 dp`() {
        show(state(ProjectCrs.ETRS89_UTM30))

        for (tag in listOf(COGO_INVERSE_TAG, COGO_TRAVERSE_TAG, COGO_INTERSECTION_TAG)) {
            compose.onNodeWithTag(tag).assertHeightIsAtLeast(56.dp)
        }
    }

    @Test
    fun `S038 RF-2 con un SC geografico no ofrece COGO y dice que hace falta uno proyectado`() {
        val geographic = state(ProjectCrs.ETRS89_GEOGRAPHIC)
        geographic.geographic shouldBe true
        show(geographic)

        compose.onNodeWithTag(COGO_GEOGRAPHIC_TAG).assertIsDisplayed()
        count(COGO_INVERSE_TAG) shouldBe 0
        count(COGO_TRAVERSE_TAG) shouldBe 0
        count(COGO_INTERSECTION_TAG) shouldBe 0
    }

    @Test
    fun `S038 RF-2 un SC proyectado no es geografico y un proyecto que no existe no carga`() {
        state(ProjectCrs.ED50_UTM30).geographic shouldBe false
        val viewModel = CogoMenuViewModel(projects).also { viewModels += it }
        viewModel.start("no-existe")

        viewModel.state.value.loaded shouldBe false
    }
}
