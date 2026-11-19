package es.topophone.app.experimental

import es.topophone.core.model.point.TiltMode
import es.topophone.core.model.receiver.WifiReceiver
import es.topophone.receiver.experimental.AntennaHeightProposal
import es.topophone.receiver.experimental.ReachDeviceSettings
import es.topophone.receiver.experimental.ReachInfoReading
import es.topophone.receiver.experimental.ReachPart
import es.topophone.receiver.experimental.ReachPartId
import es.topophone.receiver.experimental.WriteFailure
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

/** S044/RF-15, RF-18, RF-19, RF-21, RF-22: «Enviar altura de antena»: propuesta, confirmación, envío y resultado. */
@OptIn(ExperimentalCoroutinesApi::class)
class AntennaHeightViewModelTest {

    private class FakeAntennaContext(mode: TiltMode, pole: Double?) : AntennaContext {
        override val state = MutableStateFlow(AntennaInputs(mode, pole))
    }

    private val scope = TestScope()
    private val gateway = FakeReachGateway()
    private val equipment = FakeEquipmentRepository()
    private val context = FakeAntennaContext(TiltMode.RECEIVER, 1.95)
    private val key = MutableStateFlow<String?>("wifi:a")

    @Before
    fun setMain() = Dispatchers.setMain(StandardTestDispatcher(scope.testScheduler))

    @After
    fun resetMain() = Dispatchers.resetMain()

    private fun viewModel(): AntennaHeightViewModel {
        val session = ExperimentalSession(
            active = MutableStateFlow(true),
            key = key,
            wifi = FakeWifiRepository(WifiReceiver("a", "RS3", "192.168.42.1", 9001, null)),
            equipment = equipment,
            gateway = gateway,
            scope = scope.backgroundScope,
        )
        return AntennaHeightViewModel(session, context).also { scope.runCurrent() }
    }

    @Test
    fun `S044 RF-18 con la posicion compensada por el receptor se propone la altura del jalon activo y se ve la actual`() {
        val state = viewModel().state.value

        state.proposal shouldBe AntennaHeightProposal(1.95, warnDoubleCount = false)
        state.text shouldBe "1.95"
        state.valueM shouldBe 1.95
        state.currentM shouldBe 1.8
        state.canSend shouldBe true
    }

    @Test
    fun `S044 RF-18 con cualquier otro modo se propone cero y se avisa de la doble cuenta`() {
        context.state.value = AntennaInputs(TiltMode.APP, 1.8)

        val state = viewModel().state.value

        state.proposal shouldBe AntennaHeightProposal(0.0, warnDoubleCount = true)
        state.text shouldBe "0"
        state.valueM shouldBe 0.0
    }

    @Test
    fun `S044 RF-18 la propuesta se puede editar y acepta coma o punto`() {
        val vm = viewModel()

        vm.onTextChange("1,5")
        scope.runCurrent()
        vm.state.value.valueM shouldBe 1.5
        vm.state.value.text shouldBe "1,5"

        vm.onTextChange("2.25")
        scope.runCurrent()
        vm.state.value.valueM shouldBe 2.25
    }

    @Test
    fun `S044 RF-18 un valor que no es un numero no se puede enviar`() {
        val vm = viewModel()

        listOf("", "abc", "1.2.3", "NaN", "Infinity", "-").forEach {
            vm.onTextChange(it)
            scope.runCurrent()
            vm.state.value.valueM shouldBe null
            vm.onSend()
            scope.runCurrent()
            vm.state.value.confirming shouldBe false
        }
        gateway.antennaWrites shouldBe emptyList()
    }

    @Test
    fun `S044 RF-18 Enviar pide confirmacion y no envia nada hasta confirmar`() {
        val vm = viewModel()

        vm.onSend()
        scope.runCurrent()

        vm.state.value.confirming shouldBe true
        gateway.antennaWrites shouldBe emptyList()
    }

    @Test
    fun `S044 RF-18 cancelar la confirmacion no envia nada`() {
        val vm = viewModel()
        vm.onSend()
        scope.runCurrent()

        vm.onCancel()
        scope.runCurrent()

        vm.state.value.confirming shouldBe false
        gateway.antennaWrites shouldBe emptyList()
        vm.state.value.result shouldBe null
    }

    @Test
    fun `S044 RF-19 confirmar envia el valor una vez, relee y enseña el resultado`() {
        val vm = viewModel()
        vm.onTextChange("1,5")
        vm.onSend()
        scope.runCurrent()

        vm.onConfirm()
        scope.runCurrent()

        gateway.antennaWrites shouldBe listOf(1.5)
        vm.state.value.confirming shouldBe false
        vm.state.value.sending shouldBe false
        vm.state.value.result shouldBe WriteResult.Confirmed(1.5)
        gateway.infoCalls.size shouldBe 2
    }

    @Test
    fun `S044 RF-21 si el receptor guarda otro valor se enseña el que quedo y no se reintenta`() {
        gateway.antennaResult = { WriteResult.NotConfirmed(read = 1.6, requestFailed = false) }
        val vm = viewModel()
        vm.onSend()
        vm.onConfirm()
        scope.runCurrent()

        vm.state.value.result shouldBe WriteResult.NotConfirmed(read = 1.6, requestFailed = false)
        scope.runCurrent()
        gateway.antennaWrites.size shouldBe 1
    }

    @Test
    fun `S044 RF-21 si no se llega a escribir se dice por que`() {
        gateway.antennaResult = { WriteResult.Failed(WriteFailure.NO_RESPONSE) }
        val vm = viewModel()
        vm.onSend()
        vm.onConfirm()
        scope.runCurrent()

        vm.state.value.result shouldBe WriteResult.Failed(WriteFailure.NO_RESPONSE)
    }

    @Test
    fun `S044 RF-15 con la informacion basica incompatible no se puede enviar nada`() {
        gateway.reading = ReachInfoReading(
            ReachPart.Incompatible(ReachPartId.IDENTITY, "x"),
            ReachPart.Ok(ReachDeviceSettings("rover", 1.8)),
        )
        val vm = viewModel()

        vm.state.value.canSend shouldBe false
        vm.onSend()
        vm.onConfirm()
        scope.runCurrent()

        vm.state.value.confirming shouldBe false
        gateway.antennaWrites shouldBe emptyList()
    }

    @Test
    fun `S044 RF-18 sin receptor al que escribir no se puede enviar`() {
        key.value = "phone"
        val vm = viewModel()

        vm.state.value.canSend shouldBe false
        vm.onSend()
        scope.runCurrent()
        vm.state.value.confirming shouldBe false
    }

    @Test
    fun `S044 RF-18 si cambia el modo mientras no se ha editado la propuesta se actualiza`() {
        val vm = viewModel()

        context.state.value = AntennaInputs(TiltMode.VERTICAL, 1.95)
        scope.runCurrent()

        vm.state.value.text shouldBe "0"
        vm.state.value.proposal.warnDoubleCount shouldBe true
    }

    @Test
    fun `S044 RF-18 lo escrito a mano no lo pisa un cambio de modo`() {
        val vm = viewModel()
        vm.onTextChange("1.2")

        context.state.value = AntennaInputs(TiltMode.VERTICAL, 1.95)
        scope.runCurrent()

        vm.state.value.text shouldBe "1.2"
    }

    @Test
    fun `S044 RF-22 enviar la altura no escribe nada en el equipo del proyecto ni en los logs`() {
        val vm = viewModel()
        vm.onSend()
        vm.onConfirm()
        scope.runCurrent()

        gateway.logWrites shouldBe emptyList()
        equipment.writes shouldBe emptyList()
    }

    @Test
    fun `S044 RF-19 cerrar el resultado lo borra`() {
        val vm = viewModel()
        vm.onSend()
        vm.onConfirm()
        scope.runCurrent()

        vm.onDismissResult()
        scope.runCurrent()

        vm.state.value.result shouldBe null
    }
}
