package es.topophone.app.ui.gpkg

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
import dagger.hilt.android.EntryPointAccessors
import es.topophone.app.R
import es.topophone.app.ui.export.ExportFiles
import es.topophone.app.ui.export.shareExportIntent
import es.topophone.app.ui.map.MapEntryPoint
import es.topophone.app.ui.map.MapLibreMap
import es.topophone.app.ui.points.ReportOutcome
import es.topophone.app.ui.points.importSourceOf
import es.topophone.app.ui.points.saveReportTo
import es.topophone.core.model.preferences.ThemeState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Los textos del resumen de la importación, de `strings.xml` (S048/RF-25). */
@Composable
private fun rememberSummaryLabels(): GpkgSummaryLabels {
    val resources = LocalResources.current
    return remember(resources) {
        GpkgSummaryLabels(
            title = resources.getString(R.string.gpkg_summary_title),
            layer = resources.getString(R.string.gpkg_summary_layer),
            imported = resources.getString(R.string.gpkg_summary_imported),
            outsideArea = resources.getString(R.string.gpkg_summary_outside),
            ignored = resources.getString(R.string.gpkg_summary_ignored),
            omittedAttributes = resources.getString(R.string.gpkg_summary_omitted),
            total = resources.getString(R.string.gpkg_summary_total),
            leftOut = resources.getString(R.string.gpkg_summary_left_out),
            wgs84 = resources.getString(R.string.gpkg_summary_wgs84),
            reason = { resources.getString(reasonLabel(it)) },
        )
    }
}

/** La pantalla de importar el GeoPackage [fileUri] al proyecto [projectId] (S048/RF-1). */
@Composable
fun GpkgImportRoute(
    projectId: String,
    fileUri: Uri,
    themeState: ThemeState,
    onToggleSun: () -> Unit,
    onBack: () -> Unit,
    viewModel: GpkgImportViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by viewModel.state.collectAsStateWithLifecycle()
    var outcome by remember { mutableStateOf<ReportOutcome?>(null) }
    LaunchedEffect(projectId, fileUri) { viewModel.start(projectId, importSourceOf(context.contentResolver, fileUri)) }
    val labels = rememberSummaryLabels()
    val environment =
        remember { EntryPointAccessors.fromApplication(context.applicationContext, MapEntryPoint::class.java).mapEnvironment() }
    // S048/RF-25: el selector de ficheros del móvil; si el usuario cancela, no se escribe nada.
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(TEXT_MIME)) { uri ->
        scope.launch { outcome = saveReportTo(context.contentResolver, uri) { viewModel.writeSummary(it, labels) } }
    }
    GpkgImportScreen(
        themeState = themeState,
        onToggleSun = onToggleSun,
        state = state,
        actions = GpkgImportActions(
            onMarked = viewModel::setMarked,
            onCrs = viewModel::setCrs,
            onConfirmFar = viewModel::confirmFar,
            onAllContent = viewModel::setAllContent,
            onRadius = viewModel::setRadius,
            onCentralTyped = viewModel::fixCentralTyped,
            onCentralRemove = viewModel::clearCentralPoint,
            onCentralPosition = viewModel::fixCentralFromReceiver,
            onCentralTap = viewModel::resolveCentralTap,
            onCentralPicked = viewModel::setCentralPoint,
            pickerStart = viewModel::pickerStart,
            onNameField = viewModel::setNameField,
            onUseElevations = viewModel::setUseElevations,
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
                            shareExportIntent(context, file, mimeType = TEXT_MIME, chooserTitle = R.string.gpkg_summary_chooser),
                        )
                    } else {
                        outcome = ReportOutcome.FAILED
                    }
                }
            },
        ),
        onBack = onBack,
        map = { modifier, onReady -> MapLibreMap(environment, onReady, modifier) },
        summaryOutcome = outcome,
    )
}

private const val TEXT_MIME = "text/plain"

/**
 * Abre el selector de ficheros del móvil para importar un GeoPackage al proyecto que se le pase y, con el fichero elegido, pide la
 * pantalla de importación con [onPicked] (S048/RF-1). Si el usuario cancela el selector, no pasa nada. El proyecto pendiente sobrevive
 * al giro y a la muerte del proceso mientras el selector está abierto.
 */
@Composable
fun rememberImportGpkgLauncher(onPicked: (projectId: String, fileUri: String) -> Unit): (projectId: String) -> Unit {
    var pending by rememberSaveable { mutableStateOf<String?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val projectId = pending
        pending = null
        if (uri != null && projectId != null) onPicked(projectId, uri.toString())
    }
    return { projectId ->
        pending = projectId
        picker.launch(arrayOf("*/*")) // RF-2: cualquier tipo; el contenido decide si es un GeoPackage
    }
}
