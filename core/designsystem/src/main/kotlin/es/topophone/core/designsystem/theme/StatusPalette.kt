package es.topophone.core.designsystem.theme

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import es.topophone.core.model.preferences.EffectiveTheme

/** Tonos de estado (solución, conexiones). Siempre acompañan a un texto, nunca van solos (RNF-T8). */
enum class StatusTone { POSITIVE, WARNING, INFO, NEGATIVE, NEUTRAL }

data class StatusColors(val container: Color, val content: Color)

/** Colores de cada tono en un tema; todos con contraste ≥ 7:1 entre texto y fondo. */
class StatusPalette internal constructor(private val colors: Map<StatusTone, StatusColors>) {
    fun colorsFor(tone: StatusTone): StatusColors = colors.getValue(tone)
}

/** Fondos oscuros saturados con texto blanco, para los temas Claro y Sol. */
private val OnLightPalette = StatusPalette(
    mapOf(
        StatusTone.POSITIVE to StatusColors(Color(0xFF0F5A1E), Color.White),
        StatusTone.WARNING to StatusColors(Color(0xFFFFC940), Color.Black),
        StatusTone.INFO to StatusColors(Color(0xFF0B4A8F), Color.White),
        StatusTone.NEGATIVE to StatusColors(Color(0xFFA50E0E), Color.White),
        StatusTone.NEUTRAL to StatusColors(Color(0xFF4D4D4D), Color.White),
    ),
)

/** Fondos claros con texto negro, para el tema Oscuro. */
private val OnDarkPalette = StatusPalette(
    mapOf(
        StatusTone.POSITIVE to StatusColors(Color(0xFF7FD98A), Color.Black),
        StatusTone.WARNING to StatusColors(Color(0xFFFFC940), Color.Black),
        StatusTone.INFO to StatusColors(Color(0xFF9CC3FF), Color.Black),
        StatusTone.NEGATIVE to StatusColors(Color(0xFFFFB3AD), Color.Black),
        StatusTone.NEUTRAL to StatusColors(Color(0xFFC7C7C7), Color.Black),
    ),
)

fun statusPaletteFor(theme: EffectiveTheme): StatusPalette = when (theme) {
    EffectiveTheme.LIGHT, EffectiveTheme.SUN -> OnLightPalette
    EffectiveTheme.DARK -> OnDarkPalette
}

/** Paleta de estado del tema que se pinta; la fija [TopoPhoneTheme]. */
val LocalStatusPalette = staticCompositionLocalOf { OnLightPalette }
