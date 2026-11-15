package es.topophone.app.ui.web

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import es.topophone.app.receiver.CurrentEquipment
import es.topophone.app.receiver.CurrentTilt
import es.topophone.core.model.guide.PrivateAddress
import es.topophone.core.model.receiver.ReceiverKey
import es.topophone.data.equipment.EquipmentRepository
import es.topophone.data.receiver.WifiReceiverRepository
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** En qué punto está la interfaz web del receptor (S043/RF-12, RF-13, RF-16). */
enum class WebPhase {
    /** La fuente no es un receptor real (Bluetooth o Wi-Fi): no hay interfaz web (RF-22, RF-23). */
    NOT_APPLICABLE,

    /** Falta una dirección IP privada válida: se pide y se recuerda para ese receptor (RF-13). */
    ASK_ADDRESS,

    /** Cargando la página; si no termina en 10 s pasa a [FAILED] (RF-16). */
    LOADING,
    LOADED,
    FAILED,
}

/** Las causas probables de que la página no cargue (S043/RF-16, plan A4); el texto de cada una está en los recursos. */
enum class WebFailureCause {
    OUT_OF_RECEIVER_NETWORK,
    WRONG_ADDRESS_OR_PORT,
    NO_WEB_INTERFACE,
    OTHER_CLIENT_CONNECTED,
}

/**
 * Lo que muestra la interfaz web del receptor (S043/RF-12 a RF-16).
 *
 * @property url la dirección que se carga (`http://<IP privada>[:puerto]/`), o null si falta una dirección válida.
 * @property savedAddress la dirección que el usuario escribió para ese receptor, para editarla.
 * @property addressRejected lo último que se escribió no era una IP privada (un nombre, una IP pública, IPv6…).
 * @property loadId sube con cada carga (la primera y cada «Reintentar»): la pantalla vuelve a cargar cuando cambia.
 */
data class ReceiverWebUiState(
    val receiverKey: String? = null,
    val phase: WebPhase = WebPhase.NOT_APPLICABLE,
    val url: String? = null,
    val savedAddress: String = "",
    val addressRejected: Boolean = false,
    val causes: List<WebFailureCause> = emptyList(),
    val loadId: Int = 0,
)

/**
 * La interfaz web del receptor dentro de la app (S043/RF-12 a RF-16; plan D9, A3, A4): un receptor Wi-Fi abre el puerto 80 de su
 * dirección guardada y uno Bluetooth pide la IP y la recuerda. Solo se carga una IP privada o de enlace local; el temporizador de 10 s
 * y las dos salidas, reintentar y abrir en el navegador, son de aquí. No toca la conexión de posición (RF-17): la sesión vive en el
 * servicio, no en esta pantalla.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ReceiverWebViewModel @Inject constructor(
    private val equipment: EquipmentRepository,
    private val wifi: WifiReceiverRepository,
    tilt: CurrentTilt,
) : ViewModel() {

    /** Lo que se sabe de la dirección de un receptor: la guardada a mano y, con Wi-Fi, la de su alta (S021). */
    private data class Known(val key: String?, val wifiHost: String?, val saved: String?)

    private class Load(val id: Int, val phase: WebPhase, val causes: List<WebFailureCause> = emptyList())

    private val rejected = MutableStateFlow(false)
    private val load = MutableStateFlow(Load(0, WebPhase.LOADING))
    private var timer: Job? = null

    private val known: Flow<Known> = tilt.state.map { it.key }.distinctUntilChanged().flatMapLatest { key ->
        when {
            !CurrentEquipment.appliesTo(key) -> flowOf(Known(key, null, null))
            ReceiverKey.isWifi(key!!) -> combine(wifi.observe(ReceiverKey.wifiId(key)!!), equipment.webAddress(key)) { receiver, saved ->
                Known(key, receiver?.host, saved)
            }
            else -> equipment.webAddress(key).map { Known(key, null, it) }
        }
    }

    /** La dirección que se carga: con Wi-Fi, el puerto 80 de la IP del receptor (RF-12); si no es una IP, o con Bluetooth, la escrita (RF-13). */
    private fun addressOf(known: Known): PrivateAddress? {
        if (!CurrentEquipment.appliesTo(known.key)) return null
        val wifiIp = known.wifiHost?.let { PrivateAddress.parse(it) }?.takeIf { it.port == null }
        if (wifiIp != null) return PrivateAddress(wifiIp.host, HTTP_PORT)
        return known.saved?.let { PrivateAddress.parse(it) }
    }

    val state: StateFlow<ReceiverWebUiState> = combine(known, rejected, load) { known, rejected, load ->
        val applies = CurrentEquipment.appliesTo(known.key)
        val address = addressOf(known)
        ReceiverWebUiState(
            receiverKey = known.key,
            phase = when {
                !applies -> WebPhase.NOT_APPLICABLE
                address == null -> WebPhase.ASK_ADDRESS
                else -> load.phase
            },
            url = address?.url,
            savedAddress = known.saved.orEmpty(),
            addressRejected = rejected,
            causes = load.causes,
            loadId = load.id,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), ReceiverWebUiState())

    init {
        // Cada dirección nueva, o cada cambio de receptor, empieza una carga con su temporizador.
        viewModelScope.launch {
            known.map { it.key to addressOf(it)?.url }.distinctUntilChanged().collect { (_, url) -> if (url != null) begin() }
        }
    }

    /** Valida la dirección escrita (RF-14): si es una IP privada la guarda para ese receptor y empieza a cargar; si no, la rechaza. */
    fun submitAddress(text: String) {
        val key = state.value.receiverKey?.takeIf { CurrentEquipment.appliesTo(it) } ?: return
        val address = PrivateAddress.parse(text)
        if (address == null) {
            rejected.value = true
            return
        }
        rejected.value = false
        viewModelScope.launch { equipment.saveWebAddress(key, address.host + (address.port?.let { ":$it" } ?: "")) }
    }

    /** La página terminó de cargar: se cancela el temporizador (RF-16). */
    fun onPageFinished() {
        if (load.value.phase != WebPhase.LOADING) return
        timer?.cancel()
        load.value = Load(load.value.id, WebPhase.LOADED)
    }

    /** La página principal dio un error al cargar (RF-16). */
    fun onLoadError() {
        if (load.value.phase != WebPhase.LOADING) return
        fail()
    }

    /** «Reintentar»: vuelve a cargar con un temporizador nuevo (RF-16). */
    fun retry() {
        if (state.value.url == null) return
        begin()
    }

    /** La dirección con que «Abrir en el navegador» lanza la misma URL (RF-16); null si no hay una válida. */
    fun browserUrl(): String? = state.value.url

    private fun begin() {
        timer?.cancel()
        val id = load.value.id + 1
        load.value = Load(id, WebPhase.LOADING)
        timer = viewModelScope.launch {
            delay(LOAD_TIMEOUT_MS)
            if (load.value.id == id && load.value.phase == WebPhase.LOADING) fail()
        }
    }

    private fun fail() {
        timer?.cancel()
        load.value = Load(load.value.id, WebPhase.FAILED, WebFailureCause.entries)
    }

    private companion object {
        /** El puerto de la interfaz web de un receptor Wi-Fi (RF-12). */
        const val HTTP_PORT = 80

        /** Lo que se espera a que la página termine de cargar (RF-16). */
        const val LOAD_TIMEOUT_MS = 10_000L
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
