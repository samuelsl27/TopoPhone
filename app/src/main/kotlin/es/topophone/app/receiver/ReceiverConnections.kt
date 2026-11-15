package es.topophone.app.receiver

import es.topophone.core.gnss.nmea.ReplaySpeed
import es.topophone.core.gnss.scenario.Scenario
import es.topophone.core.gnss.scenario.ScenarioOrigin
import es.topophone.core.gnss.scenario.ScenarioPlayer
import es.topophone.core.model.receiver.ReceiverKey
import es.topophone.core.model.receiver.WifiReceiver
import es.topophone.data.receiver.ReceiverPreferences
import es.topophone.data.receiver.SavedReceiver
import es.topophone.data.receiver.WifiReceiverRepository
import es.topophone.receiver.platform.NetworkRouter
import es.topophone.receiver.platform.WifiSocketFactory
import es.topophone.receiver.session.ConnectionState
import es.topophone.receiver.session.ReceiverSession
import es.topophone.receiver.transport.BluetoothDevices
import es.topophone.receiver.transport.BluetoothOffException
import es.topophone.receiver.transport.LocationSource
import es.topophone.receiver.transport.PhoneGnssTransport
import es.topophone.receiver.transport.ReceiverTransport
import es.topophone.receiver.transport.TcpTransport
import es.topophone.receiver.transport.VirtualReceiverTransport
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.SocketFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/** Lo que el usuario elige para el receptor virtual: el escenario, dónde ocurre y a qué velocidad (S022/RF-13, RF-16). */
data class VirtualSelection(val scenario: Scenario, val origin: ScenarioOrigin, val speed: ReplaySpeed = ReplaySpeed.X1)

/**
 * Único punto para conectar con un receptor real: con reconexión (S004/RF-13 a RF-22), guardando el
 * último receptor (S004/RF-24) y arrancando antes el servicio en primer plano ([onConnecting], S004/RF-7).
 * Lo usan la tarjeta Conexión, "Reconectar", "Añadir receptor" y la conexión automática (S004/RF-25).
 */
class ReceiverConnections(
    private val session: ReceiverSession,
    private val bluetooth: BluetoothDevices,
    private val preferences: ReceiverPreferences,
    private val scope: CoroutineScope,
    /** Los receptores Wi-Fi guardados; sin él, una clave `wifi:` no conecta (S021). */
    private val wifiReceivers: WifiReceiverRepository? = null,
    /** La red Wi-Fi del móvil para los sockets de un receptor Wi-Fi (S021/RF-8). */
    private val router: NetworkRouter? = null,
    /** El GNSS del móvil; sin él, `phone` no conecta (S022/RF-1). */
    private val locationSource: LocationSource? = null,
    /** Los nombres de las dos fuentes que trae la app, para la cabecera y la notificación (S022/RF-1, RF-12). */
    private val labels: SourceLabels? = null,
    /** La hora en milisegundos desde 1970 con la que arranca un escenario virtual: la del reloj del móvil al conectar (S022/RNF-T10). */
    private val utcNowMs: () -> Long = System::currentTimeMillis,
    /** Se llama antes de conectar, con la clave: el servicio es de tipo location solo con el GNSS del móvil (S004/RF-7, S022/RF-4). */
    private val onConnecting: (key: String) -> Unit = {},
) {
    private val current = MutableStateFlow<String?>(null)

    private var lastVirtual: VirtualSelection? = null

    /**
     * La clave del último receptor con el que se ha pedido conectar: su dirección Bluetooth o `wifi:<id>` (S021/RF-5); es la clave de su
     * modo de inclinación (S016/RF-1) y de su equipo (S046).
     */
    val currentAddress: StateFlow<String?> = current.asStateFlow()
    private val autoConnectTried = AtomicBoolean(false)

    init {
        scope.launch {
            session.state.map { it.connection to it.replay }.distinctUntilChanged().collect { (connection, replay) ->
                val address = current.value
                // S022/plan D15: ni el GNSS del móvil ni el receptor virtual se recuerdan como último receptor.
                if (connection is ConnectionState.Receiving && replay == null && address != null && !ReceiverKey.isBuiltIn(address)) {
                    preferences.saveLastReceiver(SavedReceiver(address, connection.description)) // S004/RF-24, S021/RF-17
                }
            }
        }
        wifiReceivers?.let { repository ->
            // S021/RF-4: borrar el receptor Wi-Fi con el que se está conectado cierra la conexión.
            scope.launch {
                repository.receivers().collect { receivers ->
                    val id = current.value?.let(ReceiverKey::wifiId)
                    if (id != null && receivers.none { it.id == id } && session.state.value.connection !is ConnectionState.Disconnected) {
                        session.disconnect()
                    }
                }
            }
        }
    }

    /**
     * Conecta con el receptor [key]: una dirección Bluetooth emparejada o `wifi:<id>` de un receptor Wi-Fi guardado (S021/RF-7); si se
     * pierde el enlace, se reconecta solo.
     */
    fun connect(key: String) {
        if (ReceiverKey.isPhone(key)) return connectPhone()
        if (ReceiverKey.isVirtual(key)) {
            // El receptor virtual pide antes un escenario: sin uno elegido no hay a qué conectar (S022/RF-13).
            lastVirtual?.let(::connectVirtual)
            return
        }
        onConnecting(key)
        current.value = key
        val wifiId = ReceiverKey.wifiId(key)
        if (wifiId == null) {
            session.connect(bluetooth.transportFor(key)) { reopen(key) }
            return
        }
        scope.launch {
            val receiver = wifiReceivers?.byId(wifiId) ?: return@launch // borrado o sin repositorio: no hay a quién conectar
            session.connect(tcpTransport(receiver)) { reopenWifi(wifiId) }
        }
    }

    /**
     * Conecta con el GNSS del móvil (S022/RF-1, RF-4): sin correcciones, sin reconexión (si Android deja de dar posiciones se ve
     * «Sin datos») y sin guardarlo como último receptor.
     */
    fun connectPhone() {
        val source = locationSource ?: return
        onConnecting(ReceiverKey.PHONE)
        current.value = ReceiverKey.PHONE
        session.connect(PhoneGnssTransport(labels?.phone() ?: ReceiverKey.PHONE, source))
    }

    /**
     * Conecta con el receptor virtual y su [selection] (S022/RF-12, RF-13). La hora UTC arranca con la del reloj del móvil
     * (S022/RNF-T10). Para repetir el escenario o elegir otro se vuelve a llamar (S022/RF-17).
     */
    fun connectVirtual(selection: VirtualSelection) {
        lastVirtual = selection
        onConnecting(ReceiverKey.VIRTUAL)
        current.value = ReceiverKey.VIRTUAL
        val player = ScenarioPlayer(selection.scenario, selection.origin, utcNowMs())
        session.connect(VirtualReceiverTransport(labels?.virtual() ?: ReceiverKey.VIRTUAL, player, selection.speed))
    }

    /** Repite el último escenario virtual desde el principio (S022/RF-17); no hace nada si no había uno. */
    fun repeatVirtual() {
        lastVirtual?.let(::connectVirtual)
    }

    /**
     * Al arrancar la app, una sola vez: conecta con el último receptor si sigue emparejado, el
     * Bluetooth está encendido, hay permiso y no hay ninguna conexión ni reproducción (S004/RF-25).
     * Si falla, queda como cualquier fallo de conexión, sin reintentos (S004/RF-26).
     */
    suspend fun autoConnect(): Boolean {
        if (!autoConnectTried.compareAndSet(false, true)) return false
        val last = preferences.lastReceiver.first() ?: return false
        if (ReceiverKey.isBuiltIn(last.address)) return false // S022/plan D15
        val wifiId = ReceiverKey.wifiId(last.address)
        if (wifiId != null) {
            // S021/RF-17: un receptor Wi-Fi sigue guardado o no; no se piden ni Bluetooth ni su permiso.
            if (wifiReceivers?.byId(wifiId) == null) return false
        } else {
            if (!bluetooth.isSupported || !bluetooth.hasConnectPermission() || !bluetooth.isEnabled) return false
            if (bluetooth.bondedDevices().none { it.address == last.address }) return false
        }
        val state = session.state.value
        val busy = state.replay != null ||
            state.connection is ConnectionState.Connecting ||
            state.connection is ConnectionState.Receiving ||
            state.connection is ConnectionState.NoData ||
            state.connection is ConnectionState.Reconnecting
        if (busy) return false
        connect(last.address)
        return true
    }

    /** El TCP con un receptor Wi-Fi por su red Wi-Fi, con su puerto de correcciones (S021/RF-7, RF-8, RF-11). */
    private fun tcpTransport(receiver: WifiReceiver): ReceiverTransport = TcpTransport(
        host = receiver.host,
        port = receiver.positionPort,
        description = receiver.description,
        socketFactory = router?.let(::WifiSocketFactory) ?: SocketFactory.getDefault(),
        correctionsPort = receiver.correctionsPort,
    )

    /** Vuelve a abrir el receptor Wi-Fi con lo que tenga guardado ahora (una IP editada entre medias vale), S021/RF-15. */
    private fun reopenWifi(id: String): ReceiverTransport {
        val receiver = runBlocking { wifiReceivers?.byId(id) } ?: throw IOException("Receptor Wi-Fi borrado")
        return tcpTransport(receiver)
    }

    /** Vuelve a abrir el mismo receptor; con el Bluetooth apagado, lo dice (S004/RF-22). */
    private fun reopen(address: String): ReceiverTransport {
        if (!bluetooth.isEnabled) throw BluetoothOffException()
        return bluetooth.transportFor(address)
    }
}
