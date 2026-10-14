package es.topophone.app.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import es.topophone.core.designsystem.component.TopoPhoneTopBar
import es.topophone.core.model.preferences.ThemeMode
import es.topophone.core.model.preferences.ThemeState

/** Pantalla con la barra superior común, que siempre lleva el acceso al modo sol (S001/RF-11). */
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
            TopoPhoneTopBar(
                title = title,
                sunModeActive = themeState.mode == ThemeMode.SUN,
                onToggleSunMode = onToggleSun,
                onNavigateBack = onNavigateBack,
            )
        },
        content = content,
    )
}
