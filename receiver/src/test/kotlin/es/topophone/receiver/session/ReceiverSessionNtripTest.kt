package es.topophone.receiver.session

import es.topophone.core.gnss.nmea.NmeaChecksum
import es.topophone.core.ntrip.NtripConfig
import es.topophone.core.ntrip.NtripConnector
import es.topophone.core.ntrip.NtripFailure
import es.topophone.receiver.transport.ReceiverTransport
import io.kotest.matchers.longs.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Test

/** Reconexión automática del NTRIP (S007/RF-23 a RF-25, RF-28). */
class ReceiverSessionNtripTest {

    private fun nmea(body: String) = "$" + body + "*" + "%02X".format(NmeaChecksum.compute(body)) + "\r\n"

    private val gga = nmea("GNGGA,101530.00,4025.0080000,N,00342.2228000,W,4,24,0.6,650.123,M,51.234,M,1.0,0001")

    private class Link : ReceiverTransport {
        private val toApp = PipedOutputStream()
        override val description = "reach-RS3"
        override fun open() = Unit
        override val input: InputStream = PipedInputStream(toApp, 64 * 1024)
        override val output: OutputStream = ByteArrayOutputStream()
        fun send(text: String) = toApp.apply {
            write(text.toByteArray())
            flush()
        }
        override fun close() {
            runCatching { toApp.close() }
            runCatching { input.close() }
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val config = NtripConfig("caster.es", 2101, "VRS3M", "topo", "s3cr3t")
    private val backoff = listOf(50L, 100L, 200L)

    /** Conector que responde con [script] en cada intento (el último se repite) y anota cuándo se llamó. */
    private class ScriptedConnector(private val script: List<NtripFailure?>) : NtripConnector {
        val calls = CopyOnWriteArrayList<Long>()

        override suspend fun run(
            config: NtripConfig,
            latestGga: () -> String?,
            onConnected: () -> Unit,
            onCorrections: suspend (ByteArray) -> Unit,
        ): NtripFailure {
            calls += System.nanoTime() / 1_000_000
            val failure = script[minOf(calls.size - 1, script.lastIndex)]
            if (failure != null) return failure
            onConnected() // null: conecta y se queda recibiendo
            onCorrections(byteArrayOf(0xD3.toByte(), 0, 0, 0, 0, 0))
            awaitCancellation()
        }
    }

    @After
    fun tearDown() = scope.cancel()

    private fun ReceiverSession.await(predicate: (ReceiverSnapshot) -> Boolean): ReceiverSnapshot =
        runBlocking { withTimeout(5_000) { state.first(predicate) } }

    private fun connectedSession(connector: NtripConnector, ntripBackoff: List<Long> = backoff): ReceiverSession {
        val session = ReceiverSession(scope, connector, ntripBackoffMs = ntripBackoff)
        val link = Link()
        session.connect(link)
        link.send(gga)
        session.await { it.connection is ConnectionState.Receiving }
        return session
    }

    @Test
    fun `S007 RF-23 si no se puede contactar con el caster se reintenta con esperas que crecen hasta conectar`() {
        val connector = ScriptedConnector(listOf(NtripFailure.UNREACHABLE, NtripFailure.UNREACHABLE, NtripFailure.UNREACHABLE, null))
        val session = connectedSession(connector)

        session.startNtrip(config)

        session.await { it.ntrip is NtripState.Streaming }
        val gaps = connector.calls.zipWithNext { a, b -> b - a }
        gaps.size shouldBe 3
        gaps[0] shouldBeGreaterThanOrEqual 50
        gaps[1] shouldBeGreaterThanOrEqual 100
        gaps[2] shouldBeGreaterThanOrEqual 200
    }

    @Test
    fun `S007 RF-25 mientras se reintenta se ve el numero de intento y el motivo`() {
        val connector = ScriptedConnector(listOf(NtripFailure.NO_DATA, NtripFailure.CONNECTION_LOST, null))
        val session = connectedSession(connector, ntripBackoff = listOf(300L))

        session.startNtrip(config)

        val first = session.await { it.ntrip is NtripState.Reconnecting }.ntrip.shouldBeInstanceOf<NtripState.Reconnecting>()
        first.attempt shouldBe 1
        first.failure shouldBe NtripFailure.NO_DATA
        val second = session.await { (it.ntrip as? NtripState.Reconnecting)?.attempt == 2 }.ntrip as NtripState.Reconnecting
        second.failure shouldBe NtripFailure.CONNECTION_LOST
        (second.nextAttemptAtMs - first.nextAttemptAtMs) shouldBeGreaterThanOrEqual 300
    }

    @Test
    fun `S007 RF-23 tras volver a recibir, un corte nuevo empieza otra vez por el primer intento`() {
        var cut = false
        val connector = NtripConnector { _, _, onConnected, _ ->
            if (cut) return@NtripConnector NtripFailure.UNREACHABLE
            onConnected()
            cut = true
            NtripFailure.CONNECTION_LOST
        }
        val session = connectedSession(connector, ntripBackoff = listOf(5_000L))

        session.startNtrip(config)

        session.await { (it.ntrip as? NtripState.Reconnecting)?.failure == NtripFailure.CONNECTION_LOST }
            .ntrip.shouldBeInstanceOf<NtripState.Reconnecting>().attempt shouldBe 1
    }

    @Test
    fun `S007 RF-24 si el caster rechaza las credenciales o no existe el punto de montaje no se reintenta`() {
        listOf(NtripFailure.UNAUTHORIZED, NtripFailure.MOUNTPOINT_NOT_FOUND).forEach { failure ->
            val connector = ScriptedConnector(listOf(failure))
            val session = connectedSession(connector)

            session.startNtrip(config)

            session.await { it.ntrip is NtripState.Stopped }.ntrip shouldBe NtripState.Stopped(failure)
            Thread.sleep(300)
            connector.calls.size shouldBe 1
            session.disconnect()
        }
    }

    @Test
    fun `S007 RF-28 con un certificado TLS no valido no se reintenta`() {
        val connector = ScriptedConnector(listOf(NtripFailure.TLS_INVALID))
        val session = connectedSession(connector)

        session.startNtrip(config)

        session.await { it.ntrip is NtripState.Stopped }.ntrip shouldBe NtripState.Stopped(NtripFailure.TLS_INVALID)
        Thread.sleep(300)
        connector.calls.size shouldBe 1
    }

    @Test
    fun `S007 RF-25 detener durante la espera para los reintentos`() {
        val connector = ScriptedConnector(listOf(NtripFailure.UNREACHABLE))
        val session = connectedSession(connector, ntripBackoff = listOf(300L))
        session.startNtrip(config)
        session.await { it.ntrip is NtripState.Reconnecting }

        session.stopNtrip()

        session.state.value.ntrip shouldBe NtripState.Idle
        val calls = connector.calls.size
        Thread.sleep(700)
        connector.calls.size shouldBe calls
    }

    @Test
    fun `S007 RF-23 las esperas por defecto son de 1 s a 30 s y crecen al doble`() {
        ReceiverSession.DEFAULT_NTRIP_BACKOFF_MS shouldBe listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 30_000L)
    }
}
