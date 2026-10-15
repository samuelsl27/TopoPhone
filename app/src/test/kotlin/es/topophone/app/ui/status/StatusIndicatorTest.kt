package es.topophone.app.ui.status

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.TextLayoutResult
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.app.R
import es.topophone.app.ui.LocalStatusIndicator
import es.topophone.app.ui.SectionScaffold
import es.topophone.app.ui.text
import es.topophone.core.designsystem.component.StatusToneKey
import es.topophone.core.designsystem.theme.StatusTone
import es.topophone.core.designsystem.theme.TopoPhoneTheme
import es.topophone.core.gnss.nmea.SolutionStatus
import es.topophone.core.model.preferences.EffectiveTheme
import es.topophone.core.model.preferences.ThemeState
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Indicador de estado sin estado propio (S003/RF-17 a RF-23, S004/RF-23, S005/RF-18). Interfaz en español (es-ES). */
@RunWith(AndroidJUnit4::class)
class StatusIndicatorTest {

    @get:Rule
    val compose = createComposeRule()

    private val fix = StatusIndicatorState(source = StatusSource.RECEIVER, solution = SolutionStatus.FIX, satellites = 24)

    private fun show(state: StatusIndicatorState, onClick: () -> Unit = {}) {
        compose.setContent { TopoPhoneTheme(EffectiveTheme.LIGHT) { StatusIndicator(state, onClick) } }
    }

    private fun indicator() = compose.onNodeWithTag(STATUS_INDICATOR_TAG)

    @Test
    fun `S003 RF-17 sin receptor ni reproduccion se indica Sin receptor`() {
        show(StatusIndicatorState())

        indicator().assert(hasText(text(R.string.status_bar_no_receiver)))
        text(R.string.status_bar_no_receiver) shouldBe "Sin receptor"
    }

    @Test
    fun `S003 RF-18 recibiendo se muestran la solucion con su color y los satelites`() {
        show(fix)

        compose.onNodeWithTag(STATUS_INDICATOR_SOLUTION_TAG)
            .assert(hasText(text(R.string.solution_fix)))
            .assert(SemanticsMatcher.expectValue(StatusToneKey, StatusTone.POSITIVE))
        indicator().assert(hasText(text(R.string.status_bar_satellites, 24)))
    }

    @Test
    fun `S003 RF-18 el indicador no muestra la edad de las correcciones`() {
        show(fix)

        compose.onNodeWithText(text(R.string.status_bar_correction_age, "1,0"), substring = true).assertDoesNotExist()
    }

    @Test
    fun `S003 RF-19 con Sin datos no se muestra la solucion`() {
        show(fix.copy(stale = true))

        indicator().assert(hasText(text(R.string.status_bar_no_data)))
        compose.onNodeWithTag(STATUS_INDICATOR_SOLUTION_TAG).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.status_bar_satellites, 24)).assertDoesNotExist()
    }

    @Test
    fun `S003 RF-21 durante una reproduccion se muestra Simulacion con su color y su texto`() {
        show(fix.copy(source = StatusSource.REPLAY))

        compose.onNodeWithTag(STATUS_INDICATOR_SIMULATION_TAG)
            .assert(hasText(text(R.string.status_bar_simulation)))
            .assert(SemanticsMatcher.expectValue(StatusToneKey, StatusTone.SIMULATION))
    }

    @Test
    fun `S003 RF-23 pulsar el indicador avisa para abrir Receptor`() {
        var clicked = false
        show(fix) { clicked = true }

        indicator().performClick()

        clicked shouldBe true
    }

    @Test
    fun `S004 RF-23 durante la reconexion se muestra Reconectando en lugar de la solucion`() {
        show(fix.copy(reconnecting = true))

        indicator().assert(hasText(text(R.string.status_bar_reconnecting)))
        compose.onNodeWithTag(STATUS_INDICATOR_SOLUTION_TAG).assertDoesNotExist()
        text(R.string.status_bar_reconnecting) shouldBe "Reconectando"
    }

    private fun SemanticsNodeInteraction.textLayout(): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action?.invoke(results)
        return results.single()
    }

    /** Con gráficos nativos Robolectric mide el texto de verdad; sin ellos, cada carácter mide 1 dp. */
    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Config(qualifiers = "es-rES-w360dp-h640dp", fontScale = 1.3f)
    fun `S005 RF-18 a 360 dp con la fuente al 130 por ciento el indicador se ve entero y el titulo se acorta`() {
        val title = "Funcionamiento en segundo plano"
        compose.setContent {
            TopoPhoneTheme(EffectiveTheme.LIGHT) {
                // El caso más ancho: reproducción con solución y satélites.
                CompositionLocalProvider(LocalStatusIndicator provides { StatusIndicator(fix.copy(source = StatusSource.REPLAY), {}) }) {
                    SectionScaffold(title = title, themeState = ThemeState.DEFAULT, onToggleSun = {}) {}
                }
            }
        }

        listOf(text(R.string.status_bar_simulation), text(R.string.solution_fix), text(R.string.status_bar_satellites, 24)).forEach {
            val layout = compose.onNodeWithText(it, useUnmergedTree = true).textLayout()
            layout.isLineEllipsized(0) shouldBe false
            // Tiene todo el ancho que necesita (con softWrap = false, hasVisualOverflow compara con el máximo disponible).
            (layout.size.width + 1 >= layout.multiParagraph.intrinsics.maxIntrinsicWidth) shouldBe true
        }
        (indicator().getBoundsInRoot().right <= compose.onRoot().getBoundsInRoot().right) shouldBe true
        compose.onNodeWithText(title, useUnmergedTree = true).textLayout().isLineEllipsized(0) shouldBe true
    }
}
