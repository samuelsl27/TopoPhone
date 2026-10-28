package es.topophone.app.ui.alignment

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import es.topophone.app.di.IoDispatcher
import es.topophone.core.model.alignment.AlignmentSummary
import es.topophone.core.model.log.AppLog
import es.topophone.core.model.log.LogCategory
import es.topophone.core.model.project.LinearUnit
import es.topophone.data.alignment.AlignmentRepository
import es.topophone.data.project.ProjectRepository
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Los ejes de un proyecto con el que está activo; [unit] es la del proyecto, con la que se escriben los PK (S049/RF-14, RF-19). */
data class AlignmentsUiState(
    val alignments: List<AlignmentSummary> = emptyList(),
    val activeId: String? = null,
    val unit: LinearUnit = LinearUnit.METRE,
)

/**
 * La lista de ejes del proyecto (S049/RF-14, RF-15, RF-19): activar uno, o ninguno; renombrarlo; mostrarlo u ocultarlo en el mapa, que se
 * recuerda para el proyecto también al cerrar la app; y borrarlo entero con su rasante sin tocar ningún punto.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class AlignmentsViewModel @Inject constructor(
    private val alignments: AlignmentRepository,
    private val projects: ProjectRepository,
    @param:IoDispatcher private val io: CoroutineDispatcher,
    private val log: AppLog = AppLog.NONE,
) : ViewModel() {
    private val projectId = MutableStateFlow<String?>(null)

    val state: StateFlow<AlignmentsUiState> = projectId.filterNotNull()
        .flatMapLatest { id ->
            combine(
                alignments.summaries(id),
                alignments.activeAlignmentId(id),
                projects.observe(id).map { it?.linearUnit ?: LinearUnit.METRE },
            ) { list, active, unit -> AlignmentsUiState(list, active?.takeIf { a -> list.any { it.id == a } }, unit) }
        }
        .flowOn(io)
        .stateIn(viewModelScope, SharingStarted.Eagerly, AlignmentsUiState())

    /** Empieza a seguir los ejes del proyecto [id]. */
    fun start(id: String) {
        projectId.value = id
    }

    /** Hace activo el eje [alignmentId], o ninguno si es null (RF-14). */
    fun activate(alignmentId: String?) {
        val id = projectId.value ?: return
        viewModelScope.launch(io) {
            if (alignments.activate(id, alignmentId)) log.info(LogCategory.APP, "Ejes: cambio de eje activo")
        }
    }

    /** Cambia el nombre del eje; false si está en blanco (RF-14). */
    fun rename(alignmentId: String, name: String): Boolean {
        if (name.isBlank()) return false
        viewModelScope.launch(io) { alignments.rename(alignmentId, name) }
        return true
    }

    /** Muestra u oculta el eje en el mapa; se recuerda para el proyecto, también al cerrar la app (RF-19). */
    fun setVisible(alignmentId: String, visible: Boolean) {
        viewModelScope.launch(io) { alignments.setVisible(alignmentId, visible) }
    }

    /** Borra el eje entero con su rasante; si era el activo el proyecto queda sin ninguno y los puntos no se tocan (RF-15). */
    fun delete(alignmentId: String) {
        viewModelScope.launch(io) { alignments.delete(alignmentId) }
    }
}
