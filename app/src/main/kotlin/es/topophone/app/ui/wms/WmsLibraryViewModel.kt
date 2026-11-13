package es.topophone.app.ui.wms

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import es.topophone.core.formats.wms.WmsPresets
import es.topophone.core.model.wms.WmsAddressProblem
import es.topophone.core.model.wms.WmsLayerInfo
import es.topophone.core.model.wms.WmsLayerProblem
import es.topophone.core.model.wms.WmsPreset
import es.topophone.core.model.wms.WmsRequestMode
import es.topophone.core.model.wms.WmsService
import es.topophone.data.wms.MissingProjectLayer
import es.topophone.data.wms.WmsAddOutcome
import es.topophone.data.wms.WmsAddProblem
import es.topophone.data.wms.WmsLibraryRepository
import es.topophone.data.wms.WmsRefreshOutcome
import es.topophone.data.wms.WmsServiceAdder
import es.topophone.data.wms.WmsServiceUse
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Por qué no se pudo añadir o actualizar un servicio: la dirección escrita o lo que contestó el servicio (S036/RF-8 a RF-10). */
sealed interface WmsFailure {
    data class Address(val problem: WmsAddressProblem) : WmsFailure

    /** [detail] es el código HTTP o el mensaje de la excepción del servicio, si los hay. */
    data class Service(val problem: WmsAddProblem, val detail: String? = null) : WmsFailure
}

/** El alta de un servicio por su dirección o por un preset (S036/RF-1, RF-14). */
sealed interface WmsAddState {
    data object Idle : WmsAddState

    /** Pidiendo el GetCapabilities; [presetKey] es el del preset que se está añadiendo, si es uno. */
    data class Loading(val presetKey: String? = null) : WmsAddState

    data class Error(val failure: WmsFailure) : WmsAddState
}

/** La actualización de las capas de un servicio (S036/RF-6). */
sealed interface WmsRefreshState {
    data object Idle : WmsRefreshState

    data object Loading : WmsRefreshState

    /** Actualizado; [missing] son las capas de proyectos que el servicio ya no ofrece y siguen en su proyecto como «no disponibles». */
    data class Done(val missing: List<MissingProjectLayer>) : WmsRefreshState

    /** El servicio queda como estaba. */
    data class Failed(val failure: WmsFailure.Service) : WmsRefreshState
}

/** Un preset con el servicio de la biblioteca que lo cubre, si lo hay (S036/RF-15). */
data class WmsPresetItem(val preset: WmsPreset, val serviceId: String?) {
    val inLibrary: Boolean get() = serviceId != null
}

/** Los presets de un grupo (IGN, Catastro, ICV…), en el orden del catálogo (S036/RF-13). */
data class WmsPresetGroup(val name: String, val items: List<WmsPresetItem>)

/** Una capa de un servicio con el motivo por el que no se puede añadir a un proyecto, si es el caso (S036/RF-12). */
data class WmsLayerRow(val info: WmsLayerInfo) {
    val problem: WmsLayerProblem? get() = info.notAddableReason
}

/** Cómo se ha llegado a ver un servicio: recién añadido o ya estaba en la biblioteca (S036/RF-15). */
enum class WmsOpenNotice { ADDED, ALREADY_THERE }

/** El servicio cuyas capas se están viendo, con su árbol de capas en el orden del documento (S036/RF-3, RF-4). */
data class OpenedWmsService(val service: WmsService, val layers: List<WmsLayerRow>, val notice: WmsOpenNotice? = null)

/** Un borrado pendiente de confirmar, con los proyectos que usan capas del servicio (S036/RF-7). */
data class PendingWmsDelete(val service: WmsService, val uses: List<WmsServiceUse>)

/** Lo que muestra la biblioteca de servicios WMS (S036/RF-1 a RF-17). */
data class WmsLibraryUiState(
    val services: List<WmsService> = emptyList(),
    val presetGroups: List<WmsPresetGroup> = emptyList(),
    val address: String = "",
    val add: WmsAddState = WmsAddState.Idle,
    val opened: OpenedWmsService? = null,
    val refresh: WmsRefreshState = WmsRefreshState.Idle,
    val pendingDelete: PendingWmsDelete? = null,
)

/**
 * La biblioteca de servicios WMS (S036/RF-1 a RF-17): añadir un servicio por su dirección o un preset, ver sus capas sin conexión,
 * actualizarlas, renombrar, cambiar el modo de petición y borrar con el aviso de los proyectos que lo usan. Un servicio con una dirección
 * que ya está en la biblioteca no se pide ni se duplica: se abre el que hay (RF-15).
 */
@HiltViewModel
class WmsLibraryViewModel @Inject constructor(private val library: WmsLibraryRepository, private val adder: WmsServiceAdder) :
    ViewModel() {
    private val mutable = MutableStateFlow(WmsLibraryUiState(presetGroups = groups(emptyList())))
    val state: StateFlow<WmsLibraryUiState> = mutable.asStateFlow()

    private var working: Job? = null

    init {
        viewModelScope.launch { library.services().collect { services -> mutable.update { it.withServices(services) } } }
    }

    fun onAddressChange(text: String) {
        mutable.update { it.copy(address = text, add = if (it.add is WmsAddState.Error) WmsAddState.Idle else it.add) }
    }

    /** «Añadir» con la dirección escrita (RF-1); siempre por teselas, que se puede cambiar después (RF-17). */
    fun add() {
        val address = mutable.value.address
        launchAdd(presetKey = null) { adder.add(address, WmsRequestMode.TILES) }
    }

    /** Añade el preset [key] con el modo y el nombre que trae (RF-14). */
    fun addPreset(key: String) = launchAdd(presetKey = key) { adder.addPreset(key) }

    private fun launchAdd(presetKey: String?, call: suspend () -> WmsAddOutcome) {
        if (mutable.value.add is WmsAddState.Loading) return
        mutable.update { it.copy(add = WmsAddState.Loading(presetKey)) }
        working = viewModelScope.launch {
            when (val outcome = call()) {
                is WmsAddOutcome.Added -> {
                    show(outcome.service, WmsOpenNotice.ADDED)
                    mutable.update { it.copy(address = "", add = WmsAddState.Idle) }
                }
                is WmsAddOutcome.AlreadyThere -> {
                    show(outcome.service, WmsOpenNotice.ALREADY_THERE)
                    mutable.update { it.copy(address = "", add = WmsAddState.Idle) }
                }
                is WmsAddOutcome.InvalidAddress -> fail(WmsFailure.Address(outcome.problem))
                is WmsAddOutcome.Failed -> fail(WmsFailure.Service(outcome.problem, outcome.detail))
                WmsAddOutcome.UnknownPreset -> mutable.update { it.copy(add = WmsAddState.Idle) }
            }
        }
    }

    private fun fail(failure: WmsFailure) = mutable.update { it.copy(add = WmsAddState.Error(failure)) }

    /** Abre un servicio de la biblioteca para ver sus capas, sin pedir nada a la red (RF-3, RF-4). */
    fun open(serviceId: String) {
        viewModelScope.launch {
            val service = library.service(serviceId) ?: return@launch
            show(service, notice = null)
        }
    }

    fun close() {
        working?.cancel()
        mutable.update { it.copy(opened = null, refresh = WmsRefreshState.Idle) }
    }

    /** La hoja se cierra: lo que se estaba haciendo se cancela y al abrirla de nuevo sale la lista, sin el servicio ni los avisos de antes. */
    fun reset() {
        working?.cancel()
        mutable.update {
            it.copy(address = "", add = WmsAddState.Idle, opened = null, refresh = WmsRefreshState.Idle, pendingDelete = null)
        }
    }

    private suspend fun show(service: WmsService, notice: WmsOpenNotice?) {
        val layers = library.layers(service.id).map(::WmsLayerRow)
        mutable.update { it.copy(opened = OpenedWmsService(service, layers, notice), refresh = WmsRefreshState.Idle) }
    }

    /** «Actualizar» el servicio abierto: vuelve a leer sus capas y avisa de las de proyectos que han desaparecido (RF-6). */
    fun refresh() {
        val service = mutable.value.opened?.service ?: return
        if (mutable.value.refresh == WmsRefreshState.Loading) return
        mutable.update { it.copy(refresh = WmsRefreshState.Loading) }
        working = viewModelScope.launch {
            when (val outcome = adder.refresh(service.id)) {
                is WmsRefreshOutcome.Refreshed -> {
                    library.service(service.id)?.let { show(it, notice = null) }
                    mutable.update { it.copy(refresh = WmsRefreshState.Done(outcome.missing)) }
                }
                WmsRefreshOutcome.NotFound -> close()
                is WmsRefreshOutcome.Failed ->
                    mutable.update { it.copy(refresh = WmsRefreshState.Failed(WmsFailure.Service(outcome.problem, outcome.detail))) }
            }
        }
    }

    fun dismissRefresh() = mutable.update { it.copy(refresh = WmsRefreshState.Idle) }

    /** Cambia el nombre del servicio abierto; uno en blanco no se guarda (RF-7). */
    fun rename(name: String) {
        val id = mutable.value.opened?.service?.id ?: return
        viewModelScope.launch { library.rename(id, name.trim()) }
    }

    /** «Por teselas» o «una imagen» para el servicio abierto (RF-17). */
    fun setRequestMode(mode: WmsRequestMode) {
        val id = mutable.value.opened?.service?.id ?: return
        viewModelScope.launch { library.setRequestMode(id, mode) }
    }

    /** Pide confirmar el borrado de [serviceId], con los proyectos que usan sus capas (RF-7). */
    fun requestDelete(serviceId: String) {
        viewModelScope.launch {
            val service = library.service(serviceId) ?: return@launch
            mutable.update { it.copy(pendingDelete = PendingWmsDelete(service, library.projectsUsing(serviceId))) }
        }
    }

    fun cancelDelete() = mutable.update { it.copy(pendingDelete = null) }

    fun confirmDelete() {
        val pending = mutable.value.pendingDelete ?: return
        mutable.update { state ->
            val open = state.opened?.takeUnless { it.service.id == pending.service.id }
            state.copy(pendingDelete = null, opened = open, refresh = if (open == null) WmsRefreshState.Idle else state.refresh)
        }
        viewModelScope.launch { library.delete(pending.service.id) }
    }

    private fun WmsLibraryUiState.withServices(services: List<WmsService>): WmsLibraryUiState {
        // Una lista anterior a un alta reciente no cierra el servicio abierto; el borrado lo cierra a mano (confirmDelete).
        val current = opened?.let { open -> services.firstOrNull { it.id == open.service.id }?.let { open.copy(service = it) } ?: open }
        return copy(services = services, presetGroups = groups(services), opened = current)
    }

    private companion object {
        /** Los presets del catálogo agrupados, cada uno con el servicio de la biblioteca que lo cubre: el que se añadió como él o el que tiene su dirección. */
        fun groups(services: List<WmsService>): List<WmsPresetGroup> = WmsPresets.all.groupBy { it.group }.map { (group, presets) ->
            WmsPresetGroup(
                group,
                presets.map { preset ->
                    WmsPresetItem(preset, services.firstOrNull { it.presetKey == preset.key || it.address == preset.address }?.id)
                },
            )
        }
    }
}
