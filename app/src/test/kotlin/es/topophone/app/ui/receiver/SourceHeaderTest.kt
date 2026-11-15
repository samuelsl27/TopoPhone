package es.topophone.app.ui.receiver

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.core.designsystem.theme.TopoPhoneTheme
import es.topophone.core.gnss.nmea.ReplaySpeed
import es.topophone.core.gnss.scenario.ScenarioId
import es.topophone.core.model.point.PositionSource
import es.topophone.core.model.preferences.EffectiveTheme
import es.topophone.core.model.preferences.ThemeState
import es.topophone.receiver.session.ConnectionState
import es.topophone.receiver.session.ReceiverSnapshot
import es.topophone.receiver.session.ScenarioInfo
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * La cabecera con el receptor virtual y con el GNSS del móvil: «Simulado» y el escenario con su avance y velocidad, el móvil como
 * SINGLE, y las tres salidas al terminar el escenario (S022/RF-5, RF-16, RF-17, RNF-T8).
 */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "es-rES")
class SourceHeaderTest {

    @get:Rule
    val compose = createComposeRule()

    private fun show(snapshot: ReceiverSnapshot, actions: ReceiverActions = ReceiverActions()) {
        compose.setContent {
            TopoPhoneTheme(EffectiveTheme.LIGHT) {
                ReceiverScreen(ThemeState.DEFAULT, {}, ReceiverUiState(bluetooth = BluetoothUi.Disabled, snapshot = snapshot), actions)
            }
        }
    }

    private fun virtual(speed: ReplaySpeed = ReplaySpeed.X5, elapsed: Int = 45) = ReceiverSnapshot(
        connection = ConnectionState.Receiving("Receptor virtual"),
        source = PositionSource.VIRTUAL,
        scenario = ScenarioInfo(ScenarioId.FIX_LOSS_AND_RECOVERY, speed, durationS = 180, elapsedS = elapsed),
    )

    @Test
    fun `S022 RF-16 con el receptor virtual la cabecera dice Simulado y el nombre del escenario`() {
        show(virtual())

        compose.onNodeWithTag(HEADER_NAME_TAG).assertIsDisplayed()
        compose.onNodeWithText("Receptor virtual").assertIsDisplayed()
        compose.onNodeWithTag(SOURCE_BANNER_TAG).assertIsDisplayed()
        compose.onNodeWithText("Simulado · Pérdida de FIX y recuperación").assertIsDisplayed()
    }

    @Test
    fun `S022 RF-16 la cabecera muestra cuanto lleva el escenario de lo que dura`() {
        show(virtual(elapsed = 45))

        compose.onNodeWithTag(SCENARIO_PROGRESS_TAG).assertIsDisplayed()
        compose.onNodeWithText("Escenario: 0:45 de 3:00").assertIsDisplayed()
    }

    @Test
    fun `S022 RF-16 la velocidad se cambia desde la cabecera`() {
        val speeds = mutableListOf<ReplaySpeed>()
        show(virtual(speed = ReplaySpeed.X1), ReceiverActions(onScenarioSpeed = { speeds += it }))

        compose.onNodeWithTag(SCENARIO_SPEED_X5_TAG).performScrollTo().performClick()
        compose.onNodeWithTag(SCENARIO_SPEED_X1_TAG).performScrollTo().performClick()

        speeds shouldBe listOf(ReplaySpeed.X5, ReplaySpeed.X1)
    }

    @Test
    fun `S022 RF-5 con el GNSS del movil la cabecera dice SINGLE de pocos metros con texto`() {
        show(
            ReceiverSnapshot(
                connection = ConnectionState.Receiving("GNSS del móvil"),
                source = PositionSource.PHONE,
            ),
        )

        compose.onNodeWithText("GNSS del móvil").assertIsDisplayed()
        compose.onNodeWithTag(SOURCE_BANNER_TAG).assertIsDisplayed()
        compose.onNodeWithText("GNSS del móvil · SINGLE: posición de pocos metros").assertIsDisplayed()
    }

    @Test
    fun `S022 RF-5 con un receptor de siempre no hay aviso de fuente`() {
        show(ReceiverSnapshot(connection = ConnectionState.Receiving("reach-RS3")))

        compose.onNodeWithTag(SOURCE_BANNER_TAG).assertDoesNotExist()
        compose.onNodeWithTag(SCENARIO_PROGRESS_TAG).assertDoesNotExist()
    }

    @Test
    fun `S022 RF-17 al acabar el escenario se ofrece repetirlo, elegir otro o desconectar`() {
        var repeated = 0
        var other = 0
        var disconnected = 0
        show(
            ReceiverSnapshot(connection = ConnectionState.ScenarioFinished("Receptor virtual"), source = PositionSource.VIRTUAL),
            ReceiverActions(onRepeatScenario = { repeated++ }, onOpenVirtual = { other++ }, onDisconnect = { disconnected++ }),
        )

        compose.onNodeWithText("Escenario terminado: Receptor virtual").assertIsDisplayed()
        compose.onNodeWithTag(SCENARIO_REPEAT_TAG).performScrollTo().performClick()
        compose.onNodeWithTag(SCENARIO_OTHER_TAG).performScrollTo().performClick()
        compose.onNodeWithTag(SCENARIO_DISCONNECT_TAG).performScrollTo().performClick()

        repeated shouldBe 1
        other shouldBe 1
        disconnected shouldBe 1
    }

    @Test
    fun `S022 RF-16 el tiempo del escenario se escribe como minutos y segundos`() {
        formatScenarioTime(0) shouldBe "0:00"
        formatScenarioTime(45) shouldBe "0:45"
        formatScenarioTime(120) shouldBe "2:00"
        formatScenarioTime(185) shouldBe "3:05"
    }
}
