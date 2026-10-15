package es.topophone.app.ui.receiver

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.core.designsystem.theme.TopoPhoneTheme
import es.topophone.core.gnss.nmea.ReplaySpeed
import es.topophone.core.model.preferences.EffectiveTheme
import es.topophone.core.model.preferences.ThemeState
import es.topophone.receiver.session.ConnectFailure
import es.topophone.receiver.session.ConnectionState
import es.topophone.receiver.session.ReceiverSnapshot
import es.topophone.receiver.session.ReplayInfo
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Qué muestra la raíz de Receptor: la lista de receptores o la pantalla del receptor (S005/RF-1, RF-5). */
@RunWith(AndroidJUnit4::class)
class ReceiverHomeModeTest {

    @get:Rule
    val compose = createComposeRule()

    private val replay = ReplayInfo("sesion", ReplaySpeed.X1, loop = false)

    @Test
    fun `S005 RF-1 sin receptor conectado, conectandose ni reconectandose se muestra la lista`() {
        listOf(
            ConnectionState.Disconnected,
            ConnectionState.Failed(ConnectFailure.TIMEOUT),
            ConnectionState.Failed(ConnectFailure.BUSY),
            ConnectionState.Lost("reach-RS3"),
            ConnectionState.ReplayFinished("sesion"),
        ).forEach {
            receiverHomeMode(ReceiverSnapshot(it)) shouldBe ReceiverHomeMode.LIST
        }
        receiverHomeMode(ReceiverSnapshot(ConnectionState.ReplayFinished("sesion"), replay = replay)) shouldBe ReceiverHomeMode.LIST
    }

    @Test
    fun `S005 RF-5 conectado, conectandose o reconectandose se muestra la pantalla del receptor`() {
        listOf(
            ConnectionState.Connecting("reach-RS3"),
            ConnectionState.Receiving("reach-RS3"),
            ConnectionState.NoData("reach-RS3"),
            ConnectionState.Reconnecting("reach-RS3", 2, 5_000, 0),
        ).forEach {
            receiverHomeMode(ReceiverSnapshot(it)) shouldBe ReceiverHomeMode.DASHBOARD
        }
    }

    @Test
    fun `S005 RF-5 con una reproduccion en curso se muestra la pantalla del receptor`() {
        receiverHomeMode(ReceiverSnapshot(ConnectionState.Receiving("sesion"), replay = replay)) shouldBe ReceiverHomeMode.DASHBOARD
        receiverHomeMode(ReceiverSnapshot(ConnectionState.Connecting("sesion"), replay = replay)) shouldBe ReceiverHomeMode.DASHBOARD
    }

    @Test
    fun `S005 RF-1 S005 RF-5 la raiz de Receptor cambia de la lista a la pantalla del receptor y vuelve con el estado`() {
        var snapshot by mutableStateOf(ReceiverSnapshot())
        compose.setContent {
            TopoPhoneTheme(EffectiveTheme.LIGHT) {
                val state = ReceiverUiState(bluetooth = BluetoothUi.Ready(listOf(RS3_DEVICE)), snapshot = snapshot)
                ReceiverScreen(ThemeState.DEFAULT, {}, state, ReceiverActions())
            }
        }
        compose.onNodeWithTag(RECEIVER_LIST_TAG).assertIsDisplayed()
        compose.onNodeWithTag(RECEIVER_DASHBOARD_TAG).assertDoesNotExist()

        snapshot = ReceiverSnapshot(ConnectionState.Receiving("reach-RS3"))
        compose.waitForIdle()
        compose.onNodeWithTag(RECEIVER_DASHBOARD_TAG).assertIsDisplayed()
        compose.onNodeWithTag(RECEIVER_LIST_TAG).assertDoesNotExist()

        snapshot = ReceiverSnapshot(ConnectionState.Lost("reach-RS3"))
        compose.waitForIdle()
        compose.onNodeWithTag(RECEIVER_LIST_TAG).assertIsDisplayed()
    }
}
