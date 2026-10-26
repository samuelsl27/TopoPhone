package es.topophone.app.ui.surface

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import es.topophone.app.di.IoDispatcher
import es.topophone.core.model.format.NumberInput
import es.topophone.core.model.log.AppLog
import es.topophone.core.model.log.LogCategory
import es.topophone.core.model.project.LinearUnit
import es.topophone.core.model.surface.Surface
import es.topophone.data.project.ProjectRepository
import es.topophone.data.surface.SurfaceRepository
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

/**
 * Las superficies de un proyecto con la que está activa y el desplazamiento vertical, en metros (S042/RF-16, RF-19). [unit] es la
 * del proyecto, en la que se escribe y se muestra el desplazamiento.
 */
data class SurfacesUiState(
    val surfaces: List<Surface> = emptyList(),
    val activeId: String? = null,
    val offsetM: Double = 0.0,
    val unit: LinearUnit = LinearUnit.METRE,
)

/**
 * La lista de superficies del proyecto (S042/RF-16 a RF-20): activar una, o ninguna, en un toque; borrarla; y el desplazamiento
 * vertical del proyecto, que se escribe en la unidad del proyecto y solo se aplica si es un número.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class SurfacesViewModel @Inject constructor(
    private val surfaces: SurfaceRepository,
    private val projects: ProjectRepository,
    @param:IoDispatcher private val io: CoroutineDispatcher,
    private val log: AppLog = AppLog.NONE,
) : ViewModel() {
    private val projectId = MutableStateFlow<String?>(null)

    val state: StateFlow<SurfacesUiState> = projectId.filterNotNull()
        .flatMapLatest { id ->
            combine(
                surfaces.surfaces(id),
                surfaces.activeSurfaceId(id),
                surfaces.verticalOffset(id),
                projects.observe(id).map { it?.linearUnit ?: LinearUnit.METRE },
            ) { list, active, offset, unit -> SurfacesUiState(list, active?.takeIf { a -> list.any { it.id == a } }, offset, unit) }
        }
        .flowOn(io)
        .stateIn(viewModelScope, SharingStarted.Eagerly, SurfacesUiState())

    /** Empieza a seguir las superficies del proyecto [id]. */
    fun start(id: String) {
        projectId.value = id
    }

    /** Hace activa la superficie [surfaceId], o ninguna si es null (RF-16). */
    fun activate(surfaceId: String?) {
        val id = projectId.value ?: return
        viewModelScope.launch(io) {
            if (surfaces.activate(id, surfaceId)) {
                log.info(
                    LogCategory.APP,
                    if (surfaceId ==
                        null
                    ) {
                        "Superficies: ninguna activa"
                    } else {
                        "Superficies: cambio de superficie activa"
                    },
                )
            }
        }
    }

    /** Borra la superficie con sus teselas; si era la activa el proyecto queda sin ninguna y los puntos no se tocan (RF-18). */
    fun delete(surfaceId: String) {
        viewModelScope.launch(io) {
            surfaces.delete(surfaceId)
            log.info(LogCategory.APP, "Superficies: una superficie borrada")
        }
    }

    /**
     * Cambia el desplazamiento vertical del proyecto con lo escrito, en la unidad del proyecto y con signo (RF-19). Devuelve false si
     * no es un número y entonces no cambia nada (RF-20).
     */
    fun setOffset(text: String): Boolean {
        val id = projectId.value ?: return false
        val unit = state.value.unit
        val metres = NumberInput.parse(text)?.let(unit::toMetres)?.takeIf { it.isFinite() } ?: return false
        viewModelScope.launch(io) { surfaces.setVerticalOffset(id, metres) }
        return true
    }
}
