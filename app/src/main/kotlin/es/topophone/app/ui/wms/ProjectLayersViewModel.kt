package es.topophone.app.ui.wms

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import es.topophone.app.ui.map.WmsLayerPlan
import es.topophone.core.model.wms.WmsLimits
import es.topophone.data.wms.AddLayerResult
import es.topophone.data.wms.ProjectLayerView
import es.topophone.data.wms.ProjectLayersRepository
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Por qué no se añadió una capa al proyecto (S036/RF-12, RF-26). */
enum class ProjectLayersMessage { ALREADY_THERE, LIMIT_REACHED, NO_WEB_MERCATOR, NOT_FOUND }

/**
 * Las capas WMS del proyecto abierto (S036/RF-18 a RF-24, RF-26 a RF-28): [layers] de arriba abajo con su servicio y si siguen
 * disponibles, y [plan], lo que el mapa dibuja de ellas. [message] es por qué no se pudo añadir la última capa pedida.
 */
data class ProjectLayersUiState(
    val layers: List<ProjectLayerView> = emptyList(),
    val plan: WmsLayerPlan = WmsLayerPlan.EMPTY,
    val message: ProjectLayersMessage? = null,
) {
    /** El proyecto ya tiene el máximo de capas: «Añadir capa» queda desactivado (RF-26). */
    val full: Boolean get() = layers.size >= WmsLimits.MAX_PROJECT_LAYERS
}

/**
 * Las capas del proyecto: añadir (arriba del todo, visible y al 100 %), visibilidad, opacidad de 5 en 5, orden y quitar. Se guardan con el
 * proyecto y se recuperan al abrirlo (RF-24).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ProjectLayersViewModel @Inject constructor(private val repository: ProjectLayersRepository) : ViewModel() {
    private val projectId = MutableStateFlow<String?>(null)
    private val message = MutableStateFlow<ProjectLayersMessage?>(null)

    val state: StateFlow<ProjectLayersUiState> = combine(
        projectId.flatMapLatest { id -> if (id == null) flowOf(emptyList()) else repository.layers(id) },
        message,
    ) { layers, message -> ProjectLayersUiState(layers, WmsLayerPlan.of(layers), message) }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), ProjectLayersUiState())

    fun start(id: String) {
        if (projectId.value != null) return
        projectId.value = id
    }

    /** Añade la capa [layerName] de [serviceId] arriba del todo (RF-19); si no se puede, [ProjectLayersUiState.message] dice por qué. */
    fun add(serviceId: String, layerName: String) {
        val id = projectId.value ?: return
        viewModelScope.launch {
            message.value = when (repository.add(id, serviceId, layerName)) {
                is AddLayerResult.Added -> null
                AddLayerResult.AlreadyThere -> ProjectLayersMessage.ALREADY_THERE
                AddLayerResult.LimitReached -> ProjectLayersMessage.LIMIT_REACHED
                AddLayerResult.NoWebMercator -> ProjectLayersMessage.NO_WEB_MERCATOR
                AddLayerResult.NotFound -> ProjectLayersMessage.NOT_FOUND
            }
        }
    }

    fun dismissMessage() {
        message.value = null
    }

    fun setVisible(layerId: String, visible: Boolean) {
        viewModelScope.launch { repository.setVisible(layerId, visible) }
    }

    /** La opacidad va del 0 al 100 % de 5 en 5; otro valor se ajusta al paso más cercano (RF-21). */
    fun setOpacity(layerId: String, percent: Int) {
        viewModelScope.launch { repository.setOpacity(layerId, percent) }
    }

    /** Sube la capa un puesto, hacia arriba de la lista, donde se pinta encima de las demás (RF-22). */
    fun moveUp(layerId: String) = moveBy(layerId, -1)

    fun moveDown(layerId: String) = moveBy(layerId, 1)

    private fun moveBy(layerId: String, delta: Int) {
        val layers = state.value.layers
        val index = layers.indexOfFirst { it.layer.id == layerId }
        if (index < 0) return
        val target = (index + delta).coerceIn(0, layers.lastIndex)
        if (target != index) viewModelScope.launch { repository.move(layerId, target) }
    }

    /** Pone la capa en la posición [position] de la lista (0 es la de arriba), para arrastrarla (RF-22). */
    fun move(layerId: String, position: Int) {
        viewModelScope.launch { repository.move(layerId, position) }
    }

    /** Quita la capa del proyecto sin borrar su servicio (RF-23). */
    fun remove(layerId: String) {
        viewModelScope.launch { repository.remove(layerId) }
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
