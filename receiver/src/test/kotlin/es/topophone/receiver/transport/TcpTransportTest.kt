package es.topophone.receiver.transport

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.longs.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import java.io.IOException
import java.net.ConnectException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketAddress
import java.net.SocketTimeoutException
import javax.net.SocketFactory
import org.junit.After
import org.junit.Test

/** La conexión TCP con el receptor Wi-Fi: abrir, leer, cerrar y los fallos de conexión (S021/RF-7, RF-14). */
class TcpTransportTest {

    private val servers = mutableListOf<FakeTcpReceiver>()
    private val transports = mutableListOf<TcpTransport>()

    @After
    fun tearDown() {
        transports.forEach { runCatching { it.close() } }
        servers.forEach { it.close() }
    }

    private fun server(cutAfterBytes: Int? = null) = FakeTcpReceiver(cutAfterBytes = cutAfterBytes).also { servers += it }

    private fun transport(host: String, port: Int, factory: SocketFactory = SocketFactory.getDefault(), timeoutMs: Int = 10_000) =
        TcpTransport(host, port, "RS3 ($host:$port)", factory, timeoutMs).also { transports += it }

    private fun TcpTransport.readLine(): String {
        val line = StringBuilder()
        while (true) {
            val b = input.read()
            if (b < 0 || b == '\n'.code) return line.toString().trimEnd('\r')
            line.append(b.toChar())
        }
    }

    @Test
    fun `S021 RF-7 un servidor que acepta da su primera frase NMEA en menos de 3 segundos`() {
        val receiver = server()
        val tcp = transport(receiver.host, receiver.port)

        val start = System.nanoTime()
        tcp.open()
        val first = tcp.readLine()
        val elapsedMs = (System.nanoTime() - start) / 1_000_000

        first shouldStartWith "\$GNGGA,"
        elapsedMs shouldBeLessThan 3_000L
        tcp.description shouldBe "RS3 (${receiver.host}:${receiver.port})"
        tcp.simulated shouldBe false
    }

    @Test
    fun `S021 RF-7 el cliente sigue leyendo frase tras frase hasta el final del flujo`() {
        val receiver = server()
        val tcp = transport(receiver.host, receiver.port)
        tcp.open()

        val lines = generateSequence { tcp.readLine().takeIf { it.isNotEmpty() } }.take(10).toList()

        lines.size shouldBe 10
        lines.all { it.startsWith("$") } shouldBe true
    }

    @Test
    fun `S021 RF-14 un servidor que rechaza falla enseguida con rechazo de conexion`() {
        val receiver = FakeTcpReceiver()
        val port = receiver.port
        receiver.close()
        val tcp = transport(receiver.host, port)

        val start = System.nanoTime()
        shouldThrow<ConnectException> { tcp.open() }

        ((System.nanoTime() - start) / 1_000_000) shouldBeLessThan 3_000L
    }

    @Test
    fun `S021 RF-14 un servidor que no contesta falla por plazo de 10 segundos`() {
        val factory = SilentSocketFactory()
        val tcp = transport("192.168.42.1", 9001, factory)

        shouldThrow<SocketTimeoutException> { tcp.open() }

        factory.timeoutsMs shouldBe listOf(10_000)
        factory.addresses.single().let { (it as InetSocketAddress).port } shouldBe 9001
    }

    @Test
    fun `S021 RF-14 el plazo de conexion se puede acortar`() {
        val factory = SilentSocketFactory()
        val tcp = transport("192.168.42.1", 9001, factory, timeoutMs = 1_500)

        shouldThrow<SocketTimeoutException> { tcp.open() }

        factory.timeoutsMs shouldBe listOf(1_500)
    }

    @Test
    fun `S021 RF-7 un servidor que corta deja el flujo en su final`() {
        val receiver = server(cutAfterBytes = 40)
        val tcp = transport(receiver.host, receiver.port)
        tcp.open()

        val bytes = tcp.input.readBytes()

        bytes.size shouldBe 40
    }

    @Test
    fun `S021 RF-7 sin abrir no hay flujos y cerrar es seguro varias veces`() {
        val receiver = server()
        val tcp = transport(receiver.host, receiver.port)

        shouldThrow<IOException> { tcp.input }
        shouldThrow<IOException> { tcp.output }
        tcp.close()
        tcp.open()
        tcp.close()
        tcp.close()
        shouldThrow<IOException> { tcp.input }
    }

    @Test
    fun `S021 RF-7 cerrar corta una lectura en curso`() {
        val receiver = FakeTcpReceiver(nmea = ByteArray(0)).also { servers += it }
        val tcp = transport(receiver.host, receiver.port)
        tcp.open()
        val input = tcp.input
        var result: Any? = null
        val reader = Thread { result = runCatching { input.read() } }.apply { start() }

        Thread.sleep(200)
        tcp.close()
        reader.join(3_000)

        reader.isAlive shouldBe false
        (result as Result<*>).let { it.isFailure || it.getOrNull() == -1 } shouldBe true
    }

    @Test
    fun `S021 RF-7 el socket va sin retardo y con keep-alive`() {
        val receiver = server()
        val factory = RecordingSocketFactory()
        val tcp = transport(receiver.host, receiver.port, factory)

        tcp.open()

        factory.sockets.single().tcpNoDelay shouldBe true
        factory.sockets.single().keepAlive shouldBe true
    }

    /** Una fábrica de sockets que nunca conecta: lo que pasa con un receptor al que no llega ni el paquete. */
    private class SilentSocketFactory : SocketFactory() {
        val timeoutsMs = mutableListOf<Int>()
        val addresses = mutableListOf<SocketAddress>()

        override fun createSocket(): Socket = object : Socket() {
            override fun connect(endpoint: SocketAddress, timeout: Int) {
                addresses += endpoint
                timeoutsMs += timeout
                throw SocketTimeoutException("connect timed out")
            }
        }

        override fun createSocket(host: String?, port: Int): Socket = throw UnsupportedOperationException()

        override fun createSocket(host: String?, port: Int, localHost: java.net.InetAddress?, localPort: Int): Socket =
            throw UnsupportedOperationException()

        override fun createSocket(host: java.net.InetAddress?, port: Int): Socket = throw UnsupportedOperationException()

        override fun createSocket(address: java.net.InetAddress?, port: Int, localAddress: java.net.InetAddress?, localPort: Int): Socket =
            throw UnsupportedOperationException()
    }

    private class RecordingSocketFactory : SocketFactory() {
        val sockets = mutableListOf<Socket>()

        override fun createSocket(): Socket = Socket().also { sockets += it }

        override fun createSocket(host: String?, port: Int): Socket = throw UnsupportedOperationException()

        override fun createSocket(host: String?, port: Int, localHost: java.net.InetAddress?, localPort: Int): Socket =
            throw UnsupportedOperationException()

        override fun createSocket(host: java.net.InetAddress?, port: Int): Socket = throw UnsupportedOperationException()

        override fun createSocket(address: java.net.InetAddress?, port: Int, localAddress: java.net.InetAddress?, localPort: Int): Socket =
            throw UnsupportedOperationException()
    }
}
