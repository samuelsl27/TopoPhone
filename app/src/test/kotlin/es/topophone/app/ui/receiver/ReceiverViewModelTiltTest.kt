package es.topophone.app.ui.receiver

import es.topophone.app.receiver.MemoryTiltPreferences
import es.topophone.app.receiver.ReceiverConnections
import es.topophone.app.receiver.currentTilt
import es.topophone.core.model.point.GeodeticPosition
import es.topophone.core.model.point.PoleVector
import es.topophone.core.model.point.TiltMode
import es.topophone.core.ntrip.NtripConnector
import es.topophone.core.ntrip.NtripFailure
import es.topophone.core.survey.GroundPointCalculator
import es.topophone.core.survey.PoleTip
import es.topophone.data.receiver.ReceiverTiltSettings
import es.topophone.receiver.session.ReceiverSession
import es.topophone.receiver.session.ReplayController
import es.topophone.receiver.transport.StreamTransport
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import java.io.ByteArrayOutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import kotlin.math.cos
import kotlin.math.sin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Test

/** La punta del jalón en la pantalla Coordenadas según el modo del receptor (S016/RF-14, RF-16, RF-17). */
@OptIn(ExperimentalCoroutinesApi::class)
class ReceiverViewModelTiltTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val toApp = PipedOutputStream()
    private val bluetooth = FakeBluetooth()
    private val preferences = FakePreferences()
    private val session = ReceiverSession(scope, NtripConnector { _, _, _, _ -> NtripFailure.UNREACHABLE })
    private val rs3 = "00:11:22:33:44:55"

    /** La GGA de las épocas: 40° 25,008′ N, 3° 42,2228′ W, h = 650,123 + 51,234 m. */
    private val antenna = GeodeticPosition(40.0 + 25.008 / 60, -(3.0 + 42.2228 / 60), 650.123 + 51.234)

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        bluetooth.transports[rs3] = StreamTransport("reach-RS3", PipedInputStream(toApp, 64 * 1024), ByteArrayOutputStream())
    }

    @After
    fun tearDown() {
        scope.cancel()
        Dispatchers.resetMain()
    }

    private fun viewModel(mode: TiltMode?): ReceiverViewModel {
        val stored = if (mode == null) emptyMap() else mapOf(rs3 to ReceiverTiltSettings(mode, verified = true))
        val tilt = currentTilt(scope, MemoryTiltPreferences(stored), address = rs3)
        runBlocking { withTimeout(5_000) { tilt.state.first { it.key == rs3 } } }
        return ReceiverViewModel(
            session = session,
            bluetooth = bluetooth,
            preferences = preferences,
            calculator = { GroundPointCalculator() },
            network = { true },
            alerts = NoAlerts,
            clock = { System.nanoTime() / 1_000_000 },
            samplePeriodMs = 0,
            replay = ReplayController(session) { emptyList() },
            connections = ReceiverConnections(session, bluetooth, preferences, scope),
            tilt = tilt,
        )
    }

    private fun ReceiverViewModel.awaitState(predicate: (ReceiverUiState) -> Boolean): ReceiverUiState = runBlocking {
        val collector = CoroutineScope(Dispatchers.Unconfined).launch { uiState.collect {} }
        try {
            withTimeout(5_000) { uiState.first(predicate) }
        } finally {
            collector.cancel()
        }
    }

    /** Dos épocas con la misma GGA y [etc]; la segunda cierra la primera. */
    private fun send(etc: String) {
        listOf("101530.20", "101530.40").forEach { time ->
            toApp.write(nmea("GNGGA,$time,4025.0080000,N,00342.2228000,W,4,24,0.6,650.123,M,51.234,M,1.0,0001").toByteArray())
            toApp.write(nmea("GNETC,$time,$etc").toByteArray())
        }
        toApp.flush()
    }

    private fun ReceiverViewModel.awaitTip(): ReceiverUiState {
        connect(rs3)
        return awaitState { it.groundPoint != null && it.snapshot.epoch?.tilt != null }
    }

    @Test
    fun `S016 RF-16 en APP con estado 30 se muestra la punta calculada`() {
        val vm = viewModel(TiltMode.APP)
        send("30,0,123.40,210.50,15.25,1.10,1.20,0.30")

        val state = vm.awaitTip()

        val theta = Math.toRadians(15.25)
        val alpha = Math.toRadians(210.5)
        val pole = PoleVector(sin(theta) * sin(alpha), sin(theta) * cos(alpha), cos(theta))
        val tip = PoleTip.fromAntenna(antenna, pole, 1.8 + 0.134)
        val ground = state.groundPoint.shouldNotBeNull()
        ground.latitudeDeg shouldBe (tip.latitudeDeg plusOrMinus 1e-10)
        ground.longitudeDeg shouldBe (tip.longitudeDeg plusOrMinus 1e-10)
        ground.ellipsoidalHeightM.shouldNotBeNull() shouldBe (tip.ellipsoidalHeightM!! plusOrMinus 1e-6)
        state.tipUncompensated shouldBe false
    }

    @Test
    fun `S016 RF-17 en APP fuera del estado 30 la punta va sin compensar`() {
        val vm = viewModel(TiltMode.APP)
        send("20,0,,,,,,")

        val state = vm.awaitTip()

        state.groundPoint.shouldNotBeNull().latitudeDeg shouldBe (antenna.latitudeDeg plusOrMinus 1e-12)
        state.groundPoint!!.ellipsoidalHeightM.shouldNotBeNull() shouldBe ((antenna.ellipsoidalHeightM!! - 1.934) plusOrMinus 1e-6)
        state.tipUncompensated shouldBe true
    }

    @Test
    fun `S016 RF-14 en RECEIVER la GGA es la punta`() {
        val vm = viewModel(TiltMode.RECEIVER)
        send("30,0,123.40,210.50,15.25,1.10,1.20,0.30")

        val state = vm.awaitTip()

        state.groundPoint.shouldNotBeNull().ellipsoidalHeightM.shouldNotBeNull() shouldBe (antenna.ellipsoidalHeightM!! plusOrMinus 1e-6)
        state.tipUncompensated shouldBe false
    }

    @Test
    fun `S016 RF-16 sin modo se sigue con el jalon vertical`() {
        val vm = viewModel(null)
        send("30,0,123.40,210.50,15.25,1.10,1.20,0.30")

        val state = vm.awaitTip()

        state.groundPoint.shouldNotBeNull().latitudeDeg shouldBe (antenna.latitudeDeg plusOrMinus 1e-12)
        state.tipUncompensated shouldBe false
    }
}
