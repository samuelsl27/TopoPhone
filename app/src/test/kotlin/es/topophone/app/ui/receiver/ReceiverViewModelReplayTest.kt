package es.topophone.app.ui.receiver

import es.topophone.app.receiver.ReceiverConnections
import es.topophone.core.gnss.nmea.NmeaChecksum
import es.topophone.core.gnss.nmea.ReplaySpeed
import es.topophone.core.ntrip.NtripConnector
import es.topophone.core.ntrip.NtripFailure
import es.topophone.core.survey.GroundPointCalculator
import es.topophone.receiver.session.ConnectionState
import es.topophone.receiver.session.ReceiverSession
import es.topophone.receiver.session.ReplayController
import es.topophone.receiver.session.SessionLogStore
import es.topophone.receiver.transport.StreamTransport
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import java.io.ByteArrayOutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.time.Instant
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
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Reproducir desde la app y sus controles (S003/RF-32, RF-37, RF-40 a RF-43). */
@OptIn(ExperimentalCoroutinesApi::class)
class ReceiverViewModelReplayTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val sessionScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val session = ReceiverSession(sessionScope, NtripConnector { _, _, _, _ -> NtripFailure.UNREACHABLE })
    private val store by lazy {
        SessionLogStore(folder.root, clock = { Instant.parse("2026-10-15T08:30:00Z") }, freeSpaceBytes = { Long.MAX_VALUE })
    }
    private val replay by lazy { ReplayController(session) { store.sessions() } }
    private val bluetooth = FakeBluetooth()
    private val toApp = PipedOutputStream()

    private fun gga(time: String): String {
        val body = "GNGGA,$time,4025.0080000,N,00342.2228000,W,4,24,0.6,650.123,M,51.234,M,1.0,0001"
        return "$" + body + "*" + "%02X".format(NmeaChecksum.compute(body)) + "\r\n"
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        bluetooth.transports[RS3_DEVICE.address] =
            StreamTransport(RS3_DEVICE.name, PipedInputStream(toApp, 64 * 1024), ByteArrayOutputStream())
        store.start("reach-RS3")!!.apply {
            val bytes = (gga("101530.00") + gga("101531.00") + gga("101532.00")).toByteArray()
            write(bytes, 0, bytes.size)
            close()
        }
    }

    @After
    fun tearDown() {
        sessionScope.cancel()
        Dispatchers.resetMain()
    }

    private fun viewModel() = ReceiverViewModel(
        session = session,
        bluetooth = bluetooth,
        preferences = FakePreferences(),
        calculator = { GroundPointCalculator() },
        network = { true },
        alerts = NoAlerts,
        clock = { System.nanoTime() / 1_000_000 },
        samplePeriodMs = 0,
        replay = replay,
        connections = ReceiverConnections(session, bluetooth, FakePreferences(), CoroutineScope(Dispatchers.Default)),
    )

    private fun ReceiverViewModel.awaitState(predicate: (ReceiverUiState) -> Boolean): ReceiverUiState = runBlocking {
        val collector = CoroutineScope(Dispatchers.Unconfined).launch { uiState.collect {} }
        try {
            withTimeout(10_000) { uiState.first(predicate) }
        } finally {
            collector.cancel()
        }
    }

    @Test
    fun `S003 RF-32 reproducir una sesion la usa como receptor y S003 RF-37 su velocidad se cambia`() {
        val vm = viewModel()

        replay.play(store.latest()!!.id) shouldBe true
        vm.awaitState { it.snapshot.connection is ConnectionState.Receiving }.snapshot.replay.shouldNotBeNull().speed shouldBe
            ReplaySpeed.X1
        vm.setReplaySpeed(ReplaySpeed.X5)
        vm.setReplayLoop(true)

        // La velocidad y el bucle se piden por separado: se espera a que estén los dos.
        vm.awaitState { it.snapshot.replay?.speed == ReplaySpeed.X5 && it.snapshot.replay?.loop == true }
            .snapshot.replay.shouldNotBeNull().loop shouldBe true
    }

    @Test
    fun `S003 RF-41 Detener termina la reproduccion`() {
        val vm = viewModel()
        replay.play(store.latest()!!.id)
        vm.awaitState { it.snapshot.replay != null }

        vm.stopReplay()

        vm.awaitState { it.snapshot.replay == null }.snapshot.connection shouldBe ConnectionState.Disconnected
    }

    @Test
    fun `S003 RF-40 al terminar se puede volver a reproducir`() {
        val vm = viewModel()
        replay.play(store.latest()!!.id)
        session.setReplaySpeed(ReplaySpeed.X5)
        vm.awaitState { it.snapshot.connection is ConnectionState.ReplayFinished }

        vm.replayAgain()

        vm.awaitState { it.snapshot.connection is ConnectionState.Receiving && it.snapshot.replay != null }
    }

    @Test
    fun `S003 RF-42 con un receptor conectado hay que confirmar para reproducir`() {
        val vm = viewModel()
        replay.receiverConnected shouldBe false

        vm.connect(RS3_DEVICE.address)
        toApp.write(gga("101530.00").toByteArray())
        toApp.flush()
        vm.awaitState { it.snapshot.connection is ConnectionState.Receiving }

        replay.receiverConnected shouldBe true
    }

    @Test
    fun `S003 RF-43 conectar un receptor durante la reproduccion la termina`() {
        val vm = viewModel()
        replay.play(store.latest()!!.id)
        vm.awaitState { it.snapshot.replay != null }

        vm.connect(RS3_DEVICE.address)

        val state = vm.awaitState { it.snapshot.replay == null }
        state.snapshot.connection shouldBe ConnectionState.Connecting(RS3_DEVICE.name)
    }
}
