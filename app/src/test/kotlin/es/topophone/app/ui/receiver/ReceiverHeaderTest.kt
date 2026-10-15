package es.topophone.app.ui.receiver

import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.app.R
import es.topophone.app.ui.text
import es.topophone.core.designsystem.theme.TopoPhoneTheme
import es.topophone.core.gnss.nmea.Epoch
import es.topophone.core.gnss.nmea.TiltData
import es.topophone.core.gnss.nmea.TiltNotification
import es.topophone.core.gnss.nmea.TiltState
import es.topophone.core.model.preferences.EffectiveTheme
import es.topophone.core.model.preferences.ThemeState
import es.topophone.receiver.session.ConnectionState
import es.topophone.receiver.session.ReceiverSnapshot
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Cabecera de la pantalla del receptor (S003/RF-20, RF-22). Interfaz en español (es-ES). */
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

    private fun show(snapshot: ReceiverSnapshot) {
        compose.setContent {
            TopoPhoneTheme(EffectiveTheme.LIGHT) {
                val state = ReceiverUiState(bluetooth = BluetoothUi.Ready(listOf(RS3_DEVICE)), snapshot = snapshot)
                ReceiverScreen(ThemeState.DEFAULT, {}, state, ReceiverActions())
            }
        }
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
