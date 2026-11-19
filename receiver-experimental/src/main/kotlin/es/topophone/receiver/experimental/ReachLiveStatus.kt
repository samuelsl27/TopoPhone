package es.topophone.receiver.experimental

import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull

/** Lo que dice el directo del receptor; todo `null` y `connected = false` es «Sin estado del receptor» (S044/RF-13, RF-17). */
data class ReachLiveState(
    val connected: Boolean = false,
    val batteryPercent: Int? = null,
    val charger: String? = null,
    val logsWriting: Boolean? = null,
)

/** Lo que el escritor de logs necesita del directo: el estado actual y cada evento `active_logs` entendido (S044/RF-20). */
interface LogsObserver {
    val state: StateFlow<ReachLiveState>

    /** `true` si algún log escribía en el evento. Sin repetición: solo ve los que llegan tras suscribirse. */
    val logEvents: SharedFlow<Boolean>
}

/** Una conexión abierta con el receptor; [close] es idempotente. */
interface LiveConnection {
    fun send(text: String)

    fun close()
}

/** Lo que el transporte avisa: una trama de texto, o que la conexión terminó (cierre o fallo). Se llama desde cualquier hilo. */
interface LiveListener {
    fun onFrame(text: String)

    fun onEnded()
}

/** Abre conexiones con el directo del receptor; la de verdad es un WebSocket de OkHttp y las de los tests, falsas. */
interface LiveTransport {
    fun connect(listener: LiveListener): LiveConnection
}

/**
 * El estado en directo del receptor: un cliente propio de Engine.IO v3 sobre WebSocket (S044/RF-13, RF-16, RF-17; plan D7, A4).
 * Es una conexión **aparte** de la sesión del receptor, con su propio ámbito: si cae o se cierra, la sesión no se entera.
 * Al caer, vacía el estado y reconecta esperando 1, 2, 4… hasta 30 s; la espera vuelve a 1 s tras una conexión lograda.
 */
class ReachLiveStatus(private val transport: LiveTransport, private val scope: CoroutineScope) : LogsObserver {

    private val mutableState = MutableStateFlow(ReachLiveState())
    override val state: StateFlow<ReachLiveState> = mutableState.asStateFlow()

    private val mutableLogEvents = MutableSharedFlow<Boolean>(extraBufferCapacity = LOG_EVENT_BUFFER)

    /** Cada evento `active_logs` entendido (`true` si algún log escribe), para comprobar una escritura (S044/RF-20). */
    override val logEvents: SharedFlow<Boolean> = mutableLogEvents.asSharedFlow()

    private var job: Job? = null

    fun start() {
        if (job?.isActive == true) return
        job = scope.launch { run() }
    }

    fun close() {
        job?.cancel()
        job = null
        mutableState.value = ReachLiveState()
    }

    private sealed interface Incoming {
        data class Frame(val text: String) : Incoming

        data object Ended : Incoming
    }

    private suspend fun run() {
        var backoff = FIRST_BACKOFF_MS
        while (true) {
            val incoming = Channel<Incoming>(Channel.UNLIMITED)
            var connection: LiveConnection? = null
            val connected = try {
                connection = transport.connect(
                    object : LiveListener {
                        override fun onFrame(text: String) {
                            incoming.trySend(Incoming.Frame(text))
                        }

                        override fun onEnded() {
                            incoming.trySend(Incoming.Ended)
                        }
                    },
                )
                session(incoming, connection)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                false
            } finally {
                connection?.close()
                incoming.close()
                mutableState.value = ReachLiveState()
            }
            if (connected) backoff = FIRST_BACKOFF_MS
            delay(backoff)
            backoff = minOf(backoff * 2, MAX_BACKOFF_MS)
        }
    }

    /** Atiende una conexión hasta que cae; devuelve si llegó a estar conectada al espacio de nombres. */
    private suspend fun session(incoming: Channel<Incoming>, connection: LiveConnection): Boolean {
        var opened = false
        var connected = false
        var awaitingPong = false
        var pingIntervalMs = EngineIoPacket.DEFAULT_PING_INTERVAL_MS
        var pingTimeoutMs = EngineIoPacket.DEFAULT_PING_TIMEOUT_MS
        while (true) {
            val wait = when {
                !opened -> OPEN_TIMEOUT_MS
                awaitingPong -> pingTimeoutMs
                else -> pingIntervalMs
            }
            val event = withTimeoutOrNull(wait) { incoming.receive() }
            if (event == null) {
                if (!opened || awaitingPong) return connected
                connection.send(EngineIoPacket.PING)
                awaitingPong = true
                continue
            }
            awaitingPong = false
            when (event) {
                Incoming.Ended -> return connected
                is Incoming.Frame -> when (val packet = EngineIoPacket.parse(event.text)) {
                    is EngineIoPacket.Open -> {
                        opened = true
                        pingIntervalMs = packet.pingIntervalMs
                        pingTimeoutMs = packet.pingTimeoutMs
                    }
                    EngineIoPacket.Close -> return connected
                    EngineIoPacket.Ping -> connection.send(EngineIoPacket.PONG)
                    is EngineIoPacket.Message -> {
                        if (SocketIoMessage.isConnect(packet.body)) {
                            connected = true
                            mutableState.value = mutableState.value.copy(connected = true)
                        } else {
                            SocketIoMessage.broadcast(packet.body)?.let(::apply)
                        }
                    }
                    EngineIoPacket.Pong, EngineIoPacket.Noop, is EngineIoPacket.Unknown -> Unit
                }
            }
        }
    }

    /** Solo `battery_status` y `active_logs`; cualquier otro evento o forma se ignora sin error (RF-16). */
    private fun apply(event: BroadcastEvent) {
        val payload = event.payload as? JsonObject ?: return
        when (event.name) {
            BATTERY -> {
                val percent = (payload["state_of_charge"] as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull ?: return
                val charger = (payload["charger_status"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                mutableState.value = mutableState.value.copy(batteryPercent = percent.toInt(), charger = charger)
            }
            LOGS -> {
                val writing = LOG_KINDS.mapNotNull { isWriting(payload[it]) }
                if (writing.isEmpty()) return
                val any = writing.any { it }
                mutableState.value = mutableState.value.copy(logsWriting = any)
                mutableLogEvents.tryEmit(any)
            }
        }
    }

    private fun isWriting(kind: JsonElement?): Boolean? = ((kind as? JsonObject)?.get("is_writing") as? JsonPrimitive)?.booleanOrNull

    companion object {
        private const val FIRST_BACKOFF_MS = 1_000L
        private const val MAX_BACKOFF_MS = 30_000L

        /** Tiempo para que el servidor mande el paquete de apertura tras conectar. */
        private const val OPEN_TIMEOUT_MS = 10_000L
        private const val LOG_EVENT_BUFFER = 8
        private const val BATTERY = "battery_status"
        private const val LOGS = "active_logs"
        private val LOG_KINDS = listOf("raw", "solution", "base")
    }
}
