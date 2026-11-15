package es.topophone.app.ui.receiver

import android.content.pm.ServiceInfo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.app.receiver.ReceiverConnections
import es.topophone.app.receiver.ReceiverService
import es.topophone.core.designsystem.theme.TopoPhoneTheme
import es.topophone.core.model.preferences.EffectiveTheme
import es.topophone.core.model.preferences.ThemeState
import es.topophone.core.model.receiver.ReceiverKey
import es.topophone.core.ntrip.NtripConnector
import es.topophone.core.ntrip.NtripFailure
import es.topophone.core.survey.GroundPointCalculator
import es.topophone.receiver.session.ConnectionState
import es.topophone.receiver.session.ReceiverSession
import es.topophone.receiver.session.ReplayController
import es.topophone.receiver.transport.LocationFix
import es.topophone.receiver.transport.LocationSource
import io.kotest.matchers.shouldBe
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
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
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * El GNSS del móvil pide su permiso de ubicación precisa al elegirlo y no antes, avisa si se deniega o si la ubicación está apagada
 * y arranca el servicio de tipo location solo con él (S022/RF-2, RF-3, RF-4, RNF-T1, RNF-T2).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "es-rES")
class PhonePermissionsTest {

    @get:Rule
    val compose = createComposeRule()

    private class FakeLocation(var permission: Boolean = false, var enabled: Boolean = true) : LocationSource {
        val started = AtomicInteger()

        override fun hasPermission() = permission

        override fun isEnabled() = enabled

        override fun start(onFix: (LocationFix) -> Unit) {
            started.incrementAndGet()
        }

        override fun stop() = Unit
    }

    private val sessionScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val bluetooth = FakeBluetooth()
    private val preferences = FakePreferences()
    private val location = FakeLocation()
    private val connector = NtripConnector { _, _, _, _ -> NtripFailure.UNREACHABLE }
    private val session = ReceiverSession(sessionScope, connector, noDataTimeoutMs = 60_000, watchdogPeriodMs = 1_000)
    private val connectingKeys = mutableListOf<String>()
    private val connections = ReceiverConnections(
        session,
        bluetooth,
        preferences,
        CoroutineScope(Dispatchers.Default),
        locationSource = location,
        onConnecting = { connectingKeys += it },
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        session.disconnect()
        sessionScope.cancel()
        Dispatchers.resetMain()
    }

    private fun viewModel() = ReceiverViewModel(
        session = session,
        bluetooth = bluetooth,
        preferences = preferences,
        calculator = { CALCULATOR },
        network = { true },
        alerts = NoAlerts,
        clock = { System.nanoTime() / 1_000_000 },
        samplePeriodMs = 0,
        replay = ReplayController(session) { emptyList() },
        connections = connections,
        location = location,
    )

    private fun ReceiverViewModel.awaitState(predicate: (ReceiverUiState) -> Boolean): ReceiverUiState = runBlocking {
        val collector = CoroutineScope(Dispatchers.Unconfined).launch { uiState.collect {} }
        try {
            withTimeout(5_000) { uiState.first(predicate) }
        } finally {
            collector.cancel()
        }
    }

    @Test
    fun `S022 RNF-T1 al abrir la app no se pide ningun permiso de ubicacion`() {
        val vm = viewModel()

        val state = vm.awaitState { true }

        state.askPhonePermission shouldBe false
        state.phonePermissionDenied shouldBe false
        state.phoneLocationOff shouldBe false
        connectingKeys.size shouldBe 0
    }

    @Test
    fun `S022 RF-2 elegir el movil sin permiso lo pide con su explicacion y aun no conecta`() {
        val vm = viewModel()

        vm.connect(ReceiverKey.PHONE)

        val state = vm.awaitState { it.askPhonePermission }
        state.snapshot.connection shouldBe ConnectionState.Disconnected
        connectingKeys.size shouldBe 0
        location.started.get() shouldBe 0
    }

    @Test
    fun `S022 RF-2 con el permiso concedido en el dialogo se conecta con el movil`() {
        val vm = viewModel()
        vm.connect(ReceiverKey.PHONE)
        vm.awaitState { it.askPhonePermission }

        location.permission = true
        vm.phonePermissionAnswered(granted = true)

        val state = vm.awaitState { !it.askPhonePermission && it.snapshot.connection !is ConnectionState.Disconnected }
        state.phonePermissionDenied shouldBe false
        connectingKeys shouldBe listOf(ReceiverKey.PHONE)
        runBlocking { withTimeout(5_000) { while (location.started.get() == 0) kotlinx.coroutines.delay(10) } }
    }

    @Test
    fun `S022 RF-2 con el permiso denegado se avisa de como concederlo y no se conecta`() {
        val vm = viewModel()
        vm.connect(ReceiverKey.PHONE)
        vm.awaitState { it.askPhonePermission }

        vm.phonePermissionAnswered(granted = false)

        val state = vm.awaitState { it.phonePermissionDenied }
        state.askPhonePermission shouldBe false
        state.snapshot.connection shouldBe ConnectionState.Disconnected
        connectingKeys.size shouldBe 0
    }

    @Test
    fun `S022 RF-2 si se concede desde los ajustes el aviso se quita al volver a la pantalla`() {
        val vm = viewModel()
        vm.connect(ReceiverKey.PHONE)
        vm.phonePermissionAnswered(granted = false)
        vm.awaitState { it.phonePermissionDenied }

        location.permission = true
        vm.refreshPhoneAccess()

        vm.awaitState { !it.phonePermissionDenied }.phoneLocationOff shouldBe false
    }

    @Test
    fun `S022 RF-3 con el permiso pero la ubicacion apagada lo dice y no conecta`() {
        location.permission = true
        location.enabled = false
        val vm = viewModel()

        vm.connect(ReceiverKey.PHONE)

        val state = vm.awaitState { it.phoneLocationOff }
        state.askPhonePermission shouldBe false
        state.snapshot.connection shouldBe ConnectionState.Disconnected
        connectingKeys.size shouldBe 0
    }

    @Test
    fun `S022 RF-3 al encender la ubicacion desde los ajustes el aviso se quita y se puede conectar`() {
        location.permission = true
        location.enabled = false
        val vm = viewModel()
        vm.connect(ReceiverKey.PHONE)
        vm.awaitState { it.phoneLocationOff }

        location.enabled = true
        vm.refreshPhoneAccess()
        vm.awaitState { !it.phoneLocationOff }
        vm.connect(ReceiverKey.PHONE)

        vm.awaitState { it.snapshot.connection !is ConnectionState.Disconnected }
        connectingKeys shouldBe listOf(ReceiverKey.PHONE)
    }

    @Test
    fun `S022 RF-4 el servicio es de tipo location solo con el movil y con el permiso concedido`() {
        ReceiverService.foregroundServiceType(location = true, locationGranted = true) shouldBe ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        ReceiverService.foregroundServiceType(location = true, locationGranted = false) shouldBe
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        ReceiverService.foregroundServiceType(location = false, locationGranted = true) shouldBe
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        ReceiverService.foregroundServiceType(location = false, locationGranted = false) shouldBe
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
    }

    @Test
    fun `S022 RF-4 las conexiones avisan del servicio con la clave de cada receptor`() {
        location.permission = true
        connections.connectPhone()

        connectingKeys shouldBe listOf(ReceiverKey.PHONE)
    }

    @Test
    fun `S022 RF-2 el dialogo del permiso explica para que sirve y los avisos llevan sus botones`() {
        compose.setContent {
            TopoPhoneTheme(EffectiveTheme.LIGHT) {
                ReceiverScreen(
                    ThemeState.DEFAULT,
                    {},
                    ReceiverUiState(
                        bluetooth = BluetoothUi.Disabled,
                        askPhonePermission = true,
                        phonePermissionDenied = true,
                        phoneLocationOff = true,
                    ),
                    ReceiverActions(),
                )
            }
        }

        compose.onNodeWithText("Ubicación precisa").assertIsDisplayed()
        compose.onNodeWithTag(PHONE_PERMISSION_ALLOW_TAG).assertIsDisplayed()
        compose.onNodeWithTag(PHONE_PERMISSION_ALLOW_TAG).performClick()
    }

    private companion object {
        val CALCULATOR = GroundPointCalculator()
    }
}
