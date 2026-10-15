package es.topophone.core.designsystem.theme

import es.topophone.core.model.preferences.EffectiveTheme
import io.kotest.matchers.shouldBe
import org.junit.Test

/** Colores de estado (S002/RF-16, RNF-T8): legibles en todos los temas y distintos entre sí. */
class StatusPaletteTest {

    @Test
    fun `S002 RF-16 el texto de cada estado contrasta al menos 7 a 1 con su fondo en todos los temas`() {
        val failing = EffectiveTheme.entries.flatMap { theme ->
            StatusTone.entries.mapNotNull { tone ->
                val colors = statusPaletteFor(theme).colorsFor(tone)
                val ratio = contrastRatio(colors.content, colors.container)
                if (ratio < 7.0) "$theme/$tone: $ratio" else null
            }
        }

        failing shouldBe emptyList()
    }

    @Test
    fun `S002 RF-16 cada estado tiene un color de fondo distinto en cada tema`() {
        EffectiveTheme.entries.forEach { theme ->
            val containers = StatusTone.entries.map { statusPaletteFor(theme).colorsFor(it).container }

            containers.toSet().size shouldBe StatusTone.entries.size
        }
    }
}
