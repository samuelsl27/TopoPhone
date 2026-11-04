package es.topophone.app.ui.points

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.app.ui.map.FakePointRepository
import es.topophone.app.ui.projects.CONFIRM_TAG
import es.topophone.core.designsystem.theme.TopoPhoneTheme
import es.topophone.core.model.codes.CodeGeometry
import es.topophone.core.model.codes.CodeSymbol
import es.topophone.core.model.codes.PointCode
import es.topophone.core.model.line.LineVertex
import es.topophone.core.model.line.ProjectLine
import es.topophone.core.model.preferences.EffectiveTheme
import es.topophone.core.model.preferences.ThemeState
import es.topophone.data.line.TrashedLine
import io.kotest.matchers.shouldBe
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
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Las líneas en la papelera del proyecto: restaurar, nombre ocupado y borrar del todo (S025/RF-24). */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "es-rES")
class LineTrashTest {

    @get:Rule
    val compose = createComposeRule()

    private val bord = PointCode("BORD", "", 0x1E88E5, CodeSymbol.CIRCLE)

    private fun line(id: String, name: String, deleted: Long? = null) = ProjectLine(
        id = id,
        projectId = "p1",
        name = name,
        code = bord,
        geometry = CodeGeometry.LINE,
        closed = false,
        hidden = false,
        active = false,
        vertices = listOf(LineVertex("a"), LineVertex("b")),
        createdAtMs = 1,
        deletedAtMs = deleted,
    )

    private val lines = FakeObjectLines(listOf(line("l1", "BORD_1", deleted = 5), line("l2", "BORD_1"), line("l3", "TAPIA_1", deleted = 6)))

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun viewModel(): PointTrashViewModel {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val vm = PointTrashViewModel(FakePointRepository(emptyList()), lines)
        CoroutineScope(Dispatchers.Unconfined).launch { vm.state.collect {} }
        vm.start("p1")
        return vm
    }

    private fun PointTrashViewModel.await(predicate: (PointTrashUiState) -> Boolean): PointTrashUiState =
        runBlocking { withTimeout(5_000) { state.first(predicate) } }

    @Test
    fun `S025 RF-24 la papelera da las lineas borradas con los dias que les quedan`() {
        val state = viewModel().await { it.loaded && it.lines.isNotEmpty() }

        state.lines.map { it.line.name } shouldBe listOf("BORD_1", "TAPIA_1")
        state.lines.map { it.daysLeft } shouldBe listOf(30, 30)
    }

    @Test
    fun `S025 RF-24 restaurar con el nombre libre devuelve la linea al proyecto`() {
        val vm = viewModel()
        vm.await { it.lines.size == 2 }

        vm.restoreLine("l3")

        vm.await { it.lines.size == 1 }
        lines.lines.value.single { it.id == "l3" }.deletedAtMs shouldBe null
    }

    @Test
    fun `S025 RF-24 con el nombre ocupado se propone otro y se restaura con el que se elija`() {
        val vm = viewModel()
        vm.await { it.lines.size == 2 }

        vm.restoreLine("l1")
        val asked = vm.await { it.renameLineFor == "l1" }
        asked.renameLineProposal shouldBe "BORD_9"
        vm.restoreLineAs("l1", "BORD_1")
        vm.await { it.renameLineTaken }
        vm.restoreLineAs("l1", "BORD_2")

        vm.await { it.renameLineFor == null && it.lines.size == 1 }
        lines.lines.value.single { it.id == "l1" }.name shouldBe "BORD_2"
    }

    @Test
    fun `S025 RF-24 borrar del todo quita la linea para siempre`() {
        val vm = viewModel()
        vm.await { it.lines.size == 2 }

        vm.deleteLineForever("l3")

        vm.await { it.lines.size == 1 }
        lines.lines.value.none { it.id == "l3" } shouldBe true
    }

    private val calls = mutableListOf<String>()

    private fun show(state: PointTrashUiState) {
        compose.setContent {
            TopoPhoneTheme(EffectiveTheme.LIGHT) {
                PointTrashScreen(
                    ThemeState.DEFAULT,
                    {},
                    state,
                    PointTrashActions(
                        onRestoreLine = { calls += "restore:$it" },
                        onDeleteLineForever = { calls += "delete:$it" },
                    ),
                    onBack = {},
                )
            }
        }
    }

    @Test
    fun `S025 RF-24 la pantalla muestra cada linea con restaurar y borrar del todo tras confirmar`() {
        show(PointTrashUiState(loaded = true, lines = listOf(TrashedLine(line("l1", "BORD_1", 5), 12))))

        compose.onNodeWithText("BORD_1").assertExists()
        compose.onNodeWithTag(restoreLineTag("l1")).performClick()
        compose.onNodeWithTag(deleteLineForeverTag("l1")).performClick()
        compose.onNodeWithTag(CONFIRM_TAG).performClick()

        calls shouldBe listOf("restore:l1", "delete:l1")
    }
}
