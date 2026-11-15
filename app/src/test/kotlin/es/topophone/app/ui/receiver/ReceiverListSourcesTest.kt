package es.topophone.app.ui.receiver

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.core.designsystem.theme.TopoPhoneTheme
import es.topophone.core.model.preferences.EffectiveTheme
import es.topophone.core.model.preferences.ThemeState
import es.topophone.core.model.receiver.ReceiverKey
import es.topophone.core.model.receiver.WifiReceiver
import es.topophone.receiver.session.ConnectionState
import es.topophone.receiver.session.ReceiverSnapshot
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** El GNSS del móvil y el receptor virtual, siempre en la lista de receptores y en «Añadir receptor» (S022/RF-1, RF-12). */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "es-rES")
class ReceiverListSourcesTest {

    @get:Rule
    val compose = createComposeRule()

    private fun show(state: ReceiverUiState, actions: ReceiverActions = ReceiverActions()) {
        compose.setContent {
            TopoPhoneTheme(EffectiveTheme.LIGHT) { ReceiverScreen(ThemeState.DEFAULT, {}, state, actions) }
        }
    }

    private fun list(bluetooth: BluetoothUi = BluetoothUi.Ready(listOf(RS3_DEVICE))) = ReceiverUiState(
        bluetooth = bluetooth,
        snapshot = ReceiverSnapshot(ConnectionState.Disconnected),
        pairingSupported = true,
        wifiReceivers = listOf(WifiReceiver("w1", "RS3 hotspot", "192.168.42.1", 9001, null)),
    )

    @Test
    fun `S022 RF-1 la lista muestra el GNSS del movil y el receptor virtual junto a los demas`() {
        show(list())

        compose.onNodeWithTag(deviceTag(RS3_DEVICE.address)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag(wifiReceiverTag("w1")).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag(PHONE_ROW_TAG).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag(VIRTUAL_ROW_TAG).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("GNSS del móvil").assertIsDisplayed()
        compose.onNodeWithText("Receptor virtual").assertIsDisplayed()
    }

    @Test
    fun `S022 RF-12 las dos fuentes salen tambien con el Bluetooth apagado, sin permiso o sin Bluetooth`() {
        var shown by mutableStateOf(list(BluetoothUi.Disabled))
        compose.setContent {
            TopoPhoneTheme(EffectiveTheme.LIGHT) { ReceiverScreen(ThemeState.DEFAULT, {}, shown, ReceiverActions()) }
        }

        for (bluetooth in listOf(BluetoothUi.Disabled, BluetoothUi.PermissionNeeded, BluetoothUi.Unsupported)) {
            shown = list(bluetooth)
            compose.waitForIdle()

            compose.onNodeWithTag(PHONE_ROW_TAG).performScrollTo().assertIsDisplayed()
            compose.onNodeWithTag(VIRTUAL_ROW_TAG).performScrollTo().assertIsDisplayed()
        }
    }

    @Test
    fun `S022 RF-1 pulsar el GNSS del movil conecta con la clave phone y pulsar el virtual abre su eleccion`() {
        val connected = mutableListOf<String>()
        var virtualOpened = 0
        show(list(), ReceiverActions(onConnect = { connected += it }, onOpenVirtual = { virtualOpened++ }))

        compose.onNodeWithTag(PHONE_ROW_TAG).performScrollTo().performClick()
        compose.onNodeWithTag(VIRTUAL_ROW_TAG).performScrollTo().performClick()

        connected shouldBe listOf(ReceiverKey.PHONE)
        virtualOpened shouldBe 1
    }

    @Test
    fun `S022 RF-1 Anadir receptor ofrece Bluetooth, Wi-Fi, GNSS del movil y receptor virtual`() {
        show(list())

        compose.onNodeWithTag(ADD_RECEIVER_TAG).performScrollTo().performClick()

        compose.onNodeWithTag(ADD_BLUETOOTH_TAG).assertIsDisplayed()
        compose.onNodeWithTag(ADD_WIFI_TAG).assertIsDisplayed()
        compose.onNodeWithTag(ADD_PHONE_TAG).assertIsDisplayed()
        compose.onNodeWithTag(ADD_VIRTUAL_TAG).assertIsDisplayed()
    }

    @Test
    fun `S022 RF-1 elegir el movil o el virtual en Anadir receptor cierra la hoja y los abre`() {
        val connected = mutableListOf<String>()
        var virtualOpened = 0
        show(list(), ReceiverActions(onConnect = { connected += it }, onOpenVirtual = { virtualOpened++ }))

        compose.onNodeWithTag(ADD_RECEIVER_TAG).performScrollTo().performClick()
        compose.onNodeWithTag(ADD_PHONE_TAG).performClick()
        compose.onNodeWithTag(ADD_PHONE_TAG).assertDoesNotExist()
        compose.onNodeWithTag(ADD_RECEIVER_TAG).performScrollTo().performClick()
        compose.onNodeWithTag(ADD_VIRTUAL_TAG).performClick()

        connected shouldBe listOf(ReceiverKey.PHONE)
        virtualOpened shouldBe 1
        compose.onNodeWithTag(ADD_VIRTUAL_TAG).assertDoesNotExist()
    }
}
