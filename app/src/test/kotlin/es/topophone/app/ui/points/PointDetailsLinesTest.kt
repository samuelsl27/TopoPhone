package es.topophone.app.ui.points

import android.content.Context
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.app.ui.map.FakePointRepository
import es.topophone.app.ui.points.PointFixtures.measured
import es.topophone.app.ui.projects.CONFIRM_TAG
import es.topophone.core.designsystem.theme.TopoPhoneTheme
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

/** La ficha de un punto que es vértice de líneas avisa con sus nombres antes de borrarlo (S025/RF-25). */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "es-rES")
class PointDetailsLinesTest {

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
    private val lines = FakeObjectLines().also { it.usedBy["id-12"] = listOf("BORD_1", "PARC_1") }
    private val repository = FakePointRepository(listOf(measured("12", projectId = projectId)), clock = { 5_000 })

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        db.close()
    }

    @Test
    fun `S025 RF-25 la ficha del punto sabe de que lineas es vertice`() {
        val vm = PointDetailsViewModel(repository, projects, lines = lines) { ProjectPositionCalculator() }
        CoroutineScope(Dispatchers.Unconfined).launch { vm.state.collect {} }
        vm.start(projectId, "id-12")

        runBlocking { withTimeout(5_000) { vm.state.first { it.usedByLines.isNotEmpty() } } }.usedByLines shouldBe
            listOf("BORD_1", "PARC_1")
    }

    @Test
    fun `S025 RF-25 el aviso de borrar el punto nombra las lineas y borrar sigue pidiendo confirmar`() {
        val calls = mutableListOf<String>()
        val state = PointDetailsUiState(
            project = runBlocking { projects.project(projectId) },
            point = repository.points.value.single(),
            usedByLines = listOf("BORD_1", "PARC_1"),
        )
        compose.setContent {
            TopoPhoneTheme(EffectiveTheme.LIGHT) {
                PointDetailsScreen(ThemeState.DEFAULT, {}, state, PointDetailsActions(onDelete = { calls += "delete" }), onBack = {})
            }
        }

        compose.onNodeWithTag(POINT_DELETE_TAG).performScrollTo().performClick()

        compose.onNode(hasText("BORD_1, PARC_1", substring = true)).assertExists()
        compose.onNodeWithTag(CONFIRM_TAG).performClick()
        calls shouldBe listOf("delete")
    }
}
