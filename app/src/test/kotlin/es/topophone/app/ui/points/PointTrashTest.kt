package es.topophone.app.ui.points

import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.app.ui.map.FakePointRepository
import es.topophone.app.ui.points.PointFixtures.manual
import es.topophone.app.ui.points.PointFixtures.measured
import es.topophone.app.ui.projects.CONFIRM_TAG
import es.topophone.core.designsystem.theme.TopoPhoneTheme
import es.topophone.core.model.preferences.EffectiveTheme
import es.topophone.core.model.preferences.ThemeState
import es.topophone.data.point.TrashedPoint
import io.kotest.matchers.collections.shouldBeEmpty
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

/** Papelera de puntos: días, restaurar, nombre ocupado y borrar definitivamente (S011/RF-23 a RF-26). Interfaz en es-ES. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "es-rES")
class PointTrashTest {

    @get:Rule
    val compose = createComposeRule()

    private val day = 24L * 60 * 60 * 1000
    private var now = 10 * day
    private val repository = FakePointRepository(
        listOf(
            measured("1").copy(deletedAtMs = 8 * day),
            manual("E1").copy(deletedAtMs = 9 * day),
            measured("2"),
            measured("1").copy(id = "id-1-nuevo"),
        ),
        clock = { now },
    )

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun viewModel(): PointTrashViewModel {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val vm = PointTrashViewModel(repository)
        CoroutineScope(Dispatchers.Unconfined).launch { vm.state.collect {} }
        vm.start("p1")
        return vm
    }

    private fun PointTrashViewModel.await(predicate: (PointTrashUiState) -> Boolean): PointTrashUiState =
        runBlocking { withTimeout(5_000) { state.first(predicate) } }

    private val calls = mutableListOf<String>()
    private val actions = PointTrashActions(
        onRestore = { calls += "restore:$it" },
        onRestoreAs = { id, name -> calls += "restoreAs:$id:$name" },
        onDismissRename = { calls += "dismiss" },
        onDeleteForever = { calls += "delete:$it" },
    )

    private fun show(state: PointTrashUiState) {
        compose.setContent {
            TopoPhoneTheme(EffectiveTheme.LIGHT) { PointTrashScreen(ThemeState.DEFAULT, {}, state, actions, onBack = {}) }
        }
    }

    @Test
    fun `S011 RF-23 la papelera da los puntos borrados con los dias que les quedan`() {
        val vm = viewModel()

        val state = vm.await { it.loaded }

        state.points.map { it.point.name to it.daysLeft } shouldBe listOf("E1" to 29, "1" to 28)
    }

    @Test
    fun `S011 RF-24 restaurar devuelve el punto al proyecto`() {
        val vm = viewModel()
        vm.await { it.loaded }

        vm.restore("id-E1")

        vm.await { it.points.size == 1 }
        repository.points.value.single { it.id == "id-E1" }.deletedAtMs shouldBe null
    }

    @Test
    fun `S011 RF-25 con el nombre ocupado se pide otro y con uno libre se restaura`() {
        val vm = viewModel()
        vm.await { it.loaded }

        vm.restore("id-1")
        vm.await { it.renameFor == "id-1" }
        vm.restoreAs("id-1", "2")
        vm.await { it.renameTaken }
        vm.restoreAs("id-1", "1b")

        val state = vm.await { it.renameFor == null && it.points.size == 1 }
        state.renameTaken shouldBe false
        repository.points.value.single { it.id == "id-1" }.name shouldBe "1b"
    }

    @Test
    fun `S011 RF-26 borrar definitivamente lo quita para siempre`() {
        val vm = viewModel()
        vm.await { it.loaded }

        vm.deleteForever("id-E1")

        vm.await { it.points.size == 1 }
        repository.points.value.none { it.id == "id-E1" } shouldBe true
    }

    @Test
    fun `S011 RF-23 la pantalla da nombre y dias, y vacia lo dice`() {
        show(PointTrashUiState(loaded = true, points = listOf(TrashedPoint(measured("1").copy(deletedAtMs = 1), 28))))

        compose.onNodeWithTag(
            trashedPointTag("id-1"),
        ).assert(hasText("1", substring = true)).assert(hasText("Quedan 28 días", substring = true))
    }

    @Test
    fun `S011 RF-23 sin puntos borrados la papelera esta vacia`() {
        show(PointTrashUiState(loaded = true))

        compose.onNodeWithText("La papelera está vacía.").assertIsDisplayed()
    }

    @Test
    fun `S011 RF-24 y RF-26 se restaura o se borra definitivamente tras confirmar`() {
        show(PointTrashUiState(loaded = true, points = listOf(TrashedPoint(measured("1").copy(deletedAtMs = 1), 28))))

        compose.onNodeWithTag(restorePointTag("id-1")).performClick()
        compose.onNodeWithTag(deletePointForeverTag("id-1")).performClick()
        compose.onNodeWithText("¿Borrar «1» para siempre? No se podrá recuperar.").assertIsDisplayed()
        compose.onNodeWithTag(CONFIRM_TAG).performClick()

        calls shouldBe listOf("restore:id-1", "delete:id-1")
    }

    @Test
    fun `S011 RF-25 con el nombre ocupado se pide uno nuevo`() {
        val trashed = TrashedPoint(measured("1").copy(deletedAtMs = 1), 28)
        show(PointTrashUiState(loaded = true, points = listOf(trashed), renameFor = "id-1", renameTaken = true))

        compose.onNodeWithText("Ya hay un punto con ese nombre: escribe otro para restaurarlo.").assertIsDisplayed()
        compose.onNodeWithTag(RENAME_FIELD_TAG).performTextReplacement("1b")
        compose.onNodeWithTag(RENAME_RESTORE_TAG).performClick()

        calls shouldBe listOf("restoreAs:id-1:1b")
    }

    @Test
    fun `S011 RF-23 la lista de objetos abre la papelera desde su menu`() {
        var opened = false
        compose.setContent {
            TopoPhoneTheme(EffectiveTheme.LIGHT) {
                ObjectListScreen(ThemeState.DEFAULT, {
                }, ObjectListUiState(loaded = true), ObjectListActions(onOpenTrash = { opened = true }), onBack = {})
            }
        }

        compose.onNodeWithTag(OBJECTS_MENU_TAG).performClick()
        compose.onNodeWithText("Papelera de puntos").performClick()

        opened shouldBe true
        calls.shouldBeEmpty()
    }
}
