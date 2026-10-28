package es.topophone.app.ui.alignment

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import es.topophone.core.model.preferences.ThemeState

/** La pantalla de definir a mano un eje en el proyecto [projectId] (S049/RF-8). */
@Composable
fun ManualAlignmentRoute(
    projectId: String,
    themeState: ThemeState,
    onToggleSun: () -> Unit,
    onBack: () -> Unit,
    viewModel: ManualAlignmentViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(projectId) { viewModel.start(projectId) }
    ManualAlignmentScreen(
        themeState = themeState,
        onToggleSun = onToggleSun,
        state = state,
        actions = ManualAlignmentActions(
            onName = viewModel::setName,
            onStart = viewModel::setStartChainage,
            onAddVertex = viewModel::addVertex,
            onRemoveVertex = viewModel::removeVertex,
            onVertex = viewModel::setVertex,
            onAddProfile = viewModel::addProfileVertex,
            onRemoveProfile = viewModel::removeProfileVertex,
            onProfile = viewModel::setProfileVertex,
            onCreate = viewModel::create,
        ),
        onBack = onBack,
    )
}
