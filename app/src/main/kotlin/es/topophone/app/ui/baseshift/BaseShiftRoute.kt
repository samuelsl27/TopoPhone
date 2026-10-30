package es.topophone.app.ui.baseshift

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import es.topophone.core.model.preferences.ThemeState

/** La pantalla del desplazamiento de base del proyecto [projectId] (S020/RF-1). */
@Composable
fun BaseShiftRoute(
    projectId: String,
    themeState: ThemeState,
    onToggleSun: () -> Unit,
    onBack: () -> Unit,
    viewModel: BaseShiftViewModel = hiltViewModel(),
) {
    LaunchedEffect(projectId) { viewModel.start(projectId) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    BaseShiftScreen(
        themeState = themeState,
        onToggleSun = onToggleSun,
        state = state,
        actions = BaseShiftActions(
            onSource = viewModel::setSource,
            onSelectPoint = viewModel::selectPoint,
            onName = viewModel::setName,
            onEasting = viewModel::setEasting,
            onNorthing = viewModel::setNorthing,
            onHeight = viewModel::setHeight,
            onSeconds = viewModel::setSeconds,
            onMeasure = viewModel::measure,
            onCancel = viewModel::cancelMeasuring,
            onAcceptHorizontal = viewModel::acceptHorizontalOnly,
            onConfirmFar = viewModel::confirmFar,
            onActivate = viewModel::activate,
            onDiscard = viewModel::discard,
            onReactivate = viewModel::reactivate,
            onDeactivate = viewModel::deactivate,
            onDelete = viewModel::delete,
        ),
        onBack = onBack,
    )
}
