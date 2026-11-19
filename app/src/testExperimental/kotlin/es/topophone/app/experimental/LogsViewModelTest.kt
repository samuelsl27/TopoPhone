package es.topophone.app.experimental

import es.topophone.core.model.receiver.WifiReceiver
import es.topophone.receiver.experimental.ReachDeviceSettings
import es.topophone.receiver.experimental.ReachInfoReading
import es.topophone.receiver.experimental.ReachPart
import es.topophone.receiver.experimental.ReachPartId
import es.topophone.receiver.experimental.WriteResult
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

/** S044/RF-15, RF-20, RF-21, RF-22: «Iniciar log» y «Parar log» con confirmación y comprobación del estado resultante. */
@OptIn(ExperimentalCoroutinesApi::class)
class LogsViewModelTest {

    private val scope = TestScope()
    private val gateway = FakeReachGateway()
    private val equipment = FakeEquipmentRepository()
    private val key = MutableStateFlow<String?>("wifi:a")

    private val open = """0{"sid":"x","pingInterval":25000,"pingTimeout":60000}"""
    private val logsOn = """42["broadcast",{"name":"active_logs","payload":{"raw":{"is_writing":true}}}]"""
    private val logsOff = """42["broadcast",{"name":"active_logs","payload":{"raw":{"is_writing":false}}}]"""

    @Before
    fun setMain() = Dispatchers.setMain(StandardTestDispatcher(scope.testScheduler))

    @After
    fun resetMain() = Dispatchers.resetMain()

    /** Un ViewModel con la pantalla abierta y el directo conectado, con los logs en el estado dado (o sin saberlo). */
    private fun viewModel(logsFrame: String? = logsOff, connected: Boolean = true): LogsViewModel {
        val session = ExperimentalSession(
            active = MutableStateFlow(true),
            key = key,
            wifi = FakeWifiRepository(WifiReceiver("a", "RS3", "192.168.42.1", 9001, null)),
            equipment = equipment,
            gateway = gateway,
            scope = scope.backgroundScope,
        )
        val vm = LogsViewModel(session)
        scope.runCurrent()
        vm.onScreenOpened()
        scope.runCurrent()
        if (connected && gateway.transports.isNotEmpty()) {
            val connection = gateway.transports.single().second.connections.single()
            connection.listener.onFrame(open)
            connection.listener.onFrame("40")
            if (logsFrame != null) connection.listener.onFrame(logsFrame)
            scope.runCurrent()
        }
        return vm
    }

    @Test
    fun `S044 RF-20 con los logs parados se ofrece Iniciar`() {
        viewModel(logsOff).state.value.action shouldBe LogsAction.START
    }

    @Test
    fun `S044 RF-20 con los logs escribiendo se ofrece Parar`() {
        viewModel(logsOn).state.value.action shouldBe LogsAction.STOP
    }

    @Test
    fun `S044 RF-20 Iniciar pide confirmacion y no envia nada hasta confirmar`() {
        val vm = viewModel(logsOff)

        vm.onRequest()
        scope.runCurrent()

        vm.state.value.confirming shouldBe LogsAction.START
        gateway.logWrites shouldBe emptyList()
    }

    @Test
    fun `S044 RF-20 cancelar la confirmacion no envia nada`() {
        val vm = viewModel(logsOn)
        vm.onRequest()

        vm.onCancel()
        scope.runCurrent()

        vm.state.value.confirming shouldBe null
        gateway.logWrites shouldBe emptyList()
    }

    @Test
    fun `S044 RF-20 confirmar Iniciar envia started true una vez y enseña el resultado`() {
        val vm = viewModel(logsOff)
        vm.onRequest()

        vm.onConfirm()
        scope.runCurrent()

        gateway.logWrites shouldBe listOf(true)
        vm.state.value.result shouldBe WriteResult.Confirmed(true)
        vm.state.value.confirming shouldBe null
        vm.state.value.sending shouldBe false
    }

    @Test
    fun `S044 RF-20 confirmar Parar envia started false`() {
        val vm = viewModel(logsOn)
        vm.onRequest()

        vm.onConfirm()
        scope.runCurrent()

        gateway.logWrites shouldBe listOf(false)
        vm.state.value.result shouldBe WriteResult.Confirmed(false)
    }

    @Test
    fun `S044 RF-21 si el estado resultante no coincide se enseña el que se ve y no se reintenta`() {
        gateway.logsResult = { WriteResult.NotConfirmed(read = false, requestFailed = false) }
        val vm = viewModel(logsOff)
        vm.onRequest()

        vm.onConfirm()
        scope.runCurrent()
        scope.runCurrent()

        vm.state.value.result shouldBe WriteResult.NotConfirmed(read = false, requestFailed = false)
        gateway.logWrites.size shouldBe 1
    }

    @Test
    fun `S044 RF-20 sin directo no se ofrece ninguna accion ni se envia nada`() {
        val vm = viewModel(logsFrame = null, connected = false)

        vm.state.value.action shouldBe null
        vm.state.value.canWrite shouldBe false
        vm.onRequest()
        vm.onConfirm()
        scope.runCurrent()

        vm.state.value.confirming shouldBe null
        gateway.logWrites shouldBe emptyList()
    }

    @Test
    fun `S044 RF-20 con el estado de los logs aun sin conocer no se ofrece ninguna accion`() {
        val vm = viewModel(logsFrame = null, connected = true)

        vm.state.value.live.connected shouldBe true
        vm.state.value.action shouldBe null
        vm.state.value.canWrite shouldBe false
    }

    @Test
    fun `S044 RF-15 con la informacion basica incompatible no se puede escribir`() {
        gateway.reading = ReachInfoReading(
            ReachPart.Incompatible(ReachPartId.IDENTITY, "x"),
            ReachPart.Ok(ReachDeviceSettings("rover", 1.8)),
        )
        val vm = viewModel(logsOff)

        vm.state.value.canWrite shouldBe false
        vm.onRequest()
        scope.runCurrent()
        vm.state.value.confirming shouldBe null
    }

    @Test
    fun `S044 RF-20 el resultado se puede cerrar`() {
        val vm = viewModel(logsOff)
        vm.onRequest()
        vm.onConfirm()
        scope.runCurrent()

        vm.onDismissResult()
        scope.runCurrent()

        vm.state.value.result shouldBe null
    }

    @Test
    fun `S044 RF-16 cerrar la pantalla cierra el directo`() {
        val vm = viewModel(logsOff)

        vm.onScreenClosed()
        scope.runCurrent()

        gateway.transports.single().second.connections.single().closed shouldBe true
    }

    @Test
    fun `S044 RF-22 iniciar o parar logs no toca la altura de antena ni el equipo del proyecto`() {
        val vm = viewModel(logsOn)
        vm.onRequest()
        vm.onConfirm()
        scope.runCurrent()

        gateway.antennaWrites shouldBe emptyList()
        equipment.writes shouldBe emptyList()
    }
}
