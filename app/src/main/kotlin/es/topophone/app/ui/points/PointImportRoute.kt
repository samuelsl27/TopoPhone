package es.topophone.app.ui.points

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import es.topophone.app.R
import es.topophone.app.ui.export.ExportFiles
import es.topophone.app.ui.export.shareExportIntent
import es.topophone.core.model.preferences.ThemeState
import java.io.FileNotFoundException
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TEXT_MIME = "text/plain"

/**
 * El fichero que eligió el usuario con el selector de ficheros (S028/RF-1): su nombre y tamaño, si el proveedor los da, y cómo
 * abrirlo cada vez que hace falta, sin copiarlo (RNF-T5). Un fichero que ya no se puede abrir da null, y el asistente lo dice.
 */
fun importSourceOf(resolver: ContentResolver, uri: Uri): ImportSource {
    var name = uri.lastPathSegment.orEmpty()
    var size: Long? = null
    try {
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameColumn = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameColumn >= 0 && !cursor.isNull(nameColumn)) name = cursor.getString(nameColumn)
                val sizeColumn = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (sizeColumn >= 0 && !cursor.isNull(sizeColumn)) size = cursor.getLong(sizeColumn)
            }
        }
    } catch (_: SecurityException) {
        // sin permiso para preguntar por el nombre: se intentará abrir igualmente
    } catch (_: IllegalArgumentException) {
        // un proveedor que no admite la consulta
    }
    return ImportSource(name, size) {
        try {
            resolver.openInputStream(uri)
        } catch (_: FileNotFoundException) {
            null
        } catch (_: SecurityException) {
            null
        } catch (_: IOException) {
            null
        }
    }
}

/** Los textos del informe de errores, de `strings.xml` (S028/RF-24). */
@Composable
private fun rememberReportLabels(state: PointImportUiState): ImportReportLabels {
    val resources = LocalResources.current
    return remember(state.fileName, state.counts.rows, state.counts.errors) {
        ImportReportLabels(
            title = resources.getString(R.string.import_report_title, state.fileName),
            summary = resources.getString(R.string.import_report_summary, state.counts.rows, state.counts.errors),
            empty = resources.getString(R.string.import_errors_none),
            lineFormat = { line, reason -> resources.getString(R.string.import_errors_line, line, reason) },
            reason = { resources.getString(reasonText(it)) },
        )
    }
}

/** La pantalla del asistente de importación del proyecto [projectId] con el fichero [fileUri] (S028/RF-1). */
@Composable
fun PointImportRoute(
    projectId: String,
    fileUri: Uri,
    themeState: ThemeState,
    onToggleSun: () -> Unit,
    onBack: () -> Unit,
    viewModel: PointImportViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by viewModel.state.collectAsStateWithLifecycle()
    var reportOutcome by remember { mutableStateOf<ReportOutcome?>(null) }
    LaunchedEffect(projectId, fileUri) { viewModel.start(projectId, importSourceOf(context.contentResolver, fileUri)) }
    val labels = rememberReportLabels(state)
    // S028/RF-24: el selector de ficheros del móvil; si el usuario cancela, no se escribe nada.
    val saveReport = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(TEXT_MIME)) { uri ->
        scope.launch { reportOutcome = saveReportTo(context.contentResolver, uri) { viewModel.writeReport(it, labels) } }
    }
    PointImportScreen(
        themeState = themeState,
        onToggleSun = onToggleSun,
        state = state,
        actions = PointImportActions(
            onEncoding = viewModel::setEncoding,
            onSeparator = viewModel::setSeparator,
            onDecimal = viewModel::setDecimal,
            onHasHeader = viewModel::setHasHeader,
            onColumnRole = viewModel::setColumnRole,
            onPenzd = viewModel::applyPenzd,
            onPnezd = viewModel::applyPnezd,
            onCoordinates = viewModel::setCoordinates,
            onUnit = viewModel::setUnit,
            onZeroAsNoHeight = viewModel::setZeroAsNoHeight,
            onDuplicates = viewModel::setDuplicates,
            onSaveReport = { name ->
                reportOutcome = null
                saveReport.launch(name)
            },
            onShareReport = { name ->
                reportOutcome = null
                scope.launch {
                    val file = ExportFiles.prepare(context.cacheDir, name, System.currentTimeMillis())
                    val ok = withContext(Dispatchers.IO) { file.outputStream() }.use { viewModel.writeReport(it, labels) }
                    if (ok) {
                        context.startActivity(
                            shareExportIntent(context, file, mimeType = TEXT_MIME, chooserTitle = R.string.import_report_chooser),
                        )
                    } else {
                        reportOutcome = ReportOutcome.FAILED
                    }
                }
            },
            onImport = viewModel::import,
        ),
        onBack = onBack,
        reportOutcome = reportOutcome,
    )
}
