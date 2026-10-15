package es.topophone.receiver.session

import es.topophone.core.gnss.nmea.NmeaChecksum
import es.topophone.core.ntrip.NtripConfig
import es.topophone.core.ntrip.NtripConnector
import es.topophone.core.ntrip.NtripFailure
import es.topophone.receiver.transport.ReceiverTransport
import io.kotest.matchers.collections.shouldHaveAtLeastSize
import io.kotest.matchers.longs.shouldBeGreaterThanOrEqual
import io.kotest.matchers.longs.shouldBeLessThan
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Test

/** Reconexión automática dentro de la sesión (S004/RF-13 a RF-21). */
class ReceiverSessionReconnectTest {

    private fun nmea(body: String) = "$" + body + "*" + "%02X".format(NmeaChecksum.compute(body)) + "\r\n"

    private val gga = nmea("GNGGA,101530.00,4025.0080000,N,00342.2228000,W,4,24,0.6,650.123,M,51.234,M,1.0,0001")

    /** Receptor falso: [failOpen] hace que abrir falle; [send] envía datos; [cut] corta el enlace. */
    private class LinkTransport(private val failOpen: Boolean = false) : ReceiverTransport {
        private val toApp = PipedOutputStream()
        private val pipe = PipedInputStream(toApp, 64 * 1024)
        override val description = "reach-RS3"
        override fun open() {
            if (failOpen) throw IOException("sin respuesta")
        }
        override val input: InputStream = pipe
        override val output: OutputStream = ByteArrayOutputStream()
        fun send(text: String) = toApp.apply {
            write(text.toByteArray())
            flush()
        }
        fun cut() = runCatching { toApp.close() }
        override fun close() {
            runCatching { toApp.close() }
            runCatching { pipe.close() }
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val noNtrip = NtripConnector { _, _, _, _ -> NtripFailure.CONNECTION_LOST }
    private val attempts = CopyOnWriteArrayList<Long>()
    private fun now() = System.nanoTime() / 1_000_000

    @After
    fun tearDown() = scope.cancel()

    private fun session(backoff: List<Long> = listOf(50, 100, 200), giveUpAfter: Long = 60_000) =
        ReceiverSession(scope, noNtrip, connectTimeoutMs = 300, backoffMs = backoff, giveUpAfterMs = giveUpAfter)

    /** Cada intento de reconexión devuelve el siguiente transporte de [script] (el último se repite). */
    private fun reopen(vararg script: LinkTransport): () -> ReceiverTransport {
        var index = 0
        return {
            attempts += now()
            script[minOf(index++, script.lastIndex)]
        }
    }

    private fun ReceiverSession.await(timeoutMs: Long = 5_000, predicate: (ReceiverSnapshot) -> Boolean): ReceiverSnapshot =
        runBlocking { withTimeout(timeoutMs) { state.first(predicate) } }

    private fun ReceiverSession.connected(first: LinkTransport, reopen: () -> ReceiverTransport) {
        connect(first, reopen)
        first.send(gga)
        await { it.connection is ConnectionState.Receiving }
    }

    @Test
    fun `S004 RF-13 al perder el enlace con reconexion la sesion pasa a Reconectando y no a perdida`() {
        val session = session()
        val first = LinkTransport()
        session.connected(first, reopen(LinkTransport(failOpen = true)))

        first.cut()

        val state = session.await { it.connection is ConnectionState.Reconnecting }.connection as ConnectionState.Reconnecting
        state.description shouldBe "reach-RS3"
    }

    @Test
    fun `S004 RF-14 los reintentos esperan cada vez mas hasta el maximo`() {
        val session = session(backoff = listOf(60, 120, 240))
        val first = LinkTransport()
        session.connected(first, reopen(LinkTransport(failOpen = true)))
        val lostAt = now()

        first.cut()
        runBlocking { withTimeout(5_000) { while (attempts.size < 5) kotlinx.coroutines.delay(10) } }
        session.disconnect()

        val gaps = (listOf(lostAt) + attempts).zipWithNext { a, b -> b - a }.take(5)
        gaps[0] shouldBeGreaterThanOrEqual 60L
        gaps[1] shouldBeGreaterThanOrEqual 120L
        gaps[2] shouldBeGreaterThanOrEqual 240L
        gaps[3] shouldBeGreaterThanOrEqual 240L
        gaps[3] shouldBeLessThan 600L // la espera no sigue creciendo pasado el máximo
    }

    @Test
    fun `S004 RF-14 las esperas por defecto son de 1, 2, 4, 8 y 16 s y despues 30 s, durante 30 min`() {
        ReceiverSession.DEFAULT_BACKOFF_MS shouldBe listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 30_000L)
        ReceiverSession.GIVE_UP_AFTER_MS shouldBe 30 * 60_000L
    }

    @Test
    fun `S004 RF-17 al reconectar vuelve a Recibiendo sin hacer nada`() {
        val session = session()
        val first = LinkTransport()
        val back = LinkTransport()
        session.connected(first, reopen(LinkTransport(failOpen = true), back))

        first.cut()
        session.await { (it.connection as? ConnectionState.Reconnecting)?.attempt == 2 }
        back.send(gga)

        session.await { it.connection is ConnectionState.Receiving }.connection shouldBe ConnectionState.Receiving("reach-RS3")
        attempts shouldHaveAtLeastSize 2
    }

    @Test
    fun `S004 RF-16 Reintentar ahora hace un intento al momento`() {
        val session = session(backoff = listOf(5_000))
        val first = LinkTransport()
        session.connected(first, reopen(LinkTransport(failOpen = true)))
        first.cut()
        session.await { it.connection is ConnectionState.Reconnecting }
        val asked = now()

        session.retryNow()

        runBlocking { withTimeout(2_000) { while (attempts.isEmpty()) kotlinx.coroutines.delay(10) } }
        (attempts.first() - asked) shouldBeLessThan 1_000L
    }

    @Test
    fun `S004 RF-16 tras Reintentar ahora las esperas vuelven a empezar por la primera`() {
        val session = session(backoff = listOf(80, 2_000, 4_000))
        val first = LinkTransport()
        session.connected(first, reopen(LinkTransport(failOpen = true)))
        first.cut()
        runBlocking { withTimeout(5_000) { while (attempts.size < 1) kotlinx.coroutines.delay(10) } } // primer intento a los 80 ms
        session.await { (it.connection as? ConnectionState.Reconnecting)?.nextAttemptAtMs != null }

        session.retryNow() // en vez de esperar 2 s
        runBlocking { withTimeout(5_000) { while (attempts.size < 3) kotlinx.coroutines.delay(10) } }

        (attempts[2] - attempts[1]) shouldBeLessThan 1_000L // tras el intento inmediato, otra vez 80 ms
    }

    @Test
    fun `S004 RF-15 el estado de reconexion trae el numero de intento y cuando sera el siguiente`() {
        val session = session(backoff = listOf(100, 100))
        val first = LinkTransport()
        session.connected(first, reopen(LinkTransport(failOpen = true)))

        first.cut()

        val waiting = session.await {
            (it.connection as? ConnectionState.Reconnecting)?.attempt == 1 &&
                (it.connection as ConnectionState.Reconnecting).nextAttemptAtMs != null
        }
        val state = waiting.connection.shouldBeInstanceOf<ConnectionState.Reconnecting>()
        state.nextAttemptAtMs.shouldNotBeNull() shouldBeGreaterThanOrEqual state.lostAtMs
    }

    @Test
    fun `S004 RF-20 tras el tiempo maximo de reintentos se rinde y la conexion queda perdida`() {
        val session = session(backoff = listOf(50), giveUpAfter = 400)
        val first = LinkTransport()
        session.connected(first, reopen(LinkTransport(failOpen = true)))

        first.cut()

        session.await { it.connection is ConnectionState.Lost }.connection shouldBe ConnectionState.Lost("reach-RS3")
        val count = attempts.size
        Thread.sleep(300)
        attempts.size shouldBe count
    }

    @Test
    fun `S004 RF-21 desconectar durante la reconexion deja de reintentar`() {
        val session = session(backoff = listOf(50))
        val first = LinkTransport()
        session.connected(first, reopen(LinkTransport(failOpen = true)))
        first.cut()
        session.await { (it.connection as? ConnectionState.Reconnecting)?.attempt == 1 }

        session.disconnect()
        val count = attempts.size
        Thread.sleep(300)

        session.state.value.connection shouldBe ConnectionState.Disconnected
        attempts.size shouldBe count
    }

    @Test
    fun `S004 RF-18 durante la reconexion el NTRIP queda en pausa y se reanuda con la misma configuracion`() {
        val configs = CopyOnWriteArrayList<NtripConfig>()
        val connector = NtripConnector { config, _, onConnected, _ ->
            configs += config
            onConnected()
            kotlinx.coroutines.awaitCancellation()
        }
        val session = ReceiverSession(scope, connector, connectTimeoutMs = 300, backoffMs = listOf(50), giveUpAfterMs = 60_000)
        val first = LinkTransport()
        val back = LinkTransport()
        session.connected(first, reopen(back))
        val config = NtripConfig("caster.es", 2101, "VRS3", "topo", "s3cr3t")
        session.startNtrip(config)
        session.await { it.ntrip is NtripState.Streaming }

        first.cut()
        session.await { it.ntrip == NtripState.Paused && it.connection is ConnectionState.Reconnecting }
        back.send(gga)

        session.await { it.connection is ConnectionState.Receiving && it.ntrip is NtripState.Streaming }
        configs.toList() shouldBe listOf(config, config)
    }

    @Test
    fun `S004 RF-18 un NTRIP parado a mano no se reanuda al reconectar`() {
        val calls = AtomicInteger()
        val connector = NtripConnector { _, _, onConnected, _ ->
            calls.incrementAndGet()
            onConnected()
            kotlinx.coroutines.awaitCancellation()
        }
        val session = ReceiverSession(scope, connector, connectTimeoutMs = 300, backoffMs = listOf(50), giveUpAfterMs = 60_000)
        val first = LinkTransport()
        val back = LinkTransport()
        session.connected(first, reopen(back))
        session.startNtrip(NtripConfig("caster.es", 2101, "VRS3", "topo", ""))
        session.await { it.ntrip is NtripState.Streaming }
        session.stopNtrip()

        first.cut()
        session.await { it.connection is ConnectionState.Reconnecting }
        back.send(gga)

        session.await { it.connection is ConnectionState.Receiving }.ntrip shouldBe NtripState.Idle
        Thread.sleep(200)
        calls.get() shouldBe 1
    }

    @Test
    fun `S004 RF-19 la grabacion sigue en la misma sesion durante el corte y tras reconectar`() {
        val folder = kotlin.io.path.createTempDirectory("sesiones").toFile()
        try {
            val store = SessionLogStore(folder, freeSpaceBytes = { Long.MAX_VALUE })
            val session =
                ReceiverSession(scope, noNtrip, recorder = store, connectTimeoutMs = 300, backoffMs = listOf(50), giveUpAfterMs = 60_000)
            val first = LinkTransport()
            val back = LinkTransport()
            val second = nmea("GNGGA,101540.00,4025.0080000,N,00342.2228000,W,4,24,0.6,650.123,M,51.234,M,1.0,0001")
            session.connected(first, reopen(back))

            first.cut()
            session.await { it.connection is ConnectionState.Reconnecting }.recording shouldBe true
            back.send(second)
            session.await { it.gga?.utcTime == "101540.00" }
            session.disconnect()
            back.cut()

            val sessions = runBlocking {
                withTimeout(5_000) {
                    while (store.sessions().singleOrNull()?.receiverFiles?.sumOf { it.length() } != (gga.length + second.length).toLong()) {
                        kotlinx.coroutines.delay(20)
                    }
                    store.sessions()
                }
            }
            sessions.single().receiverFiles.flatMap { it.readBytes().toList() } shouldBe (gga + second).toByteArray().toList()
        } finally {
            folder.deleteRecursively()
        }
    }
}
