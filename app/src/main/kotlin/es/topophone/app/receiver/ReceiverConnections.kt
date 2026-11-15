package es.topophone.app.receiver

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
import es.topophone.receiver.transport.ReceiverTransport
import es.topophone.receiver.transport.TcpTransport
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
    private val onConnecting: () -> Unit = {},
) {
    private val current = MutableStateFlow<String?>(null)

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
                if (connection is ConnectionState.Receiving && replay == null && address != null) {
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
        onConnecting()
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
     * Al arrancar la app, una sola vez: conecta con el último receptor si sigue emparejado, el
     * Bluetooth está encendido, hay permiso y no hay ninguna conexión ni reproducción (S004/RF-25).
     * Si falla, queda como cualquier fallo de conexión, sin reintentos (S004/RF-26).
     */
    suspend fun autoConnect(): Boolean {
        if (!autoConnectTried.compareAndSet(false, true)) return false
        val last = preferences.lastReceiver.first() ?: return false
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
