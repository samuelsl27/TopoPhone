package es.topophone.app.ui.localization

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import es.topophone.app.ui.export.ExportFiles
import es.topophone.app.ui.export.shareExportIntent
import es.topophone.app.ui.export.writeToUri
import es.topophone.core.model.preferences.ThemeState
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // RF-25: el selector de ficheros del móvil; si el usuario cancela, no se escribe nada.
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null) scope.launch { viewModel.reportResult(writeToUri(context.contentResolver, uri, viewModel::writeReport)) }
    }
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
            onLimits = viewModel::setLimits,
            onRequestApply = viewModel::requestApply,
            onCancelApply = viewModel::cancelApply,
            onConfirmApply = viewModel::confirmApply,
            onRequestRemove = viewModel::requestRemove,
            onCancelRemove = viewModel::cancelRemove,
            onConfirmRemove = viewModel::confirmRemove,
            onReactivate = viewModel::reactivate,
            onDiscardDraft = viewModel::discardDraft,
            onDismissNotice = viewModel::dismissNotice,
            onSaveCrs = viewModel::openSaveCrs,
            onCrsName = viewModel::setCrsName,
            onConfirmSaveCrs = viewModel::confirmSaveCrs,
            onCancelSaveCrs = viewModel::closeSaveCrs,
            onUseCrs = viewModel::useCrs,
            onDeleteCrs = viewModel::deleteCrs,
            onSaveReport = { save.launch(viewModel.reportFileName(Instant.now(), ZoneId.systemDefault())) },
            onShareReport = {
                scope.launch {
                    val name = viewModel.reportFileName(Instant.now(), ZoneId.systemDefault())
                    val file = ExportFiles.prepare(context.cacheDir, name, System.currentTimeMillis())
                    val ok = withContext(Dispatchers.IO) { file.outputStream() }.use { viewModel.writeReport(it) }
                    if (ok) context.startActivity(shareExportIntent(context, file)) else viewModel.reportResult(false)
                }
            },
        ),
        onBack = onBack,
    )
}
