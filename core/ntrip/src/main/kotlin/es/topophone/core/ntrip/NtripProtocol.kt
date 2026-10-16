package es.topophone.core.ntrip

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.Base64

/** Datos de conexión a un caster NTRIP (S002/RF-18). La contraseña solo vive en memoria (S002/RF-27). */
data class NtripConfig(val host: String, val port: Int = DEFAULT_PORT, val mountpoint: String, val username: String, val password: String) {
    override fun toString(): String = "NtripConfig(host=$host, port=$port, mountpoint=$mountpoint, username=$username, password=***)"

    companion object {
        const val DEFAULT_PORT = 2101
    }
}

/** Por qué no se pudo conectar o se perdió la conexión con el caster. */
enum class NtripFailure { UNAUTHORIZED, MOUNTPOINT_NOT_FOUND, UNREACHABLE, NO_DATA, CONNECTION_LOST, PROTOCOL }

/** Resultado de la negociación con el caster. */
sealed interface NtripHandshake {
    data class Streaming(val chunked: Boolean) : NtripHandshake

    data class Rejected(val failure: NtripFailure) : NtripHandshake
}

/** Petición NTRIP 2.0 que también aceptan los casters 1.0 (ADR-13). */
object NtripRequest {
    fun build(config: NtripConfig, userAgent: String): ByteArray {
        val mountpoint = config.mountpoint.trim().removePrefix("/")
        val lines = buildList {
            add("GET /$mountpoint HTTP/1.1")
            add("Host: ${config.host}:${config.port}")
            add("Ntrip-Version: Ntrip/2.0")
            add("User-Agent: $userAgent")
            if (config.username.isNotEmpty()) {
                val credentials = Base64.getEncoder().encodeToString("${config.username}:${config.password}".toByteArray(Charsets.UTF_8))
                add("Authorization: Basic $credentials")
            }
            add("Connection: close")
        }
        return (lines.joinToString("\r\n") + "\r\n\r\n").toByteArray(Charsets.ISO_8859_1)
    }
}

/**
 * Lee la respuesta del caster sin consumir los datos que vienen detrás (S002/RF-19, RF-21, RF-22).
 * `ICY 200 OK` (v1) no lleva cabeceras: lo siguiente ya son correcciones.
 */
object NtripResponseParser {
    private const val MAX_LINE = 1024

    fun read(input: InputStream): NtripHandshake {
        val status = readLine(input) ?: return NtripHandshake.Rejected(NtripFailure.CONNECTION_LOST)
        val upper = status.uppercase()
        return when {
            upper.startsWith("ICY 200") -> NtripHandshake.Streaming(chunked = false)
            upper.startsWith("SOURCETABLE") -> NtripHandshake.Rejected(NtripFailure.MOUNTPOINT_NOT_FOUND)
            upper.startsWith("ERROR") && "PASSWORD" in upper -> NtripHandshake.Rejected(NtripFailure.UNAUTHORIZED)
            upper.startsWith("ERROR") && "MOUNT" in upper -> NtripHandshake.Rejected(NtripFailure.MOUNTPOINT_NOT_FOUND)
            upper.startsWith("HTTP/1.") -> http(status, readHeaders(input))
            else -> NtripHandshake.Rejected(NtripFailure.PROTOCOL)
        }
    }

    private fun http(status: String, headers: Map<String, String>): NtripHandshake {
        val code = status.split(' ').getOrNull(1)?.toIntOrNull()
        val contentType = headers["content-type"].orEmpty().lowercase()
        return when {
            code == 401 || code == 403 -> NtripHandshake.Rejected(NtripFailure.UNAUTHORIZED)
            code == 404 || "sourcetable" in contentType -> NtripHandshake.Rejected(NtripFailure.MOUNTPOINT_NOT_FOUND)
            code == 200 -> NtripHandshake.Streaming(chunked = headers["transfer-encoding"].orEmpty().lowercase().contains("chunked"))
            else -> NtripHandshake.Rejected(NtripFailure.PROTOCOL)
        }
    }

    /** Cabeceras HTTP hasta la línea vacía, con el nombre en minúsculas. */
    internal fun readHeaders(input: InputStream): Map<String, String> {
        val headers = HashMap<String, String>()
        while (true) {
            val line = readLine(input) ?: break
            if (line.isEmpty()) break
            val colon = line.indexOf(':')
            if (colon > 0) headers[line.substring(0, colon).trim().lowercase()] = line.substring(colon + 1).trim()
        }
        return headers
    }

    /** Lee hasta `\n` (quitando `\r`); `null` si el flujo termina antes de leer nada. */
    internal fun readLine(input: InputStream): String? {
        val line = StringBuilder()
        while (line.length < MAX_LINE) {
            val b = input.read()
            if (b < 0) return if (line.isEmpty()) null else line.toString()
            if (b == '\n'.code) return line.toString().trimEnd('\r')
            line.append(b.toChar())
        }
        return line.toString()
    }
}

/** Descodifica `Transfer-Encoding: chunked` de NTRIP 2.0 de forma incremental. */
class ChunkedDecoder {
    private enum class State { SIZE, DATA, DATA_END, TRAILER }

    private var state = State.SIZE
    private val sizeLine = StringBuilder()
    private var remaining = 0L
    private var newlinesSeen = 0

    var finished = false
        private set

    fun feed(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size - offset): ByteArray {
        val out = ByteArrayOutputStream(length)
        var index = offset
        val end = offset + length
        while (index < end && !finished) {
            when (state) {
                State.SIZE -> {
                    val c = bytes[index++].toInt().toChar()
                    if (c == '\n') {
                        val hex = sizeLine.toString().trim().substringBefore(';').trim()
                        remaining = hex.toLongOrNull(16) ?: throw IOException("Tamaño de bloque chunked no válido: '$hex'")
                        sizeLine.setLength(0)
                        state = if (remaining == 0L) State.TRAILER else State.DATA
                    } else if (c != '\r') {
                        sizeLine.append(c)
                        if (sizeLine.length > 64) throw IOException("Línea de tamaño chunked demasiado larga")
                    }
                }
                State.DATA -> {
                    val n = minOf(remaining, (end - index).toLong()).toInt()
                    out.write(bytes, index, n)
                    index += n
                    remaining -= n
                    if (remaining == 0L) state = State.DATA_END
                }
                State.DATA_END -> if (bytes[index++].toInt() == '\n'.code) state = State.SIZE
                State.TRAILER -> {
                    if (bytes[index++].toInt() == '\n'.code) newlinesSeen++
                    if (newlinesSeen >= 1) finished = true
                }
            }
        }
        return out.toByteArray()
    }
}
