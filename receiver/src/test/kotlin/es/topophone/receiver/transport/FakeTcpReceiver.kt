package es.topophone.receiver.transport

import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * Un receptor Wi-Fi de mentira para los tests (S021): un servidor TCP en localhost que, al aceptar una conexión, emite el NMEA que se le
 * da (por defecto el de `fixtures/nmea/`) y guarda todo lo que el cliente le escribe (las correcciones). No hace falta ningún módulo de
 * testing compartido: vive en los tests de `receiver`.
 */
class FakeTcpReceiver(
    private val nmea: ByteArray = fixtureNmea(),
    private val cutAfterBytes: Int? = null,
    /** 0 elige uno libre; un puerto que ya se usó permite "volver a levantar" el servidor donde estaba. */
    port: Int = 0,
) : Closeable {

    private val server = bind(port)
    private val clients = CopyOnWriteArrayList<Socket>()
    private val received = ByteArrayOutputStream()
    private val accepted = AtomicInteger()

    @Volatile private var closed = false

    val port: Int get() = server.localPort

    val host: String get() = "127.0.0.1"

    /** Cuántas conexiones ha aceptado. */
    val connections: Int get() = accepted.get()

    /** Lo que el cliente ha escrito en todas sus conexiones, tal cual llegó. */
    fun receivedBytes(): ByteArray = synchronized(received) { received.toByteArray() }

    init {
        thread(isDaemon = true, name = "fake-tcp-receiver-$port") {
            while (!closed) {
                val client = try {
                    server.accept()
                } catch (_: Exception) {
                    break
                }
                clients += client // antes de contarla: con connections >= n, dropClients() ya la corta
                accepted.incrementAndGet()
                serve(client)
            }
        }
    }

    private fun serve(client: Socket) {
        thread(isDaemon = true, name = "fake-tcp-writer-$port") {
            runCatching {
                val out = client.getOutputStream()
                val bytes = if (cutAfterBytes != null) nmea.copyOf(minOf(cutAfterBytes, nmea.size)) else nmea
                out.write(bytes)
                out.flush()
                if (cutAfterBytes != null) client.close()
            }
        }
        thread(isDaemon = true, name = "fake-tcp-reader-$port") {
            runCatching {
                val input = client.getInputStream()
                val buffer = ByteArray(4096)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    synchronized(received) { received.write(buffer, 0, n) }
                }
            }
        }
    }

    /** Corta todas las conexiones abiertas sin cerrar el servidor (el receptor se queda sin Wi-Fi un momento). */
    fun dropClients() {
        clients.forEach { runCatching { it.close() } }
        clients.clear()
    }

    /** Deja de aceptar: lo siguiente que se conecte recibe un rechazo inmediato. */
    override fun close() {
        closed = true
        runCatching { server.close() }
        dropClients()
    }

    companion object {
        /** Enlaza el puerto pedido; con uno ya usado reintenta un momento porque el sistema tarda en liberarlo. */
        private fun bind(port: Int): ServerSocket {
            var last: java.io.IOException? = null
            repeat(BIND_ATTEMPTS) {
                try {
                    return ServerSocket(port, 50, InetAddress.getLoopbackAddress())
                } catch (e: java.io.IOException) {
                    last = e
                    Thread.sleep(BIND_WAIT_MS)
                }
            }
            throw last ?: java.io.IOException("sin puerto")
        }

        private const val BIND_ATTEMPTS = 40
        private const val BIND_WAIT_MS = 100L

        /** El NMEA sintético de una sesión completa, del repositorio. */
        fun fixtureNmea(name: String = "rs3-completo.nmea"): ByteArray {
            val root = generateSequence(File("").absoluteFile) { it.parentFile }
                .first { File(it, "settings.gradle.kts").exists() && File(it, "fixtures").isDirectory }
            return File(root, "fixtures/nmea/sintetico/$name").readBytes()
        }
    }
}
