package es.topophone.app.ui.settings

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
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.lifecycle.HiltViewModel
import es.topophone.app.R
import es.topophone.app.log.SystemReportWriter
import es.topophone.app.ui.export.ExportFiles
import es.topophone.app.ui.export.shareExportIntent
import es.topophone.app.ui.export.writeToUri
import es.topophone.core.designsystem.component.MinTouchTarget
import es.topophone.core.formats.backup.BackupFileNames
import es.topophone.core.model.log.AppLog
import es.topophone.core.model.log.LogCategory
import java.io.File
import java.io.OutputStream
import java.time.Instant
import java.time.ZoneId
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

const val REPORT_SAVE_TAG = "report_save"
const val REPORT_SHARE_TAG = "report_share"
const val REPORT_MESSAGE_TAG = "report_message"

private const val REPORT_MIME = "application/zip"

/** Lo que se dice al terminar el informe (S014/RF-10, RF-17). */
enum class ReportMessage { SAVED, NOT_SAVED }

/** [fileName] es el nombre propuesto (S014/RF-16); [choosing] mientras se pregunta si guardarlo o compartirlo. */
data class ReportUiState(val choosing: Boolean = false, val fileName: String = "", val message: ReportMessage? = null)

/** "Crear informe de sistema" (S014/RF-10, RF-16, RF-17). */
@HiltViewModel
class SystemReportViewModel @Inject constructor(private val writer: SystemReportWriter, private val log: AppLog) : ViewModel() {
    internal var clock: () -> Instant = Instant::now
    internal var zone: () -> ZoneId = ZoneId::systemDefault

    private val mutable = MutableStateFlow(ReportUiState())
    val state: StateFlow<ReportUiState> = mutable.asStateFlow()

    fun start() {
        mutable.value = ReportUiState(choosing = true, fileName = BackupFileNames.systemReport(clock(), zone()))
    }

    fun startWriting() {
        mutable.update { it.copy(choosing = false) }
    }

    fun cancel() {
        mutable.value = ReportUiState()
    }

    suspend fun write(out: OutputStream): Boolean = writer.write(out)

    /** Escribe el informe en [file] para compartirlo; si no se puede, lo borra para no dejarlo a medias (S014/RF-17). */
    suspend fun writeFile(file: File): Boolean {
        var ok = false
        try {
            val out = withContext(Dispatchers.IO) { runCatching { file.outputStream() }.getOrNull() } ?: return false
            ok = out.use { write(it) }
            return ok
        } finally {
            if (!ok) withContext(NonCancellable + Dispatchers.IO) { file.delete() }
        }
    }

    /** [saved] null: se ha compartido o se canceló el selector. */
    fun done(saved: Boolean?) {
        when (saved) {
            true -> log.info(LogCategory.APP, "Informe de sistema creado: ${mutable.value.fileName}")
            false -> log.error(LogCategory.APP, "No se pudo crear el informe de sistema") // S014/RF-17
            null -> Unit
        }
        mutable.value = ReportUiState(message = saved?.let { if (it) ReportMessage.SAVED else ReportMessage.NOT_SAVED })
    }

    fun dismissMessage() {
        mutable.update { it.copy(message = null) }
    }
}

/**
 * Crea el informe con el selector de ficheros o lo comparte (S014/RF-10, RF-17). Devuelve lo que llama "Crear informe de
 * sistema" en Ajustes.
 */
@Composable
fun rememberSystemReport(viewModel: SystemReportViewModel = hiltViewModel()): () -> Unit {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val resolver = context.contentResolver
    val scope = rememberCoroutineScope()
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(REPORT_MIME)) { uri ->
        if (uri == null) {
            viewModel.done(null)
        } else {
            scope.launch {
                val saved = writeToUri(resolver, uri, viewModel::write)
                if (!saved) runCatching { DocumentsContract.deleteDocument(resolver, uri) } // S014/RF-17: nada a medias.
                viewModel.done(saved)
            }
        }
    }
    SystemReportDialogs(
        state = state,
        onSave = {
            viewModel.startWriting()
            save.launch(state.fileName)
        },
        onShare = {
            val fileName = state.fileName
            viewModel.startWriting()
            scope.launch {
                val file = ExportFiles.prepare(context.cacheDir, fileName, System.currentTimeMillis())
                val ok = viewModel.writeFile(file)
                if (ok) {
                    context.startActivity(
                        shareExportIntent(context, file, mimeType = REPORT_MIME, chooserTitle = R.string.report_share_chooser),
                    )
                }
                viewModel.done(if (ok) null else false)
            }
        },
        onCancel = viewModel::cancel,
        onDismissMessage = viewModel::dismissMessage,
    )
    return remember(viewModel) { viewModel::start }
}

@Composable
fun SystemReportDialogs(state: ReportUiState, onSave: () -> Unit, onShare: () -> Unit, onCancel: () -> Unit, onDismissMessage: () -> Unit) {
    if (state.choosing) {
        AlertDialog(
            onDismissRequest = onCancel,
            title = { Text(stringResource(R.string.report_create)) },
            text = { Text(stringResource(R.string.report_question)) },
            confirmButton = {
                Row {
                    TextButton(onClick = onSave, modifier = Modifier.heightIn(min = MinTouchTarget).testTag(REPORT_SAVE_TAG)) {
                        Text(stringResource(R.string.export_save))
                    }
                    TextButton(onClick = onShare, modifier = Modifier.heightIn(min = MinTouchTarget).testTag(REPORT_SHARE_TAG)) {
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
                val text = if (message == ReportMessage.SAVED) R.string.report_saved else R.string.report_not_saved
                Text(stringResource(text), modifier = Modifier.testTag(REPORT_MESSAGE_TAG))
            },
            confirmButton = {
                TextButton(onClick = onDismissMessage, modifier = Modifier.heightIn(min = MinTouchTarget)) {
                    Text(stringResource(R.string.backup_dismiss))
                }
            },
        )
    }
}
