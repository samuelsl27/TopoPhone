package es.topophone.receiver.session

import es.topophone.core.gnss.nmea.NmeaChecksum
import es.topophone.core.gnss.nmea.SolutionStatus
import es.topophone.core.ntrip.NtripConfig
import es.topophone.core.ntrip.NtripConnector
import es.topophone.core.ntrip.NtripFailure
import es.topophone.receiver.transport.ReceiverTransport
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Test

/** Sesión con el receptor: estados, plazos, reenvío de correcciones y NTRIP (S002/RF-5 a RF-10, RF-12 a RF-17, RF-19, RF-28). */
class ReceiverSessionTest {

    private fun nmea(body: String) = "$" + body + "*" + "%02X".format(NmeaChecksum.compute(body)) + "\r\n"

    private val gga = nmea("GNGGA,101530.20,4025.1234567,N,00342.7654321,W,4,24,0.6,650.123,M,51.234,M,1.0,0001")
    private val gst = nmea("GNGST,101530.20,0.010,0.012,0.008,45.0,0.009,0.012,0.020")

    /** Receptor falso: lo que se escribe en [feed] lo "envía" el receptor; [written] recoge lo que le llega. */
    private class FakeTransport(private val openBehaviour: () -> Unit = {}) : ReceiverTransport {
        private val toApp = PipedOutputStream()
        private val pipe = PipedInputStream(toApp, 64 * 1024)
        val written = ByteArrayOutputStream()
        val closed = CountDownLatch(1)
        override val description = "RS3 de prueba"
        override fun open() = openBehaviour()
        override val input: InputStream = pipe
        override val output: OutputStream = object : OutputStream() {
            override fun write(b: Int) = synchronized(written) { written.write(b) }
            override fun write(b: ByteArray, off: Int, len: Int) = synchronized(written) { written.write(b, off, len) }
        }
        fun feed(text: String) = toApp.apply {
            write(text.toByteArray(Charsets.US_ASCII))
            flush()
        }
        fun breakLink() = toApp.close()
        fun writtenBytes() = synchronized(written) { written.toByteArray() }
        override fun close() {
            runCatching { toApp.close() }
            runCatching { pipe.close() }
            closed.countDown()
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @After
    fun tearDown() = scope.cancel()

    private fun session(connector: NtripConnector = NtripConnector { _, _, _, _ -> NtripFailure.CONNECTION_LOST }) =
        ReceiverSession(scope, connector, connectTimeoutMs = 300, noDataTimeoutMs = 300, accuracyStaleMs = 300, watchdogPeriodMs = 50)

    private fun ReceiverSession.await(predicate: (ReceiverSnapshot) -> Boolean): ReceiverSnapshot =
        runBlocking { withTimeout(3_000) { state.first(predicate) } }

    @Test
    fun `S002 RF-5 S002 RF-6 al conectar se pasa por Conectando y la primera frase valida da Recibiendo`() {
        val transport = FakeTransport()
        val session = session()

        session.connect(transport)
        session.state.value.connection.shouldBeInstanceOf<ConnectionState.Connecting>()
        transport.feed(gga)

        val snapshot = session.await { it.connection is ConnectionState.Receiving }
        snapshot.connection shouldBe ConnectionState.Receiving("RS3 de prueba")
    }

    @Test
    fun `S002 RF-12 S002 RF-13 S002 RF-14 S002 RF-15 la sesion expone la ultima GGA y la ultima GST`() {
        val transport = FakeTransport()
        val session = session()
        session.connect(transport)

        transport.feed(gga + gst)

        val snapshot = session.await { it.gga != null && it.gst != null }
        snapshot.gga.shouldNotBeNull().status shouldBe SolutionStatus.FIX
        snapshot.gga.satellites shouldBe 24
        snapshot.gst.shouldNotBeNull().verticalM shouldBe 0.020
    }

    @Test
    fun `S002 RF-17 si deja de llegar la GST la precision deja de estar disponible`() {
        val transport = FakeTransport()
        val session = session()
        session.connect(transport)
        transport.feed(gga + gst)
        session.await { it.gst != null }

        session.await { it.gst == null }.gst.shouldBeNull()
    }

    @Test
    fun `S002 RF-7 si la conexion no se abre a tiempo falla por plazo y se cierra`() {
        val transport = FakeTransport(openBehaviour = { Thread.sleep(2_000) })
        val session = session()

        session.connect(transport)

        session.await { it.connection is ConnectionState.Failed }.connection shouldBe ConnectionState.Failed(ConnectFailure.TIMEOUT)
        transport.closed.await(2, TimeUnit.SECONDS) shouldBe true
    }

    @Test
    fun `S002 RF-7 si la conexion da error se indica`() {
        val session =
            ReceiverSession(
                scope,
                NtripConnector { _, _, _, _ ->
                    NtripFailure.CONNECTION_LOST
                },
                connectTimeoutMs = 1_000,
                busyThresholdMs = 50,
            )

        // Un rechazo inmediato es BUSY (S004/RF-27); este tarda más que el umbral.
        session.connect(
            FakeTransport(openBehaviour = {
                Thread.sleep(150)
                throw IOException("rechazada")
            }),
        )

        session.await { it.connection is ConnectionState.Failed }.connection shouldBe ConnectionState.Failed(ConnectFailure.ERROR)
    }

    @Test
    fun `S002 RF-8 sin frases validas durante el plazo se pasa a Sin datos y vuelve a Recibiendo`() {
        val transport = FakeTransport()
        val session = session()
        session.connect(transport)
        transport.feed("basura sin formato\r\n")

        session.await { it.connection is ConnectionState.NoData }
        transport.feed(gga)

        session.await { it.connection is ConnectionState.Receiving }
    }

    @Test
    fun `S002 RF-9 si se corta el enlace con el receptor la conexion se da por perdida`() {
        val transport = FakeTransport()
        val session = session()
        session.connect(transport)
        transport.feed(gga)
        session.await { it.connection is ConnectionState.Receiving }

        transport.breakLink()

        session.await { it.connection is ConnectionState.Lost }.connection shouldBe ConnectionState.Lost("RS3 de prueba")
    }

    @Test
    fun `S002 RF-10 desconectar cierra el receptor y el NTRIP`() {
        val ntripCancelled = CompletableDeferred<Unit>()
        val connector = NtripConnector { _, _, onConnected, _ ->
            onConnected()
            try {
                CompletableDeferred<Unit>().await()
                NtripFailure.CONNECTION_LOST
            } finally {
                ntripCancelled.complete(Unit)
            }
        }
        val transport = FakeTransport()
        val session = session(connector)
        session.connect(transport)
        transport.feed(gga)
        session.await { it.connection is ConnectionState.Receiving }
        session.startNtrip(CONFIG)
        session.await { it.ntrip is NtripState.Streaming }

        session.disconnect()

        session.state.value.connection shouldBe ConnectionState.Disconnected
        session.state.value.ntrip shouldBe NtripState.Idle
        transport.closed.await(2, TimeUnit.SECONDS) shouldBe true
        runBlocking { withTimeout(2_000) { ntripCancelled.await() } }
    }

    @Test
    fun `S002 RF-19 las correcciones del caster llegan al receptor sin modificar y en orden, con la ultima GGA`() {
        val blocks = listOf(byteArrayOf(0xD3.toByte(), 0, 2, 1, 2), byteArrayOf(0xD3.toByte(), 0, 1, 3))
        val ggaSeen = CompletableDeferred<String?>()
        val connector = NtripConnector { _, latestGga, onConnected, onCorrections ->
            onConnected()
            ggaSeen.complete(latestGga())
            blocks.forEach { onCorrections(it) }
            NtripFailure.CONNECTION_LOST
        }
        val transport = FakeTransport()
        val session = session(connector)
        session.connect(transport)
        transport.feed(gga)
        session.await { it.connection is ConnectionState.Receiving }

        session.startNtrip(CONFIG)

        session.await { it.ntrip is NtripState.Stopped }.ntrip shouldBe NtripState.Stopped(NtripFailure.CONNECTION_LOST)
        runBlocking { withTimeout(2_000) { ggaSeen.await() } } shouldBe gga.trimEnd()
        runBlocking { withTimeout(2_000) { while (transport.writtenBytes().size < 9) kotlinx.coroutines.delay(20) } }
        transport.writtenBytes().toList() shouldBe blocks.flatMap { it.toList() }
    }

    @Test
    fun `S002 RF-25 mientras llegan correcciones se cuentan los bytes`() {
        val connector = NtripConnector { _, _, onConnected, onCorrections ->
            onConnected()
            onCorrections(ByteArray(100))
            onCorrections(ByteArray(23))
            CompletableDeferred<Unit>().await()
            NtripFailure.CONNECTION_LOST
        }
        val transport = FakeTransport()
        val session = session(connector)
        session.connect(transport)
        transport.feed(gga)
        session.await { it.connection is ConnectionState.Receiving }

        session.startNtrip(CONFIG)

        val streaming = session.await { (it.ntrip as? NtripState.Streaming)?.bytesReceived == 123L }.ntrip as NtripState.Streaming
        streaming.lastCorrectionAtMs.shouldNotBeNull()
    }

    @Test
    fun `S002 RF-28 sin receptor conectado no se conecta al caster`() {
        var called = false
        val session = session(
            NtripConnector { _, _, _, _ ->
                called = true
                NtripFailure.CONNECTION_LOST
            },
        )

        session.startNtrip(CONFIG)

        session.state.value.ntrip shouldBe NtripState.ReceiverNotConnected
        called shouldBe false
    }

    private companion object {
        val CONFIG = NtripConfig("caster", 2101, "MP", "u", "p")
    }
}
