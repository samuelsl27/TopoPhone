package es.topophone.app.ui.backup

import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
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
import es.topophone.data.backup.ImportChoice
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

const val PACKAGE_SAVE_TAG = "package_save"
const val PACKAGE_SHARE_TAG = "package_share"
const val PACKAGE_MESSAGE_TAG = "package_message"
const val PACKAGE_REPLACE_TAG = "package_replace"
const val PACKAGE_AS_NEW_TAG = "package_as_new"

/** Tipo de los paquetes: así el selector de ficheros no les añade ".zip" (plan, contratos). */
const val PACKAGE_MIME = "application/octet-stream"

/** Lo que llaman los menús: exportar el paquete de un proyecto (S013/RF-24) e importar uno (S013/RF-25). */
class PackageHost(val export: (Project) -> Unit, val import: () -> Unit)

/**
 * Paquetes de proyecto desde la lista de proyectos y la de una carpeta (S013/RF-24 a RF-28): el selector de ficheros,
 * compartir, la pregunta de sustituir o importar como nuevo, y cómo ha ido.
 */
@Composable
fun rememberPackageHost(viewModel: PackageViewModel = hiltViewModel()): PackageHost {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val resolver = context.contentResolver
    val scope = rememberCoroutineScope()
    val importedLabel = stringResource(R.string.project_imported_label)
    // S013/RF-5: si no se ha podido escribir, el documento a medias se borra.
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(PACKAGE_MIME)) { uri ->
        if (uri == null) {
            viewModel.exportDone(null)
        } else {
            scope.launch {
                val saved = writeToUri(resolver, uri, viewModel::writePackage)
                if (!saved) runCatching { DocumentsContract.deleteDocument(resolver, uri) }
                viewModel.exportDone(saved)
            }
        }
    }
    // RNF-T5: se valida por el contenido, no por el tipo.
    val open = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri !=
            null
        ) {
            viewModel.openPackage({ resolver.openInputStream(uri) ?: throw IOException("Sin entrada para $uri") }, importedLabel)
        }
    }
    PackageDialogs(
        state = state,
        actions = PackageDialogActions(
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
            onCancelExport = viewModel::cancelExport,
            onAnswer = viewModel::answer,
            onCancelImport = viewModel::cancelImport,
            onDismissMessage = viewModel::dismissMessage,
        ),
    )
    return remember(viewModel) {
        PackageHost(
            export = { project -> viewModel.startExport(project.id, project.name) },
            import = { open.launch(arrayOf("*/*")) },
        )
    }
}

class PackageDialogActions(
    val onSave: (fileName: String) -> Unit = {},
    val onShare: (fileName: String) -> Unit = {},
    val onCancelExport: () -> Unit = {},
    val onAnswer: (ImportChoice) -> Unit = {},
    val onCancelImport: () -> Unit = {},
    val onDismissMessage: () -> Unit = {},
)

/** Diálogos de los paquetes: guardar o compartir, sustituir o importar como nuevo, y el resultado (S013). */
@Composable
fun PackageDialogs(state: PackageUiState, actions: PackageDialogActions) {
    val export = state.export
    if (export != null && !export.writing) {
        AlertDialog(
            onDismissRequest = actions.onCancelExport,
            title = { Text(stringResource(R.string.package_export)) },
            text = { Text(stringResource(R.string.package_export_question, export.projectName)) },
            confirmButton = {
                Row {
                    DialogButton(R.string.export_save, PACKAGE_SAVE_TAG) { actions.onSave(export.fileName) }
                    DialogButton(R.string.export_share, PACKAGE_SHARE_TAG) { actions.onShare(export.fileName) }
                }
            },
            dismissButton = { DialogButton(R.string.dialog_cancel, null, actions.onCancelExport) },
        )
    }
    state.question?.let { existing ->
        AlertDialog(
            onDismissRequest = actions.onCancelImport,
            text = { Text(stringResource(R.string.package_exists, existing)) },
            confirmButton = {
                Column {
                    DialogButton(R.string.package_replace, PACKAGE_REPLACE_TAG) { actions.onAnswer(ImportChoice.REPLACE) }
                    DialogButton(R.string.package_as_new, PACKAGE_AS_NEW_TAG) { actions.onAnswer(ImportChoice.AS_NEW) }
                }
            },
            dismissButton = { DialogButton(R.string.dialog_cancel, null, actions.onCancelImport) },
        )
    }
    state.message?.let { message ->
        AlertDialog(
            onDismissRequest = actions.onDismissMessage,
            text = {
                Text(stringResource(message.textRes(), state.messageArg.orEmpty()), modifier = Modifier.testTag(PACKAGE_MESSAGE_TAG))
            },
            confirmButton = { DialogButton(R.string.backup_dismiss, null, actions.onDismissMessage) },
        )
    }
}

@Composable
private fun DialogButton(text: Int, tag: String?, onClick: () -> Unit) {
    val modifier = Modifier.heightIn(min = MinTouchTarget)
    TextButton(onClick = onClick, modifier = if (tag == null) modifier else modifier.testTag(tag)) { Text(stringResource(text)) }
}

private fun PackageMessage.textRes(): Int = when (this) {
    PackageMessage.SAVED -> R.string.package_saved
    PackageMessage.NOT_SAVED -> R.string.package_not_saved
    PackageMessage.IMPORTED -> R.string.package_imported
    PackageMessage.NOT_TOPOPHONE -> R.string.package_not_topophone
    PackageMessage.IS_BACKUP -> R.string.package_is_backup
    PackageMessage.DAMAGED -> R.string.package_damaged
    PackageMessage.NEWER -> R.string.package_newer
    PackageMessage.MEASURING -> R.string.backup_measuring
    PackageMessage.NOT_IMPORTED -> R.string.package_not_imported
}
