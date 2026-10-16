package es.topophone.app.ui.receiver

import android.app.Activity
import android.app.PendingIntent
import android.content.Intent
import android.content.IntentSender
import android.provider.Settings
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.app.R
import es.topophone.app.receiver.ReceiverConnections
import es.topophone.app.ui.text
import es.topophone.core.designsystem.theme.TopoPhoneTheme
import es.topophone.core.model.preferences.EffectiveTheme
import es.topophone.core.model.preferences.ThemeState
import es.topophone.core.ntrip.NtripConnector
import es.topophone.core.ntrip.NtripFailure
import es.topophone.core.survey.GroundPointCalculator
import es.topophone.receiver.session.ConnectionState
import es.topophone.receiver.session.ReceiverSession
import es.topophone.receiver.session.ReplayController
import es.topophone.receiver.transport.BondedDevice
import es.topophone.receiver.transport.PairingResult
import es.topophone.receiver.transport.ReceiverPairing
import es.topophone.receiver.transport.StreamTransport
import io.kotest.matchers.shouldBe
import java.io.ByteArrayOutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
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

/** "Añadir receptor" en la tarjeta Conexión (S004/RF-1, RF-2, RF-3, RF-4). Interfaz en español (es-ES). */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class AddReceiverTest {

    @get:Rule
    val compose = createComposeRule()

    private val sessionScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val toApp = PipedOutputStream()
    private val bluetooth = FakeBluetooth()
    private val preferences = FakePreferences()
    private val connector = NtripConnector { _, _, _, _ -> NtripFailure.UNREACHABLE }
    private val session = ReceiverSession(sessionScope, connector, noDataTimeoutMs = 60_000, watchdogPeriodMs = 1_000)
    private val chooser: IntentSender = PendingIntent.getActivity(
        ApplicationProvider.getApplicationContext(),
        0,
        Intent(),
        PendingIntent.FLAG_IMMUTABLE,
    ).intentSender

    private class FakePairing(
        override val isSupported: Boolean = true,
        var chooser: IntentSender? = null,
        var result: PairingResult = PairingResult.SearchCancelled,
    ) : ReceiverPairing {
        var completed: Pair<Int, Intent?>? = null

        override suspend fun search() = chooser

        override suspend fun complete(resultCode: Int, data: Intent?): PairingResult {
            completed = resultCode to data
            return result
        }
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        bluetooth.transports[RS3_DEVICE.address] = StreamTransport("reach-RS3", PipedInputStream(toApp, 64 * 1024), ByteArrayOutputStream())
    }

    @After
    fun tearDown() {
        session.disconnect()
        runCatching { toApp.close() }
        sessionScope.cancel()
        Dispatchers.resetMain()
    }

    private fun viewModel(pairing: ReceiverPairing) = ReceiverViewModel(
        session = session,
        bluetooth = bluetooth,
        preferences = preferences,
        calculator = { CALCULATOR },
        network = { true },
        alerts = NoAlerts,
        clock = { System.nanoTime() / 1_000_000 },
        samplePeriodMs = 0,
        replay = ReplayController(session) { emptyList() },
        connections = ReceiverConnections(session, bluetooth, preferences, CoroutineScope(Dispatchers.Default)),
        pairing = pairing,
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
    fun `S004 RF-1 Anadir receptor abre la busqueda del sistema una sola vez`() {
        val vm = viewModel(FakePairing(chooser = chooser))
        vm.awaitState { true }.pairingSupported shouldBe true

        vm.addReceiver()

        vm.awaitState { it.pairing.chooser == chooser }
        vm.chooserOpened()
        vm.awaitState { it.pairing.chooser == null }
    }

    @Test
    fun `S004 RF-2 S004 RF-3 al elegir un receptor se empareja y se conecta con el`() {
        val pairing = FakePairing(chooser = chooser, result = PairingResult.Paired(BondedDevice("reach-RS3", RS3_DEVICE.address)))
        val vm = viewModel(pairing)
        vm.addReceiver()
        val data = Intent()

        vm.receiverChosen(Activity.RESULT_OK, data)

        pairing.completed shouldBe (Activity.RESULT_OK to data)
        val state = vm.awaitState { it.snapshot.connection != ConnectionState.Disconnected }
        state.snapshot.connection shouldBe ConnectionState.Connecting("reach-RS3")
        state.pairing.message shouldBe null
    }

    @Test
    fun `S004 RF-4 si se cancela la busqueda se indica y sigue la lista de emparejados`() {
        val vm = viewModel(FakePairing(chooser = chooser, result = PairingResult.SearchCancelled))
        vm.addReceiver()

        vm.receiverChosen(Activity.RESULT_CANCELED, null)

        val state = vm.awaitState { it.pairing.message != null }
        state.pairing.message shouldBe PairingMessage.SEARCH_CANCELLED
        state.bluetooth shouldBe BluetoothUi.Ready(listOf(RS3_DEVICE))
        state.snapshot.connection shouldBe ConnectionState.Disconnected
    }

    @Test
    fun `S004 RF-4 si el emparejamiento falla o se cancela se indica`() {
        val vm = viewModel(FakePairing(chooser = chooser, result = PairingResult.PairingFailed))
        vm.addReceiver()

        vm.receiverChosen(Activity.RESULT_OK, Intent())

        vm.awaitState { it.pairing.message != null }.pairing.message shouldBe PairingMessage.PAIRING_FAILED
    }

    @Test
    fun `S004 RF-4 si el sistema no puede buscar se indica`() {
        val vm = viewModel(FakePairing(chooser = null))

        vm.addReceiver()

        vm.awaitState { it.pairing.message != null }.pairing.message shouldBe PairingMessage.SEARCH_FAILED
    }

    @Test
    fun `S004 RF-1 una busqueda nueva quita el aviso anterior`() {
        val pairing = FakePairing(chooser = null)
        val vm = viewModel(pairing)
        vm.addReceiver()
        vm.awaitState { it.pairing.message != null }

        pairing.chooser = chooser
        vm.addReceiver()

        vm.awaitState { it.pairing.chooser == chooser }.pairing.message shouldBe null
    }

    private fun show(state: ReceiverUiState, actions: ReceiverActions) {
        compose.setContent {
            TopoPhoneTheme(EffectiveTheme.LIGHT) { ReceiverScreen(ThemeState.DEFAULT, {}, state, actions) }
        }
    }

    private val ready = ReceiverUiState(bluetooth = BluetoothUi.Ready(listOf(RS3_DEVICE)), pairingSupported = true)

    @Test
    fun `S004 RF-1 la tarjeta Conexion ofrece Anadir receptor`() {
        var added = 0
        var settings = 0
        show(ready, ReceiverActions(onAddReceiver = { added++ }, onOpenBluetoothSettings = { settings++ }))

        compose.onNodeWithTag(ADD_RECEIVER_TAG).performScrollTo().assertIsDisplayed().performClick()

        added shouldBe 1
        settings shouldBe 0
    }

    @Test
    fun `S004 RF-1 sin busqueda del sistema Anadir receptor abre los ajustes de Bluetooth`() {
        var added = 0
        var settings = 0
        show(ready.copy(pairingSupported = false), ReceiverActions(onAddReceiver = { added++ }, onOpenBluetoothSettings = { settings++ }))

        compose.onNodeWithTag(ADD_RECEIVER_TAG).performScrollTo().performClick()

        added shouldBe 0
        settings shouldBe 1
        bluetoothSettingsIntent().action shouldBe Settings.ACTION_BLUETOOTH_SETTINGS
    }

    @Test
    fun `S004 RF-1 sin emparejados se invita a anadir el receptor`() {
        show(ready.copy(bluetooth = BluetoothUi.Ready(emptyList())), ReceiverActions())

        compose.onNodeWithText(text(R.string.receiver_no_bonded_devices)).assertIsDisplayed()
        compose.onNodeWithTag(ADD_RECEIVER_TAG).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `S004 RF-4 los avisos se muestran sin quitar la lista de emparejados`() {
        val messages = mapOf(
            PairingMessage.SEARCH_CANCELLED to R.string.receiver_pairing_search_cancelled,
            PairingMessage.SEARCH_FAILED to R.string.receiver_pairing_search_failed,
            PairingMessage.PAIRING_FAILED to R.string.receiver_pairing_failed,
        )
        var shown by mutableStateOf(ready)
        compose.setContent {
            TopoPhoneTheme(EffectiveTheme.LIGHT) { ReceiverScreen(ThemeState.DEFAULT, {}, shown, ReceiverActions()) }
        }

        messages.forEach { (message, res) ->
            shown = ready.copy(pairing = PairingUi(message = message))
            compose.waitForIdle()

            compose.onNodeWithText(text(res)).performScrollTo().assertIsDisplayed()
            compose.onNodeWithTag(deviceTag(RS3_DEVICE.address)).performScrollTo().assertIsDisplayed()
        }
    }

    private companion object {
        val CALCULATOR = GroundPointCalculator()
    }
}
