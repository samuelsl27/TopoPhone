package es.topophone.receiver.transport

import es.topophone.core.gnss.nmea.NmeaChecksum
import es.topophone.core.gnss.nmea.ReplaySpeed
import es.topophone.core.ntrip.NtripConfig
import es.topophone.core.ntrip.NtripConnector
import es.topophone.core.ntrip.NtripFailure
import es.topophone.receiver.session.ConnectionState
import es.topophone.receiver.session.NtripState
import es.topophone.receiver.session.ReceiverSession
import es.topophone.receiver.session.ReceiverSnapshot
import es.topophone.receiver.session.ReplayInfo
import es.topophone.receiver.session.SessionLogStore
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.longs.shouldBeLessThan
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
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

/** Reproducción de una sesión como si fuera el receptor (S003/RF-32, S003/RF-37, S003/RF-39, S003/RF-40, S003/RF-44, S003/RF-45). */
class ReplayTransportTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val ntripCalls = AtomicInteger()
    private val connector = NtripConnector { _, _, _, _ ->
        ntripCalls.incrementAndGet()
        NtripFailure.CONNECTION_LOST
    }

    @After
    fun tearDown() = scope.cancel()

    private fun nmea(body: String) = "$" + body + "*" + "%02X".format(NmeaChecksum.compute(body)) + "\r\n"

    private fun gga(time: String) = nmea("GNGGA,$time,4025.0080000,N,00342.2228000,W,4,24,0.6,650.123,M,51.234,M,1.0,0001")

    /** Una sesión de tres épocas a 1 Hz, repartida en dos partes. */
    private fun sessionFiles(): List<File> = listOf(
        File(folder.root, "receiver-0001.nmea").apply { writeText(gga("101530.00") + gga("101531.00")) },
        File(folder.root, "receiver-0002.nmea").apply { writeText(gga("101532.00")) },
    )

    private fun ReceiverSession.await(predicate: (ReceiverSnapshot) -> Boolean): ReceiverSnapshot =
        runBlocking { withTimeout(10_000) { state.first(predicate) } }

    @Test
    fun `S003 RF-32 la sesion se reproduce como si llegara de un receptor conectado`() {
        val session = ReceiverSession(scope, connector)
        val transport = ReplayTransport("20261015-083000", sessionFiles(), ReplaySpeed.X5, loop = false)

        session.connect(transport)

        val snapshot = session.await { it.epoch?.utcMillisOfDay == 36_932_000 }
        snapshot.gga.shouldNotBeNull().satellites shouldBe 24
        snapshot.replay shouldBe ReplayInfo("20261015-083000", ReplaySpeed.X5, loop = false)
    }

    @Test
    fun `S003 RF-40 sin bucle al terminar la sesion la reproduccion queda terminada`() {
        val session = ReceiverSession(scope, connector)

        session.connect(ReplayTransport("sesion", sessionFiles(), ReplaySpeed.X5, loop = false))

        session.await { it.connection is ConnectionState.ReplayFinished }.connection shouldBe ConnectionState.ReplayFinished("sesion")
    }

    @Test
    fun `S003 RF-39 con el bucle activado vuelve a empezar y las epocas se reinician`() {
        val session = ReceiverSession(scope, connector)

        session.connect(ReplayTransport("sesion", sessionFiles(), ReplaySpeed.X5, loop = true))

        session.await { it.epoch?.utcMillisOfDay == 36_932_000 }
        session.await { it.epoch?.utcMillisOfDay == 36_930_000 || it.epoch?.utcMillisOfDay == 36_931_000 }
        session.state.value.connection.let { it is ConnectionState.Receiving } shouldBe true
    }

    @Test
    fun `S003 RF-37 la velocidad y el bucle se cambian durante la reproduccion`() {
        val session = ReceiverSession(scope, connector)
        session.connect(ReplayTransport("sesion", sessionFiles(), ReplaySpeed.X1, loop = false))

        session.setReplaySpeed(ReplaySpeed.X5)
        session.setReplayLoop(true)

        session.state.value.replay shouldBe ReplayInfo("sesion", ReplaySpeed.X5, loop = true)
    }

    @Test
    fun `S003 RF-44 durante la reproduccion no se conecta al caster`() {
        val session = ReceiverSession(scope, connector)
        session.connect(ReplayTransport("sesion", sessionFiles(), ReplaySpeed.X5, loop = true))
        session.await { it.connection is ConnectionState.Receiving }

        session.startNtrip(NtripConfig("caster.es", 2101, "VRS3", "topo", ""))

        session.state.value.ntrip shouldBe NtripState.UnavailableInReplay
        Thread.sleep(100)
        ntripCalls.get() shouldBe 0
    }

    @Test
    fun `S003 RF-45 durante la reproduccion no se graba ninguna sesion`() {
        val sessions = File(folder.root, "sessions")
        val store = SessionLogStore(sessions, freeSpaceBytes = { Long.MAX_VALUE })
        val session = ReceiverSession(scope, connector, recorder = store)

        session.connect(ReplayTransport("sesion", sessionFiles(), ReplaySpeed.X5, loop = false))

        session.await { it.connection is ConnectionState.ReplayFinished }.recording shouldBe false
        store.sessions() shouldHaveSize 0
    }

    @Test
    fun `S003 RF-41 detener la reproduccion la termina al momento aunque espere la siguiente epoca`() {
        val session = ReceiverSession(scope, connector)
        val slow = listOf(File(folder.root, "lenta.nmea").apply { writeText(gga("101530.00") + gga("101539.00")) })
        session.connect(ReplayTransport("lenta", slow, ReplaySpeed.X1, loop = false))
        session.await { it.gga != null }

        val started = System.nanoTime()
        session.disconnect()

        session.state.value shouldBe ReceiverSnapshot()
        ((System.nanoTime() - started) / 1_000_000) shouldBeLessThan 500L
    }
}
