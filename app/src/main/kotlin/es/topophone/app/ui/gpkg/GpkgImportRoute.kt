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
import es.topophone.app.ui.points.ImportSource
import es.topophone.app.ui.points.ReportOutcome
import es.topophone.app.ui.points.importSourceOf
import es.topophone.app.ui.points.saveReportTo
import es.topophone.core.model.preferences.ThemeState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Los textos del resumen de la importación, de `strings.xml` (S048/RF-25, S030/RF-25). */
@Composable
private fun rememberSummaryLabels(format: ImportFormat): GpkgSummaryLabels {
    val resources = LocalResources.current
    return remember(resources, format) {
        val shapefile = format == ImportFormat.SHAPEFILE
        GpkgSummaryLabels(
            title = resources.getString(if (shapefile) R.string.shp_summary_title else R.string.gpkg_summary_title),
            layer = resources.getString(R.string.gpkg_summary_layer),
            imported = resources.getString(R.string.gpkg_summary_imported),
            outsideArea = resources.getString(R.string.gpkg_summary_outside),
            ignored = resources.getString(R.string.gpkg_summary_ignored),
            omittedAttributes = resources.getString(R.string.gpkg_summary_omitted),
            total = resources.getString(R.string.gpkg_summary_total),
            leftOut = resources.getString(if (shapefile) R.string.shp_summary_left_out else R.string.gpkg_summary_left_out),
            wgs84 = resources.getString(R.string.gpkg_summary_wgs84),
            reason = { resources.getString(reasonLabel(it)) },
            withoutAttributes = resources.getString(R.string.shp_summary_without_attributes),
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
    LaunchedEffect(projectId, fileUri) { viewModel.start(projectId, importSourceOf(context.contentResolver, fileUri)) }
    ImportRoute(ImportFormat.GEOPACKAGE, themeState, onToggleSun, onBack, viewModel)
}

/** La pantalla de importar el Shapefile de [fileUris] —un ZIP o ficheros sueltos— al proyecto [projectId] (S030/RF-1). */
@Composable
fun ShpImportRoute(
    projectId: String,
    fileUris: List<Uri>,
    themeState: ThemeState,
    onToggleSun: () -> Unit,
    onBack: () -> Unit,
    viewModel: GpkgImportViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    LaunchedEffect(projectId, fileUris) {
        val sources: List<ImportSource> = fileUris.map { importSourceOf(context.contentResolver, it) }
        viewModel.startShapefile(projectId, sources)
    }
    ImportRoute(ImportFormat.SHAPEFILE, themeState, onToggleSun, onBack, viewModel)
}

/** Lo que tienen en común las dos importaciones: el estado, el mapa del selector del punto central y guardar o compartir el resumen. */
@Composable
private fun ImportRoute(
    format: ImportFormat,
    themeState: ThemeState,
    onToggleSun: () -> Unit,
    onBack: () -> Unit,
    viewModel: GpkgImportViewModel,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by viewModel.state.collectAsStateWithLifecycle()
    var outcome by remember { mutableStateOf<ReportOutcome?>(null) }
    val labels = rememberSummaryLabels(format)
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
            onEncoding = viewModel::setEncoding,
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

/**
 * Abre el selector de ficheros del móvil, que deja marcar un ZIP o varios ficheros sueltos, para importar un Shapefile al proyecto que se
 * le pase y, con lo elegido, pide la pantalla de importación con [onPicked] (S030/RF-1). Si el usuario cancela, no pasa nada.
 */
@Composable
fun rememberImportShpLauncher(onPicked: (projectId: String, fileUris: List<String>) -> Unit): (projectId: String) -> Unit {
    var pending by rememberSaveable { mutableStateOf<String?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        val projectId = pending
        pending = null
        if (uris.isNotEmpty() && projectId != null) onPicked(projectId, uris.map { it.toString() })
    }
    return { projectId ->
        pending = projectId
        picker.launch(arrayOf("*/*")) // RF-2: cualquier tipo; el contenido decide qué es cada fichero
    }
}
