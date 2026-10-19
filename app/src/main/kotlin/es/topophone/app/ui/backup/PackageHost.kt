package es.topophone.app.ui.backup

import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import es.topophone.app.R
import es.topophone.app.ui.export.ExportFiles
import es.topophone.app.ui.export.shareExportIntent
import es.topophone.app.ui.export.writeToUri
import es.topophone.core.designsystem.component.MinTouchTarget
import es.topophone.core.model.project.Project
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

const val PACKAGE_SAVE_TAG = "package_save"
const val PACKAGE_SHARE_TAG = "package_share"
const val PACKAGE_MESSAGE_TAG = "package_message"

/** Tipo de los paquetes: así el selector de ficheros no les añade ".zip" (plan, contratos). */
const val PACKAGE_MIME = "application/octet-stream"

/**
 * "Exportar paquete" de un proyecto (S013/RF-24): pregunta si guardarlo con el selector de ficheros o compartirlo, y
 * dice cómo ha ido. Devuelve lo que llama el menú del proyecto. La usan la lista de proyectos y la de una carpeta.
 */
@Composable
fun rememberPackageExport(viewModel: PackageViewModel = hiltViewModel()): (Project) -> Unit {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // S013/RF-5: si no se ha podido escribir, el documento a medias se borra.
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(PACKAGE_MIME)) { uri ->
        if (uri == null) {
            viewModel.exportDone(null)
        } else {
            scope.launch {
                val saved = writeToUri(context.contentResolver, uri, viewModel::writePackage)
                if (!saved) runCatching { DocumentsContract.deleteDocument(context.contentResolver, uri) }
                viewModel.exportDone(saved)
            }
        }
    }
    PackageExportDialogs(
        state = state,
        onSave = { fileName ->
            viewModel.startWriting()
            save.launch(fileName)
        },
        onShare = { fileName ->
            viewModel.startWriting()
            scope.launch {
                // Desde la caché de la app, con el FileProvider, como el CSV (S012/RF-18).
                val file = ExportFiles.prepare(context.cacheDir, fileName, System.currentTimeMillis())
                val ok = withContext(Dispatchers.IO) { file.outputStream() }.use { viewModel.writePackage(it) }
                if (ok) {
                    context.startActivity(
                        shareExportIntent(context, file, mimeType = PACKAGE_MIME, chooserTitle = R.string.package_share_chooser),
                    )
                }
                viewModel.exportDone(if (ok) null else false)
            }
        },
        onCancel = viewModel::cancelExport,
        onDismissMessage = viewModel::dismissMessage,
    )
    return remember(viewModel) { { project -> viewModel.startExport(project.id, project.name) } }
}

/** Diálogos de la exportación de un paquete: guardar o compartir, y el resultado (S013/RF-5, RF-24). */
@Composable
fun PackageExportDialogs(
    state: PackageUiState,
    onSave: (fileName: String) -> Unit,
    onShare: (fileName: String) -> Unit,
    onCancel: () -> Unit,
    onDismissMessage: () -> Unit,
) {
    val export = state.export
    if (export != null && !export.writing) {
        AlertDialog(
            onDismissRequest = onCancel,
            title = { Text(stringResource(R.string.package_export)) },
            text = { Text(stringResource(R.string.package_export_question, export.projectName)) },
            confirmButton = {
                Row {
                    TextButton(onClick = {
                        onSave(export.fileName)
                    }, modifier = Modifier.heightIn(min = MinTouchTarget).testTag(PACKAGE_SAVE_TAG)) {
                        Text(stringResource(R.string.export_save))
                    }
                    TextButton(onClick = {
                        onShare(export.fileName)
                    }, modifier = Modifier.heightIn(min = MinTouchTarget).testTag(PACKAGE_SHARE_TAG)) {
                        Text(stringResource(R.string.export_share))
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = onCancel, modifier = Modifier.heightIn(min = MinTouchTarget)) {
                    Text(stringResource(R.string.dialog_cancel))
                }
            },
        )
    }
    state.message?.let { message ->
        AlertDialog(
            onDismissRequest = onDismissMessage,
            text = {
                val text = if (message == PackageMessage.SAVED) R.string.package_saved else R.string.package_not_saved
                Text(stringResource(text), modifier = Modifier.testTag(PACKAGE_MESSAGE_TAG))
            },
            confirmButton = {
                TextButton(onClick = onDismissMessage, modifier = Modifier.heightIn(min = MinTouchTarget)) {
                    Text(stringResource(R.string.backup_dismiss))
                }
            },
        )
    }
}
