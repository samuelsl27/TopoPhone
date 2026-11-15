package es.topophone.receiver.session

import es.topophone.core.gnss.nmea.NmeaChecksum
import es.topophone.core.ntrip.NtripConfig
import es.topophone.core.ntrip.NtripConnector
import es.topophone.receiver.transport.CorrectionsLinkStatus
import es.topophone.receiver.transport.ReceiverTransport
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Test

/** La sesión con un receptor Wi-Fi: sin puerto de correcciones no abre el caster y con el enlace caído no graba lo que se descarta (S021/RF-12, RF-13). */
class ReceiverSessionWifiTest {

    private fun nmea(body: String) = "$" + body + "*" + "%02X".format(NmeaChecksum.compute(body)) + "\r\n"

    private val gga = nmea("GNGGA,101530.00,4025.0080000,N,00342.2228000,W,4,24,0.6,650.123,M,51.234,M,1.0,0001")

    private class WifiLink(
        override val acceptsCorrections: Boolean = true,
        override val correctionsStatus: StateFlow<CorrectionsLinkStatus>? = null,
    ) : ReceiverTransport {
        private val toApp = PipedOutputStream()
        private val written = ByteArrayOutputStream()
        override val description = "RS3 (192.168.42.1:9001)"
        override fun open() = Unit
        override val input: InputStream = PipedInputStream(toApp, 64 * 1024)
        override val output: OutputStream = object : OutputStream() {
            override fun write(b: Int) = synchronized(written) { written.write(b) }
            override fun write(b: ByteArray, off: Int, len: Int) = synchronized(written) { written.write(b, off, len) }
        }

        fun written(): ByteArray = synchronized(written) { written.toByteArray() }

        fun send(text: String) = toApp.apply {
            write(text.toByteArray())
            flush()
        }

        fun cutFromReceiver() = runCatching { toApp.close() }

        override fun close() {
            runCatching { toApp.close() }
            runCatching { input.close() }
        }
    }

    /** Un grabador que guarda las correcciones que la sesión dice haber enviado. */
    private class CorrectionsRecorder : SessionRecorder {
        val corrections = ByteArrayOutputStream()

        override fun start(description: String): SessionWriter = object : SessionWriter {
            override fun write(bytes: ByteArray, offset: Int, length: Int) = Unit
            override fun writeCorrections(bytes: ByteArray) = synchronized(corrections) { corrections.write(bytes) }
            override fun flush() = Unit
            override fun close() = Unit
        }

        fun recorded(): ByteArray = synchronized(corrections) { corrections.toByteArray() }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val config = NtripConfig("caster.es", 2101, "AVL13M", "anonimo", "anonimo", sendGga = false)
    private val casterCalls = AtomicInteger()
    private val connector = NtripConnector { _, _, onConnected, onCorrections ->
        casterCalls.incrementAndGet()
        onConnected()
        onCorrections(byteArrayOf(0xD3.toByte(), 0, 2, 0x11, 0x22))
        awaitCancellation()
    }

    @After
    fun tearDown() = scope.cancel()

    private fun ReceiverSession.await(timeoutMs: Long = 5_000, predicate: (ReceiverSnapshot) -> Boolean): ReceiverSnapshot =
        runBlocking { withTimeout(timeoutMs) { state.first(predicate) } }

    private fun receiving(link: WifiLink, recorder: SessionRecorder = SessionRecorder.NONE): ReceiverSession {
        val session = ReceiverSession(scope, connector, recorder = recorder)
        session.connect(link)
        link.send(gga)
        session.await { it.connection is ConnectionState.Receiving }
        return session
    }

    @Test
    fun `S021 RF-12 sin puerto de correcciones el caster no se abre y la sesion lo dice`() {
        val link = WifiLink(acceptsCorrections = false)
        val session = receiving(link)

        session.startNtrip(config)

        session.state.value.ntrip shouldBe NtripState.NoCorrectionsPort
        Thread.sleep(200)
        casterCalls.get() shouldBe 0
        link.written().size shouldBe 0
        session.state.value.connection.shouldBeInstanceOf<ConnectionState.Receiving>()
    }

    @Test
    fun `S021 RF-12 sin puerto de correcciones la posicion sigue llegando`() {
        val link = WifiLink(acceptsCorrections = false)
        val session = receiving(link)
        session.startNtrip(config)

        link.send(gga)

        session.await { it.gga != null && it.connection is ConnectionState.Receiving }.ntrip shouldBe NtripState.NoCorrectionsPort
    }

    @Test
    fun `S021 RF-12 parar el NTRIP tras el aviso lo deja en reposo`() {
        val link = WifiLink(acceptsCorrections = false)
        val session = receiving(link)
        session.startNtrip(config)

        session.stopNtrip()

        session.state.value.ntrip shouldBe NtripState.Idle
    }

    @Test
    fun `S021 RF-11 con puerto de correcciones el caster se abre y lo recibido se escribe al receptor y se graba`() {
        val link = WifiLink(acceptsCorrections = true)
        val recorder = CorrectionsRecorder()
        val session = receiving(link, recorder)

        session.startNtrip(config)
        session.await { (it.ntrip as? NtripState.Streaming)?.bytesReceived == 5L }
        waitFor { link.written().size == 5 && recorder.recorded().size == 5 }

        casterCalls.get() shouldBe 1
        link.written() shouldBe byteArrayOf(0xD3.toByte(), 0, 2, 0x11, 0x22)
        recorder.recorded() shouldBe link.written()
    }

    @Test
    fun `S021 RF-13 el estado del enlace de correcciones del transporte pasa al estado de la sesion`() {
        val status = MutableStateFlow<CorrectionsLinkStatus>(CorrectionsLinkStatus.Connecting)
        val link = WifiLink(correctionsStatus = status)
        val session = receiving(link)

        session.await { it.correctionsLink == CorrectionsLinkStatus.Connecting }
        status.value = CorrectionsLinkStatus.Ready
        session.await { it.correctionsLink == CorrectionsLinkStatus.Ready }
        status.value = CorrectionsLinkStatus.Failed(2, 99_000L)

        session.await { it.correctionsLink == CorrectionsLinkStatus.Failed(2, 99_000L) }
            .connection.shouldBeInstanceOf<ConnectionState.Receiving>()
    }

    @Test
    fun `S021 RF-13 sin segundo enlace el estado no lleva enlace de correcciones`() {
        val session = receiving(WifiLink(correctionsStatus = null))

        session.state.value.correctionsLink.shouldBeNull()
    }

    @Test
    fun `S021 RF-13 con el enlace caido la posicion sigue y lo descartado no se graba`() {
        val status = MutableStateFlow<CorrectionsLinkStatus>(CorrectionsLinkStatus.Failed(1, 1_000L))
        val link = WifiLink(correctionsStatus = status)
        val recorder = CorrectionsRecorder()
        val session = receiving(link, recorder)

        session.startNtrip(config)
        session.await { (it.ntrip as? NtripState.Streaming)?.bytesReceived == 5L }
        Thread.sleep(300)
        link.send(gga)

        session.await { it.connection is ConnectionState.Receiving }
        recorder.recorded().size shouldBe 0
        status.value = CorrectionsLinkStatus.Ready
        session.await { it.correctionsLink == CorrectionsLinkStatus.Ready }
        recorder.recorded().size shouldBe 0
    }

    @Test
    fun `S021 RF-13 al perder el enlace de posicion el estado del de correcciones se limpia`() {
        val status = MutableStateFlow<CorrectionsLinkStatus>(CorrectionsLinkStatus.Ready)
        val link = WifiLink(correctionsStatus = status)
        val session = ReceiverSession(scope, connector, backoffMs = listOf(60_000L))
        session.connect(link, reopen = { throw java.io.IOException("sin red") })
        link.send(gga)
        session.await { it.correctionsLink == CorrectionsLinkStatus.Ready }

        link.cutFromReceiver()

        session.await { it.connection is ConnectionState.Reconnecting }.correctionsLink.shouldBeNull()
    }

    private fun waitFor(timeoutMs: Long = 5_000, condition: () -> Boolean) {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        while (!condition() && System.nanoTime() < deadline) Thread.sleep(10)
    }
}
