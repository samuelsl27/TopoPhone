package es.topophone.app.ui.offline

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import es.topophone.app.ui.map.MapCamera
import es.topophone.core.model.map.Basemap
import es.topophone.data.wms.ProjectLayerView
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** Lo que la vista de proyecto necesita de las zonas sin conexión: si el mapa está cubierto y si se acerca más de lo guardado (S037/RF-20, RF-21). */
data class OfflineCoverageUiState(val covered: Boolean = false, val beyondDetail: Boolean = false)

/**
 * Sigue la cámara del mapa contra las zonas sin conexión completas (S037/RF-20, RF-21). Cubre con cualquier zona de cualquier proyecto, porque las teselas están
 * en la misma base y sirven igual. Las zonas se vuelven a leer cuando cambia la descarga.
 */
@HiltViewModel
class OfflineCoverageViewModel @Inject constructor(private val store: OfflineRegionStore, private val downloader: OfflineDownloader) :
    ViewModel() {
    private val mutableState = MutableStateFlow(OfflineCoverageUiState())
    val state: StateFlow<OfflineCoverageUiState> = mutableState.asStateFlow()

    private var zones: List<ZoneCoverage> = emptyList()
    private var drawn: Set<String> = emptySet()
    private var camera: MapCamera? = null
    private var opened = false

    /** Empieza a leer las zonas y a seguir la descarga; una sola vez. */
    fun open() {
        if (opened) return
        opened = true
        viewModelScope.launch {
            downloader.state.map {
                Triple(it.phase, it.zoneId, it.sources.map { s -> s.state })
            }.distinctUntilChanged().collect { reload() }
        }
    }

    /** Lo que dibuja el mapa ahora: el mapa base y las capas WMS del proyecto. */
    fun drawing(basemap: Basemap, layers: List<ProjectLayerView>) {
        drawn = OfflineCoverage.drawnKeys(basemap, layers)
        recompute()
    }

    fun onCamera(camera: MapCamera) {
        this.camera = camera
        recompute()
    }

    /** Vuelve a leer las regiones: tras una descarga, un borrado o una actualización. */
    fun reload() {
        viewModelScope.launch {
            zones = store.list().mapNotNull { ZoneCoverage.of(it) }
            recompute()
        }
    }

    private fun recompute() {
        val current = camera ?: return
        val status = OfflineCoverage.status(zones, drawn, current)
        mutableState.value = OfflineCoverageUiState(status.covered, status.beyondDetail)
    }
}
