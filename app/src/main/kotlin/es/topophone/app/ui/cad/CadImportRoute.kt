package es.topophone.app.ui.cad

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import es.topophone.app.R
import es.topophone.app.ui.export.ExportFiles
import es.topophone.app.ui.export.shareExportIntent
import es.topophone.app.ui.points.ReportOutcome
import es.topophone.app.ui.points.importSourceOf
import es.topophone.app.ui.points.saveReportTo
import es.topophone.core.model.preferences.ThemeState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TEXT_MIME = "text/plain"

/** Los textos del resumen de la importación, de `strings.xml` (S040/RF-18). */
@Composable
private fun rememberSummaryLabels(): CadSummaryLabels {
    val resources = LocalResources.current
    return remember(resources) {
        CadSummaryLabels(
            title = resources.getString(R.string.cad_summary_title),
            imported = resources.getString(R.string.cad_summary_imported),
            ignored = resources.getString(R.string.cad_summary_ignored),
            layers = resources.getString(R.string.cad_summary_layers),
            incomplete = resources.getString(R.string.cad_result_incomplete),
            kind = { resources.getString(kindLabel(it)) },
            ignoredItem = { ignoredText(resources, it) },
        )
    }
}

/** La pantalla de importar el DXF [fileUri] al proyecto [projectId] (S040/RF-1). */
@Composable
fun CadImportRoute(
    projectId: String,
    fileUri: Uri,
    themeState: ThemeState,
    onToggleSun: () -> Unit,
    onBack: () -> Unit,
    viewModel: CadImportViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by viewModel.state.collectAsStateWithLifecycle()
    var outcome by remember { mutableStateOf<ReportOutcome?>(null) }
    LaunchedEffect(projectId, fileUri) { viewModel.start(projectId, importSourceOf(context.contentResolver, fileUri)) }
    val labels = rememberSummaryLabels()
    // S040/RF-18: el selector de ficheros del móvil; si el usuario cancela, no se escribe nada.
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(TEXT_MIME)) { uri ->
        scope.launch { outcome = saveReportTo(context.contentResolver, uri) { viewModel.writeSummary(it, labels) } }
    }
    CadImportScreen(
        themeState = themeState,
        onToggleSun = onToggleSun,
        state = state,
        actions = CadImportActions(
            onSource = viewModel::setSource,
            onUnit = viewModel::setUnit,
            onUseElevations = viewModel::setUseElevations,
            onConfirmFar = viewModel::confirmFar,
            onConfirmDuplicate = viewModel::confirmDuplicate,
            onImport = viewModel::import,
            onCancel = viewModel::cancel,
            onSaveSummary = { name ->
                outcome = null
                save.launch(name)
            },
            onShareSummary = { name ->
                outcome = null
                scope.launch {
                    val file = ExportFiles.prepare(context.cacheDir, name, System.currentTimeMillis())
                    val ok = withContext(Dispatchers.IO) { file.outputStream() }.use { viewModel.writeSummary(it, labels) }
                    if (ok) {
                        context.startActivity(
                            shareExportIntent(context, file, mimeType = TEXT_MIME, chooserTitle = R.string.cad_summary_chooser),
                        )
                    } else {
                        outcome = ReportOutcome.FAILED
                    }
                }
            },
        ),
        onBack = onBack,
        summaryOutcome = outcome,
    )
}

/**
 * Abre el selector de ficheros del móvil para importar un DXF al proyecto que se le pase y, con el fichero elegido, pide la pantalla
 * de importación con [onPicked] (S040/RF-1). Si el usuario cancela el selector, no pasa nada. El proyecto pendiente sobrevive al giro
 * y a la muerte del proceso mientras el selector está abierto.
 */
@Composable
fun rememberImportDxfLauncher(onPicked: (projectId: String, fileUri: String) -> Unit): (projectId: String) -> Unit {
    var pending by rememberSaveable { mutableStateOf<String?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val projectId = pending
        pending = null
        if (uri != null && projectId != null) onPicked(projectId, uri.toString())
    }
    return { projectId ->
        pending = projectId
        picker.launch(arrayOf("*/*")) // RF-2: cualquier tipo; el contenido decide si es un DXF
    }
}
