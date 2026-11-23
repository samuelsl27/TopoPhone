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
        actions = LocalizationActions(
            onOpenAddPair = viewModel::openAddPair,
            onCloseAddPair = viewModel::closeAddPair,
            onSelectGnss = viewModel::selectGnss,
            onSource = viewModel::setSource,
            onSelectKnown = viewModel::selectKnown,
            onEasting = viewModel::setEasting,
            onNorthing = viewModel::setNorthing,
            onHeight = viewModel::setHeight,
            onConfirmAddPair = viewModel::confirmAddPair,
            onConfirmPair = viewModel::confirmPair,
            onComponent = viewModel::setComponent,
            onRemovePair = viewModel::removePair,
            onFixScale = viewModel::setFixScale,
        ),
        onBack = onBack,
    )
}
