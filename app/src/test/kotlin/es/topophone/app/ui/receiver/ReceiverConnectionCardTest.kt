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
import es.topophone.receiver.session.ConnectFailure
import es.topophone.receiver.session.ConnectionState
import es.topophone.receiver.session.ReceiverSnapshot
import es.topophone.receiver.transport.BondedDevice
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Tarjeta Conexión de la pantalla Receptor (S002/RF-1 a RF-10). */
@RunWith(AndroidJUnit4::class)
class ReceiverConnectionCardTest {

    @get:Rule
    val compose = createComposeRule()

    private val rs3 = BondedDevice("reach-RS3", "00:11:22:33:44:55")

    private fun show(state: ReceiverUiState, actions: ReceiverActions = ReceiverActions()) {
        compose.setContent {
            TopoPhoneTheme(EffectiveTheme.LIGHT) { ReceiverScreen(ThemeState.DEFAULT, {}, state, actions) }
        }
    }

    private fun connected(connection: ConnectionState) =
        ReceiverUiState(bluetooth = BluetoothUi.Ready(listOf(rs3)), snapshot = ReceiverSnapshot(connection))

    @Test
    fun `S002 RF-1 se muestran los dispositivos emparejados y elegir uno conecta con su direccion`() {
        var chosen: String? = null
        show(ReceiverUiState(bluetooth = BluetoothUi.Ready(listOf(rs3))), ReceiverActions(onConnect = { chosen = it }))

        compose.onNodeWithTag(deviceTag(rs3.address)).performScrollTo().assert(hasText("reach-RS3")).assert(hasText(rs3.address))
        compose.onNodeWithTag(deviceTag(rs3.address)).performClick()

        chosen shouldBe rs3.address
    }

    @Test
    fun `S002 RF-1 sin emparejados se explica como emparejar el receptor`() {
        show(ReceiverUiState(bluetooth = BluetoothUi.Ready(emptyList())))

        compose.onNodeWithText(text(R.string.receiver_no_bonded_devices)).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `S002 RF-2 sin permiso se explica para que es y se puede pedir`() {
        var requested = false
        show(ReceiverUiState(bluetooth = BluetoothUi.PermissionNeeded), ReceiverActions(onRequestPermission = { requested = true }))

        compose.onNodeWithText(text(R.string.receiver_bluetooth_permission_rationale)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag(PERMISSION_REQUEST_TAG).performScrollTo().performClick()

        requested shouldBe true
    }

    @Test
    fun `S002 RF-3 se indica como conceder el permiso desde los ajustes del movil`() {
        var opened = false
        show(ReceiverUiState(bluetooth = BluetoothUi.PermissionNeeded), ReceiverActions(onOpenAppSettings = { opened = true }))

        compose.onNodeWithText(text(R.string.receiver_bluetooth_permission_settings_help)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag(OPEN_SETTINGS_TAG).performScrollTo().performClick()

        opened shouldBe true
    }

    @Test
    fun `S002 RF-4 con el Bluetooth apagado se ofrece activarlo`() {
        var enable = false
        show(ReceiverUiState(bluetooth = BluetoothUi.Disabled), ReceiverActions(onEnableBluetooth = { enable = true }))

        compose.onNodeWithText(text(R.string.receiver_bluetooth_disabled)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag(ENABLE_BLUETOOTH_TAG).performScrollTo().performClick()

        enable shouldBe true
    }

    @Test
    fun `S002 RF-5 S002 RF-6 los estados Conectando y Recibiendo se muestran con el receptor`() {
        show(connected(ConnectionState.Connecting("reach-RS3")))
        compose.onNodeWithTag(HEADER_NAME_TAG).assert(hasText("reach-RS3"))
        compose.onNodeWithTag(CONNECTION_STATUS_TAG).assert(hasText(text(R.string.receiver_header_connecting)))
    }

    @Test
    fun `S002 RF-6 recibiendo datos`() {
        show(connected(ConnectionState.Receiving("reach-RS3")))
        compose.onNodeWithTag(HEADER_NAME_TAG).assert(hasText("reach-RS3"))
        compose.onNodeWithTag(CONNECTION_STATUS_TAG).assert(hasText(text(R.string.receiver_header_receiving)))
    }

    @Test
    fun `S002 RF-7 si no se puede conectar se muestran las causas probables, Emlid Flow incluido`() {
        show(connected(ConnectionState.Failed(ConnectFailure.TIMEOUT)))

        compose.onNodeWithTag(CONNECTION_STATUS_TAG).assert(hasText(text(R.string.receiver_state_failed_timeout)))
        compose.onNodeWithText(text(R.string.receiver_connect_failed_causes)).assertIsDisplayed()
        text(R.string.receiver_connect_failed_causes).contains("Emlid Flow") shouldBe true
    }

    @Test
    fun `S002 RF-8 sin datos NMEA se indica`() {
        show(connected(ConnectionState.NoData("reach-RS3")))
        compose.onNodeWithTag(CONNECTION_STATUS_TAG).assert(hasText(text(R.string.receiver_header_no_data)))
    }

    @Test
    fun `S002 RF-9 si se pierde la conexion se indica y se ofrece reconectar`() {
        var reconnect = false
        show(connected(ConnectionState.Lost("reach-RS3")), ReceiverActions(onReconnect = { reconnect = true }))

        compose.onNodeWithTag(CONNECTION_STATUS_TAG).assert(hasText(text(R.string.receiver_state_lost, "reach-RS3")))
        compose.onNodeWithTag(RECONNECT_TAG).performClick()

        reconnect shouldBe true
    }

    @Test
    fun `S002 RF-10 con el receptor conectado se puede desconectar`() {
        var disconnected = false
        show(connected(ConnectionState.Receiving("reach-RS3")), ReceiverActions(onDisconnect = { disconnected = true }))

        compose.onNodeWithTag(DISCONNECT_TAG).performClick()

        disconnected shouldBe true
    }

    @Test
    fun `S003 RF-28 si se deja de grabar por falta de espacio se avisa en la tarjeta Conexion`() {
        show(
            ReceiverUiState(
                bluetooth = BluetoothUi.Ready(listOf(rs3)),
                snapshot = ReceiverSnapshot(ConnectionState.Receiving("reach-RS3"), recordingStoppedLowSpace = true),
            ),
        )

        compose.onNodeWithText(text(R.string.receiver_recording_stopped_low_space)).assertIsDisplayed()
    }
}
