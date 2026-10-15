package es.topophone.core.ntrip

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

/** Cliente NTRIP frente a un caster falso local (S002/RF-19, RF-20, RF-23, RF-24, RF-25). */
class NtripClientTest {

    /** Caster de prueba: responde a la primera conexión con un guion y anota lo que recibe del cliente. */
    private class FakeCaster(private val script: (Socket) -> Unit) : AutoCloseable {
        val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        val received: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val closedByClient = CountDownLatch(1)
        private val worker = thread(isDaemon = true) {
            runCatching {
                server.accept().use { socket ->
                    val reader = thread(isDaemon = true) {
                        val buffer = ByteArrayOutputStream()
                        runCatching {
                            val input = socket.getInputStream()
                            while (true) {
                                val b = input.read()
                                if (b < 0) break
                                buffer.write(b)
                                if (b == '\n'.code) {
                                    received += buffer.toString(Charsets.ISO_8859_1).trimEnd()
                                    buffer.reset()
                                }
                            }
                        }
                        closedByClient.countDown()
                    }
                    script(socket)
                    // Fin del guion: el caster corta su lado y espera a que el cliente cierre el suyo.
                    runCatching { socket.shutdownOutput() }
                    reader.join(5_000)
                }
            }
        }

        val config get() = NtripConfig(host = "127.0.0.1", port = server.localPort, mountpoint = "MP", username = "u", password = "p")

        fun ggaLines() = received.filter { it.startsWith("\$GNGGA") }

        override fun close() {
            server.close()
            worker.join(2_000)
        }
    }

    private val casters = mutableListOf<FakeCaster>()

    private fun caster(script: (Socket) -> Unit) = FakeCaster(script).also(casters::add)

    @AfterEach
    fun closeCasters() = casters.forEach(FakeCaster::close)

    private fun client(noDataMs: Int = 2_000, ggaMs: Long = 5_000) =
        NtripClient(userAgent = "NTRIP TopoPhone/test", connectTimeoutMs = 1_000, noDataTimeoutMs = noDataMs, ggaIntervalMs = ggaMs)

    private val rtcmBlocks = listOf(
        byteArrayOf(0xD3.toByte(), 0x00, 0x03, 1, 2, 3, 9, 9, 9),
        byteArrayOf(0xD3.toByte(), 0x00, 0x02, 4, 5, 8, 8, 8),
        byteArrayOf(0xD3.toByte(), 0x00, 0x01, 6, 7, 7, 7),
    )

    @Test
    fun `S002 RF-19 las correcciones de un caster v1 se reenvian sin modificar y en orden`() = runBlocking<Unit> {
        val caster = caster { socket ->
            socket.getOutputStream().apply {
                write("ICY 200 OK\r\n".toByteArray())
                rtcmBlocks.forEach {
                    write(it)
                    flush()
                    Thread.sleep(30)
                }
            }
        }
        val received = ByteArrayOutputStream()
        var connected = false

        val failure = withTimeout(5_000) {
            client().run(caster.config, latestGga = { null }, onConnected = { connected = true }) { received.write(it) }
        }

        connected shouldBe true
        received.toByteArray().toList() shouldBe rtcmBlocks.flatMap { it.toList() }
        failure shouldBe NtripFailure.CONNECTION_LOST
        caster.received.first() shouldStartWith "GET /MP HTTP/1.1"
    }

    @Test
    fun `S002 RF-19 las correcciones de un caster v2 chunked se descodifican antes de reenviarlas`() = runBlocking<Unit> {
        val caster = caster { socket ->
            socket.getOutputStream().apply {
                write("HTTP/1.1 200 OK\r\nContent-Type: gnss/data\r\nTransfer-Encoding: chunked\r\n\r\n".toByteArray())
                rtcmBlocks.forEach { block ->
                    write("${Integer.toHexString(block.size)}\r\n".toByteArray())
                    write(block)
                    write("\r\n".toByteArray())
                    flush()
                }
                write("0\r\n\r\n".toByteArray())
                flush()
            }
        }
        val received = ByteArrayOutputStream()

        withTimeout(5_000) { client().run(caster.config, { null }, {}) { received.write(it) } }

        received.toByteArray().toList() shouldBe rtcmBlocks.flatMap { it.toList() }
    }

    @Test
    fun `S002 RF-20 se envia al caster la ultima GGA al conectar y despues periodicamente`() = runBlocking<Unit> {
        val caster = caster { socket ->
            socket.getOutputStream().write("ICY 200 OK\r\n".toByteArray())
            repeat(10) {
                socket.getOutputStream().write(rtcmBlocks[0])
                Thread.sleep(50)
            }
        }
        var gga = "\$GNGGA,101530.20,4025.1234567,N,00342.7654321,W,4,24,0.6,650.123,M,51.234,M,1.0,0001*00"

        withTimeout(5_000) {
            client(ggaMs = 150).run(caster.config, latestGga = { gga }, onConnected = {}) { gga = gga.replace("101530", "101531") }
        }

        caster.ggaLines().size shouldBeGreaterThanOrEqual 3
        caster.ggaLines().first() shouldStartWith "\$GNGGA,1015"
    }

    @Test
    fun `S002 RF-20 la GGA se envia cada 5 s por defecto`() {
        NtripClient.DEFAULT_GGA_INTERVAL_MS shouldBe 5_000L
    }

    @Test
    fun `S002 RF-20 sin GGA del receptor no se envia nada al caster`() = runBlocking<Unit> {
        val caster = caster { socket ->
            socket.getOutputStream().write("ICY 200 OK\r\n".toByteArray())
            repeat(5) {
                socket.getOutputStream().write(rtcmBlocks[0])
                Thread.sleep(40)
            }
        }

        withTimeout(5_000) { client(ggaMs = 50).run(caster.config, { null }, {}) {} }

        caster.ggaLines().shouldBeEmpty()
    }

    @Test
    fun `S002 RF-21 con credenciales rechazadas no se llega a conectar`() = runBlocking<Unit> {
        val caster = caster { it.getOutputStream().write("HTTP/1.1 401 Unauthorized\r\n\r\n".toByteArray()) }
        var connected = false

        val failure = withTimeout(5_000) { client().run(caster.config, { null }, { connected = true }) {} }

        failure shouldBe NtripFailure.UNAUTHORIZED
        connected shouldBe false
    }

    @Test
    fun `S002 RF-23 si no se puede contactar con el caster el fallo es inaccesible`() = runBlocking<Unit> {
        val port = ServerSocket(0).use { it.localPort } // puerto cerrado

        val failure = withTimeout(5_000) {
            client().run(NtripConfig("127.0.0.1", port, "MP", "u", "p"), { null }, {}) {}
        }

        failure shouldBe NtripFailure.UNREACHABLE
    }

    @Test
    fun `S002 RF-23 un nombre de servidor inexistente es inaccesible`() = runBlocking<Unit> {
        val failure = withTimeout(10_000) {
            client().run(NtripConfig("caster.invalid", 2101, "MP", "u", "p"), { null }, {}) {}
        }

        failure shouldBe NtripFailure.UNREACHABLE
    }

    @Test
    fun `S002 RF-24 si el caster deja de enviar correcciones el fallo es sin datos`() = runBlocking<Unit> {
        val caster = caster { socket ->
            socket.getOutputStream().write("ICY 200 OK\r\n".toByteArray())
            Thread.sleep(2_000)
        }

        val failure = withTimeout(5_000) { client(noDataMs = 300).run(caster.config, { null }, {}) {} }

        failure shouldBe NtripFailure.NO_DATA
    }

    @Test
    fun `S002 RF-24 cancelar la conexion la cierra enseguida`() = runBlocking<Unit> {
        val caster = caster { socket ->
            socket.getOutputStream().write("ICY 200 OK\r\n".toByteArray())
            Thread.sleep(3_000)
        }
        val job = async { client(noDataMs = 10_000).run(caster.config, { null }, {}) {} }
        delay(300)

        job.cancelAndJoin()

        caster.closedByClient.await(2, TimeUnit.SECONDS) shouldBe true
    }

    @Test
    fun `S002 RF-25 las estadisticas cuentan bytes y segundos desde la ultima correccion`() {
        val stats = NtripStatistics()

        stats.bytesReceived shouldBe 0L
        stats.secondsSinceLast(nowMs = 1_000) shouldBe null
        stats.record(byteCount = 100, nowMs = 1_000)
        stats.record(byteCount = 50, nowMs = 3_500)

        stats.bytesReceived shouldBe 150L
        stats.secondsSinceLast(nowMs = 6_000) shouldBe 2L
    }
}
