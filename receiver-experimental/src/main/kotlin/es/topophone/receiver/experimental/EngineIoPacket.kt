package es.topophone.receiver.experimental

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * Un paquete de Engine.IO v3 sobre WebSocket: un carácter con el tipo y el resto (S044/RF-16; plan D7). Solo se entiende lo que el directo
 * necesita; lo demás es [Unknown] y se ignora sin error.
 */
sealed interface EngineIoPacket {
    /** `0{json}`: el servidor abre y dice cada cuánto hay que mandarle un ping. */
    data class Open(val pingIntervalMs: Long, val pingTimeoutMs: Long) : EngineIoPacket

    data object Close : EngineIoPacket

    data object Ping : EngineIoPacket

    data object Pong : EngineIoPacket

    data object Noop : EngineIoPacket

    /** `4…`: un mensaje de Socket.IO; [body] es lo que va tras el tipo de paquete. */
    data class Message(val body: String) : EngineIoPacket

    data class Unknown(val frame: String) : EngineIoPacket

    companion object {
        const val PING = "2"
        const val PONG = "3"
        const val DEFAULT_PING_INTERVAL_MS = 25_000L
        const val DEFAULT_PING_TIMEOUT_MS = 60_000L

        fun parse(frame: String): EngineIoPacket {
            if (frame.isEmpty()) return Unknown(frame)
            val rest = frame.substring(1)
            return when (frame[0]) {
                '0' -> open(rest) ?: Unknown(frame)
                '1' -> Close
                '2' -> Ping
                '3' -> Pong
                '4' -> Message(rest)
                '6' -> Noop
                else -> Unknown(frame)
            }
        }

        private fun open(json: String): Open? {
            val obj = ReachJson.parse(json) as? JsonObject ?: return null
            fun millis(key: String, default: Long) = ((obj[key] as? JsonPrimitive)?.longOrNull)?.takeIf { it > 0 } ?: default
            return Open(millis("pingInterval", DEFAULT_PING_INTERVAL_MS), millis("pingTimeout", DEFAULT_PING_TIMEOUT_MS))
        }
    }
}

/** El evento `broadcast` de Socket.IO: `{name, payload}` (plan «Lo que enseña el código de referencia»). */
data class BroadcastEvent(val name: String, val payload: JsonElement?)

/** Lo que va dentro de un mensaje de Engine.IO: la conexión al espacio de nombres y los eventos de Socket.IO v2 (espacio por defecto). */
object SocketIoMessage {
    private const val CONNECT = '0'
    private const val EVENT = '2'
    private const val BROADCAST = "broadcast"

    /** `40`: el servidor acepta la conexión al espacio de nombres `/`. */
    fun isConnect(body: String): Boolean = body == CONNECT.toString()

    /** El evento `broadcast` de un mensaje `2[...]` (con o sin número de acuse), o `null` si es otra cosa o tiene otra forma. */
    fun broadcast(body: String): BroadcastEvent? {
        if (body.isEmpty() || body[0] != EVENT) return null
        val json = ReachJson.parse(body.substring(1).trimStart { it.isDigit() }) as? JsonArray ?: return null
        if ((json.getOrNull(0) as? JsonPrimitive)?.takeIf { it.isString }?.content != BROADCAST) return null
        val data = json.getOrNull(1) as? JsonObject ?: return null
        val name = ReachJson.string(data, "name") ?: return null
        return BroadcastEvent(name, data["payload"])
    }
}
