package es.topophone.core.designsystem.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import es.topophone.core.model.preferences.EffectiveTheme

private val LightScheme = lightColorScheme(
    primary = Color(0xFF0B5CAD),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFD5E3FF),
    onPrimaryContainer = Color(0xFF001B3C),
    secondary = Color(0xFF7A5900),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFFFDEA6),
    onSecondaryContainer = Color(0xFF261900),
    tertiary = Color(0xFF2E6B30),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFB0F2A9),
    onTertiaryContainer = Color(0xFF002204),
)

private val DarkScheme = darkColorScheme(
    primary = Color(0xFFA7C8FF),
    onPrimary = Color(0xFF003061),
    primaryContainer = Color(0xFF004788),
    onPrimaryContainer = Color(0xFFD5E3FF),
    secondary = Color(0xFFF5BE48),
    onSecondary = Color(0xFF402D00),
    secondaryContainer = Color(0xFF5C4300),
    onSecondaryContainer = Color(0xFFFFDEA6),
    tertiary = Color(0xFF95D58F),
    onTertiary = Color(0xFF00390A),
    tertiaryContainer = Color(0xFF13521B),
    onTertiaryContainer = Color(0xFFB0F2A9),
)

/**
 * Modo sol: tema claro de alto contraste para leer a pleno sol. Todos los pares
 * texto/fondo superan 7:1 (S001/RF-14) y no cambia con el modo oscuro (S001/RF-13).
 */
private val SunScheme = lightColorScheme(
    primary = Color(0xFF00337A),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFD6E4FF),
    onPrimaryContainer = Color(0xFF001538),
    secondary = Color(0xFF4A3500),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFFFE08A),
    onSecondaryContainer = Color(0xFF000000),
    tertiary = Color(0xFF00471F),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFCCF5D8),
    onTertiaryContainer = Color(0xFF001A0A),
    error = Color(0xFF8C0009),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF3B0001),
    background = Color(0xFFFFFFFF),
    onBackground = Color(0xFF000000),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF000000),
    surfaceVariant = Color(0xFFEDEDED),
    onSurfaceVariant = Color(0xFF1A1A1A),
    surfaceTint = Color(0xFF00337A),
    inverseSurface = Color(0xFF000000),
    inverseOnSurface = Color(0xFFFFFFFF),
    inversePrimary = Color(0xFFA7C8FF),
    outline = Color(0xFF2B2B2B),
    outlineVariant = Color(0xFF5C5C5C),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFFFFFFFF),
    surfaceDim = Color(0xFFDEDEDE),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF8F8F8),
    surfaceContainer = Color(0xFFF2F2F2),
    surfaceContainerHigh = Color(0xFFEBEBEB),
    surfaceContainerHighest = Color(0xFFE3E3E3),
)

/** Esquema de color del tema que se pinta. */
fun colorSchemeFor(theme: EffectiveTheme): ColorScheme = when (theme) {
    EffectiveTheme.LIGHT -> LightScheme
    EffectiveTheme.DARK -> DarkScheme
    EffectiveTheme.SUN -> SunScheme
}
