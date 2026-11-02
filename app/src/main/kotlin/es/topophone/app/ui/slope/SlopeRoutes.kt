package es.topophone.app.ui.slope

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import es.topophone.core.model.preferences.ThemeState

/** La pantalla «Taludes» del eje [alignmentId] del proyecto [projectId] (S051/RF-1). */
@Composable
fun SlopesRoute(
    projectId: String,
    alignmentId: String,
    themeState: ThemeState,
    onToggleSun: () -> Unit,
    onBack: () -> Unit,
    viewModel: SlopesViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(projectId, alignmentId) { viewModel.start(projectId, alignmentId) }
    SlopesScreen(
        themeState = themeState,
        onToggleSun = onToggleSun,
        state = state,
        actions = SlopesActions(
            onAdd = viewModel::startDraft,
            onCancel = viewModel::cancelDraft,
            onDraft = { change -> viewModel.updateDraft(change) },
            onSave = viewModel::save,
            onDelete = viewModel::delete,
        ),
        onBack = onBack,
    )
}
