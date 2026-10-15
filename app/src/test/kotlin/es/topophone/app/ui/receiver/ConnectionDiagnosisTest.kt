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
import es.topophone.receiver.session.ConnectFailure
import es.topophone.receiver.session.ConnectionState
import es.topophone.receiver.session.ReceiverSnapshot
import io.kotest.matchers.string.shouldContain
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Diagnósticos de la conexión (S004/RF-27, S004/RF-28). Interfaz en español (es-ES). */
@RunWith(AndroidJUnit4::class)
class ConnectionDiagnosisTest {

    @get:Rule
    val compose = createComposeRule()

    private fun show(snapshot: ReceiverSnapshot) {
        compose.setContent {
            TopoPhoneTheme(EffectiveTheme.LIGHT) {
                ReceiverScreen(
                    themeState = ThemeState.DEFAULT,
                    onToggleSun = {},
                    state = ReceiverUiState(bluetooth = BluetoothUi.Ready(emptyList()), snapshot = snapshot),
                    actions = ReceiverActions(),
                )
            }
        }
    }

    @Test
    fun `S004 RF-27 un rechazo inmediato apunta a otro dispositivo conectado al receptor`() {
        show(ReceiverSnapshot(ConnectionState.Failed(ConnectFailure.BUSY)))

        compose.onNodeWithTag(CONNECTION_STATUS_TAG).assert(hasText(text(R.string.receiver_state_failed_busy)))
        compose.onNodeWithText(text(R.string.receiver_busy_cause)).performScrollTo().assertIsDisplayed()
        text(R.string.receiver_busy_cause) shouldContain "Emlid Flow"
        compose.onNodeWithText(text(R.string.receiver_connect_failed_causes)).assertDoesNotExist()
    }

    @Test
    fun `S004 RF-27 los demas fallos siguen mostrando todas las causas probables`() {
        show(ReceiverSnapshot(ConnectionState.Failed(ConnectFailure.ERROR)))

        compose.onNodeWithText(text(R.string.receiver_connect_failed_causes)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(text(R.string.receiver_busy_cause)).assertDoesNotExist()
    }

    @Test
    fun `S004 RF-28 si no llega NMEA se indica que la salida de posicion puede estar desactivada`() {
        show(ReceiverSnapshot(ConnectionState.NoData("reach-RS3"), nmeaMissing = true))

        compose.onNodeWithTag(NMEA_MISSING_TAG).performScrollTo().assert(hasText(text(R.string.receiver_nmea_missing)))
        text(R.string.receiver_nmea_missing) shouldContain "salida de posición"
    }

    @Test
    fun `S004 RF-28 sin ese problema no se muestra`() {
        show(ReceiverSnapshot(ConnectionState.NoData("reach-RS3")))

        compose.onNodeWithTag(NMEA_MISSING_TAG).assertDoesNotExist()
    }
}
