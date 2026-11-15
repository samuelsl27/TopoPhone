package es.topophone.receiver.transport

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import javax.net.SocketFactory

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
) : ReceiverTransport {

    @Volatile private var socket: Socket? = null

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
    }

    override val input: InputStream get() = socket?.getInputStream() ?: throw IOException("No conectado")
    override val output: OutputStream get() = socket?.getOutputStream() ?: throw IOException("No conectado")

    override fun close() {
        val current = socket
        socket = null
        runCatching { current?.close() }
    }

    companion object {
        /** Plazo para conectar con el receptor (S021/RF-14). */
        const val CONNECT_TIMEOUT_MS = 10_000
    }
}
