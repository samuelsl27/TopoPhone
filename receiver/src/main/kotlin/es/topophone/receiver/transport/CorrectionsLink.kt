package es.topophone.receiver.transport

import java.io.IOException
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import javax.net.SocketFactory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Cómo está el enlace de correcciones de un receptor Wi-Fi con puerto de correcciones distinto al de posición (S021/RF-13). */
sealed interface CorrectionsLinkStatus {
    /** Abriendo el segundo socket. */
    data object Connecting : CorrectionsLinkStatus

    /** Abierto: lo que se escribe llega al receptor. */
    data object Ready : CorrectionsLinkStatus

    /**
     * Cerrado o sin poder abrir: las correcciones se descartan y se reintenta en [nextAttemptAtMs]; [attempt] empieza en 1 y vuelve a 1
     * tras un corte de un enlace que estaba abierto.
     */
    data class Failed(val attempt: Int, val nextAttemptAtMs: Long) : CorrectionsLinkStatus
}

/**
 * El segundo socket por el que van las correcciones RTCM cuando el receptor las recibe en un puerto distinto al de posición
 * (S021/RF-11). Es un [OutputStream] que no falla nunca: con el enlace abierto escribe y, si está caído o se corta en mitad de una
 * escritura, descarta (RF-13) y lo reabre solo con esperas crecientes. Un corte se detecta también sin escribir, leyendo el socket.
 * Lo que pase aquí no toca el enlace de posición: la sesión sigue recibiendo NMEA.
 */
class CorrectionsLink(
    private val host: String,
    private val port: Int,
    private val socketFactory: SocketFactory = SocketFactory.getDefault(),
    private val connectTimeoutMs: Int = TcpTransport.CONNECT_TIMEOUT_MS,
    private val backoffMs: List<Long> = DEFAULT_BACKOFF_MS,
    private val clock: () -> Long = System::currentTimeMillis,
) : OutputStream() {

    private val mutableStatus = MutableStateFlow<CorrectionsLinkStatus>(CorrectionsLinkStatus.Connecting)
    val status: StateFlow<CorrectionsLinkStatus> = mutableStatus

    private val lock = Object()

    @Volatile private var socket: Socket? = null

    @Volatile private var closed = false
    private var worker: Thread? = null

    /** Empieza a abrir el enlace y a mantenerlo abierto, en un hilo propio. */
    fun start() {
        synchronized(lock) {
            if (worker != null || closed) return
            worker = Thread(::run, "corrections-link-$port").apply {
                isDaemon = true
                start()
            }
        }
    }

    private fun run() {
        var attempt = 0
        while (!closed) {
            mutableStatus.value = CorrectionsLinkStatus.Connecting
            val opened = open()
            if (opened != null) {
                attempt = 0
                mutableStatus.value = CorrectionsLinkStatus.Ready
                watch(opened) // vuelve cuando el enlace se cae
                if (closed) break
            }
            attempt++
            val wait = backoffMs[minOf(attempt - 1, backoffMs.lastIndex)]
            mutableStatus.value = CorrectionsLinkStatus.Failed(attempt, clock() + wait)
            sleep(wait)
        }
    }

    private fun open(): Socket? {
        val created = try {
            socketFactory.createSocket()
        } catch (_: IOException) {
            return null
        }
        return try {
            created.tcpNoDelay = true
            created.keepAlive = true
            created.connect(InetSocketAddress(host, port), connectTimeoutMs)
            synchronized(lock) {
                if (closed) {
                    created.close()
                    null
                } else {
                    socket = created
                    created
                }
            }
        } catch (_: Exception) {
            runCatching { created.close() }
            null
        }
    }

    /** Lee y tira lo que llegue hasta que el socket se cierra por cualquiera de los dos lados. */
    private fun watch(opened: Socket) {
        try {
            val input = opened.getInputStream()
            val sink = ByteArray(READ_BUFFER)
            while (input.read(sink) >= 0) Unit
        } catch (_: IOException) {
            // cortado
        } finally {
            synchronized(lock) {
                if (socket === opened) socket = null
            }
            runCatching { opened.close() }
        }
    }

    private fun sleep(ms: Long) {
        val until = System.nanoTime() + ms * NANOS_PER_MS
        synchronized(lock) {
            while (!closed) {
                val left = (until - System.nanoTime()) / NANOS_PER_MS
                if (left <= 0) return
                lock.wait(left)
            }
        }
    }

    override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

    override fun write(b: ByteArray, off: Int, len: Int) {
        val current = socket ?: return // enlace caído: se descarta (S021/RF-13)
        try {
            current.getOutputStream().write(b, off, len)
        } catch (_: IOException) {
            runCatching { current.close() } // el vigilante lo ve y reabre
        }
    }

    override fun flush() {
        val current = socket ?: return
        try {
            current.getOutputStream().flush()
        } catch (_: IOException) {
            runCatching { current.close() }
        }
    }

    override fun close() {
        synchronized(lock) {
            closed = true
            lock.notifyAll()
        }
        runCatching { socket?.close() }
        socket = null
    }

    companion object {
        /** Esperas entre reintentos: 1, 2, 4, 8 y 16 s y después cada 30 s, como las del receptor (S021/RF-13). */
        val DEFAULT_BACKOFF_MS = listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 30_000L)

        private const val READ_BUFFER = 256
        private const val NANOS_PER_MS = 1_000_000L
    }
}
