package es.topophone.app.experimental

import es.topophone.core.model.guide.EquipmentProfile
import es.topophone.core.model.receiver.WifiReceiver
import es.topophone.receiver.experimental.ReachDeviceSettings
import es.topophone.receiver.experimental.ReachInfoReading
import es.topophone.receiver.experimental.ReachPart
import es.topophone.receiver.experimental.ReachPartId
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
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

/** S044/RF-11, RF-12, RF-15: el panel del módulo: lo leído, el perfil de equipo que solo se guarda al confirmar y la IP que se pide. */
@OptIn(ExperimentalCoroutinesApi::class)
class ExperimentalPanelViewModelTest {

    private val scope = TestScope()
    private val active = MutableStateFlow(true)
    private val key = MutableStateFlow<String?>("wifi:a")
    private val gateway = FakeReachGateway()
    private val equipment = FakeEquipmentRepository()
    private val wifi = FakeWifiRepository(
        WifiReceiver("a", "RS3 de la oficina", "192.168.42.1", 9001, null),
        WifiReceiver("b", "RS3 por nombre", "reach.local", 9001, null),
    )

    @Before
    fun setMain() = Dispatchers.setMain(StandardTestDispatcher(scope.testScheduler))

    @After
    fun resetMain() = Dispatchers.resetMain()

    private fun viewModel(): ExperimentalPanelViewModel {
        val session = ExperimentalSession(active, key, wifi, equipment, gateway, scope.backgroundScope)
        return ExperimentalPanelViewModel(session, equipment).also { scope.runCurrent() }
    }

    @Test
    fun `S044 RF-11 el panel junta la fuente y lo leido`() {
        val vm = viewModel()

        vm.state.value.source.shouldBeInstanceOf<ReachSource.Ready>()
        vm.state.value.info shouldBe ReachInfoState.Loaded(gateway.reading)
        vm.state.value.profileProposal shouldBe null
    }

    @Test
    fun `S044 RF-12 rellenar el perfil propone modelo, serie y firmware y no guarda nada hasta confirmar`() {
        val vm = viewModel()

        vm.onFillProfile()
        scope.runCurrent()

        vm.state.value.profileProposal shouldBe EquipmentProfile("Reach RS3", "SINTETICO0001", "v0.0")
        equipment.writes shouldBe emptyList()
    }

    @Test
    fun `S044 RF-12 confirmar guarda el perfil del receptor en uso y avisa`() {
        val vm = viewModel()
        vm.onFillProfile()
        scope.runCurrent()

        vm.onConfirmProfile()
        scope.runCurrent()

        equipment.profiles.value shouldBe mapOf("wifi:a" to EquipmentProfile("Reach RS3", "SINTETICO0001", "v0.0"))
        equipment.writes shouldBe listOf("perfil:wifi:a")
        vm.state.value.profileProposal shouldBe null
        vm.state.value.profileSaved shouldBe true
    }

    @Test
    fun `S044 RF-12 cancelar no guarda nada y cierra la propuesta`() {
        val vm = viewModel()
        vm.onFillProfile()
        scope.runCurrent()

        vm.onCancelProfile()
        scope.runCurrent()

        equipment.writes shouldBe emptyList()
        vm.state.value.profileProposal shouldBe null
        vm.state.value.profileSaved shouldBe false
    }

    @Test
    fun `S044 RF-15 con la identidad incompatible no se propone ningun perfil`() {
        gateway.reading = ReachInfoReading(
            ReachPart.Incompatible(ReachPartId.IDENTITY, "falta device.serial_number"),
            ReachPart.Ok(ReachDeviceSettings("rover", 1.8)),
        )
        val vm = viewModel()

        vm.onFillProfile()
        scope.runCurrent()

        vm.state.value.profileProposal shouldBe null
        equipment.writes shouldBe emptyList()
    }

    @Test
    fun `S044 RF-11 guardar una IP privada la deja en Datos del receptor y una publica se rechaza`() {
        key.value = "wifi:b"
        val vm = viewModel()
        vm.state.value.source shouldBe ReachSource.NeedsAddress("wifi:b")

        vm.onSaveAddress("8.8.8.8")
        scope.runCurrent()
        vm.state.value.addressRejected shouldBe true
        equipment.writes shouldBe emptyList()

        vm.onSaveAddress("192.168.1.99")
        scope.runCurrent()
        vm.state.value.addressRejected shouldBe false
        equipment.addresses.value shouldBe mapOf("wifi:b" to "192.168.1.99")
        vm.state.value.source.shouldBeInstanceOf<ReachSource.Ready>()
    }

    @Test
    fun `S044 RF-11 reintentar vuelve a leer el receptor`() {
        val vm = viewModel()

        vm.onRefresh()
        scope.runCurrent()

        gateway.infoCalls.size shouldBe 2
    }

    @Test
    fun `S044 RF-12 el aviso de perfil guardado se puede cerrar`() {
        val vm = viewModel()
        vm.onFillProfile()
        scope.runCurrent()
        vm.onConfirmProfile()
        scope.runCurrent()

        vm.onDismissSaved()
        scope.runCurrent()

        vm.state.value.profileSaved shouldBe false
    }
}
