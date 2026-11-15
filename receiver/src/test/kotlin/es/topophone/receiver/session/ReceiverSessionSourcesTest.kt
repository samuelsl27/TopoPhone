package es.topophone.receiver.session

import es.topophone.core.gnss.nmea.ReplaySpeed
import es.topophone.core.gnss.nmea.SolutionStatus
import es.topophone.core.gnss.scenario.Scenario
import es.topophone.core.gnss.scenario.ScenarioId
import es.topophone.core.gnss.scenario.ScenarioOrigin
import es.topophone.core.gnss.scenario.ScenarioPlayer
import es.topophone.core.gnss.scenario.ScenarioState
import es.topophone.core.gnss.scenario.Scenarios
import es.topophone.core.model.point.PositionSource
import es.topophone.core.ntrip.NtripConfig
import es.topophone.core.ntrip.NtripConnector
import es.topophone.core.ntrip.NtripFailure
import es.topophone.receiver.transport.ReceiverTransport
import es.topophone.receiver.transport.VirtualReceiverTransport
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
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

/**
 * La sesión con el receptor virtual y con otras fuentes que no son un receptor: lleva la fuente y el escenario, no graba, no abre el
 * caster, avisa del fin del escenario y respeta el plazo de «Sin datos» de cada transporte (S022/RF-8, RF-9, RF-18, RF-20).
 */
class ReceiverSessionSourcesTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val ntripCalls = AtomicInteger()
    private val connector = NtripConnector { _, _, _, _ ->
        ntripCalls.incrementAndGet()
        NtripFailure.CONNECTION_LOST
    }
    private val origin = ScenarioOrigin(43.2630126, -2.9349852, 40.0)
    private val start = 1_794_736_800_000L

    @After
    fun tearDown() = scope.cancel()

    private fun ReceiverSession.await(predicate: (ReceiverSnapshot) -> Boolean): ReceiverSnapshot =
        runBlocking { withTimeout(10_000) { state.first(predicate) } }

    private fun virtual(scenario: Scenario = Scenarios.stableFix, speed: ReplaySpeed = ReplaySpeed.X5) =
        VirtualReceiverTransport("Receptor virtual", ScenarioPlayer(scenario, origin, start), speed)

    /** Un escenario de tres segundos, para ver el final sin esperar. */
    private val short = Scenario(ScenarioId.STABLE_FIX, durationS = 3) {
        ScenarioState(0.0, 0.0, SolutionStatus.FIX, 20, 0.5, 0.008, 0.007, 0.015, 1.0)
    }

    /** Una fuente que no da datos: la conexión se queda esperando y solo importa el plazo de «Sin datos». */
    private class SilentSource(
        override val positionSource: PositionSource,
        override val noDataTimeoutMs: Long?,
        override val recordsSession: Boolean = false,
        override val acceptsCorrections: Boolean = false,
    ) : ReceiverTransport {
        private val closed = CountDownLatch(1)
        override val description = "Fuente"
        override val simulated = false

        override fun open() = Unit

        override val input: InputStream = object : InputStream() {
            override fun read(): Int {
                closed.await()
                return -1
            }
        }
        override val output: OutputStream = object : OutputStream() {
            override fun write(b: Int) = Unit
        }

        override fun close() = closed.countDown()
    }

    @Test
    fun `S022 RF-16 con el receptor virtual la sesion lleva la fuente y el escenario en marcha`() {
        val session = ReceiverSession(scope, connector)
        session.connect(virtual(speed = ReplaySpeed.X5))

        val snapshot = session.await { (it.scenario?.elapsedS ?: -1) >= 1 }

        snapshot.source shouldBe PositionSource.VIRTUAL
        snapshot.scenario.shouldNotBeNull().id shouldBe ScenarioId.STABLE_FIX
        snapshot.scenario.shouldNotBeNull().speed shouldBe ReplaySpeed.X5
        snapshot.scenario.shouldNotBeNull().durationS shouldBe 120
        snapshot.replay shouldBe null
        snapshot.gga.shouldNotBeNull().status.name shouldBe "FIX"
    }

    @Test
    fun `S022 RF-16 la velocidad del escenario se cambia con la sesion en marcha`() {
        val session = ReceiverSession(scope, connector)
        val transport = virtual(speed = ReplaySpeed.X1)
        session.connect(transport)

        session.setScenarioSpeed(ReplaySpeed.X5)

        transport.speed shouldBe ReplaySpeed.X5
        session.state.value.scenario.shouldNotBeNull().speed shouldBe ReplaySpeed.X5
    }

    @Test
    fun `S022 RF-17 al acabar el escenario la conexion queda en escenario terminado con su ultima epoca`() {
        val session = ReceiverSession(scope, connector)

        session.connect(virtual(short))

        val finished = session.await { it.connection is ConnectionState.ScenarioFinished }
        finished.connection shouldBe ConnectionState.ScenarioFinished("Receptor virtual")
        finished.epoch.shouldNotBeNull().utcMillisOfDay shouldBe 10 * 3_600_000 + 2_000 // la de las 10:00:02
        finished.source shouldBe PositionSource.VIRTUAL
    }

    @Test
    fun `S022 RF-18 con el receptor virtual no se graba ninguna sesion`() {
        val store = SessionLogStore(File(folder.root, "sessions"), freeSpaceBytes = { Long.MAX_VALUE })
        val session = ReceiverSession(scope, connector, recorder = store)

        session.connect(virtual(short))

        session.await { it.connection is ConnectionState.ScenarioFinished }.recording shouldBe false
        store.sessions() shouldHaveSize 0
    }

    @Test
    fun `S022 RF-18 con el receptor virtual no se conecta al caster y se dice por que`() {
        val session = ReceiverSession(scope, connector)
        session.connect(virtual())
        session.await { it.connection is ConnectionState.Receiving }

        session.startNtrip(NtripConfig("caster.es", 2101, "VRS3", "topo", ""))

        session.state.value.ntrip shouldBe NtripState.UnavailableForSource(PositionSource.VIRTUAL)
        Thread.sleep(100)
        ntripCalls.get() shouldBe 0
    }

    @Test
    fun `S022 RF-9 con el GNSS del movil no se conecta al caster ni se habla de puerto de correcciones`() {
        val session = ReceiverSession(scope, connector)
        session.connect(SilentSource(PositionSource.PHONE, noDataTimeoutMs = 3_000))

        session.startNtrip(NtripConfig("caster.es", 2101, "VRS3", "topo", ""))

        session.state.value.ntrip shouldBe NtripState.UnavailableForSource(PositionSource.PHONE)
        session.state.value.source shouldBe PositionSource.PHONE
        ntripCalls.get() shouldBe 0
    }

    @Test
    fun `S022 RF-20 con el GNSS del movil no se graba aunque no sea una fuente simulada`() {
        val store = SessionLogStore(File(folder.root, "sessions"), freeSpaceBytes = { Long.MAX_VALUE })
        val session = ReceiverSession(scope, connector, recorder = store)

        session.connect(SilentSource(PositionSource.PHONE, noDataTimeoutMs = 3_000, recordsSession = false))
        Thread.sleep(150)

        session.state.value.recording shouldBe false
        store.sessions() shouldHaveSize 0
    }

    @Test
    fun `S022 RNF-T3 el plazo de Sin datos es el del transporte y a falta de el, el de la sesion`() {
        val now = AtomicLong(0)
        val withOwn = ReceiverSession(scope, connector, noDataTimeoutMs = 2_000, watchdogPeriodMs = 10, clock = { now.get() })
        withOwn.connect(SilentSource(PositionSource.PHONE, noDataTimeoutMs = 3_000))
        Thread.sleep(100)

        now.set(2_500) // más de los 2 s de la sesión, menos de los 3 s del móvil
        Thread.sleep(200)
        (withOwn.state.value.connection is ConnectionState.NoData) shouldBe false

        now.set(3_500)
        withOwn.await { it.connection is ConnectionState.NoData }

        now.set(0)
        val plain = ReceiverSession(scope, connector, noDataTimeoutMs = 2_000, watchdogPeriodMs = 10, clock = { now.get() })
        plain.connect(SilentSource(PositionSource.RECEIVER, noDataTimeoutMs = null))
        Thread.sleep(100)
        now.set(2_500)
        plain.await { it.connection is ConnectionState.NoData }
    }

    @Test
    fun `S022 RF-12 un receptor de siempre sigue dando fuente RECEIVER y ningun escenario`() {
        val session = ReceiverSession(scope, connector)

        session.connect(SilentSource(PositionSource.RECEIVER, noDataTimeoutMs = null, recordsSession = true, acceptsCorrections = true))

        session.state.value.source shouldBe PositionSource.RECEIVER
        session.state.value.scenario shouldBe null
    }
}
