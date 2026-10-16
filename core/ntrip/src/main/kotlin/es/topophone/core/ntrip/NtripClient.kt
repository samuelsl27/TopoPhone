package es.topophone.core.ntrip

import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import javax.net.SocketFactory
import javax.net.ssl.SSLException
import javax.net.ssl.SSLSocketFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Fuente de correcciones NTRIP; [NtripClient] es la real y los tests usan una falsa. */
fun interface NtripConnector {
    suspend fun run(
        config: NtripConfig,
        latestGga: () -> String?,
        onConnected: () -> Unit,
        onCorrections: suspend (ByteArray) -> Unit,
    ): NtripFailure
}

/**
 * Cliente NTRIP sobre un socket crudo (ADR-13): negocia con el caster, reenvía las correcciones
 * tal cual (S002/RF-19) y le envía la última GGA del receptor cada [ggaIntervalMs] (S002/RF-20).
 *
 * La [socketFactory] se inyecta para elegir la red (ADR-17, S020); con TLS, el socket ya conectado se
 * envuelve con [sslSocketFactory] (S007/RF-27).
 */
class NtripClient(
    private val userAgent: String,
    private val socketFactory: SocketFactory = SocketFactory.getDefault(),
    private val sslSocketFactory: SSLSocketFactory = SSLSocketFactory.getDefault() as SSLSocketFactory,
    private val connectTimeoutMs: Int = DEFAULT_TIMEOUT_MS,
    private val noDataTimeoutMs: Int = DEFAULT_TIMEOUT_MS,
    private val ggaIntervalMs: Long = DEFAULT_GGA_INTERVAL_MS,
) : NtripConnector {

    /**
     * Mantiene la conexión hasta que falla, y devuelve el motivo. Cancelar la corrutina cierra el socket.
     *
     * @param latestGga última frase GGA del receptor, o `null` si aún no hay ninguna.
     * @param onConnected se llama una vez, cuando el caster acepta la petición.
     * @param onCorrections recibe cada bloque de correcciones en el orden de llegada.
     */
    override suspend fun run(
        config: NtripConfig,
        latestGga: () -> String?,
        onConnected: () -> Unit,
        onCorrections: suspend (ByteArray) -> Unit,
    ): NtripFailure = coroutineScope {
        val socket = socketFactory.createSocket()
        val session = async(Dispatchers.IO) { session(socket, config, latestGga, onConnected, onCorrections) }
        try {
            session.await()
        } finally {
            runCatching { socket.close() }
        }
    }

    private suspend fun session(
        socket: Socket,
        config: NtripConfig,
        latestGga: () -> String?,
        onConnected: () -> Unit,
        onCorrections: suspend (ByteArray) -> Unit,
    ): NtripFailure = coroutineScope {
        try {
            socket.connect(InetSocketAddress(config.host, config.port), connectTimeoutMs)
        } catch (_: IOException) {
            return@coroutineScope NtripFailure.UNREACHABLE
        } catch (_: IllegalArgumentException) {
            return@coroutineScope NtripFailure.UNREACHABLE
        }
        socket.soTimeout = noDataTimeoutMs
        val stream = if (config.useTls) {
            try {
                Tls.wrap(socket, sslSocketFactory, config.host, config.port)
            } catch (_: SSLException) {
                return@coroutineScope NtripFailure.TLS_INVALID // S007/RF-28
            } catch (_: IOException) {
                return@coroutineScope NtripFailure.UNREACHABLE
            }
        } else {
            socket
        }
        val output = stream.getOutputStream()
        val input = BufferedInputStream(stream.getInputStream())
        try {
            output.write(NtripRequest.build(config, userAgent))
            output.flush()
            val handshake = NtripResponseParser.read(input)
            if (handshake is NtripHandshake.Rejected) return@coroutineScope handshake.failure
            onConnected()

            val gga = if (config.sendGga) launch { sendGgaPeriodically(output, latestGga) } else null // S007/RF-21
            try {
                forward(input, chunked = (handshake as NtripHandshake.Streaming).chunked, onCorrections)
            } finally {
                gga?.cancel()
            }
        } catch (_: SocketTimeoutException) {
            NtripFailure.NO_DATA
        } catch (_: IOException) {
            NtripFailure.CONNECTION_LOST
        }
    }

    private suspend fun sendGgaPeriodically(output: OutputStream, latestGga: () -> String?) {
        while (currentCoroutineContext().isActive) {
            latestGga()?.let { sentence ->
                runCatching {
                    synchronized(output) {
                        output.write((sentence + "\r\n").toByteArray(Charsets.US_ASCII))
                        output.flush()
                    }
                }
            }
            delay(ggaIntervalMs)
        }
    }

    /** Lee hasta que el caster corta (devuelve [NtripFailure.CONNECTION_LOST]) o vence el plazo sin datos (excepción). */
    private suspend fun forward(input: InputStream, chunked: Boolean, onCorrections: suspend (ByteArray) -> Unit): NtripFailure {
        val decoder = if (chunked) ChunkedDecoder() else null
        val buffer = ByteArray(BUFFER_SIZE)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) return NtripFailure.CONNECTION_LOST
            val payload = decoder?.feed(buffer, 0, n) ?: buffer.copyOf(n)
            if (payload.isNotEmpty()) onCorrections(payload)
            if (decoder?.finished == true) return NtripFailure.CONNECTION_LOST
        }
    }

    companion object {
        const val DEFAULT_TIMEOUT_MS = 10_000
        const val DEFAULT_GGA_INTERVAL_MS = 5_000L
        private const val BUFFER_SIZE = 4096
    }
}

/** Bytes de corrección recibidos y tiempo desde la última corrección (S002/RF-25). */
class NtripStatistics {
    var bytesReceived: Long = 0
        private set
    private var lastDataAtMs: Long? = null

    fun record(byteCount: Int, nowMs: Long) {
        bytesReceived += byteCount
        lastDataAtMs = nowMs
    }

    fun secondsSinceLast(nowMs: Long): Long? = lastDataAtMs?.let { (nowMs - it) / 1000 }
}
