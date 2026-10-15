package es.topophone.app.ui.status

import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.app.R
import es.topophone.app.ui.text
import es.topophone.core.designsystem.component.StatusToneKey
import es.topophone.core.designsystem.theme.StatusTone
import es.topophone.core.designsystem.theme.TopoPhoneTheme
import es.topophone.core.gnss.nmea.SolutionStatus
import es.topophone.core.gnss.nmea.TiltState
import es.topophone.core.model.preferences.EffectiveTheme
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Barra de estado sin estado propio (S003/RF-17 a RF-22). Interfaz en español (es-ES). */
@RunWith(AndroidJUnit4::class)
class StatusBarTest {

    @get:Rule
    val compose = createComposeRule()

    private val fix = StatusBarState(source = StatusSource.RECEIVER, solution = SolutionStatus.FIX, satellites = 24, correctionAgeS = 1.0)

    private fun show(state: StatusBarState, onClick: () -> Unit = {}) {
        compose.setContent { TopoPhoneTheme(EffectiveTheme.LIGHT) { StatusBar(state, onClick) } }
    }

    private fun bar() = compose.onNodeWithTag(STATUS_BAR_TAG)

    @Test
    fun `S003 RF-17 sin receptor ni reproduccion se indica Sin receptor`() {
        show(StatusBarState())

        bar().assert(hasText(text(R.string.status_bar_no_receiver)))
        text(R.string.status_bar_no_receiver) shouldBe "Sin receptor"
    }

    @Test
    fun `S003 RF-18 recibiendo se muestran la solucion con su color, los satelites y la edad de las correcciones`() {
        show(fix)

        compose.onNodeWithTag(STATUS_BAR_SOLUTION_TAG)
            .assert(hasText(text(R.string.solution_fix)))
            .assert(SemanticsMatcher.expectValue(StatusToneKey, StatusTone.POSITIVE))
        bar().assert(hasText(text(R.string.status_bar_satellites, 24))).assert(hasText(text(R.string.status_bar_correction_age, "1,0")))
    }

    @Test
    fun `S003 RF-18 sin edad de las correcciones no se muestra`() {
        show(fix.copy(solution = SolutionStatus.SINGLE, correctionAgeS = null))

        compose.onNodeWithTag(STATUS_BAR_SOLUTION_TAG).assert(SemanticsMatcher.expectValue(StatusToneKey, StatusTone.NEGATIVE))
        compose.onNodeWithText(text(R.string.status_bar_correction_age, "1,0")).assertDoesNotExist()
    }

    @Test
    fun `S003 RF-19 con Sin datos no se muestra la solucion`() {
        show(fix.copy(stale = true))

        bar().assert(hasText(text(R.string.status_bar_no_data)))
        compose.onNodeWithTag(STATUS_BAR_SOLUTION_TAG).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.status_bar_satellites, 24)).assertDoesNotExist()
    }

    @Test
    fun `S003 RF-20 mientras se graba se muestra Grabando`() {
        show(fix.copy(recording = true))

        compose.onNodeWithText(text(R.string.status_bar_recording)).assertIsDisplayed()
    }

    @Test
    fun `S003 RF-20 sin grabar no se muestra Grabando`() {
        show(fix)

        compose.onNodeWithText(text(R.string.status_bar_recording)).assertDoesNotExist()
    }

    @Test
    fun `S003 RF-21 durante una reproduccion se muestra Simulacion con su color y su texto`() {
        show(fix.copy(source = StatusSource.REPLAY))

        compose.onNodeWithTag(STATUS_BAR_SIMULATION_TAG)
            .assert(hasText(text(R.string.status_bar_simulation)))
            .assert(SemanticsMatcher.expectValue(StatusToneKey, StatusTone.SIMULATION))
    }

    @Test
    fun `S003 RF-22 con frases ETC se muestra el estado de la inclinacion`() {
        mapOf(
            TiltState.OFF to "Apagada",
            TiltState.FATAL to "Error",
            TiltState.SETUP to "Configuración",
            TiltState.ALIGNMENT to "Alineación",
            TiltState.COMPENSATING to "Compensando",
        ).forEach { (state, name) ->
            text(tiltNameRes(state)) shouldBe name
        }
        show(fix.copy(tilt = TiltState.COMPENSATING))

        bar().assert(hasText(text(R.string.status_bar_tilt, "Compensando")))
    }

    @Test
    fun `S003 RF-22 sin frases ETC no se muestra la inclinacion`() {
        show(fix)

        compose.onNodeWithText(text(R.string.status_bar_tilt, "Compensando")).assertDoesNotExist()
    }

    @Test
    fun `S003 RF-23 pulsar la barra avisa para abrir Receptor`() {
        var clicked = false
        show(fix) { clicked = true }

        bar().performClick()

        clicked shouldBe true
    }
}
