package es.topophone.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import es.topophone.core.designsystem.component.TopoPhoneTopBar
import es.topophone.core.model.preferences.ThemeMode
import es.topophone.core.model.preferences.ThemeState

/** Barra de estado que pinta [SectionScaffold] bajo la barra superior; la fija [TopoPhoneApp] (S003/RF-16). */
val LocalStatusBar = staticCompositionLocalOf<(@Composable () -> Unit)?> { null }

/**
 * Pantalla con la barra superior común, que siempre lleva el acceso al modo sol (S001/RF-11), y
 * debajo la barra de estado del receptor (S003/RF-16).
 */
@Composable
fun SectionScaffold(
    title: String,
    themeState: ThemeState,
    onToggleSun: () -> Unit,
    modifier: Modifier = Modifier,
    onNavigateBack: (() -> Unit)? = null,
    content: @Composable (PaddingValues) -> Unit,
) {
    Scaffold(
        modifier = modifier,
        contentWindowInsets = WindowInsets(0),
        topBar = {
            Column {
                TopoPhoneTopBar(
                    title = title,
                    sunModeActive = themeState.mode == ThemeMode.SUN,
                    onToggleSunMode = onToggleSun,
                    onNavigateBack = onNavigateBack,
                )
                LocalStatusBar.current?.invoke() // S003/RF-16
            }
        },
        content = content,
    )
}
