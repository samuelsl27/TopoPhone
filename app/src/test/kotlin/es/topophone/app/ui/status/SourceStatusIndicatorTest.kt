package es.topophone.app.ui.status

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.core.designsystem.theme.TopoPhoneTheme
import es.topophone.core.gnss.nmea.GgaFix
import es.topophone.core.gnss.nmea.ReplaySpeed
import es.topophone.core.gnss.nmea.SolutionStatus
import es.topophone.core.gnss.scenario.ScenarioId
import es.topophone.core.model.point.PositionSource
import es.topophone.core.model.preferences.EffectiveTheme
import es.topophone.receiver.session.ConnectionState
import es.topophone.receiver.session.ReceiverSnapshot
import es.topophone.receiver.session.ReplayInfo
import es.topophone.receiver.session.ScenarioInfo
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** El indicador de estado con el receptor virtual y con el GNSS del móvil: «Simulado» y el escenario, o «Móvil» y SINGLE (S022/RF-5, RF-16). */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "es-rES")
class SourceStatusIndicatorTest {

    @get:Rule
    val compose = createComposeRule()

    private fun show(state: StatusIndicatorState) {
        compose.setContent { TopoPhoneTheme(EffectiveTheme.LIGHT) { StatusIndicator(state, {}) } }
    }

    private fun gga(status: SolutionStatus) = GgaFix("100000.00", 43.0, -2.0, status, 12, null, 10.0, 0.0, null, "")

    @Test
    fun `S022 RF-16 el indicador con el receptor virtual dice Simulado, el escenario y la solucion con texto`() {
        show(
            StatusIndicatorState(
                source = StatusSource.VIRTUAL,
                solution = SolutionStatus.FIX,
                satellites = 22,
                scenario = ScenarioId.STABLE_FIX,
                speed = ReplaySpeed.X1,
            ),
        )

        compose.onNodeWithTag(STATUS_INDICATOR_SIMULATION_TAG, useUnmergedTree = true).assertExists()
        compose.onNodeWithText("Simulado", useUnmergedTree = true).assertExists()
        compose.onNodeWithTag(STATUS_INDICATOR_SCENARIO_TAG, useUnmergedTree = true).assertExists()
        compose.onNodeWithText("FIX estable", useUnmergedTree = true).assertExists()
        compose.onNodeWithTag(STATUS_INDICATOR_SOLUTION_TAG, useUnmergedTree = true).assertExists()
    }

    @Test
    fun `S022 RF-16 a velocidad x5 el indicador lo dice`() {
        show(
            StatusIndicatorState(
                source = StatusSource.VIRTUAL,
                solution = SolutionStatus.FIX,
                scenario = ScenarioId.DATA_GAP,
                speed = ReplaySpeed.X5,
            ),
        )

        compose.onNodeWithText("×5", useUnmergedTree = true).assertExists()
    }

    @Test
    fun `S022 RF-5 el indicador con el movil dice Movil y SINGLE con texto`() {
        show(StatusIndicatorState(source = StatusSource.PHONE, solution = SolutionStatus.SINGLE, satellites = 9))

        compose.onNodeWithTag(STATUS_INDICATOR_PHONE_TAG, useUnmergedTree = true).assertExists()
        compose.onNodeWithText("Móvil", useUnmergedTree = true).assertExists()
        compose.onNodeWithTag(STATUS_INDICATOR_SOLUTION_TAG, useUnmergedTree = true).assertExists()
        compose.onNodeWithText("Autónoma", useUnmergedTree = true).assertExists() // SINGLE se llama Autónoma en la app (glosario)
        compose.onNodeWithTag(STATUS_INDICATOR_SIMULATION_TAG, useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun `S022 RF-5 la reproduccion y el receptor de siempre se muestran como antes`() {
        show(StatusIndicatorState(source = StatusSource.RECEIVER, solution = SolutionStatus.FIX, satellites = 24))
        compose.onNodeWithTag(STATUS_INDICATOR_SCENARIO_TAG, useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithTag(STATUS_INDICATOR_PHONE_TAG, useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithTag(STATUS_INDICATOR_SIMULATION_TAG, useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun `S022 RF-16 la fuente del indicador sale de la sesion segun sea virtual, del movil, reproduccion o receptor`() {
        val receiving = ConnectionState.Receiving("x")
        val virtual = ReceiverSnapshot(
            connection = receiving,
            gga = gga(SolutionStatus.FIX),
            source = PositionSource.VIRTUAL,
            scenario = ScenarioInfo(ScenarioId.BASE_CHANGE, ReplaySpeed.X5, 150, 10),
        )
        val phone = ReceiverSnapshot(connection = receiving, gga = gga(SolutionStatus.SINGLE), source = PositionSource.PHONE)
        val mock = phone.copy(source = PositionSource.PHONE_MOCK)
        val replay = ReceiverSnapshot(
            connection = receiving,
            gga = gga(SolutionStatus.FIX),
            replay = ReplayInfo("s", ReplaySpeed.X1, false),
        )
        val real = ReceiverSnapshot(connection = receiving, gga = gga(SolutionStatus.FIX))

        toStatusIndicatorState(virtual).source shouldBe StatusSource.VIRTUAL
        toStatusIndicatorState(virtual).scenario shouldBe ScenarioId.BASE_CHANGE
        toStatusIndicatorState(virtual).speed shouldBe ReplaySpeed.X5
        toStatusIndicatorState(phone).source shouldBe StatusSource.PHONE
        toStatusIndicatorState(mock).source shouldBe StatusSource.PHONE
        toStatusIndicatorState(replay).source shouldBe StatusSource.REPLAY
        toStatusIndicatorState(real).source shouldBe StatusSource.RECEIVER
        toStatusIndicatorState(real).scenario shouldBe null
    }
}
