package es.topophone.app.ui.receiver

import es.topophone.core.model.receiver.WifiReceiver
import es.topophone.core.model.receiver.WifiReceiverInput
import es.topophone.core.model.receiver.WifiReceiverProblem
import es.topophone.core.model.receiver.WifiReceiverWarning
import es.topophone.data.receiver.WifiReceiverRepository
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

/** Alta, edición y borrado de un receptor Wi-Fi: el motivo del rechazo, los avisos y la identidad (S021/RF-2 a RF-5). */
@OptIn(ExperimentalCoroutinesApi::class)
class WifiReceiverFormViewModelTest {

    private class MemoryRepository : WifiReceiverRepository {
        val list = MutableStateFlow<List<WifiReceiver>>(emptyList())
        var added = 0
        private var next = 0

        override fun receivers(): Flow<List<WifiReceiver>> = list

        override fun observe(id: String): Flow<WifiReceiver?> = list.map { rows -> rows.firstOrNull { it.id == id } }

        override suspend fun byId(id: String): WifiReceiver? = list.value.firstOrNull { it.id == id }

        override suspend fun add(input: WifiReceiverInput): String {
            added++
            val id = "id-${++next}"
            list.value += WifiReceiver(id, input.name, input.host, input.positionPort, input.correctionsPort)
            return id
        }

        override suspend fun update(id: String, input: WifiReceiverInput): Boolean {
            if (list.value.none { it.id == id }) return false
            list.value = list.value.map {
                if (it.id == id) WifiReceiver(id, input.name, input.host, input.positionPort, input.correctionsPort) else it
            }
            return true
        }

        override suspend fun delete(id: String): Boolean {
            val had = list.value.any { it.id == id }
            list.value = list.value.filterNot { it.id == id }
            return had
        }
    }

    private val repository = MemoryRepository()
    private var backs = 0

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun viewModel(id: String? = null) = WifiReceiverFormViewModel(repository).also { it.start(id) }

    private fun WifiReceiverFormViewModel.type(
        name: String? = null,
        host: String? = null,
        position: String? = null,
        corrections: String? = null,
    ) {
        val actions = actions { backs++ }
        name?.let(actions.onName)
        host?.let(actions.onHost)
        position?.let(actions.onPositionPort)
        corrections?.let(actions.onCorrectionsPort)
    }

    @Test
    fun `S021 RF-2 un receptor nuevo empieza con 192-168-42-1 y 9001 y sin puerto de correcciones`() {
        val vm = viewModel()

        val state = vm.state.value
        state.loaded shouldBe true
        state.editing shouldBe false
        state.draft.name shouldBe ""
        state.draft.host shouldBe "192.168.42.1"
        state.draft.positionPort shouldBe "9001"
        state.draft.correctionsPort shouldBe ""
    }

    @Test
    fun `S021 RF-2 guardar un receptor valido lo da de alta con lo escrito y cierra el formulario`() {
        val vm = viewModel()
        vm.type(name = " RS3 campo ", host = " reach.local ", position = "9100", corrections = "9101")

        vm.save()

        repository.list.value.single().let {
            it.name shouldBe "RS3 campo"
            it.host shouldBe "reach.local"
            it.positionPort shouldBe 9100
            it.correctionsPort shouldBe 9101
        }
        vm.state.value.done shouldBe true
    }

    @Test
    fun `S021 RF-2 sin nombre se guarda con la direccion y el puerto`() {
        val vm = viewModel()

        vm.save()

        repository.list.value.single().name shouldBe "192.168.42.1:9001"
    }

    @Test
    fun `S021 RF-3 una direccion vacia no se guarda y se dice el motivo`() {
        val vm = viewModel()
        vm.type(host = "  ")

        vm.save()

        vm.state.value.problems shouldBe setOf(WifiReceiverProblem.HOST_EMPTY)
        vm.state.value.done shouldBe false
        repository.added shouldBe 0
    }

    @Test
    fun `S021 RF-3 una direccion con URL o un puerto fuera de rango no se guardan y cada motivo es el suyo`() {
        val vm = viewModel()
        vm.type(host = "http://192.168.42.1", position = "0", corrections = "70000")

        vm.save()

        vm.state.value.problems shouldBe setOf(
            WifiReceiverProblem.HOST_INVALID,
            WifiReceiverProblem.POSITION_PORT_INVALID,
            WifiReceiverProblem.CORRECTIONS_PORT_INVALID,
        )
        repository.added shouldBe 0
    }

    @Test
    fun `S021 RF-3 al corregir un campo se quita su motivo y los demas siguen`() {
        val vm = viewModel()
        vm.type(host = "a b", position = "0")
        vm.save()

        vm.type(position = "9001")

        vm.state.value.problems shouldBe setOf(WifiReceiverProblem.HOST_INVALID)
    }

    @Test
    fun `S021 RF-3 un puerto menor de 3000 avisa pero deja guardar`() {
        val vm = viewModel()

        vm.type(position = "2999", corrections = "80")

        vm.state.value.warnings shouldBe setOf(WifiReceiverWarning.RESERVED_POSITION_PORT, WifiReceiverWarning.RESERVED_CORRECTIONS_PORT)
        vm.save()
        repository.list.value.single().positionPort shouldBe 2_999
        vm.type(position = "3000", corrections = "")
        vm.state.value.warnings shouldBe emptySet()
    }

    @Test
    fun `S021 RF-5 editar carga el receptor y guardar conserva su identidad`() {
        runBlocking { repository.add(WifiReceiverInput("RS3", "192.168.0.20", 9001, 9002)) }
        val vm = viewModel("id-1")

        vm.state.value.editing shouldBe true
        vm.state.value.draft.host shouldBe "192.168.0.20"
        vm.state.value.draft.correctionsPort shouldBe "9002"
        vm.type(name = "RS3 nuevo", host = "192.168.42.1", position = "9100", corrections = "")
        vm.save()

        repository.list.value shouldBe listOf(WifiReceiver("id-1", "RS3 nuevo", "192.168.42.1", 9100, null))
        repository.added shouldBe 1
        vm.state.value.done shouldBe true
    }

    @Test
    fun `S021 RF-4 borrar pide confirmacion y Cancelar no borra`() {
        runBlocking { repository.add(WifiReceiverInput("RS3", "192.168.42.1", 9001, null)) }
        val vm = viewModel("id-1")
        val actions = vm.actions { backs++ }

        actions.onAskDelete()
        vm.state.value.confirmingDelete shouldBe true
        repository.list.value.size shouldBe 1
        actions.onCancelDelete()

        vm.state.value.confirmingDelete shouldBe false
        repository.list.value.size shouldBe 1
        vm.state.value.done shouldBe false
    }

    @Test
    fun `S021 RF-4 confirmar el borrado quita el receptor y cierra el formulario`() {
        runBlocking { repository.add(WifiReceiverInput("RS3", "192.168.42.1", 9001, null)) }
        runBlocking { repository.add(WifiReceiverInput("Otro", "192.168.42.2", 9001, null)) }
        val vm = viewModel("id-1")
        val actions = vm.actions { backs++ }

        actions.onAskDelete()
        actions.onConfirmDelete()

        repository.list.value.map { it.id } shouldBe listOf("id-2")
        vm.state.value.done shouldBe true
    }

    @Test
    fun `S021 RF-4 abrir un receptor que ya no existe vuelve a la lista sin error`() {
        val vm = viewModel("no-existe")

        vm.state.value.done shouldBe true
        repository.added shouldBe 0
    }
}
