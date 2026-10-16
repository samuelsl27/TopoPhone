package es.topophone.receiver.session

import es.topophone.core.gnss.nmea.NmeaChecksum
import es.topophone.core.ntrip.NtripConfig
import es.topophone.core.ntrip.NtripConnector
import es.topophone.receiver.transport.ReceiverTransport
import io.kotest.matchers.longs.shouldBeGreaterThanOrEqual
import io.kotest.matchers.longs.shouldBeLessThan
import io.kotest.matchers.shouldBe
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Test

/** Recuento de mensajes RTCM y alarma por edad de las correcciones (S007/RF-29, RF-30, RF-32, RF-33). */
class ReceiverSessionCorrectionsTest {

    private fun nmea(body: String) = "$" + body + "*" + "%02X".format(NmeaChecksum.compute(body)) + "\r\n"

    private val gga = nmea("GNGGA,101530.00,4025.0080000,N,00342.2228000,W,4,24,0.6,650.123,M,51.234,M,1.0,0001")

    private class Link : ReceiverTransport {
        private val toApp = PipedOutputStream()
        private val written = ByteArrayOutputStream()
        override val description = "reach-RS3"
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
        override fun close() {
            runCatching { toApp.close() }
            runCatching { input.close() }
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val config = NtripConfig("caster.es", 2101, "AVL13M", "anonimo", "anonimo", sendGga = false)
    private val link = Link()

    /** Lo que el caster falso envía: cada elemento del canal es un bloque de correcciones. */
    private val blocks = Channel<ByteArray>(Channel.UNLIMITED)
    private val connector = NtripConnector { _, _, onConnected, onCorrections ->
        onConnected()
        for (block in blocks) onCorrections(block)
        awaitCancellation()
    }

    private val rtcm: ByteArray by lazy {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }
            .first { File(it, "settings.gradle.kts").exists() && File(it, "fixtures").isDirectory }
        File(root, "fixtures/rtcm/rgapa-AVL13M.rtcm3").readBytes()
    }

    @After
    fun tearDown() = scope.cancel()

    private fun ReceiverSession.await(timeoutMs: Long = 5_000, predicate: (ReceiverSnapshot) -> Boolean): ReceiverSnapshot =
        runBlocking { withTimeout(timeoutMs) { state.first(predicate) } }

    private fun session(alarmSeconds: Int = 10): ReceiverSession {
        val session = ReceiverSession(scope, connector)
        session.setCorrectionsAlarmThreshold(alarmSeconds)
        session.connect(link)
        link.send(gga)
        session.await { it.connection is ConnectionState.Receiving }
        return session
    }

    private fun streaming(session: ReceiverSession) = session.state.value.ntrip as NtripState.Streaming

    private fun now() = System.nanoTime() / 1_000_000

    @Test
    fun `S007 RF-32 se cuentan los mensajes RTCM de cada tipo que llegan`() {
        val session = session()
        session.startNtrip(config)

        rtcm.toList().chunked(700).forEach { blocks.trySend(it.toByteArray()) }

        session.await { (it.ntrip as? NtripState.Streaming)?.bytesReceived == rtcm.size.toLong() }
        streaming(session).rtcm.counts shouldBe mapOf(
            1006 to 2L,
            1008 to 3L,
            1013 to 3L,
            1033 to 3L,
            1075 to 24L,
            1085 to 25L,
            1095 to 25L,
            1125 to 25L,
            1230 to 3L,
        )
    }

    @Test
    fun `S007 RF-33 un mensaje con el CRC erroneo se cuenta y se reenvia al receptor sin tocarlo`() {
        val corrupted = rtcm.copyOf().also { it[2000] = (it[2000].toInt() xor 0x5A).toByte() }
        val session = session()
        session.startNtrip(config)

        blocks.trySend(corrupted)

        session.await { (it.ntrip as? NtripState.Streaming)?.rtcm?.crcErrors == 1L }
        runBlocking { withTimeout(2_000) { while (link.written().size < corrupted.size) kotlinx.coroutines.delay(20) } }
        link.written().toList() shouldBe corrupted.toList()
    }

    @Test
    fun `S007 RF-29 si las correcciones dejan de llegar mas que el umbral salta la alarma en menos de 1 s`() {
        val session = session(alarmSeconds = 2)
        session.startNtrip(config)
        blocks.trySend(rtcm.copyOfRange(0, 1000))
        // La edad se cuenta desde que la sesión recibió la corrección, con su mismo reloj monótono.
        val lastCorrection = (session.await { (it.ntrip as? NtripState.Streaming)?.bytesReceived == 1000L }.ntrip as NtripState.Streaming)
            .lastCorrectionAtMs!!

        session.await(timeoutMs = 6_000) { it.correctionsAlarm }

        val elapsed = now() - lastCorrection
        elapsed shouldBeGreaterThanOrEqual 2_000
        elapsed shouldBeLessThan 3_000
    }

    @Test
    fun `S007 RF-30 cuando vuelven las correcciones se quita la alarma, que sono una sola vez por corte`() {
        val session = session(alarmSeconds = 2)
        val rises = mutableListOf<Boolean>()
        val watcher = scope.launch {
            var previous = false
            session.state.collect {
                if (it.correctionsAlarm && !previous) rises += true
                previous = it.correctionsAlarm
            }
        }
        session.startNtrip(config)
        blocks.trySend(rtcm.copyOfRange(0, 1000))
        session.await(timeoutMs = 6_000) { it.correctionsAlarm }
        Thread.sleep(1_000) // sigue sin correcciones: la alarma no se repite

        blocks.trySend(rtcm.copyOfRange(1000, 2000))

        session.await { !it.correctionsAlarm }
        watcher.cancel()
        rises.size shouldBe 1
    }

    @Test
    fun `S007 RF-29 sin conexion NTRIP abierta no hay alarma`() {
        val session = session(alarmSeconds = 2)
        session.startNtrip(config)
        blocks.trySend(rtcm.copyOfRange(0, 1000))
        session.await(timeoutMs = 6_000) { it.correctionsAlarm }

        session.stopNtrip()

        session.await { !it.correctionsAlarm }
        Thread.sleep(2_500)
        session.state.value.correctionsAlarm shouldBe false
    }

    @Test
    fun `S007 RF-31 el umbral de la alarma es 10 s si no se cambia`() {
        ReceiverSession.DEFAULT_CORRECTIONS_ALARM_S shouldBe 10
    }
}
