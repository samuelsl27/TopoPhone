package es.topophone.app.ui.alignment

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.core.model.alignment.AlignmentElement
import es.topophone.core.model.alignment.AlignmentExtent
import es.topophone.core.model.alignment.AlignmentSource
import es.topophone.core.model.project.LinearUnit
import es.topophone.core.model.project.ProjectDefaults
import es.topophone.data.alignment.AlignmentToSave
import es.topophone.data.alignment.RoomAlignmentRepository
import es.topophone.data.db.TopoPhoneDatabase
import es.topophone.data.project.RoomProjectRepository
import es.topophone.data.project.SaveResult
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** La lista de ejes del proyecto: activo, renombrar, mostrar u ocultar y borrar (S049/RF-14, RF-15, RF-19). */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class AlignmentsViewModelTest {

    private val db = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext<Context>(),
        TopoPhoneDatabase::class.java,
    ).build()
    private val projects = RoomProjectRepository(db)
    private val alignments = RoomAlignmentRepository(db)
    private val dispatcher = UnconfinedTestDispatcher()
    private val projectId = runBlocking {
        projects.create(ProjectDefaults().toDraft().copy(name = "Obra")).shouldBeInstanceOf<SaveResult.Saved>().id
    }

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        db.close()
    }

    private fun item(name: String) = AlignmentToSave(
        name,
        "ejes.xml",
        AlignmentSource.LANDXML,
        listOf(AlignmentElement.line(0.0, 100.0, 440000.0, 4474000.0, 1.0)),
        null,
        AlignmentExtent(0.0, 1.0, 0.0, 1.0),
        false,
    )

    private fun ids(vararg names: String) = runBlocking { alignments.add(projectId, names.map(::item), 1L).ids }

    private fun open(): AlignmentsViewModel {
        val vm = AlignmentsViewModel(alignments, projects, dispatcher)
        vm.start(projectId)
        return vm
    }

    private fun AlignmentsViewModel.await(condition: (AlignmentsUiState) -> Boolean): AlignmentsUiState =
        runBlocking { withTimeout(10_000) { state.first(condition) } }

    @Test
    fun `S049 RF-14 la lista trae los ejes y el primero que se importo esta activo`() {
        val ids = ids("Eje A", "Eje B")
        val vm = open()

        val state = vm.await { it.alignments.size == 2 && it.activeId != null }

        state.alignments.map { it.name } shouldBe listOf("Eje A", "Eje B")
        state.activeId shouldBe ids.first()
        state.unit shouldBe LinearUnit.METRE
    }

    @Test
    fun `S049 RF-14 se cambia el eje activo y se puede dejar ninguno`() {
        val ids = ids("Eje A", "Eje B")
        val vm = open()
        vm.await { it.activeId == ids[0] }

        vm.activate(ids[1])
        vm.await { it.activeId == ids[1] }
        vm.activate(null)

        vm.await { it.alignments.size == 2 && it.activeId == null }.activeId.shouldBeNull()
    }

    @Test
    fun `S049 RF-14 renombrar cambia el nombre y uno en blanco se rechaza sin cambiar nada`() {
        val ids = ids("Eje A")
        val vm = open()
        vm.await { it.alignments.size == 1 }

        vm.rename(ids[0], "   ") shouldBe false
        vm.rename(ids[0], "Carretera") shouldBe true

        vm.await { it.alignments.firstOrNull()?.name == "Carretera" }
    }

    @Test
    fun `S049 RF-19 ocultar un eje se guarda y volver a mostrarlo tambien`() {
        val ids = ids("Eje A")
        val vm = open()
        vm.await { it.alignments.firstOrNull()?.visible == true }

        vm.setVisible(ids[0], false)
        vm.await { it.alignments.firstOrNull()?.visible == false }
        // la elección está en la base de datos: otro modelo la ve igual (también tras cerrar la app)
        val again = open()
        again.await { it.alignments.isNotEmpty() }.alignments.single().visible shouldBe false
        vm.setVisible(ids[0], true)

        vm.await { it.alignments.firstOrNull()?.visible == true }
    }

    @Test
    fun `S049 RF-15 borrar el eje activo lo quita entero y el proyecto queda sin activo`() {
        val ids = ids("Eje A", "Eje B")
        val vm = open()
        vm.await { it.activeId == ids[0] }

        vm.delete(ids[0])

        val state = vm.await { it.alignments.size == 1 }
        state.activeId.shouldBeNull()
        state.alignments.single().id shouldBe ids[1]
        runBlocking { alignments.alignment(ids[0]) }.shouldBeNull()
    }
}
