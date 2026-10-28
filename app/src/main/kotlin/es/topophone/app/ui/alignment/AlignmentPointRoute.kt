package es.topophone.app.ui.alignment

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import es.topophone.core.model.preferences.ThemeState

/** La pantalla de calcular un punto sobre el eje [alignmentId] del proyecto [projectId] (S049/RF-20 a RF-22). */
@Composable
fun AlignmentPointRoute(
    projectId: String,
    alignmentId: String,
    themeState: ThemeState,
    onToggleSun: () -> Unit,
    onBack: () -> Unit,
    viewModel: AlignmentPointViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(projectId, alignmentId) { viewModel.start(projectId, alignmentId) }
    AlignmentPointScreen(
        themeState = themeState,
        onToggleSun = onToggleSun,
        state = state,
        actions = AlignmentPointActions(
            onChainage = viewModel::setChainage,
            onOffset = viewModel::setOffset,
            onName = viewModel::setName,
            onSave = viewModel::save,
            onAnother = viewModel::another,
        ),
        onBack = onBack,
    )
}
