package es.topophone.app.experimental

import es.topophone.core.model.receiver.WifiReceiver
import es.topophone.core.model.receiver.WifiReceiverInput
import es.topophone.receiver.experimental.DiscoveryPhase
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

/** S044/RF-7 a RF-10: buscar receptores, el permiso de red local, «Ya añadido» y el alta solo si se confirma. */
@OptIn(ExperimentalCoroutinesApi::class)
class DiscoveryViewModelTest {

    private val scope = TestScope()
    private val browser = FakeServiceBrowser()
    private val wifi = FakeWifiRepository(WifiReceiver("w1", "RS3 de la oficina", "192.168.42.1", 9001, null))
    private var permission = FakeLocalNetworkPermission(required = false, granted = true)

    @Before
    fun setMain() = Dispatchers.setMain(StandardTestDispatcher(scope.testScheduler))

    @After
    fun resetMain() = Dispatchers.resetMain()

    private fun viewModel(): DiscoveryViewModel = DiscoveryViewModel(browser, wifi, permission).also { scope.runCurrent() }

    @Test
    fun `S044 RF-7 al abrir la pantalla empieza la busqueda y enseña lo que va llegando`() {
        val vm = viewModel()

        vm.onScreenOpened()
        scope.runCurrent()
        browser.starts shouldBe 1
        vm.state.value.phase shouldBe DiscoveryPhase.Searching

        browser.resolved("rs3-a", "192.168.1.50")
        scope.runCurrent()

        vm.state.value.found shouldBe listOf(FoundRow("rs3-a", "192.168.1.50", alreadyAdded = false))
    }

    @Test
    fun `S044 RF-7 a los 10 segundos termina y salir de la pantalla la para`() {
        val vm = viewModel()
        vm.onScreenOpened()
        scope.runCurrent()
        browser.resolved("rs3-a", "192.168.1.50")

        scope.advanceTimeBy(10_000)
        scope.runCurrent()
        vm.state.value.phase shouldBe DiscoveryPhase.Done

        vm.onScreenClosed()
        scope.runCurrent()
        vm.state.value.phase shouldBe DiscoveryPhase.Idle
    }

    @Test
    fun `S044 RF-9 sin ningun resultado la busqueda acaba como ninguno y se puede reintentar`() {
        val vm = viewModel()
        vm.onScreenOpened()
        scope.runCurrent()

        scope.advanceTimeBy(10_000)
        scope.runCurrent()
        vm.state.value.phase shouldBe DiscoveryPhase.Empty

        vm.onRetry()
        scope.runCurrent()
        vm.state.value.phase shouldBe DiscoveryPhase.Searching
        browser.starts shouldBe 2
    }

    @Test
    fun `S044 RF-8 un receptor que ya esta dado de alta sale como Ya anadido`() {
        val vm = viewModel()
        vm.onScreenOpened()
        scope.runCurrent()

        browser.resolved("RS3 de la oficina", "192.168.42.1")
        browser.resolved("rs3-nuevo", "192.168.1.50")
        scope.runCurrent()

        vm.state.value.found.map { it.alreadyAdded } shouldBe listOf(true, false)
    }

    @Test
    fun `S044 RF-8 elegir un receptor propone nombre, direccion y puerto 9001 y no guarda nada`() {
        val vm = viewModel()
        vm.onScreenOpened()
        scope.runCurrent()
        browser.resolved("rs3-a", "192.168.1.50")
        scope.runCurrent()

        vm.onAdd(vm.state.value.found.single())
        scope.runCurrent()

        vm.state.value.draft shouldBe AddDraft("rs3-a", "192.168.1.50", "9001")
        wifi.added shouldBe emptyList()
    }

    @Test
    fun `S044 RF-8 confirmar da de alta el receptor con lo propuesto`() {
        val vm = viewModel()
        vm.onScreenOpened()
        scope.runCurrent()
        browser.resolved("rs3-a", "192.168.1.50")
        scope.runCurrent()
        vm.onAdd(vm.state.value.found.single())

        vm.onConfirmAdd()
        scope.runCurrent()

        wifi.added shouldBe listOf(WifiReceiverInput("rs3-a", "192.168.1.50", 9001, null))
        vm.state.value.draft shouldBe null
        vm.state.value.addedName shouldBe "rs3-a"
        vm.state.value.found.single().alreadyAdded shouldBe true
    }

    @Test
    fun `S044 RF-8 nombre, direccion y puerto se pueden editar antes de confirmar`() {
        val vm = viewModel()
        vm.onScreenOpened()
        scope.runCurrent()
        browser.resolved("rs3-a", "192.168.1.50")
        scope.runCurrent()
        vm.onAdd(vm.state.value.found.single())

        vm.onDraftChange(AddDraft("Mi RS3", "192.168.1.51", "9002"))
        vm.onConfirmAdd()
        scope.runCurrent()

        wifi.added shouldBe listOf(WifiReceiverInput("Mi RS3", "192.168.1.51", 9002, null))
    }

    @Test
    fun `S044 RF-8 un puerto o una direccion invalidos no se guardan y se avisa`() {
        val vm = viewModel()
        vm.onScreenOpened()
        scope.runCurrent()
        browser.resolved("rs3-a", "192.168.1.50")
        scope.runCurrent()
        vm.onAdd(vm.state.value.found.single())

        vm.onDraftChange(AddDraft("x", "192.168.1.50", "abc"))
        vm.onConfirmAdd()
        scope.runCurrent()

        wifi.added shouldBe emptyList()
        vm.state.value.draft!!.invalid shouldBe true
    }

    @Test
    fun `S044 RF-8 cancelar el alta no guarda nada`() {
        val vm = viewModel()
        vm.onScreenOpened()
        scope.runCurrent()
        browser.resolved("rs3-a", "192.168.1.50")
        scope.runCurrent()
        vm.onAdd(vm.state.value.found.single())

        vm.onCancelAdd()
        scope.runCurrent()

        vm.state.value.draft shouldBe null
        wifi.added shouldBe emptyList()
    }

    @Test
    fun `S044 RF-7 con el permiso de red local pendiente no se busca hasta que se conceda`() {
        permission = FakeLocalNetworkPermission(required = true, granted = false)
        val vm = viewModel()

        vm.onScreenOpened()
        scope.runCurrent()
        vm.state.value.permission shouldBe DiscoveryPermission.NeedsRequest
        browser.starts shouldBe 0

        permission.granted = true
        vm.onPermissionAnswered(granted = true)
        scope.runCurrent()
        vm.state.value.permission shouldBe DiscoveryPermission.Granted
        browser.starts shouldBe 1
    }

    @Test
    fun `S044 RF-7 si se deniega el permiso no se busca y se explica`() {
        permission = FakeLocalNetworkPermission(required = true, granted = false)
        val vm = viewModel()
        vm.onScreenOpened()
        scope.runCurrent()

        vm.onPermissionAnswered(granted = false)
        scope.runCurrent()

        vm.state.value.permission shouldBe DiscoveryPermission.Denied
        browser.starts shouldBe 0
    }

    @Test
    fun `S044 RF-7 reintentar tras denegar vuelve a pedir el permiso`() {
        permission = FakeLocalNetworkPermission(required = true, granted = false)
        val vm = viewModel()
        vm.onScreenOpened()
        vm.onPermissionAnswered(granted = false)
        scope.runCurrent()

        vm.onRetry()
        scope.runCurrent()

        vm.state.value.permission shouldBe DiscoveryPermission.NeedsRequest
    }

    @Test
    fun `S044 RF-8 buscar no guarda ni cambia ningun receptor`() {
        val vm = viewModel()
        vm.onScreenOpened()
        scope.runCurrent()
        browser.resolved("rs3-a", "192.168.1.50")
        scope.advanceTimeBy(10_000)
        scope.runCurrent()

        wifi.added shouldBe emptyList()
        wifi.all.value.size shouldBe 1
    }
}
