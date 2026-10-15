package es.topophone.app.receiver

import es.topophone.data.receiver.ReceiverPreferences
import es.topophone.data.receiver.SavedReceiver
import es.topophone.receiver.session.ConnectionState
import es.topophone.receiver.session.ReceiverSession
import es.topophone.receiver.transport.BluetoothDevices
import es.topophone.receiver.transport.BluetoothOffException
import es.topophone.receiver.transport.ReceiverTransport
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

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
    private val onConnecting: () -> Unit = {},
) {
    @Volatile private var current: String? = null
    private val autoConnectTried = AtomicBoolean(false)

    init {
        scope.launch {
            session.state.map { it.connection to it.replay }.distinctUntilChanged().collect { (connection, replay) ->
                val address = current
                if (connection is ConnectionState.Receiving && replay == null && address != null) {
                    preferences.saveLastReceiver(SavedReceiver(address, connection.description)) // S004/RF-24
                }
            }
        }
    }

    /** Conecta con el receptor emparejado [address]; si se pierde el enlace, se reconecta solo. */
    fun connect(address: String) {
        onConnecting()
        current = address
        session.connect(bluetooth.transportFor(address)) { reopen(address) }
    }

    /**
     * Al arrancar la app, una sola vez: conecta con el último receptor si sigue emparejado, el
     * Bluetooth está encendido, hay permiso y no hay ninguna conexión ni reproducción (S004/RF-25).
     * Si falla, queda como cualquier fallo de conexión, sin reintentos (S004/RF-26).
     */
    suspend fun autoConnect(): Boolean {
        if (!autoConnectTried.compareAndSet(false, true)) return false
        val last = preferences.lastReceiver.first() ?: return false
        if (!bluetooth.isSupported || !bluetooth.hasConnectPermission() || !bluetooth.isEnabled) return false
        if (bluetooth.bondedDevices().none { it.address == last.address }) return false
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

    /** Vuelve a abrir el mismo receptor; con el Bluetooth apagado, lo dice (S004/RF-22). */
    private fun reopen(address: String): ReceiverTransport {
        if (!bluetooth.isEnabled) throw BluetoothOffException()
        return bluetooth.transportFor(address)
    }
}
