package es.topophone.core.ntrip

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import javax.net.SocketFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

/** Por qué no se pudo obtener la tabla de fuentes (S007/RF-20). */
enum class SourcetableFailure { UNREACHABLE, UNAUTHORIZED, NOT_A_SOURCETABLE, EMPTY }

sealed interface SourcetableResult {
    data class Ok(val streams: List<SourcetableStream>) : SourcetableResult

    data class Failed(val reason: SourcetableFailure) : SourcetableResult
}

/**
 * Descarga la tabla de fuentes del caster (`GET /`) con la misma petición que la conexión NTRIP,
 * en 1.0 (`SOURCETABLE 200 OK`) o en 2.0 (HTTP, quizá por bloques) (S007/RF-14).
 */
class SourcetableClient(
    private val userAgent: String,
    private val socketFactory: SocketFactory = SocketFactory.getDefault(),
    private val timeoutMs: Int = DEFAULT_TIMEOUT_MS,
    private val maxBytes: Int = MAX_BYTES,
) {
    suspend fun fetch(host: String, port: Int, username: String, password: String): SourcetableResult = coroutineScope {
        val socket = socketFactory.createSocket()
        val request = async(Dispatchers.IO) { download(socket, NtripConfig(host, port, mountpoint = "", username, password)) }
        try {
            request.await()
        } finally {
            runCatching { socket.close() }
        }
    }

    private fun download(socket: Socket, config: NtripConfig): SourcetableResult = try {
        socket.connect(InetSocketAddress(config.host, config.port), timeoutMs)
        socket.soTimeout = timeoutMs
        socket.getOutputStream().apply { write(NtripRequest.build(config, userAgent)) }.flush()
        read(BufferedInputStream(socket.getInputStream()))
    } catch (_: IOException) {
        SourcetableResult.Failed(SourcetableFailure.UNREACHABLE)
    } catch (_: IllegalArgumentException) {
        SourcetableResult.Failed(SourcetableFailure.UNREACHABLE)
    }

    private fun read(input: InputStream): SourcetableResult {
        val status = NtripResponseParser.readLine(input) ?: return failed(SourcetableFailure.UNREACHABLE)
        val upper = status.uppercase()
        return when {
            upper.startsWith("SOURCETABLE 200") -> {
                NtripResponseParser.readHeaders(input)
                streams(body(input, chunked = false), declared = true)
            }
            upper.startsWith("ERROR") && "PASSWORD" in upper -> failed(SourcetableFailure.UNAUTHORIZED)
            upper.startsWith("HTTP/1.") -> {
                val code = status.split(' ').getOrNull(1)?.toIntOrNull()
                val headers = NtripResponseParser.readHeaders(input)
                when (code) {
                    401, 403 -> failed(SourcetableFailure.UNAUTHORIZED)
                    200 -> streams(
                        body(input, chunked = "chunked" in headers["transfer-encoding"].orEmpty().lowercase()),
                        declared = "sourcetable" in headers["content-type"].orEmpty().lowercase(),
                    )
                    else -> failed(SourcetableFailure.NOT_A_SOURCETABLE)
                }
            }
            else -> failed(SourcetableFailure.NOT_A_SOURCETABLE)
        }
    }

    /** Sin puntos de montaje: tabla vacía si lo era ([declared] o `ENDSOURCETABLE`), y si no, otra cosa. */
    private fun streams(body: ByteArray, declared: Boolean): SourcetableResult {
        val lines = SourcetableParser.lines(body)
        val streams = SourcetableParser.parse(lines.asSequence())
        return when {
            streams.isNotEmpty() -> SourcetableResult.Ok(streams)
            declared || lines.any { it.startsWith(END) } -> failed(SourcetableFailure.EMPTY)
            else -> failed(SourcetableFailure.NOT_A_SOURCETABLE)
        }
    }

    /** Lee hasta el final del flujo, `ENDSOURCETABLE` o [maxBytes]. */
    private fun body(input: InputStream, chunked: Boolean): ByteArray {
        val decoder = if (chunked) ChunkedDecoder() else null
        val body = ByteArrayOutputStream()
        val buffer = ByteArray(BUFFER_SIZE)
        var tail = "" // final del bloque anterior, por si `ENDSOURCETABLE` llega partido
        while (body.size() < maxBytes) {
            val n = input.read(buffer)
            if (n < 0) break
            val payload = decoder?.feed(buffer, 0, n) ?: buffer.copyOf(n)
            body.write(payload)
            val text = tail + payload.toString(Charsets.ISO_8859_1)
            if (decoder?.finished == true || END in text) break
            tail = text.takeLast(END.length)
        }
        return body.toByteArray()
    }

    private fun failed(reason: SourcetableFailure) = SourcetableResult.Failed(reason)

    companion object {
        const val DEFAULT_TIMEOUT_MS = 15_000
        const val MAX_BYTES = 8 * 1024 * 1024
        private const val BUFFER_SIZE = 8192
        private const val END = "ENDSOURCETABLE"
    }
}
