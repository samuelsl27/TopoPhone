package es.topophone.app.ui.codes

import android.content.Context
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.core.model.codes.CodeGeometry
import es.topophone.core.model.codes.CodeLibrary
import es.topophone.core.model.codes.SurveyCode
import es.topophone.core.model.project.NameError
import es.topophone.data.codes.LibrarySummary
import es.topophone.data.codes.RoomCodeLibraryRepository
import es.topophone.data.db.TopoPhoneDatabase
import io.kotest.matchers.shouldBe
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
import org.junit.Test
import org.junit.runner.RunWith

/** La lista de bibliotecas: filas, crear, renombrar, duplicar y borrar con confirmación (S023/RF-1 a RF-4). */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class CodeLibraryListViewModelTest {

    private val db = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext<Context>(),
        TopoPhoneDatabase::class.java,
    ).build()
    private val general = CodeLibrary(
        "default:general",
        "Topografía general",
        true,
        listOf(SurveyCode("g1", "VER", "Vértice", CodeGeometry.POINT, 1)),
    )
    private val repository = RoomCodeLibraryRepository(db, { listOf(general) }, clock = { 1L })
    private val viewModels = mutableListOf<CodeLibraryListViewModel>()

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() {
        viewModels.forEach { it.viewModelScope.cancel() }
        db.close()
        Dispatchers.resetMain()
    }

    private fun viewModel() = CodeLibraryListViewModel(repository).also { viewModels += it }

    private fun <T> CodeLibraryListViewModel.awaitState(
        predicate: (CodeLibraryListUiState) -> Boolean,
        read: (CodeLibraryListUiState) -> T,
    ): T = runBlocking {
        val collector = CoroutineScope(Dispatchers.Unconfined).launch { state.collect {} }
        try {
            read(withTimeout(5_000) { state.first(predicate) })
        } finally {
            collector.cancel()
        }
    }

    @Test
    fun `S023 RF-1 la lista trae las por defecto y las del usuario con su numero de codigos`() {
        val vm = viewModel()
        vm.openNew()
        vm.submitName("Zona norte")

        vm.awaitState({
            it.libraries.size == 2 && it.dialog == null
        }) { it.libraries.map { l -> l.name to l.isDefault to l.codeCount } } shouldBe
            listOf("Topografía general" to true to 1, "Zona norte" to false to 0)
    }

    @Test
    fun `S023 RF-2 un nombre vacio o repetido no se guarda y el dialogo dice por que`() {
        val vm = viewModel()
        vm.openNew()
        vm.submitName("  ")
        vm.awaitState({ it.dialog?.error == NameError.EMPTY }) { it.dialog?.dialog } shouldBe LibraryDialog.Create
        vm.submitName("topografia general")

        vm.awaitState({ it.dialog?.error == NameError.DUPLICATE }) { it.libraries.size } shouldBe 1
    }

    @Test
    fun `S023 RF-2 renombrar parte del nombre actual y lo cambia`() {
        val vm = viewModel()
        vm.openNew()
        vm.submitName("Zona norte")
        val mine = vm.awaitState({ it.libraries.size == 2 }) { s -> s.libraries.first { !it.isDefault } }

        vm.openRename(mine)
        vm.awaitState({ it.dialog != null }) { it.dialog?.dialog } shouldBe LibraryDialog.Rename(mine.id, "Zona norte")
        vm.submitName("Zona sur")

        vm.awaitState({ it.dialog == null && it.libraries.any { l -> l.name == "Zona sur" } }) { s -> s.libraries.map { it.name } } shouldBe
            listOf("Topografía general", "Zona sur")
    }

    @Test
    fun `S023 RF-3 duplicar una por defecto crea una copia editable`() {
        val vm = viewModel()

        vm.duplicate("default:general", "copia")

        vm.awaitState({ it.libraries.size == 2 }) { s -> s.libraries.last() }.let {
            it.name shouldBe "Topografía general (copia)"
            it.isDefault shouldBe false
            it.codeCount shouldBe 1
        }
    }

    @Test
    fun `S023 RF-2 borrar pide confirmacion y cancelar no borra`() {
        val vm = viewModel()
        vm.openNew()
        vm.submitName("Zona norte")
        val mine: LibrarySummary = vm.awaitState({ it.libraries.size == 2 }) { s -> s.libraries.first { !it.isDefault } }

        vm.askDelete(mine)
        vm.awaitState({ it.confirmDelete == mine }) { it.confirmDelete } shouldBe mine
        vm.cancelDelete()
        vm.awaitState({ it.confirmDelete == null }) { it.libraries.size } shouldBe 2

        vm.askDelete(mine)
        vm.confirmDelete()

        vm.awaitState({ it.libraries.size == 1 && it.confirmDelete == null }) { s -> s.libraries.map { it.name } } shouldBe
            listOf("Topografía general")
    }
}
