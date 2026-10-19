package es.topophone.app.ui.backup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import es.topophone.app.ui.collector.MeasurementInProgress
import es.topophone.core.formats.backup.BackupFileNames
import es.topophone.core.formats.backup.BackupKind
import es.topophone.core.model.log.AppLog
import es.topophone.core.model.log.LogCategory
import es.topophone.data.backup.BackupRead
import es.topophone.data.backup.Backups
import es.topophone.data.backup.ImportChoice
import es.topophone.data.backup.ImportResult
import java.io.InputStream
import java.io.OutputStream
import java.time.Instant
import java.time.ZoneId
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** El paquete que se está exportando y su nombre propuesto (S013/RF-4, RF-24); [writing] mientras se guarda o comparte. */
data class PackageExport(val projectId: String, val projectName: String, val fileName: String, val writing: Boolean = false)

/** Lo que se dice al terminar con un paquete (S013). */
enum class PackageMessage {
    /** S013/RF-24. */
    SAVED,

    /** S013/RF-5. */
    NOT_SAVED,

    /** S013/RF-25, RF-27, RF-28: con el nombre del proyecto importado. */
    IMPORTED,

    /** S013/RF-12. */
    NOT_TOPOPHONE,

    /** S013/RF-12: es una copia completa, que se restaura desde Ajustes. */
    IS_BACKUP,

    /** S013/RF-12. */
    DAMAGED,

    /** S013/RF-13. */
    NEWER,

    /** S013/RF-16. */
    MEASURING,

    /** El paquete era válido, pero no se ha podido añadir. */
    NOT_IMPORTED,
}

/**
 * [question] es el nombre del proyecto del móvil cuando el del paquete ya está (S013/RF-26); [messageArg], el nombre del
 * proyecto importado.
 */
data class PackageUiState(
    val export: PackageExport? = null,
    val question: String? = null,
    val message: PackageMessage? = null,
    val messageArg: String? = null,
)

/** Paquetes de proyecto desde la lista de proyectos: exportar e importar (S013/RF-24 a RF-28). */
@HiltViewModel
class PackageViewModel @Inject constructor(
    private val backups: Backups,
    private val measuring: MeasurementInProgress,
    private val log: AppLog = AppLog.NONE,
) : ViewModel() {
    internal var clock: () -> Instant = Instant::now
    internal var zone: () -> ZoneId = ZoneId::systemDefault

    private val mutable = MutableStateFlow(PackageUiState())
    val state: StateFlow<PackageUiState> = mutable.asStateFlow()
    private var chosen: BackupRead.Valid? = null
    private var importedLabel = ""

    /** S013/RF-24: pregunta si guardarlo o compartirlo, con el nombre propuesto (S013/RF-4). */
    fun startExport(projectId: String, projectName: String) {
        mutable.value =
            PackageUiState(export = PackageExport(projectId, projectName, BackupFileNames.projectPackage(projectName, clock(), zone())))
    }

    /** Elegido guardar o compartir: el diálogo se cierra mientras se escribe. */
    fun startWriting() {
        mutable.update { it.copy(export = it.export?.copy(writing = true)) }
    }

    fun cancelExport() {
        mutable.update { it.copy(export = null) }
    }

    /** Escribe el paquete del proyecto elegido en [out]; false si no se ha podido. */
    suspend fun writePackage(out: OutputStream): Boolean {
        val export = mutable.value.export ?: return false
        return backups.writePackage(export.projectId, out)
    }

    /** [saved] null: se ha compartido o el usuario canceló el selector, y no hay nada que decir. */
    fun exportDone(saved: Boolean?) {
        if (saved == false) log.error(LogCategory.BACKUPS, "No se pudo guardar el paquete en el sitio elegido") // S014/RF-5
        mutable.value = PackageUiState(
            message = when (saved) {
                true -> PackageMessage.SAVED
                false -> PackageMessage.NOT_SAVED
                null -> null
            },
        )
    }

    /**
     * S013/RF-25, RF-26, RF-12, RF-13: lee el paquete elegido y lo importa; si el proyecto ya está en el móvil, pregunta.
     * [label] es la palabra de la interfaz para el nombre de un proyecto importado como nuevo (S013/RF-28).
     */
    fun openPackage(input: () -> InputStream, label: String) {
        importedLabel = label
        viewModelScope.launch {
            when (val read = backups.read(input, BackupKind.PACKAGE)) {
                is BackupRead.Valid -> {
                    chosen = read
                    finish(backups.importPackage(read, choice = null, importedLabel))
                }
                BackupRead.NotTopoPhone -> show(PackageMessage.NOT_TOPOPHONE)
                is BackupRead.WrongKind -> show(PackageMessage.IS_BACKUP)
                BackupRead.Damaged -> show(PackageMessage.DAMAGED)
                BackupRead.Newer -> show(PackageMessage.NEWER)
            }
        }
    }

    /** S013/RF-26 a RF-28, RF-16: la respuesta a "ya está en el móvil". */
    fun answer(choice: ImportChoice) {
        val valid = chosen ?: return
        mutable.update { it.copy(question = null) }
        if (choice == ImportChoice.REPLACE && measuring.active.value) {
            log.warning(LogCategory.BACKUPS, "Sustitución no hecha: hay una medida en curso") // S014/RF-5
            return show(PackageMessage.MEASURING)
        }
        viewModelScope.launch { finish(backups.importPackage(valid, choice, importedLabel)) }
    }

    fun cancelImport() {
        chosen = null
        mutable.update { it.copy(question = null) }
    }

    fun dismissMessage() {
        mutable.update { it.copy(message = null, messageArg = null) }
    }

    private fun finish(result: ImportResult) {
        when (result) {
            is ImportResult.Imported -> {
                chosen = null
                mutable.update { it.copy(message = PackageMessage.IMPORTED, messageArg = result.name) }
            }
            is ImportResult.NeedsChoice -> mutable.update { it.copy(question = result.existingName) }
            ImportResult.Failed -> show(PackageMessage.NOT_IMPORTED)
        }
    }

    private fun show(message: PackageMessage) {
        mutable.update { it.copy(message = message, messageArg = null) }
    }
}
