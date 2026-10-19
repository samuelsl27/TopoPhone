package es.topophone.app.ui.backup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import es.topophone.app.backup.AutoBackupScheduler
import es.topophone.app.ui.collector.MeasurementInProgress
import es.topophone.core.formats.backup.BackupKind
import es.topophone.data.backup.AutoBackupPreferences
import es.topophone.data.backup.BackupRead
import es.topophone.data.backup.Backups
import es.topophone.data.backup.RestoreResult
import java.io.InputStream
import java.io.OutputStream
import java.time.Instant
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Lo que la pantalla de copias le dice al usuario (S013). */
enum class BackupMessage {
    /** S013/RF-1. */
    SAVED,

    /** S013/RF-5. */
    NOT_SAVED,

    /** S013/RF-8. */
    RESTORED,

    /** S013/RF-11. */
    UNDONE,

    /** S013/RF-12. */
    NOT_TOPOPHONE,

    /** S013/RF-12: es un paquete de proyecto, que se importa desde la lista de proyectos. */
    IS_PACKAGE,

    /** S013/RF-12. */
    DAMAGED,

    /** S013/RF-13. */
    NEWER,

    /** S013/RF-16. */
    MEASURING,

    /** S013/RF-10. */
    NOT_RESTORED,

    /** S013/RF-17: el selector dio una carpeta en la que no se puede escribir. */
    FOLDER_FAILED,
}

/** Dónde está la restauración (S013/RF-6). */
sealed interface BackupStep {
    data object Idle : BackupStep

    data object Working : BackupStep

    /** Copia leída: su fecha y lo que tiene, para confirmar que sustituya todo (S013/RF-6). */
    data class Confirm(val createdAt: Instant, val projects: Int, val points: Int) : BackupStep
}

data class BackupsUiState(
    val step: BackupStep = BackupStep.Idle,
    val undoAvailable: Boolean = false,
    val folderUri: String? = null,
    val lastAutoBackupMs: Long? = null,
    val autoFailing: Boolean = false,
    val message: BackupMessage? = null,
)

/** Pantalla "Copias de seguridad" de Ajustes (S013/RF-1, RF-5, RF-6, RF-11 a RF-13, RF-16, RF-17, RF-20, RF-21). */
@HiltViewModel
class BackupsViewModel @Inject constructor(
    private val backups: Backups,
    private val preferences: AutoBackupPreferences,
    private val scheduler: AutoBackupScheduler,
    private val measuring: MeasurementInProgress,
) : ViewModel() {
    private val step = MutableStateFlow<BackupStep>(BackupStep.Idle)
    private val message = MutableStateFlow<BackupMessage?>(null)
    private var chosen: BackupRead.Valid? = null

    val state: StateFlow<BackupsUiState> = combine(step, message, backups.undoAvailable, preferences.state) { step, message, undo, auto ->
        BackupsUiState(
            step = step,
            undoAvailable = undo,
            folderUri = auto.folderUri,
            lastAutoBackupMs = auto.lastSuccessMs,
            autoFailing = auto.failing,
            message = message,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), BackupsUiState())

    /** S013/RF-1: la pantalla lo escribe en el sitio elegido con el selector de ficheros. */
    suspend fun writeBackup(out: OutputStream): Boolean = backups.writeBackup(out)

    /** S013/RF-1, RF-5. */
    fun backupSaved(saved: Boolean) {
        message.value = if (saved) BackupMessage.SAVED else BackupMessage.NOT_SAVED
    }

    /** S013/RF-6, RF-12, RF-13: lee la copia elegida y, si vale, pide confirmación. */
    fun open(input: () -> InputStream) {
        viewModelScope.launch {
            step.value = BackupStep.Working
            when (val read = backups.read(input, BackupKind.BACKUP)) {
                is BackupRead.Valid -> {
                    chosen = read
                    step.value = BackupStep.Confirm(read.manifest.createdAt, read.manifest.projectCount, read.manifest.pointCount)
                }
                BackupRead.NotTopoPhone -> fail(BackupMessage.NOT_TOPOPHONE)
                is BackupRead.WrongKind -> fail(BackupMessage.IS_PACKAGE)
                BackupRead.Damaged -> fail(BackupMessage.DAMAGED)
                BackupRead.Newer -> fail(BackupMessage.NEWER)
            }
        }
    }

    /** S013/RF-7, RF-8, RF-10, RF-16. */
    fun confirmRestore() {
        val valid = chosen ?: return
        chosen = null
        if (measuring.active.value) return fail(BackupMessage.MEASURING)
        viewModelScope.launch {
            step.value = BackupStep.Working
            val result = backups.restore(valid, scheduler::keepBeforeRestore)
            step.value = BackupStep.Idle
            message.value = if (result is RestoreResult.Restored) BackupMessage.RESTORED else BackupMessage.NOT_RESTORED
        }
    }

    fun cancelRestore() {
        chosen = null
        step.value = BackupStep.Idle
    }

    /** S013/RF-11, RF-16 (la pantalla ya ha pedido confirmación). */
    fun undo() {
        if (measuring.active.value) return fail(BackupMessage.MEASURING)
        viewModelScope.launch {
            step.value = BackupStep.Working
            val result = backups.undoRestore()
            step.value = BackupStep.Idle
            message.value = if (result is RestoreResult.Restored) BackupMessage.UNDONE else BackupMessage.NOT_RESTORED
        }
    }

    /** S013/RF-17: la carpeta elegida con el selector de carpetas. */
    fun folderChosen(treeUri: String) {
        viewModelScope.launch {
            if (!scheduler.folderChosen(treeUri) &&
                preferences.state.first().folderUri != treeUri
            ) {
                message.value = BackupMessage.FOLDER_FAILED
            }
        }
    }

    fun removeFolder() {
        viewModelScope.launch { scheduler.folderRemoved() }
    }

    fun dismissMessage() {
        message.value = null
    }

    private fun fail(reason: BackupMessage) {
        step.value = BackupStep.Idle
        message.value = reason
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
