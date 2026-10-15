package es.topophone.app.ui.receiver

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsSelected
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
import es.topophone.core.gnss.nmea.ReplaySpeed
import es.topophone.core.model.preferences.EffectiveTheme
import es.topophone.core.model.preferences.ThemeState
import es.topophone.receiver.session.ConnectionState
import es.topophone.receiver.session.ReceiverSnapshot
import es.topophone.receiver.session.ReplayInfo
import es.topophone.receiver.transport.BondedDevice
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Controles de la reproducción en la cabecera de la pantalla del receptor (S003/RF-35, RF-37, RF-40, RF-41, RF-43). Interfaz en español (es-ES). */
@RunWith(AndroidJUnit4::class)
class ReplayControlsTest {

    @get:Rule
    val compose = createComposeRule()

    private val rs3 = BondedDevice("reach-RS3", "00:11:22:33:44:55")
    private val replay = ReplayInfo("20261015-083000", ReplaySpeed.X1, loop = false)

    private fun show(snapshot: ReceiverSnapshot, actions: ReceiverActions = ReceiverActions()) {
        compose.setContent {
            TopoPhoneTheme(EffectiveTheme.LIGHT) {
                ReceiverScreen(ThemeState.DEFAULT, {
                }, ReceiverUiState(bluetooth = BluetoothUi.Ready(listOf(rs3)), snapshot = snapshot), actions)
            }
        }
    }

    private fun replaying() = ReceiverSnapshot(ConnectionState.Receiving(replay.name), replay = replay)

    @Test
    fun `S003 RF-35 durante la reproduccion se indica Reproduciendo con la sesion y la velocidad`() {
        show(replaying())

        compose.onNodeWithTag(HEADER_NAME_TAG).assert(hasText(replay.name))
        compose.onNodeWithTag(CONNECTION_STATUS_TAG).assert(hasText(text(R.string.receiver_header_replaying)))
        compose.onNodeWithTag(REPLAY_SPEED_X1_TAG).performScrollTo().assertIsSelected()
        compose.onNodeWithTag(REPLAY_LOOP_TAG).performScrollTo().assertIsOff()
    }

    @Test
    fun `S003 RF-37 la velocidad y el bucle se cambian desde la tarjeta`() {
        var speed: ReplaySpeed? = null
        var loop: Boolean? = null
        show(replaying(), ReceiverActions(onReplaySpeed = { speed = it }, onReplayLoop = { loop = it }))

        compose.onNodeWithTag(REPLAY_SPEED_X5_TAG).performScrollTo().performClick()
        compose.onNodeWithTag(REPLAY_LOOP_TAG).performScrollTo().performClick()

        speed shouldBe ReplaySpeed.X5
        loop shouldBe true
    }

    @Test
    fun `S003 RF-41 Detener termina la reproduccion`() {
        var stopped = false
        show(replaying(), ReceiverActions(onStopReplay = { stopped = true }))

        compose.onNodeWithTag(REPLAY_STOP_TAG).performScrollTo().performClick()

        stopped shouldBe true
        compose.onNodeWithTag(DISCONNECT_TAG).assertDoesNotExist()
    }

    @Test
    fun `S003 RF-40 al terminar sin bucle se indica y se ofrece volver a reproducir`() {
        var again = false
        show(
            ReceiverSnapshot(ConnectionState.ReplayFinished(replay.name), replay = replay),
            ReceiverActions(onReplayAgain = {
                again = true
            }),
        )

        compose.onNodeWithTag(CONNECTION_STATUS_TAG).assert(hasText(text(R.string.receiver_replay_finished, replay.name)))
        text(R.string.receiver_replay_finished, replay.name).startsWith("Reproducción terminada") shouldBe true
        compose.onNodeWithTag(REPLAY_AGAIN_TAG).performScrollTo().performClick()

        again shouldBe true
    }

    @Test
    fun `S003 RF-43 durante la reproduccion se puede elegir un receptor para conectarlo`() {
        var chosen: String? = null
        show(replaying(), ReceiverActions(onConnect = { chosen = it }))

        compose.onNodeWithTag(deviceTag(rs3.address)).performScrollTo().assertIsDisplayed().performClick()

        chosen shouldBe rs3.address
    }

    @Test
    fun `S003 RF-37 la fila del bucle se activa pulsando tambien su texto`() {
        var loop: Boolean? = null
        show(replaying(), ReceiverActions(onReplayLoop = { loop = it }))

        compose.onNodeWithText(text(R.string.replay_loop)).performScrollTo().performClick()

        loop shouldBe true
    }

    @Test
    fun `S003 RF-35 al empezar una reproduccion la pantalla sube hasta la tarjeta Conexion`() {
        var snapshot by mutableStateOf(ReceiverSnapshot())
        compose.setContent {
            TopoPhoneTheme(EffectiveTheme.LIGHT) {
                ReceiverScreen(ThemeState.DEFAULT, {
                }, ReceiverUiState(bluetooth = BluetoothUi.Ready(listOf(rs3)), snapshot = snapshot), ReceiverActions())
            }
        }
        compose.onNodeWithTag(OPEN_SESSIONS_TAG).performScrollTo()
        compose.onNodeWithTag(CONNECTION_STATUS_TAG).assertIsNotDisplayed()

        snapshot = replaying()
        compose.waitForIdle()

        compose.onNodeWithTag(CONNECTION_STATUS_TAG).assertIsDisplayed()
    }
}
