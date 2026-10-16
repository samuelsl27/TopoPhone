package es.topophone.app.ui.receiver

import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
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
import es.topophone.data.receiver.SavedReceiver
import es.topophone.receiver.session.ConnectFailure
import es.topophone.receiver.session.ConnectionState
import es.topophone.receiver.session.ReceiverSession
import es.topophone.receiver.session.ReceiverSnapshot
import es.topophone.receiver.session.ReplayController
import es.topophone.receiver.transport.BondedDevice
import io.kotest.matchers.shouldBe
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
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Lista de receptores, sin conexión (S005/RF-2, RF-3, RF-4). Interfaz en español (es-ES). */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class ReceiverListTest {

    @get:Rule
    val compose = createComposeRule()

    private val other = BondedDevice("Auriculares", "AA:BB:CC:DD:EE:FF")

    private fun show(state: ReceiverUiState, actions: ReceiverActions = ReceiverActions()) {
        compose.setContent {
            TopoPhoneTheme(EffectiveTheme.LIGHT) { ReceiverScreen(ThemeState.DEFAULT, {}, state, actions) }
        }
    }

    private fun list(connection: ConnectionState = ConnectionState.Disconnected, lastReceiver: String? = null) = ReceiverUiState(
        bluetooth = BluetoothUi.Ready(listOf(other, RS3_DEVICE)),
        snapshot = ReceiverSnapshot(connection),
        lastReceiverAddress = lastReceiver,
        pairingSupported = true,
    )

    @Test
    fun `S005 RF-2 la lista muestra los emparejados, Anadir receptor y el acceso a las sesiones grabadas`() {
        var sessions = 0
        show(list(), ReceiverActions(onOpenSessions = { sessions++ }))

        compose.onNodeWithTag(deviceTag(RS3_DEVICE.address)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag(deviceTag(other.address)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag(ADD_RECEIVER_TAG).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag(LIST_SESSIONS_TAG).performScrollTo().assert(hasText(text(R.string.receiver_menu_sessions))).performClick()

        sessions shouldBe 1
    }

    @Test
    fun `S005 RF-2 cada fila de la lista mide al menos 56 dp`() {
        show(list())

        listOf(deviceTag(RS3_DEVICE.address), deviceTag(other.address), LIST_SESSIONS_TAG).forEach {
            val bounds = compose.onNodeWithTag(it).performScrollTo().getBoundsInRoot()
            (bounds.bottom - bounds.top >= 56.dp) shouldBe true
        }
    }

    @Test
    fun `S005 RF-3 el ultimo receptor con el que se conecto esta senalado`() {
        show(list(lastReceiver = RS3_DEVICE.address))

        compose.onNodeWithTag(deviceTag(RS3_DEVICE.address)).performScrollTo()
            .assert(hasText(text(R.string.receiver_last_used)))
        compose.onNodeWithTag(deviceTag(other.address)).performScrollTo()
            .assert(hasText(text(R.string.receiver_last_used)).not())
    }

    @Test
    fun `S005 RF-4 si la conexion fallo se muestra el motivo encima de la lista`() {
        show(list(ConnectionState.Failed(ConnectFailure.BUSY)))

        val status = compose.onNodeWithTag(CONNECTION_STATUS_TAG)
            .assert(hasText(text(R.string.receiver_state_failed_busy)))
            .getBoundsInRoot()
        val firstRow = compose.onNodeWithTag(deviceTag(other.address)).getBoundsInRoot()
        (status.bottom <= firstRow.top) shouldBe true
    }

    @Test
    fun `S005 RF-4 si la conexion se perdio se ofrece Reconectar encima de la lista`() {
        show(list(ConnectionState.Lost("reach-RS3")))

        compose.onNodeWithTag(CONNECTION_STATUS_TAG).assert(hasText(text(R.string.receiver_state_lost, "reach-RS3")))
        val reconnect = compose.onNodeWithTag(RECONNECT_TAG).assertIsDisplayed().getBoundsInRoot()
        val firstRow = compose.onNodeWithTag(deviceTag(other.address)).getBoundsInRoot()
        (reconnect.bottom <= firstRow.top) shouldBe true
    }

    @Test
    fun `S005 RF-3 el ultimo receptor sale de las preferencias del receptor`() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val preferences = FakePreferences().apply { lastReceiverFlow.value = SavedReceiver(RS3_DEVICE.address, "reach-RS3") }
            val session = ReceiverSession(scope, NtripConnector { _, _, _, _ -> NtripFailure.UNREACHABLE })
            val bluetooth = FakeBluetooth()
            val vm = ReceiverViewModel(
                session = session,
                bluetooth = bluetooth,
                preferences = preferences,
                calculator = { GroundPointCalculator() },
                network = { true },
                alerts = NoAlerts,
                clock = { System.nanoTime() / 1_000_000 },
                samplePeriodMs = 0,
                replay = ReplayController(session) { emptyList() },
                connections = ReceiverConnections(session, bluetooth, preferences, CoroutineScope(Dispatchers.Default)),
            )

            val state = runBlocking {
                val collector = CoroutineScope(Dispatchers.Unconfined).launch { vm.uiState.collect {} }
                try {
                    withTimeout(5_000) { vm.uiState.first { it.lastReceiverAddress != null } }
                } finally {
                    collector.cancel()
                }
            }

            state.lastReceiverAddress shouldBe RS3_DEVICE.address
        } finally {
            scope.cancel()
            Dispatchers.resetMain()
        }
    }
}
