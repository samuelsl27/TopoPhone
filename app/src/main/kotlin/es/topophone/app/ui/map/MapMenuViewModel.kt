package es.topophone.app.ui.map

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import es.topophone.core.formats.wms.FeatureInfoResult
import es.topophone.core.formats.wms.FeatureInfoText
import es.topophone.core.formats.wms.WmsPresets
import es.topophone.core.formats.wms.WmsRequests
import es.topophone.core.model.wms.WmsAddress
import es.topophone.core.model.wms.WmsAddressResult
import es.topophone.core.model.wms.WmsLimits
import es.topophone.data.wms.ProjectLayerView
import es.topophone.data.wms.WmsHttp
import es.topophone.data.wms.WmsHttpResponse
import es.topophone.data.wms.WmsTimeoutException
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** Por qué una capa no dio su información (S036/RF-39). */
enum class LayerInfoProblem { TIMEOUT, NO_RESPONSE, HTTP_ERROR, SERVICE_EXCEPTION, NO_ADDRESS }

/** Lo que dice una capa de lo que hay en el punto marcado (S036/RF-36, RF-38, RF-39). */
sealed interface LayerInfoStatus {
    data object Loading : LayerInfoStatus

    data class Text(val text: String) : LayerInfoStatus

    /** El servicio contestó y no hay nada en ese punto. */
    data object NoData : LayerInfoStatus

    /** [detail] es el código HTTP o el mensaje de la excepción del servicio, si los hay. */
    data class Failed(val problem: LayerInfoProblem, val detail: String? = null) : LayerInfoStatus
}

/** La respuesta de una capa, con su título y el nombre de su servicio (RF-36). */
data class LayerInfoResult(val layerId: String, val layerTitle: String, val serviceName: String, val status: LayerInfoStatus)

/** Qué muestra el menú del mapa: las dos opciones o la información de las capas (S036/RF-34, RF-36). */
enum class MapMenuScreen { MENU, INFO }

/**
 * El menú del mapa que sale al mantener pulsado un punto (S036/RF-34 a RF-39): [press] es el punto marcado, o null con el menú cerrado;
 * [visibleLayers] cuántas capas visibles tiene el proyecto y [queryable] las que se pueden consultar, de arriba abajo (RF-37).
 */
data class MapMenuUiState(
    val press: MapPress? = null,
    val screen: MapMenuScreen = MapMenuScreen.MENU,
    val visibleLayers: Int = 0,
    val queryable: List<ProjectLayerView> = emptyList(),
    val results: List<LayerInfoResult> = emptyList(),
) {
    val isOpen: Boolean get() = press != null

    /** «Consultar información de las capas» solo se puede elegir si alguna capa visible admite consulta (RF-37). */
    val canQuery: Boolean get() = queryable.isNotEmpty()
}

/**
 * El menú del mapa y la consulta de información (GetFeatureInfo) de las capas WMS visibles en el punto marcado (S036/RF-34 a RF-39, A6 del
 * plan): hasta 5 peticiones a la vez y cada capa muestra su respuesta, «sin datos» o el motivo del fallo en cuanto llega, sin esperar a las
 * demás. Cerrar el menú cancela lo que esté en vuelo (RF-35).
 */
@HiltViewModel
class MapMenuViewModel @Inject constructor(private val http: WmsHttp) : ViewModel() {
    private val mutable = MutableStateFlow(MapMenuUiState())
    val state: StateFlow<MapMenuUiState> = mutable.asStateFlow()

    private var querying: Job? = null

    /** Abre el menú en el punto [press], con las capas del proyecto de arriba abajo (RF-34, RF-37). */
    fun open(press: MapPress, layers: List<ProjectLayerView>) {
        querying?.cancel()
        val visible = layers.filter { it.layer.visible }
        val queryable = visible.filter { it.info?.queryable == true && it.info?.mercatorCode != null }
        mutable.value = MapMenuUiState(press = press, visibleLayers = visible.size, queryable = queryable)
    }

    /** Cierra el menú y cancela lo que esté en vuelo; la marca del punto se quita fuera (RF-35). */
    fun close() {
        querying?.cancel()
        mutable.value = MapMenuUiState()
    }

    fun backToMenu() {
        querying?.cancel()
        mutable.update { it.copy(screen = MapMenuScreen.MENU, results = emptyList()) }
    }

    /** «Consultar información de las capas»: pregunta a cada capa consultable qué hay en el punto marcado (RF-36). */
    fun queryLayers() {
        val current = mutable.value
        val press = current.press ?: return
        if (!current.canQuery) return
        querying?.cancel()
        mutable.update { state ->
            state.copy(
                screen = MapMenuScreen.INFO,
                results = current.queryable.map { LayerInfoResult(it.layer.id, it.title, it.service.name, LayerInfoStatus.Loading) },
            )
        }
        querying = viewModelScope.launch {
            val limit = Semaphore(WmsLimits.MAX_PARALLEL_INFO)
            coroutineScope {
                current.queryable.forEach { view ->
                    launch { limit.withPermit { show(view.layer.id, query(press, view)) } }
                }
            }
        }
    }

    private fun show(layerId: String, status: LayerInfoStatus) {
        mutable.update { state -> state.copy(results = state.results.map { if (it.layerId == layerId) it.copy(status = status) else it }) }
    }

    private suspend fun query(press: MapPress, view: ProjectLayerView): LayerInfoStatus {
        val service = view.service
        val address = (WmsAddress.parse(service.address) as? WmsAddressResult.Valid)?.address
        val code = view.info?.mercatorCode
        if (address == null || code == null) return LayerInfoStatus.Failed(LayerInfoProblem.NO_ADDRESS)
        // El Catastro INSPIRE solo devuelve parcelas en 1.1.1: el preset puede pedir otra versión para la consulta.
        val version = service.presetKey?.let(WmsPresets::byKey)?.infoVersion ?: service.version
        val area = press.area
        val url = WmsRequests.featureInfo(
            address,
            version,
            view.layer.layerName,
            code,
            infoFormat(service.infoFormats),
            area.south,
            area.west,
            area.north,
            area.east,
            area.widthPx,
            area.heightPx,
            press.pixelX,
            press.pixelY,
        )
        return try {
            http.get(url, ::read)
        } catch (_: WmsTimeoutException) {
            LayerInfoStatus.Failed(LayerInfoProblem.TIMEOUT)
        } catch (_: IOException) {
            LayerInfoStatus.Failed(LayerInfoProblem.NO_RESPONSE)
        }
    }

    private fun read(response: WmsHttpResponse): LayerInfoStatus {
        val parsed = FeatureInfoText.parse(readLimited(response.body))
        if (parsed is FeatureInfoResult.ServiceError) return LayerInfoStatus.Failed(LayerInfoProblem.SERVICE_EXCEPTION, parsed.message)
        if (response.code != HTTP_OK) return LayerInfoStatus.Failed(LayerInfoProblem.HTTP_ERROR, response.code.toString())
        return when (parsed) {
            is FeatureInfoResult.Text -> LayerInfoStatus.Text(parsed.text)
            FeatureInfoResult.NoData -> LayerInfoStatus.NoData
            is FeatureInfoResult.ServiceError -> LayerInfoStatus.Failed(LayerInfoProblem.SERVICE_EXCEPTION, parsed.message)
        }
    }

    /** Lo que llegue hasta [MAX_BYTES]: una respuesta mayor es la de un servicio que no sabe lo que contesta y no hace falta leerla entera. */
    private fun readLimited(input: InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(BUFFER_BYTES)
        while (out.size() < MAX_BYTES) {
            val read = input.read(buffer, 0, minOf(buffer.size, MAX_BYTES - out.size()))
            if (read < 0) break
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }

    /** El primero que anuncie el servicio de los que sabemos leer; si no anuncia ninguno de ellos, el primero que anuncie (A6 del plan). */
    private fun infoFormat(announced: List<String>): String =
        PREFERRED_FORMATS.firstNotNullOfOrNull { wanted -> announced.firstOrNull { it.equals(wanted, ignoreCase = true) } }
            ?: announced.firstOrNull()
            ?: PREFERRED_FORMATS.first()

    private companion object {
        const val HTTP_OK = 200
        const val MAX_BYTES = 2 * 1024 * 1024
        const val BUFFER_BYTES = 8192
        val PREFERRED_FORMATS = listOf("text/plain", "application/vnd.ogc.gml", "text/xml", "text/html")
    }
}
