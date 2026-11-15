package es.topophone.receiver.transport

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import javax.net.SocketFactory
import kotlinx.coroutines.flow.StateFlow

/**
 * Conexión TCP con un receptor Wi-Fi (S021/RF-7): la app es el cliente y el receptor el servidor, que emite el NMEA por el puerto de
 * posición. El [socketFactory] decide por qué red sale (en la app, la del Wi-Fi aunque no tenga internet, RF-8; en los tests, el de
 * siempre). Conectar tiene un plazo de [connectTimeoutMs] (RF-14); un rechazo o una red inalcanzable fallan enseguida con su propia
 * excepción de E/S, que es lo que el diagnóstico distingue.
 */
class TcpTransport(
    private val host: String,
    private val port: Int,
    override val description: String,
    private val socketFactory: SocketFactory = SocketFactory.getDefault(),
    private val connectTimeoutMs: Int = CONNECT_TIMEOUT_MS,
    private val correctionsPort: Int? = null,
    correctionsBackoffMs: List<Long> = CorrectionsLink.DEFAULT_BACKOFF_MS,
) : ReceiverTransport {

    /** El segundo socket, solo si el puerto de correcciones es otro distinto al de posición (S021/RF-11). */
    private val link: CorrectionsLink? = if (correctionsPort != null && correctionsPort != port) {
        CorrectionsLink(host, correctionsPort, socketFactory, connectTimeoutMs, correctionsBackoffMs)
    } else {
        null
    }

    override val correctionsStatus: StateFlow<CorrectionsLinkStatus>? get() = link?.status

    @Volatile private var socket: Socket? = null

    /** Sin puerto de correcciones el receptor no las recibe (S021/RF-12). */
    override val acceptsCorrections: Boolean get() = correctionsPort != null

    override val routesNtripOverNetwork: Boolean get() = true

    override fun open() {
        val created = socketFactory.createSocket()
        try {
            created.tcpNoDelay = true
            created.keepAlive = true
            created.connect(InetSocketAddress(host, port), connectTimeoutMs)
        } catch (e: Exception) {
            runCatching { created.close() }
            throw e
        }
        socket = created
        link?.start() // un puerto de correcciones caido no impide la posicion: se reintenta aparte (S021/RF-13)
    }

    override val input: InputStream get() = socket?.getInputStream() ?: throw IOException("No conectado")

    /**
     * Las correcciones salen por el socket de posición si el puerto de correcciones es el mismo, por el segundo enlace si es otro
     * (S021/RF-11); sin puerto no se escribe nada (RF-12).
     */
    override val output: OutputStream
        get() {
            val current = socket ?: throw IOException("No conectado")
            return link ?: if (correctionsPort == port) current.getOutputStream() else DiscardingOutput
        }

    override fun close() {
        link?.close()
        val current = socket
        socket = null
        runCatching { current?.close() }
    }

    /** Lo que se escribe sin puerto de correcciones: se descarta (no hay `OutputStream.nullOutputStream` por debajo de la API 33). */
    private object DiscardingOutput : OutputStream() {
        override fun write(b: Int) = Unit

        override fun write(b: ByteArray, off: Int, len: Int) = Unit
    }

    companion object {
        /** Plazo para conectar con el receptor (S021/RF-14). */
        const val CONNECT_TIMEOUT_MS = 10_000
    }
}
