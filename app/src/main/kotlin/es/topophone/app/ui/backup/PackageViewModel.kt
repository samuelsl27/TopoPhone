package es.topophone.app.ui.backup

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import es.topophone.core.formats.backup.BackupFileNames
import es.topophone.data.backup.Backups
import java.io.OutputStream
import java.time.Instant
import java.time.ZoneId
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** El paquete que se está exportando y su nombre propuesto (S013/RF-4, RF-24); [writing] mientras se guarda o comparte. */
data class PackageExport(val projectId: String, val projectName: String, val fileName: String, val writing: Boolean = false)

/** Lo que se dice al terminar con un paquete (S013). */
enum class PackageMessage {
    /** S013/RF-24. */
    SAVED,

    /** S013/RF-5. */
    NOT_SAVED,
}

data class PackageUiState(val export: PackageExport? = null, val message: PackageMessage? = null)

/** Paquetes de proyecto desde la lista de proyectos (S013/RF-24). */
@HiltViewModel
class PackageViewModel @Inject constructor(private val backups: Backups) : ViewModel() {
    internal var clock: () -> Instant = Instant::now
    internal var zone: () -> ZoneId = ZoneId::systemDefault

    private val mutable = MutableStateFlow(PackageUiState())
    val state: StateFlow<PackageUiState> = mutable.asStateFlow()

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
        mutable.value = PackageUiState(
            message = when (saved) {
                true -> PackageMessage.SAVED
                false -> PackageMessage.NOT_SAVED
                null -> null
            },
        )
    }

    fun dismissMessage() {
        mutable.update { it.copy(message = null) }
    }
}
