package es.topophone.app.ui.points

import android.content.Context
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.app.ui.export.ExportSelection
import es.topophone.app.ui.map.FakePointRepository
import es.topophone.app.ui.points.PointFixtures.manual
import es.topophone.app.ui.points.PointFixtures.measured
import es.topophone.core.designsystem.theme.TopoPhoneTheme
import es.topophone.core.model.codes.CodeSymbol
import es.topophone.core.model.codes.PointCode
import es.topophone.core.model.point.PointCodeFilter
import es.topophone.core.model.preferences.EffectiveTheme
import es.topophone.core.model.preferences.ThemeState
import es.topophone.core.model.project.ProjectDefaults
import es.topophone.core.survey.ProjectPositionCalculator
import es.topophone.data.db.TopoPhoneDatabase
import es.topophone.data.project.RoomProjectRepository
import es.topophone.data.project.SaveResult
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

/** El codigo en la lista de objetos: se ve, se filtra y se busca (S024/RF-18). Interfaz en es-ES. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "es-rES")
class ObjectListCodeTest {

    @get:Rule
    val compose = createComposeRule()

    private val db = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext<Context>(),
        TopoPhoneDatabase::class.java,
    ).build()
    private val projects = RoomProjectRepository(db)
    private val projectId = runBlocking {
        projects.create(ProjectDefaults().toDraft().copy(name = "Calle")).shouldBeInstanceOf<SaveResult.Saved>().id
    }

    private fun coded(code: String, color: Int = 0x1E88E5) = PointCode(code, "", color, CodeSymbol.CIRCLE)

    private val repository = FakePointRepository(
        listOf(
            measured("1", projectId = projectId, createdAtMs = 1_000).copy(code = coded("ARB")),
            measured("2", projectId = projectId, createdAtMs = 2_000).copy(code = coded("Poste", 0xD32F2F)),
            manual("E1", projectId = projectId, createdAtMs = 3_000),
            measured("3", projectId = projectId, createdAtMs = 4_000).copy(code = coded("arb")),
        ),
        clock = { 10_000 },
    )
    private var collector: kotlinx.coroutines.Job? = null
    private lateinit var vm: ObjectListViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        vm = ObjectListViewModel(repository, projects, ExportSelection()) { ProjectPositionCalculator() }
        collector = CoroutineScope(Dispatchers.Unconfined).launch { vm.state.collect {} }
        vm.start(projectId)
    }

    @After
    fun tearDown() {
        collector?.cancel()
        Dispatchers.resetMain()
        db.close()
    }

    private fun await(predicate: (ObjectListUiState) -> Boolean): ObjectListUiState =
        runBlocking { withTimeout(5_000) { vm.state.first(predicate) } }

    @Test
    fun `S024 RF-18 la lista conoce los codigos del proyecto y si hay puntos sin codigo`() {
        val state = await { it.loaded }

        state.codes shouldBe listOf("arb", "Poste")
        state.hasUncoded shouldBe true
        state.points.first { it.name == "1" }.code shouldBe "ARB"
    }

    @Test
    fun `S024 RF-18 filtrar por codigo deja solo los puntos de ese codigo sin distinguir mayusculas`() {
        await { it.loaded }

        vm.filterByCode("arb")

        await { it.codeFilter == "arb" }.points.map { it.name } shouldBe listOf("3", "1")
        vm.filterByCode(PointCodeFilter.NONE)
        await { it.codeFilter == PointCodeFilter.NONE }.points.map { it.name } shouldBe listOf("E1")
        vm.filterByCode(null)
        await { it.codeFilter == null }.points.size shouldBe 4
    }

    @Test
    fun `S024 RF-18 la busqueda tambien encuentra por codigo y se combina con el filtro`() {
        await { it.loaded }

        vm.search("poste")
        await { it.query == "poste" }.points.map { it.name } shouldBe listOf("2")
        vm.filterByCode("arb")
        await { it.codeFilter == "arb" }.points shouldBe emptyList()
    }

    private val calls = mutableListOf<String>()

    private fun show(state: ObjectListUiState) {
        compose.setContent {
            TopoPhoneTheme(EffectiveTheme.LIGHT) {
                ObjectListScreen(ThemeState.DEFAULT, {}, state, ObjectListActions(onCodeFilter = { calls += "filter:$it" }), onBack = {})
            }
        }
    }

    private val uiState = ObjectListUiState(
        loaded = true,
        points = listOf(
            measured("2", createdAtMs = 2_000).copy(code = coded("Poste", 0xD32F2F)).summary(),
            manual("E1", createdAtMs = 3_000).summary(),
        ),
        total = 2,
        codes = listOf("Poste"),
        hasUncoded = true,
    )

    @Test
    fun `S024 RF-18 cada punto muestra su codigo con texto ademas del color`() {
        show(uiState)

        compose.onNodeWithTag(
            objectCodeTag("id-2"),
            useUnmergedTree = true,
        ).assertExists().assert(hasAnyDescendant(hasText("Código: Poste")))
        compose.onNodeWithTag(objectCodeTag("id-E1"), useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun `S024 RF-18 los filtros son Todos, un chip por codigo y Sin codigo, y comunican la clave`() {
        show(uiState)

        compose.onNodeWithTag(OBJECT_CODE_FILTER_ALL_TAG).assertIsDisplayed()
        compose.onNodeWithTag(objectCodeFilterTag("Poste")).performClick()
        compose.onNodeWithTag(OBJECT_CODE_FILTER_NONE_TAG).performClick()
        compose.onNodeWithTag(OBJECT_CODE_FILTER_ALL_TAG).performClick()

        calls shouldBe listOf("filter:poste", "filter:", "filter:null")
    }

    @Test
    fun `S024 RF-18 sin codigos en el proyecto no hay filtros`() {
        show(uiState.copy(codes = emptyList(), hasUncoded = true))

        compose.onNodeWithTag(OBJECT_CODE_FILTER_ALL_TAG).assertDoesNotExist()
    }
}
