package es.topophone.app.ui.receiver

import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.app.R
import es.topophone.app.ui.text
import es.topophone.core.designsystem.theme.TopoPhoneTheme
import es.topophone.core.model.preferences.EffectiveTheme
import es.topophone.core.model.preferences.ThemeState
import es.topophone.receiver.session.ConnectionState
import es.topophone.receiver.session.ReceiverSnapshot
import es.topophone.receiver.transport.BondedDevice
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Tarjeta Conexión durante la reconexión (S004/RF-13, RF-15, RF-16, RF-20, RF-21, RF-22). Interfaz en español (es-ES). */
@RunWith(AndroidJUnit4::class)
class ReconnectingCardTest {

    @get:Rule
    val compose = createComposeRule()

    private val rs3 = BondedDevice("reach-RS3", "00:11:22:33:44:55")

    private fun show(connection: ConnectionState, nowMs: Long = 10_000, actions: ReceiverActions = ReceiverActions()) {
        compose.setContent {
            TopoPhoneTheme(EffectiveTheme.LIGHT) {
                ReceiverScreen(
                    ThemeState.DEFAULT,
                    {},
                    ReceiverUiState(bluetooth = BluetoothUi.Ready(listOf(rs3)), snapshot = ReceiverSnapshot(connection), nowMs = nowMs),
                    actions,
                )
            }
        }
    }

    private fun reconnecting(attempt: Int = 3, nextAt: Long? = 14_200, bluetoothOff: Boolean = false) =
        ConnectionState.Reconnecting("reach-RS3", attempt, nextAt, lostAtMs = 1_000, bluetoothOff = bluetoothOff)

    @Test
    fun `S004 RF-13 al perder el enlace se indica que se esta reconectando`() {
        show(reconnecting())

        compose.onNodeWithTag(HEADER_NAME_TAG).assert(hasText("reach-RS3"))
        compose.onNodeWithTag(CONNECTION_STATUS_TAG).assert(hasText(text(R.string.receiver_header_reconnecting)))
    }

    @Test
    fun `S004 RF-15 se muestran el numero de intento y los segundos hasta el siguiente`() {
        show(reconnecting(attempt = 3, nextAt = 14_200), nowMs = 10_000)

        compose.onNodeWithTag(RECONNECT_DETAIL_TAG).assert(hasText(text(R.string.receiver_reconnect_waiting, 3, 5)))
    }

    @Test
    fun `S004 RF-15 durante un intento se indica que se esta intentando`() {
        show(reconnecting(attempt = 4, nextAt = null))

        compose.onNodeWithTag(RECONNECT_DETAIL_TAG).assert(hasText(text(R.string.receiver_reconnect_trying, 4)))
    }

    @Test
    fun `S004 RF-16 Reintentar ahora pide un intento al momento`() {
        var retried = false
        show(reconnecting(), actions = ReceiverActions(onRetryNow = { retried = true }))

        compose.onNodeWithTag(RETRY_NOW_TAG).performScrollTo().performClick()

        retried shouldBe true
    }

    @Test
    fun `S004 RF-21 durante la reconexion se puede desconectar`() {
        var disconnected = false
        show(reconnecting(), actions = ReceiverActions(onDisconnect = { disconnected = true }))

        compose.onNodeWithTag(DISCONNECT_TAG).performScrollTo().performClick()

        disconnected shouldBe true
    }

    @Test
    fun `S004 RF-22 con el Bluetooth apagado se indica y se sigue esperando`() {
        show(reconnecting(bluetoothOff = true))

        compose.onNodeWithText(text(R.string.receiver_reconnect_bluetooth_off)).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `S004 RF-20 tras rendirse se indica la conexion perdida y se ofrece reconectar`() {
        var reconnect = false
        show(ConnectionState.Lost("reach-RS3"), actions = ReceiverActions(onReconnect = { reconnect = true }))

        compose.onNodeWithTag(CONNECTION_STATUS_TAG).assert(hasText(text(R.string.receiver_state_lost, "reach-RS3")))
        compose.onNodeWithTag(RECONNECT_TAG).performClick()

        reconnect shouldBe true
    }
}
