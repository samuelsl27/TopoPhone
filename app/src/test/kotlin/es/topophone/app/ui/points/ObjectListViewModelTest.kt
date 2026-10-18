package es.topophone.app.ui.points

import es.topophone.app.ui.map.FakePointRepository
import es.topophone.app.ui.points.PointFixtures.manual
import es.topophone.app.ui.points.PointFixtures.measured
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
import org.junit.Before
import org.junit.Test

/** Lista de objetos: puntos, búsqueda, visibilidad y selección (S011/RF-1, RF-2, RF-4, RF-22). */
@OptIn(ExperimentalCoroutinesApi::class)
class ObjectListViewModelTest {

    private val repository = FakePointRepository(
        listOf(
            measured("1", createdAtMs = 1_000),
            measured("2", description = "Camión de bomberos", createdAtMs = 2_000),
            manual("E1", createdAtMs = 3_000),
            measured("otro", projectId = "p2", createdAtMs = 4_000),
        ),
        clock = { 10_000 },
    )
    private lateinit var vm: ObjectListViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        vm = ObjectListViewModel(repository)
        CoroutineScope(Dispatchers.Unconfined).launch { vm.state.collect {} }
        vm.start("p1")
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun await(predicate: (ObjectListUiState) -> Boolean): ObjectListUiState =
        runBlocking { withTimeout(5_000) { vm.state.first(predicate) } }

    @Test
    fun `S011 RF-1 la lista da los puntos del proyecto del mas reciente al mas antiguo`() {
        val state = await { it.loaded }

        state.points.map { it.name } shouldBe listOf("E1", "2", "1")
        state.total shouldBe 3
    }

    @Test
    fun `S011 RF-2 la busqueda filtra por nombre o descripcion sin mayusculas ni acentos`() {
        await { it.loaded }

        vm.search("CAMION")
        await { it.query == "CAMION" && it.points.size == 1 }.points.single().name shouldBe "2"

        vm.search("e1")
        await { it.query == "e1" }.points.map { it.name } shouldBe listOf("E1")

        vm.search("")
        await { it.query == "" }.points.size shouldBe 3
    }

    @Test
    fun `S011 RF-4 un punto se oculta y se vuelve a mostrar`() {
        await { it.loaded }

        vm.setHidden("id-1", hidden = true)
        await { state -> state.points.first { it.id == "id-1" }.hidden }

        vm.setHidden("id-1", hidden = false)
        await { state -> !state.points.first { it.id == "id-1" }.hidden }
        repository.points.value.first { it.id == "id-1" }.hidden shouldBe false
    }

    @Test
    fun `S011 RF-4 se ocultan o se muestran varios seleccionados y la seleccion se quita`() {
        await { it.loaded }
        vm.toggleSelection("id-1")
        vm.toggleSelection("id-2")
        vm.toggleSelection("id-E1")
        vm.toggleSelection("id-E1")
        await { it.selected == setOf("id-1", "id-2") }

        vm.setSelectedHidden(hidden = true)

        val state = await { it.selected.isEmpty() && it.points.count { p -> p.hidden } == 2 }
        state.points.filter { it.hidden }.map { it.name }.toSet() shouldBe setOf("1", "2")
    }

    @Test
    fun `S011 RF-22 los seleccionados se mueven a la papelera y salen de la lista`() {
        await { it.loaded }
        vm.toggleSelection("id-1")
        vm.toggleSelection("id-E1")

        vm.deleteSelected()

        val state = await { it.points.size == 1 }
        state.points.single().name shouldBe "2"
        state.selected.shouldBeEmpty()
        repository.points.value.filter { it.deletedAtMs != null }.map { it.name }.toSet() shouldBe setOf("1", "E1")
    }

    @Test
    fun `S011 RF-4 cancelar la seleccion no cambia nada`() {
        await { it.loaded }
        vm.toggleSelection("id-1")
        await { it.selected.isNotEmpty() }

        vm.clearSelection()

        await { it.selected.isEmpty() }.points.none { it.hidden } shouldBe true
    }
}
