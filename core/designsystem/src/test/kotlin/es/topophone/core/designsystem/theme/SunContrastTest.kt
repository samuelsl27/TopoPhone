package es.topophone.core.designsystem.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import es.topophone.core.model.preferences.EffectiveTheme
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.doubles.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import org.junit.Test

/** Esquema Sol de alto contraste (S001/RF-13, S001/RF-14, RNF-T8). */
class SunContrastTest {

    private val sun: ColorScheme = colorSchemeFor(EffectiveTheme.SUN)

    /** Pares texto/icono sobre fondo que Material 3 usa en los componentes de esta app. */
    private fun pairs(scheme: ColorScheme): Map<String, Pair<Color, Color>> = with(scheme) {
        mapOf(
            "onPrimary/primary" to (onPrimary to primary),
            "onPrimaryContainer/primaryContainer" to (onPrimaryContainer to primaryContainer),
            "onSecondary/secondary" to (onSecondary to secondary),
            "onSecondaryContainer/secondaryContainer" to (onSecondaryContainer to secondaryContainer),
            "onTertiary/tertiary" to (onTertiary to tertiary),
            "onTertiaryContainer/tertiaryContainer" to (onTertiaryContainer to tertiaryContainer),
            "onError/error" to (onError to error),
            "onErrorContainer/errorContainer" to (onErrorContainer to errorContainer),
            "onBackground/background" to (onBackground to background),
            "onSurface/surface" to (onSurface to surface),
            "onSurfaceVariant/surface" to (onSurfaceVariant to surface),
            "onSurfaceVariant/surfaceVariant" to (onSurfaceVariant to surfaceVariant),
            "inverseOnSurface/inverseSurface" to (inverseOnSurface to inverseSurface),
            "primary/surface" to (primary to surface),
            "error/surface" to (error to surface),
            "onSurface/surfaceContainerLowest" to (onSurface to surfaceContainerLowest),
            "onSurface/surfaceContainerLow" to (onSurface to surfaceContainerLow),
            "onSurface/surfaceContainer" to (onSurface to surfaceContainer),
            "onSurface/surfaceContainerHigh" to (onSurface to surfaceContainerHigh),
            "onSurface/surfaceContainerHighest" to (onSurface to surfaceContainerHighest),
            "onSurfaceVariant/surfaceContainer" to (onSurfaceVariant to surfaceContainer),
            "onSurfaceVariant/surfaceContainerHighest" to (onSurfaceVariant to surfaceContainerHighest),
        )
    }

    @Test
    fun `S001 RF-14 todos los textos e iconos del tema Sol tienen contraste de al menos 7 a 1`() {
        val failing = pairs(sun).mapValues { (_, colors) -> contrastRatio(colors.first, colors.second) }.filterValues { it < 7.0 }

        failing shouldBe emptyMap()
    }

    @Test
    fun `S001 RF-13 el tema Sol es texto oscuro sobre fondo claro`() {
        sun.background.luminanceDouble() shouldBeGreaterThanOrEqual 0.9
        contrastRatio(sun.onBackground, Color.Black) shouldBe (1.0 plusOrMinus 0.1)
    }

    @Test
    fun `S001 RF-14 el tema Sol tiene mas contraste que el claro en los textos secundarios`() {
        val light = colorSchemeFor(EffectiveTheme.LIGHT)

        val sunSecondary = contrastRatio(sun.onSurfaceVariant, sun.surfaceContainer)
        val lightSecondary = contrastRatio(light.onSurfaceVariant, light.surfaceContainer)

        (sunSecondary > lightSecondary) shouldBe true
    }

    @Test
    fun `S001 RF-14 la formula de contraste coincide con la referencia WCAG`() {
        contrastRatio(Color.Black, Color.White) shouldBe (21.0 plusOrMinus 0.01)
        contrastRatio(Color.White, Color.White) shouldBe (1.0 plusOrMinus 0.001)
        contrastRatio(Color(0xFF767676), Color.White) shouldBe (4.54 plusOrMinus 0.01)
    }

    @Test
    fun `S001 RF-13 claro y oscuro son esquemas distintos y oscuro tiene fondo oscuro`() {
        val light = colorSchemeFor(EffectiveTheme.LIGHT)
        val dark = colorSchemeFor(EffectiveTheme.DARK)

        (light.background != dark.background) shouldBe true
        (dark.background.luminanceDouble() < 0.1) shouldBe true
    }

    private fun Color.luminanceDouble(): Double = relativeLuminance(this)
}
