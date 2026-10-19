package es.topophone.app.ui.backup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import es.topophone.app.backup.AutoBackupScheduler
import es.topophone.core.formats.backup.AutoBackupPolicy
import es.topophone.data.backup.AutoBackupPreferences
import es.topophone.data.project.ProjectRepository
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Lo que la lista de proyectos dice de las copias automáticas (S013/RF-21, RF-22). */
enum class BackupNotice {
    NONE,

    /** S013/RF-22: no hay carpeta; se ofrece elegirla. */
    NO_FOLDER,

    /** S013/RF-21: la última copia automática falló. */
    FAILED,
}

/** Aviso y alerta de las copias automáticas en la lista de proyectos (S013/RF-21 a RF-23; plan, A8). */
@HiltViewModel
class BackupNoticeViewModel @Inject constructor(
    private val preferences: AutoBackupPreferences,
    projects: ProjectRepository,
    private val scheduler: AutoBackupScheduler,
) : ViewModel() {
    internal var clock: () -> Long = System::currentTimeMillis

    /** Proyectos activos de todas las carpetas: la búsqueda vacía los encuentra todos (S006/RF-4). */
    private val projectCount = projects.search("").map { it.size }

    val notice: StateFlow<BackupNotice> = combine(preferences.state, projectCount) { auto, count ->
        when {
            auto.folderUri != null && auto.failing -> BackupNotice.FAILED
            AutoBackupPolicy.showNotice(clock(), auto.folderUri != null, count, auto.noticeDismissedMs) -> BackupNotice.NO_FOLDER
            else -> BackupNotice.NONE
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), BackupNotice.NONE)

    /** S013/RF-23. */
    fun dismiss() {
        viewModelScope.launch { preferences.dismissNotice(clock()) }
    }

    /** S013/RF-22: la carpeta elegida desde el aviso; el aviso desaparece y se hace la primera copia. */
    fun folderChosen(treeUri: String) {
        viewModelScope.launch { scheduler.folderChosen(treeUri) }
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
