package es.topophone.receiver.session

import es.topophone.core.gnss.nmea.NmeaChecksum
import es.topophone.core.ntrip.NtripConfig
import es.topophone.core.ntrip.NtripConnector
import es.topophone.core.ntrip.NtripFailure
import es.topophone.receiver.transport.StreamTransport
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import java.io.ByteArrayOutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** La sesión graba los datos del receptor y las correcciones que se le envían (S002/RF-42, S003/RF-20, S003/RF-24). */
class ReceiverSessionRecordingTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val toApp = PipedOutputStream()
    private val sentToReceiver = ByteArrayOutputStream()
    private val transport = StreamTransport("reach-RS3", PipedInputStream(toApp, 64 * 1024), sentToReceiver)
    private val store by lazy { SessionLogStore(folder.root, clock = { Instant.parse("2026-10-15T08:30:00Z") }) }
    private val corrections = listOf(byteArrayOf(0xD3.toByte(), 0x00, 0x02, 0x11, 0x22), byteArrayOf(0x33, 0x44, 0x55))
    private val ntripDone = CompletableDeferred<Unit>()

    private val connector = NtripConnector { _, _, onConnected, onCorrections ->
        onConnected()
        corrections.forEach { onCorrections(it) }
        ntripDone.await()
        NtripFailure.CONNECTION_LOST
    }

    private fun nmea(body: String) = "$" + body + "*" + "%02X".format(NmeaChecksum.compute(body)) + "\r\n"

    @After
    fun tearDown() = scope.cancel()

    private fun ReceiverSession.await(predicate: (ReceiverSnapshot) -> Boolean): ReceiverSnapshot =
        runBlocking { withTimeout(5_000) { state.first(predicate) } }

    @Test
    fun `S003 RF-24 la sesion graba las correcciones enviadas al receptor y S002 RF-42 los datos recibidos`() {
        val session = ReceiverSession(scope, connector, recorder = store)
        val gga = nmea("GNGGA,101530.00,4025.0080000,N,00342.2228000,W,4,24,0.6,650.123,M,51.234,M,1.0,0001")
        session.connect(transport)
        toApp.write(gga.toByteArray())
        toApp.flush()
        session.await { it.connection is ConnectionState.Receiving }

        session.startNtrip(NtripConfig("caster.es", 2101, "VRS3", "topo", "s3cr3t"))
        session.await { (it.ntrip as? NtripState.Streaming)?.bytesReceived == 8L }
        runBlocking {
            withTimeout(5_000) {
                while (synchronized(sentToReceiver) { sentToReceiver.size() } <
                    8
                ) {
                    kotlinx.coroutines.delay(10)
                }
            }
        }
        Thread.sleep(200) // la cola de escritura graba cada corrección justo después de enviarla
        session.disconnect()
        toApp.close() // como el socket Bluetooth al cerrarse: desbloquea la lectura
        ntripDone.complete(Unit)

        // La grabación se cierra en segundo plano al desconectar.
        val recorded = runBlocking {
            withTimeout(5_000) {
                var latest = store.latest()
                while (latest?.receiverFiles?.sumOf { it.length() } != gga.length.toLong()) {
                    kotlinx.coroutines.delay(20)
                    latest = store.latest()
                }
                latest
            }
        }.shouldNotBeNull()
        recorded.receiver shouldBe "reach-RS3"
        recorded.receiverFiles.flatMap { it.readBytes().toList() } shouldBe gga.toByteArray().toList()
        recorded.correctionFiles.flatMap { it.readBytes().toList() } shouldBe corrections.flatMap { it.toList() }
    }

    @Test
    fun `S003 RF-20 mientras se graba la sesion lo indica y al desconectar deja de grabar`() {
        val session = ReceiverSession(scope, connector, recorder = store)
        session.state.value.recording shouldBe false

        session.connect(transport)

        session.await { it.recording }
        session.disconnect()
        session.state.value.recording shouldBe false
    }

    @Test
    fun `S003 RF-20 sin registro de sesiones no se indica grabacion`() {
        val session = ReceiverSession(scope, connector)
        session.connect(transport)
        toApp.write(nmea("GNGGA,101530.00,4025.0080000,N,00342.2228000,W,4,24,0.6,650.123,M,51.234,M,1.0,0001").toByteArray())
        toApp.flush()

        session.await { it.connection is ConnectionState.Receiving }.recording shouldBe false
    }
}
