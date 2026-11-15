package es.topophone.receiver.transport

import io.kotest.matchers.shouldBe
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import org.junit.After
import org.junit.Test

/** Las correcciones RTCM por el mismo socket de posición, o ninguna si el receptor no tiene puerto para ellas (S021/RF-11, RF-12). */
class TcpCorrectionsTest {

    private val servers = mutableListOf<FakeTcpReceiver>()
    private val transports = mutableListOf<TcpTransport>()

    @After
    fun tearDown() {
        transports.forEach { runCatching { it.close() } }
        servers.forEach { it.close() }
    }

    private fun server() = FakeTcpReceiver().also { servers += it }

    private fun transport(receiver: FakeTcpReceiver, correctionsPort: Int?) =
        TcpTransport(receiver.host, receiver.port, "RS3", correctionsPort = correctionsPort).also { transports += it }

    private fun awaitReceived(receiver: FakeTcpReceiver, size: Int): ByteArray {
        val deadline = System.nanoTime() + 3_000_000_000L
        while (receiver.receivedBytes().size < size && System.nanoTime() < deadline) Thread.sleep(10)
        return receiver.receivedBytes()
    }

    /** Unas tramas RTCM3 de mentira: preámbulo 0xD3 y todos los valores de byte, para ver que no se altera ninguno. */
    private val rtcm = ByteArray(1_024) { (if (it == 0) 0xD3 else it * 7).toByte() }

    @Test
    fun `S021 RF-11 con el mismo puerto lo escrito llega intacto al servidor por el mismo socket`() {
        val receiver = server()
        val tcp = transport(receiver, correctionsPort = receiver.port)
        tcp.open()

        tcp.acceptsCorrections shouldBe true
        tcp.output.write(rtcm)
        tcp.output.flush()

        awaitReceived(receiver, rtcm.size) shouldBe rtcm
        receiver.connections shouldBe 1
    }

    @Test
    fun `S021 RF-11 las escrituras en varios trozos llegan en el mismo orden`() {
        val receiver = server()
        val tcp = transport(receiver, correctionsPort = receiver.port)
        tcp.open()

        rtcm.toList().chunked(100).forEach { tcp.output.write(it.toByteArray()) }
        tcp.output.flush()

        awaitReceived(receiver, rtcm.size) shouldBe rtcm
    }

    @Test
    fun `S021 RF-12 sin puerto de correcciones no se acepta ni se escribe nada`() {
        val receiver = server()
        val tcp = transport(receiver, correctionsPort = null)
        tcp.open()

        tcp.acceptsCorrections shouldBe false
        tcp.output.write(rtcm)
        tcp.output.flush()
        Thread.sleep(300)

        receiver.receivedBytes().size shouldBe 0
        receiver.connections shouldBe 1
    }

    @Test
    fun `S021 RF-12 sin puerto de correcciones la posicion se sigue leyendo`() {
        val receiver = server()
        val tcp = transport(receiver, correctionsPort = null)
        tcp.open()

        val first = tcp.input.bufferedReader().readLine()

        first.startsWith("\$GNGGA,") shouldBe true
    }

    @Test
    fun `S021 RF-11 los otros transportes aceptan correcciones salvo que digan lo contrario`() {
        StreamTransport("flujo", ByteArrayInputStream(ByteArray(0)), ByteArrayOutputStream()).acceptsCorrections shouldBe true
    }
}
