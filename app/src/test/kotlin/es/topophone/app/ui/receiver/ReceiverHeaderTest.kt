package es.topophone.app.ui.receiver

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.app.R
import es.topophone.app.ui.text
import es.topophone.core.designsystem.theme.TopoPhoneTheme
import es.topophone.core.gnss.nmea.Epoch
import es.topophone.core.gnss.nmea.ReplaySpeed
import es.topophone.core.gnss.nmea.TiltData
import es.topophone.core.gnss.nmea.TiltNotification
import es.topophone.core.gnss.nmea.TiltState
import es.topophone.core.model.preferences.EffectiveTheme
import es.topophone.core.model.preferences.ThemeState
import es.topophone.receiver.session.ConnectionState
import es.topophone.receiver.session.ReceiverSnapshot
import es.topophone.receiver.session.ReplayInfo
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Cabecera de la pantalla del receptor (S005/RF-6 a RF-8; S003/RF-20, RF-22, RF-35). Interfaz en español (es-ES). */
@RunWith(AndroidJUnit4::class)
class ReceiverHeaderTest {

    @get:Rule
    val compose = createComposeRule()

    private val receiving = ReceiverSnapshot(ConnectionState.Receiving("reach-RS3"))

    private fun tiltEpoch(state: TiltState) = Epoch(
        36_930_000,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        TiltData(36_930_000, state, TiltNotification.NONE, null, null, null, null),
        emptyMap(),
        emptyMap(),
        0,
    )

    private fun show(snapshot: ReceiverSnapshot, notificationsDenied: Boolean = false) {
        compose.setContent {
            TopoPhoneTheme(EffectiveTheme.LIGHT) {
                val state = ReceiverUiState(
                    bluetooth = BluetoothUi.Ready(listOf(RS3_DEVICE)),
                    snapshot = snapshot,
                    notificationsDenied = notificationsDenied,
                )
                ReceiverScreen(ThemeState.DEFAULT, {}, state, ReceiverActions())
            }
        }
    }

    private var snapshot by mutableStateOf(ReceiverSnapshot())

    /** Una sola composición cuyo estado cambia el test. */
    private fun showChanging() {
        compose.setContent {
            TopoPhoneTheme(EffectiveTheme.LIGHT) {
                ReceiverScreen(ThemeState.DEFAULT, {}, ReceiverUiState(snapshot = snapshot), ReceiverActions())
            }
        }
    }

    private fun inHeader(tag: String) = compose.onNode(hasTestTag(tag) and hasAnyAncestor(hasTestTag(RECEIVER_HEADER_TAG)))

    @Test
    fun `S005 RF-6 la cabecera muestra el nombre del receptor y el estado de la conexion`() {
        showChanging()
        mapOf(
            ConnectionState.Connecting("reach-RS3") to R.string.receiver_header_connecting,
            ConnectionState.Receiving("reach-RS3") to R.string.receiver_header_receiving,
            ConnectionState.NoData("reach-RS3") to R.string.receiver_header_no_data,
            ConnectionState.Reconnecting("reach-RS3", 1, 5_000, 0) to R.string.receiver_header_reconnecting,
        ).forEach { (connection, state) ->
            snapshot = ReceiverSnapshot(connection)
            compose.waitForIdle()
            inHeader(HEADER_NAME_TAG).assert(hasText("reach-RS3"))
            inHeader(CONNECTION_STATUS_TAG).assert(hasText(text(state)))
        }
    }

    @Test
    fun `S005 RF-6 S003 RF-35 durante una reproduccion la cabecera muestra el nombre de la sesion y Reproduciendo`() {
        val replay = ReplayInfo("20261015-083000", ReplaySpeed.X5, loop = false)

        show(ReceiverSnapshot(ConnectionState.Receiving(replay.name), replay = replay))

        inHeader(HEADER_NAME_TAG).assert(hasText(replay.name))
        inHeader(CONNECTION_STATUS_TAG).assert(hasText(text(R.string.receiver_header_replaying)))
        inHeader(REPLAY_SPEED_X5_TAG).performScrollTo().assertIsSelected()
    }

    @Test
    fun `S005 RF-7 la cabecera ofrece las acciones de cada estado`() {
        showChanging()
        snapshot = receiving
        compose.waitForIdle()
        inHeader(DISCONNECT_TAG).assertIsDisplayed()
        inHeader(RETRY_NOW_TAG).assertDoesNotExist()

        snapshot = ReceiverSnapshot(ConnectionState.Reconnecting("reach-RS3", 2, 5_000, 0))
        compose.waitForIdle()
        inHeader(RETRY_NOW_TAG).assertIsDisplayed()
        inHeader(DISCONNECT_TAG).assertIsDisplayed()

        val replay = ReplayInfo("sesion", ReplaySpeed.X1, loop = false)
        snapshot = ReceiverSnapshot(ConnectionState.Receiving("sesion"), replay = replay)
        compose.waitForIdle()
        listOf(REPLAY_SPEED_X1_TAG, REPLAY_SPEED_X5_TAG, REPLAY_LOOP_TAG, REPLAY_STOP_TAG).forEach {
            inHeader(it).performScrollTo().assertIsDisplayed()
        }
        inHeader(DISCONNECT_TAG).assertDoesNotExist()
    }

    @Test
    fun `S005 RF-7 S003 RF-43 durante una reproduccion la cabecera ofrece los emparejados para conectar, sin Anadir receptor`() {
        val replay = ReplayInfo("sesion", ReplaySpeed.X1, loop = false)

        show(ReceiverSnapshot(ConnectionState.Receiving("sesion"), replay = replay))

        inHeader(deviceTag(RS3_DEVICE.address)).performScrollTo().assertIsDisplayed()
        inHeader(ADD_RECEIVER_TAG).assertDoesNotExist()
    }

    @Test
    fun `S005 RF-7 durante una reproduccion sin emparejados la cabecera no habla de emparejar`() {
        val replay = ReplayInfo("sesion", ReplaySpeed.X1, loop = false)
        compose.setContent {
            TopoPhoneTheme(EffectiveTheme.LIGHT) {
                val state = ReceiverUiState(
                    bluetooth = BluetoothUi.Ready(emptyList()),
                    snapshot = ReceiverSnapshot(ConnectionState.Receiving("sesion"), replay = replay),
                )
                ReceiverScreen(ThemeState.DEFAULT, {}, state, ReceiverActions())
            }
        }

        compose.onNodeWithText(text(R.string.receiver_no_bonded_devices)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.receiver_replay_connect)).assertDoesNotExist()
    }

    @Test
    fun `S005 RF-8 la cabecera muestra los avisos de la conexion`() {
        show(receiving.copy(nmeaMissing = true, recordingStoppedLowSpace = true), notificationsDenied = true)

        inHeader(NMEA_MISSING_TAG).assertIsDisplayed()
        inHeader(ENABLE_NOTIFICATIONS_TAG).performScrollTo().assertIsDisplayed()
        inHeader(LOW_SPACE_TAG).performScrollTo().assert(hasText(text(R.string.receiver_recording_stopped_low_space)))
    }

    @Test
    fun `S003 RF-20 mientras se graba la cabecera muestra Grabando`() {
        show(receiving.copy(recording = true))

        compose.onNodeWithTag(RECORDING_TAG).assert(hasText(text(R.string.status_bar_recording))).assertIsDisplayed()
    }

    @Test
    fun `S003 RF-20 sin grabar no se muestra Grabando`() {
        show(receiving)

        compose.onNodeWithTag(RECORDING_TAG).assertDoesNotExist()
    }

    @Test
    fun `S003 RF-22 con frases ETC la cabecera muestra el estado de la inclinacion`() {
        mapOf(
            TiltState.OFF to "Apagada",
            TiltState.FATAL to "Error",
            TiltState.SETUP to "Configuración",
            TiltState.ALIGNMENT to "Alineación",
            TiltState.COMPENSATING to "Compensando",
        ).forEach { (state, name) ->
            text(tiltNameRes(state)) shouldBe name
        }
        show(receiving.copy(epoch = tiltEpoch(TiltState.COMPENSATING)))

        compose.onNodeWithTag(TILT_TAG).assert(hasText(text(R.string.status_bar_tilt, "Compensando")))
    }

    @Test
    fun `S003 RF-22 sin frases ETC no se muestra la inclinacion`() {
        show(receiving)

        compose.onNodeWithTag(TILT_TAG).assertDoesNotExist()
    }
}
