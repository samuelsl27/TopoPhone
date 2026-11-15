package es.topophone.app.ui.receiver

import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.app.R
import es.topophone.app.ui.text
import es.topophone.core.designsystem.theme.TopoPhoneTheme
import es.topophone.core.model.preferences.EffectiveTheme
import es.topophone.core.model.preferences.ThemeState
import es.topophone.core.model.receiver.ReceiverKey
import es.topophone.receiver.session.ConnectFailure
import es.topophone.receiver.session.ConnectionState
import es.topophone.receiver.session.NtripState
import es.topophone.receiver.session.ReceiverSnapshot
import es.topophone.receiver.transport.CorrectionsLinkStatus
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Los diagnósticos de un receptor Wi-Fi: causas del fallo, salida TCP desactivada y los avisos de Correcciones (S021/RF-12 a RF-14, RF-16). */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "es-rES")
class WifiDiagnosticsTest {

    @get:Rule
    val compose = createComposeRule()

    private val wifiKey = ReceiverKey.wifi("w1")

    private fun state(snapshot: ReceiverSnapshot, key: String?) =
        ReceiverUiState(bluetooth = BluetoothUi.Ready(emptyList()), snapshot = snapshot, receiverKey = key)

    private fun show(state: ReceiverUiState) {
        compose.setContent {
            TopoPhoneTheme(EffectiveTheme.LIGHT) { ReceiverScreen(ThemeState.DEFAULT, {}, state, ReceiverActions()) }
        }
    }

    @Test
    fun `S021 RF-14 un fallo al conectar con un receptor Wi-Fi da las cuatro causas probables`() {
        show(state(ReceiverSnapshot(ConnectionState.Failed(ConnectFailure.TIMEOUT)), wifiKey))

        compose.onNodeWithText(text(R.string.wifi_connect_failed_causes)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(text(R.string.receiver_connect_failed_causes)).assertDoesNotExist()
        val causes = text(R.string.wifi_connect_failed_causes)
        causes shouldContain "red Wi-Fi del receptor" // el móvil no está en la red del receptor
        causes shouldContain "dirección y el puerto" // dirección o puerto incorrectos
        causes shouldContain "salida de posición por TCP" // desactivada en el receptor
        causes shouldContain "otro dispositivo" // el receptor ya tiene otro cliente
        causes shouldNotContain "Bluetooth"
    }

    @Test
    fun `S021 RF-14 un rechazo inmediato de un receptor Wi-Fi apunta a otro cliente, al puerto o a la salida TCP`() {
        show(state(ReceiverSnapshot(ConnectionState.Failed(ConnectFailure.BUSY)), wifiKey))

        compose.onNodeWithText(text(R.string.wifi_busy_cause)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(text(R.string.receiver_busy_cause)).assertDoesNotExist()
        text(R.string.wifi_busy_cause) shouldNotContain "Emlid"
    }

    @Test
    fun `S021 RF-14 con Bluetooth los diagnosticos son los de siempre`() {
        show(state(ReceiverSnapshot(ConnectionState.Failed(ConnectFailure.ERROR)), "AA:BB:CC:DD:EE:FF"))

        compose.onNodeWithText(text(R.string.receiver_connect_failed_causes)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(text(R.string.wifi_connect_failed_causes)).assertDoesNotExist()
    }

    @Test
    fun `S021 RF-14 sin receptor conocido los diagnosticos son los de siempre`() {
        show(state(ReceiverSnapshot(ConnectionState.Failed(ConnectFailure.ERROR)), null))

        compose.onNodeWithText(text(R.string.receiver_connect_failed_causes)).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `S021 RF-16 sin frases NMEA durante 10 segundos con Wi-Fi se habla de la salida de posicion por TCP`() {
        show(state(ReceiverSnapshot(ConnectionState.Receiving("RS3 (192.168.42.1:9001)"), nmeaMissing = true), wifiKey))

        compose.onNodeWithTag(NMEA_MISSING_TAG).assert(hasText(text(R.string.wifi_nmea_missing)))
        text(R.string.wifi_nmea_missing) shouldContain "salida de posición por TCP"
        text(R.string.wifi_nmea_missing) shouldNotContain "Bluetooth"
    }

    @Test
    fun `S021 RF-16 con Bluetooth el aviso de sin posicion es el de siempre`() {
        show(state(ReceiverSnapshot(ConnectionState.Receiving("reach-RS3"), nmeaMissing = true), "AA:BB:CC:DD:EE:FF"))

        compose.onNodeWithTag(NMEA_MISSING_TAG).assert(hasText(text(R.string.receiver_nmea_missing)))
    }

    private fun showCorrections(snapshot: ReceiverSnapshot) {
        compose.setContent {
            TopoPhoneTheme(EffectiveTheme.LIGHT) {
                CorrectionsScreen(
                    ThemeState.DEFAULT,
                    {},
                    {},
                    ReceiverUiState(snapshot = snapshot, receiverKey = wifiKey, nowMs = 1_000),
                    CorrectionsUiState(),
                    CorrectionsActions(),
                )
            }
        }
    }

    private val receiving = ConnectionState.Receiving("RS3 (192.168.42.1:9001)")

    @Test
    fun `S021 RF-12 sin puerto de correcciones Correcciones dice que el receptor las recibe por otra via`() {
        showCorrections(ReceiverSnapshot(receiving, ntrip = NtripState.NoCorrectionsPort))

        compose.onNodeWithTag(CORRECTIONS_STATUS_TAG).assert(hasText(text(R.string.ntrip_no_corrections_port)))
        text(R.string.ntrip_no_corrections_port) shouldContain "por otra vía"
    }

    @Test
    fun `S021 RF-13 con el enlace de correcciones caido se dice, con el intento y la espera, y la posicion sigue`() {
        showCorrections(ReceiverSnapshot(receiving, correctionsLink = CorrectionsLinkStatus.Failed(attempt = 3, nextAttemptAtMs = 5_000)))

        val line = text(R.string.corrections_link_failed, 3, 4)
        compose.onNodeWithTag(CORRECTIONS_LINK_TAG).assert(hasText(line))
        line shouldContain "reintento 3"
    }

    @Test
    fun `S021 RF-13 mientras se abre el enlace de correcciones se dice`() {
        showCorrections(ReceiverSnapshot(receiving, correctionsLink = CorrectionsLinkStatus.Connecting))

        compose.onNodeWithTag(CORRECTIONS_LINK_TAG).assert(hasText(text(R.string.corrections_link_connecting)))
    }

    @Test
    fun `S021 RF-13 con el enlace abierto o sin segundo enlace no hay linea de aviso`() {
        showCorrections(ReceiverSnapshot(receiving, correctionsLink = CorrectionsLinkStatus.Ready))
        compose.onNodeWithTag(CORRECTIONS_LINK_TAG).assertDoesNotExist()
    }

    @Test
    fun `S021 RF-13 sin segundo enlace tampoco hay aviso`() {
        showCorrections(ReceiverSnapshot(receiving))

        compose.onNodeWithTag(CORRECTIONS_LINK_TAG).assertDoesNotExist()
    }

    @Test
    fun `S021 RF-10 la causa de sin red con internet nombra los datos moviles`() {
        text(R.string.ntrip_needs_internet) shouldContain "datos móviles"
        ReceiverUiState(receiverKey = wifiKey).wifiReceiver shouldBe true
        ReceiverUiState(receiverKey = "AA:BB:CC:DD:EE:FF").wifiReceiver shouldBe false
        ReceiverUiState().wifiReceiver shouldBe false
    }
}
