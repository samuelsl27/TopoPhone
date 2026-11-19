package es.topophone.app.experimental

import es.topophone.core.model.receiver.WifiReceiver
import es.topophone.receiver.experimental.ReachConfigEntry
import es.topophone.receiver.experimental.ReachConfigView
import es.topophone.receiver.experimental.ReachPart
import es.topophone.receiver.experimental.ReachPartId
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

/** S044/RF-14, RF-15: la lectura de la configuración del receptor en solo lectura. */
@OptIn(ExperimentalCoroutinesApi::class)
class ReceiverConfigViewModelTest {

    private val scope = TestScope()
    private val key = MutableStateFlow<String?>("wifi:a")
    private val gateway = FakeReachGateway()
    private val equipment = FakeEquipmentRepository()
    private val view = ReachConfigView(
        positioning = listOf(ReachConfigEntry("positioning_settings.snr_mask", "35", false)),
        output = listOf(ReachConfigEntry("position_output.output2.token", "", true)),
    )

    @Before
    fun setMain() = Dispatchers.setMain(StandardTestDispatcher(scope.testScheduler))

    @After
    fun resetMain() = Dispatchers.resetMain()

    private fun viewModel(): ReceiverConfigViewModel {
        val session = ExperimentalSession(
            active = MutableStateFlow(true),
            key = key,
            wifi = FakeWifiRepository(
                WifiReceiver("a", "RS3", "192.168.42.1", 9001, null),
                WifiReceiver("b", "RS3 b", "reach.local", 9001, null),
            ),
            equipment = equipment,
            gateway = gateway,
            scope = scope.backgroundScope,
        )
        return ReceiverConfigViewModel(session).also { scope.runCurrent() }
    }

    @Test
    fun `S044 RF-14 abrir la pantalla lee la configuracion una vez y la enseña`() {
        gateway.config = ReachPart.Ok(view)

        val vm = viewModel()

        vm.state.value shouldBe ConfigUiState.Loaded(ReachPart.Ok(view))
        gateway.configCalls.size shouldBe 1
    }

    @Test
    fun `S044 RF-15 una forma incompatible llega como parte incompatible`() {
        gateway.config = ReachPart.Incompatible(ReachPartId.SETTINGS, "falta configuration.positioning_settings")

        val vm = viewModel()

        vm.state.value shouldBe
            ConfigUiState.Loaded(ReachPart.Incompatible(ReachPartId.SETTINGS, "falta configuration.positioning_settings"))
    }

    @Test
    fun `S044 RF-14 Volver a leer pide la configuracion otra vez`() {
        gateway.config = ReachPart.Ok(view)
        val vm = viewModel()

        vm.onReload()
        scope.runCurrent()

        gateway.configCalls.size shouldBe 2
    }

    @Test
    fun `S044 RF-14 sin receptor al que preguntar no se lee nada`() {
        key.value = "phone"

        val vm = viewModel()

        vm.state.value shouldBe ConfigUiState.Unavailable
        gateway.configCalls shouldBe emptyList()
    }

    @Test
    fun `S044 RF-14 sin IP privada tampoco se lee`() {
        key.value = "wifi:b"

        val vm = viewModel()

        vm.state.value shouldBe ConfigUiState.Unavailable
        gateway.configCalls shouldBe emptyList()
    }

    @Test
    fun `S044 RF-14 la lectura no escribe nada ni en el receptor ni en el equipo`() {
        gateway.config = ReachPart.Ok(view)

        viewModel().onReload()
        scope.runCurrent()

        gateway.antennaWrites shouldBe emptyList()
        gateway.logWrites shouldBe emptyList()
        equipment.writes shouldBe emptyList()
    }
}
