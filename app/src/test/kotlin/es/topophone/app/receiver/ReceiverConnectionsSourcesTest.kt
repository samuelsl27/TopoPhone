package es.topophone.app.receiver

import es.topophone.app.ui.receiver.FakeBluetooth
import es.topophone.app.ui.receiver.FakePreferences
import es.topophone.core.gnss.nmea.ReplaySpeed
import es.topophone.core.gnss.nmea.SolutionStatus
import es.topophone.core.gnss.scenario.Scenario
import es.topophone.core.gnss.scenario.ScenarioId
import es.topophone.core.gnss.scenario.ScenarioOrigin
import es.topophone.core.gnss.scenario.ScenarioState
import es.topophone.core.gnss.scenario.Scenarios
import es.topophone.core.model.point.PositionSource
import es.topophone.core.model.receiver.ReceiverKey
import es.topophone.core.ntrip.NtripConnector
import es.topophone.core.ntrip.NtripFailure
import es.topophone.data.receiver.SavedReceiver
import es.topophone.receiver.session.ConnectionState
import es.topophone.receiver.session.ReceiverSession
import es.topophone.receiver.session.ReceiverSnapshot
import es.topophone.receiver.transport.LocationFix
import es.topophone.receiver.transport.LocationSource
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Test

/** Conectar con el GNSS del móvil y con el receptor virtual por sus claves `phone` y `virtual` (S022/RF-1, RF-12, RF-17). */
class ReceiverConnectionsSourcesTest {

    private class FakeLocation : LocationSource {
        @Volatile var started = false
        val stops = AtomicInteger()
        private var callback: ((LocationFix) -> Unit)? = null

        override fun hasPermission() = true

        override fun isEnabled() = true

        override fun start(onFix: (LocationFix) -> Unit) {
            callback = onFix
            started = true
        }

        override fun stop() {
            stops.incrementAndGet()
        }

        fun emit(timeMs: Long) = callback!!.invoke(LocationFix(43.2630126, -2.9349852, 40.0, 3.0, null, timeMs, mock = false))
    }

    private object Labels : SourceLabels {
        override fun phone() = "Movil"

        override fun virtual() = "Virtual"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val session = ReceiverSession(scope, NtripConnector { _, _, _, _ -> NtripFailure.CONNECTION_LOST })
    private val bluetooth = FakeBluetooth()
    private val preferences = FakePreferences()
    private val location = FakeLocation()
    private val connectingCalls = AtomicInteger()
    private val startMs = 1_794_736_800_000L // 2026-11-15 10:00:00 UTC
    private val origin = ScenarioOrigin(43.2630126, -2.9349852, 40.0)

    private val connections = ReceiverConnections(
        session,
        bluetooth,
        preferences,
        scope,
        onConnecting = { connectingCalls.incrementAndGet() },
        locationSource = location,
        labels = Labels,
        utcNowMs = { startMs },
    )

    /** Un escenario de tres segundos para ver su final sin esperar. */
    private val short = Scenario(ScenarioId.STABLE_FIX, durationS = 3) {
        ScenarioState(0.0, 0.0, SolutionStatus.FIX, 20, 0.5, 0.008, 0.007, 0.015, 1.0)
    }

    @After
    fun tearDown() = scope.cancel()

    private fun await(predicate: (ReceiverSnapshot) -> Boolean) = runBlocking { withTimeout(10_000) { session.state.first(predicate) } }

    private fun awaitStarted() = runBlocking { withTimeout(10_000) { while (!location.started) delay(10) } }

    @Test
    fun `S022 RF-1 conectar con la clave phone abre el GNSS del movil como fuente PHONE y recibe SINGLE`() {
        connections.connect(ReceiverKey.PHONE)
        awaitStarted()

        location.emit(startMs)
        location.emit(startMs + 1_000)
        val snapshot = await { it.epoch != null }

        snapshot.source shouldBe PositionSource.PHONE
        snapshot.gga.shouldNotBeNull().status shouldBe SolutionStatus.SINGLE
        (snapshot.connection as ConnectionState.Receiving).description shouldBe "Movil"
        connections.currentAddress.value shouldBe ReceiverKey.PHONE
        connectingCalls.get() shouldBe 1
    }

    @Test
    fun `S022 RF-4 desconectar el movil para la lectura de ubicacion`() {
        connections.connectPhone()
        awaitStarted()

        session.disconnect()

        location.stops.get() shouldBe 1
        session.state.value.connection shouldBe ConnectionState.Disconnected
    }

    @Test
    fun `S022 RF-12 conectar con el virtual arranca el escenario con la hora UTC del movil`() {
        connections.connectVirtual(VirtualSelection(Scenarios.stableFix, origin, ReplaySpeed.X5))

        val snapshot = await { it.epoch != null }

        snapshot.source shouldBe PositionSource.VIRTUAL
        snapshot.scenario.shouldNotBeNull().id shouldBe ScenarioId.STABLE_FIX
        snapshot.scenario.shouldNotBeNull().speed shouldBe ReplaySpeed.X5
        snapshot.gga.shouldNotBeNull().utcTime.startsWith("1000") shouldBe true // las 10:00:0x: el reloj del móvil al conectar
        (snapshot.connection as ConnectionState.Receiving).description shouldBe "Virtual"
        connections.currentAddress.value shouldBe ReceiverKey.VIRTUAL
    }

    @Test
    fun `S022 RF-17 al acabar el escenario repetirlo lo arranca de nuevo y elegir otro cambia el escenario`() {
        val selection = VirtualSelection(short, origin, ReplaySpeed.X5)
        connections.connectVirtual(selection)
        await { it.connection is ConnectionState.ScenarioFinished }

        connections.repeatVirtual()

        await { it.connection is ConnectionState.Connecting || it.connection is ConnectionState.Receiving }
        await { it.connection is ConnectionState.ScenarioFinished } // vuelve a terminar: se repitió entero
        connections.connectVirtual(VirtualSelection(Scenarios.straightWalk, origin, ReplaySpeed.X5))
        await { it.scenario?.id == ScenarioId.STRAIGHT_WALK && it.epoch != null }
    }

    @Test
    fun `S022 RF-13 conectar con la clave virtual sin un escenario elegido no conecta`() {
        connections.connect(ReceiverKey.VIRTUAL)

        Thread.sleep(150)

        session.state.value.connection shouldBe ConnectionState.Disconnected
        connectingCalls.get() shouldBe 0
    }

    @Test
    fun `S022 RF-1 sin acceso a la ubicacion del sistema el movil no conecta`() {
        val bare = ReceiverConnections(session, bluetooth, preferences, scope)

        bare.connectPhone()

        session.state.value.connection shouldBe ConnectionState.Disconnected
    }

    @Test
    fun `S022 plan D15 ni el movil ni el virtual se guardan como ultimo receptor`() {
        connections.connectVirtual(VirtualSelection(Scenarios.stableFix, origin, ReplaySpeed.X5))
        await { it.connection is ConnectionState.Receiving }
        Thread.sleep(300)
        preferences.lastReceiverFlow.value.shouldBeNull()

        connections.connectPhone()
        awaitStarted()
        location.emit(startMs)
        location.emit(startMs + 1_000)
        await { it.connection is ConnectionState.Receiving && it.source == PositionSource.PHONE }
        Thread.sleep(300)
        preferences.lastReceiverFlow.value.shouldBeNull()
    }

    @Test
    fun `S022 plan D15 la conexion automatica no reconecta el movil ni el virtual aunque estuvieran guardados`() {
        for (key in listOf(ReceiverKey.PHONE, ReceiverKey.VIRTUAL)) {
            preferences.lastReceiverFlow.value = SavedReceiver(key, "x")
            val fresh = ReceiverConnections(session, bluetooth, preferences, scope, locationSource = location, labels = Labels)

            runBlocking { fresh.autoConnect() } shouldBe false
        }
        session.state.value.connection shouldBe ConnectionState.Disconnected
    }
}
