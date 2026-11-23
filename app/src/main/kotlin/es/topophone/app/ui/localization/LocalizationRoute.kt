package es.topophone.app.ui.localization

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import es.topophone.core.model.preferences.ThemeState

/** La pantalla de localización del proyecto [projectId] (S039/RF-1). */
@Composable
fun LocalizationRoute(
    projectId: String,
    themeState: ThemeState,
    onToggleSun: () -> Unit,
    onBack: () -> Unit,
    viewModel: LocalizationViewModel = hiltViewModel(),
) {
    LaunchedEffect(projectId) { viewModel.start(projectId) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    LocalizationScreen(
        themeState = themeState,
        onToggleSun = onToggleSun,
        state = state,
        actions = LocalizationActions(),
        onBack = onBack,
    )
}
