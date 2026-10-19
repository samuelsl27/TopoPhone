package es.topophone.app.ui.backup

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import es.topophone.app.R
import es.topophone.app.ui.SectionScaffold
import es.topophone.app.ui.export.writeToUri
import es.topophone.app.ui.projects.ConfirmDialog
import es.topophone.app.ui.projects.formatDateTime
import es.topophone.app.ui.receiver.uiLocale
import es.topophone.core.designsystem.component.MinTouchTarget
import es.topophone.core.designsystem.component.SettingsInfo
import es.topophone.core.designsystem.component.SettingsItem
import es.topophone.core.designsystem.component.SettingsSectionHeader
import es.topophone.core.formats.backup.BackupFileNames
import es.topophone.core.model.preferences.ThemeState
import java.io.IOException
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.launch

const val BACKUP_CREATE_TAG = "backup_create"
const val BACKUP_RESTORE_TAG = "backup_restore"
const val BACKUP_UNDO_TAG = "backup_undo"
const val BACKUP_FOLDER_TAG = "backup_folder"
const val BACKUP_FOLDER_REMOVE_TAG = "backup_folder_remove"
const val BACKUP_MESSAGE_TAG = "backup_message"

/** Lo que el usuario hace en la pantalla de copias (S013). */
data class BackupsActions(
    /** S013/RF-1: abre el selector de ficheros con el nombre propuesto (S013/RF-4). */
    val onCreate: (fileName: String) -> Unit = {},
    /** S013/RF-6: abre el selector de ficheros para elegir la copia. */
    val onRestore: () -> Unit = {},
    val onConfirmRestore: () -> Unit = {},
    val onCancelRestore: () -> Unit = {},
    /** S013/RF-11, ya confirmado. */
    val onUndo: () -> Unit = {},
    /** S013/RF-17: abre el selector de carpetas. */
    val onChooseFolder: () -> Unit = {},
    val onRemoveFolder: () -> Unit = {},
    val onDismissMessage: () -> Unit = {},
)

/**
 * "Copias de seguridad" de Ajustes con los selectores del sistema (RNF-T5). [onRestored] se llama tras restaurar o
 * deshacer, para volver a la lista de proyectos con los datos nuevos.
 */
@Composable
fun BackupsRoute(
    themeState: ThemeState,
    onToggleSun: () -> Unit,
    onBack: () -> Unit,
    onRestored: () -> Unit = {},
    viewModel: BackupsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val resolver = LocalContext.current.contentResolver
    val scope = rememberCoroutineScope()
    // S013/RF-1, RF-5: si el usuario cancela, no se escribe nada; si falla, no queda un fichero a medias.
    val create = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(ZIP_MIME)) { uri ->
        if (uri != null) {
            scope.launch {
                val saved = writeToUri(resolver, uri, viewModel::writeBackup)
                if (!saved) deleteQuietly(resolver, uri)
                viewModel.backupSaved(saved)
            }
        }
    }
    val open = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.open { resolver.openInputStream(uri) ?: throw IOException("Sin entrada para $uri") }
    }
    val folder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) viewModel.folderChosen(uri.toString())
    }
    LaunchedEffect(state.message) {
        if (state.message == BackupMessage.RESTORED || state.message == BackupMessage.UNDONE) onRestored()
    }
    BackupsScreen(
        themeState,
        onToggleSun,
        state,
        BackupsActions(
            onCreate = { name ->
                viewModel.dismissMessage()
                create.launch(name)
            },
            onRestore = {
                viewModel.dismissMessage()
                open.launch(arrayOf("*/*")) // RNF-T5: se valida por el contenido, no por el tipo.
            },
            onConfirmRestore = viewModel::confirmRestore,
            onCancelRestore = viewModel::cancelRestore,
            onUndo = viewModel::undo,
            onChooseFolder = { folder.launch(null) },
            onRemoveFolder = viewModel::removeFolder,
            onDismissMessage = viewModel::dismissMessage,
        ),
        onBack,
    )
}

@Composable
fun BackupsScreen(
    themeState: ThemeState,
    onToggleSun: () -> Unit,
    state: BackupsUiState,
    actions: BackupsActions,
    onBack: () -> Unit,
    now: () -> Instant = Instant::now,
) {
    var confirmUndo by rememberSaveable { mutableStateOf(false) }
    val locale = uiLocale()
    SectionScaffold(
        title = stringResource(R.string.backups_title),
        themeState = themeState,
        onToggleSun = onToggleSun,
        onNavigateBack = onBack,
    ) { padding ->
        Column(modifier = Modifier.padding(padding).verticalScroll(rememberScrollState())) {
            if (state.step == BackupStep.Working) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Text(stringResource(R.string.backup_working), modifier = Modifier.padding(16.dp))
            }
            state.message?.let { message -> MessageCard(message, actions.onDismissMessage) }
            SettingsSectionHeader(stringResource(R.string.backup_section_full))
            SettingsItem(
                title = stringResource(R.string.backup_create),
                onClick = { actions.onCreate(BackupFileNames.backup(now(), ZoneId.systemDefault())) },
                modifier = Modifier.testTag(BACKUP_CREATE_TAG),
            )
            SettingsItem(
                title = stringResource(R.string.backup_restore),
                onClick = actions.onRestore,
                modifier = Modifier.testTag(BACKUP_RESTORE_TAG),
            )
            if (state.undoAvailable) {
                SettingsItem(
                    title = stringResource(R.string.backup_undo),
                    onClick = { confirmUndo = true },
                    modifier = Modifier.testTag(BACKUP_UNDO_TAG),
                )
            }
            SettingsSectionHeader(stringResource(R.string.backup_section_auto))
            SettingsItem(
                title = stringResource(R.string.backup_folder),
                value = state.folderUri?.let(::folderLabel) ?: stringResource(R.string.backup_folder_none),
                onClick = actions.onChooseFolder,
                modifier = Modifier.testTag(BACKUP_FOLDER_TAG),
            )
            if (state.folderUri != null) {
                SettingsInfo(
                    title = stringResource(R.string.backup_last_auto),
                    value = state.lastAutoBackupMs?.let { formatDateTime(it, locale) } ?: stringResource(R.string.backup_last_auto_none),
                )
                SettingsItem(
                    title = stringResource(R.string.backup_folder_remove),
                    onClick = actions.onRemoveFolder,
                    modifier = Modifier.testTag(BACKUP_FOLDER_REMOVE_TAG),
                )
            }
            if (state.autoFailing) {
                Text(
                    stringResource(R.string.backup_auto_failed),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(16.dp),
                )
            }
        }
    }
    (state.step as? BackupStep.Confirm)?.let { confirm ->
        val projects = pluralStringResource(R.plurals.backup_projects, confirm.projects, confirm.projects)
        val points = pluralStringResource(R.plurals.backup_points, confirm.points, confirm.points)
        ConfirmDialog(
            message = stringResource(
                R.string.backup_confirm_text,
                formatDateTime(confirm.createdAt.toEpochMilli(), locale),
                projects,
                points,
            ),
            confirm = stringResource(R.string.backup_confirm),
            onConfirm = actions.onConfirmRestore,
            onDismiss = actions.onCancelRestore,
        )
    }
    if (confirmUndo) {
        ConfirmDialog(
            message = stringResource(R.string.backup_undo_text),
            confirm = stringResource(R.string.backup_undo_confirm),
            onConfirm = {
                confirmUndo = false
                actions.onUndo()
            },
            onDismiss = { confirmUndo = false },
        )
    }
}

@Composable
private fun MessageCard(message: BackupMessage, onDismiss: () -> Unit) {
    Surface(
        tonalElevation = 2.dp,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth().padding(16.dp).testTag(BACKUP_MESSAGE_TAG),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(stringResource(message.textRes()), style = MaterialTheme.typography.bodyLarge)
            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = MinTouchTarget)) {
                Text(stringResource(R.string.backup_dismiss))
            }
        }
    }
}

private fun BackupMessage.textRes(): Int = when (this) {
    BackupMessage.SAVED -> R.string.backup_saved
    BackupMessage.NOT_SAVED -> R.string.backup_not_saved
    BackupMessage.RESTORED -> R.string.backup_restored
    BackupMessage.UNDONE -> R.string.backup_undone
    BackupMessage.NOT_TOPOPHONE -> R.string.backup_not_topophone
    BackupMessage.IS_PACKAGE -> R.string.backup_is_package
    BackupMessage.DAMAGED -> R.string.backup_damaged
    BackupMessage.NEWER -> R.string.backup_newer
    BackupMessage.MEASURING -> R.string.backup_measuring
    BackupMessage.NOT_RESTORED -> R.string.backup_not_restored
    BackupMessage.FOLDER_FAILED -> R.string.backup_folder_failed
}

/** Nombre legible de la carpeta elegida: "Documents/TopoPhone" en vez del URI del árbol de documentos. */
internal fun folderLabel(treeUri: String): String {
    val id = Uri.parse(treeUri).lastPathSegment ?: return treeUri
    return id.substringAfter(':', id).ifEmpty { id }
}

/** S013/RF-5: el documento que no se ha podido escribir se borra. */
private fun deleteQuietly(resolver: ContentResolver, uri: Uri) {
    runCatching { DocumentsContract.deleteDocument(resolver, uri) }
}

private const val ZIP_MIME = "application/zip"
