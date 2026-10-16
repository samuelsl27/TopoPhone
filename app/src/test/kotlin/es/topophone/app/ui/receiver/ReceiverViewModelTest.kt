package es.topophone.app.ui.receiver

import es.topophone.app.receiver.ReceiverConnections
import es.topophone.core.ntrip.NtripConfig
import es.topophone.core.ntrip.NtripConnector
import es.topophone.core.ntrip.NtripFailure
import es.topophone.core.survey.GroundPointCalculator
import es.topophone.receiver.platform.Alerts
import es.topophone.receiver.session.ConnectionState
import es.topophone.receiver.session.NtripState
import es.topophone.receiver.session.ReceiverSession
import es.topophone.receiver.session.ReplayController
import es.topophone.receiver.transport.StreamTransport
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.io.ByteArrayOutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
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

/** ViewModel de la sección Receptor (S002/RF-1 a RF-4, RF-9, RF-19, RF-23, RF-26, RF-28, RF-30, RF-31). */
@OptIn(ExperimentalCoroutinesApi::class)
class ReceiverViewModelTest {

    private val sessionScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val toApp = PipedOutputStream()
    private val receiverInput = PipedInputStream(toApp, 64 * 1024)

    private val bluetooth = FakeBluetooth()
    private val calculator = GroundPointCalculator()
    private val preferences = FakePreferences()
    private var connectionLostAlerts = 0
    private val countingAlerts = object : Alerts {
        override fun connectionLost() {
            connectionLostAlerts++
        }

        override fun correctionsLost() = Unit
    }
    private var internet = true
    private var ntripConfigUsed: NtripConfig? = null
    private val connector = NtripConnector { config, _, _, _ ->
        ntripConfigUsed = config
        NtripFailure.UNREACHABLE
    }
    private val session = ReceiverSession(sessionScope, connector, noDataTimeoutMs = 60_000, watchdogPeriodMs = 1_000)

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        bluetooth.transports["00:11:22:33:44:55"] = StreamTransport("reach-RS3", receiverInput, ByteArrayOutputStream())
    }

    @After
    fun tearDown() {
        sessionScope.cancel()
        Dispatchers.resetMain()
    }

    private fun viewModel() = ReceiverViewModel(
        session = session,
        bluetooth = bluetooth,
        preferences = preferences,
        calculator = { calculator },
        network = { internet },
        alerts = countingAlerts,
        clock = { System.nanoTime() / 1_000_000 },
        samplePeriodMs = 0,
        replay = ReplayController(session) { emptyList() },
        connections = ReceiverConnections(session, bluetooth, preferences, CoroutineScope(Dispatchers.Default)),
    )

    private fun ReceiverViewModel.awaitState(predicate: (ReceiverUiState) -> Boolean): ReceiverUiState = runBlocking {
        val collector = CoroutineScope(Dispatchers.Unconfined).launch { uiState.collect {} }
        try {
            withTimeout(5_000) { uiState.first(predicate) }
        } finally {
            collector.cancel()
        }
    }

    @Test
    fun `S002 RF-2 sin permiso el estado pide el permiso y al concederlo se listan los emparejados`() {
        bluetooth.permission = false
        val vm = viewModel()
        vm.awaitState { true }.bluetooth shouldBe BluetoothUi.PermissionNeeded

        bluetooth.permission = true
        vm.refreshBluetooth()

        vm.awaitState { it.bluetooth is BluetoothUi.Ready }.bluetooth shouldBe BluetoothUi.Ready(bluetooth.bondedDevices())
    }

    @Test
    fun `S002 RF-4 con el Bluetooth apagado el estado lo indica`() {
        bluetooth.enabled = false

        viewModel().awaitState { true }.bluetooth shouldBe BluetoothUi.Disabled
    }

    @Test
    fun `S002 RF-1 S002 RF-5 conectar con un emparejado abre la sesion y calcula el punto del suelo`() {
        val vm = viewModel()

        vm.connect("00:11:22:33:44:55")
        toApp.write(nmea("GNGGA,101530.20,4025.0080000,N,00342.2228000,W,4,24,0.6,650.123,M,51.234,M,1.0,0001").toByteArray())
        toApp.flush()

        val state = vm.awaitState { it.groundPoint != null }
        state.snapshot.connection shouldBe ConnectionState.Receiving("reach-RS3")
        state.groundPoint.shouldNotBeNull().utm.shouldNotBeNull().zone shouldBe 30
    }

    @Test
    fun `S002 RF-9 S004 RF-13 al perder la conexion se avisa con vibracion y se empieza a reconectar`() {
        val vm = viewModel()
        vm.awaitState { true }
        vm.connect("00:11:22:33:44:55")
        toApp.write(nmea("GNGGA,101530.20,4025.0080000,N,00342.2228000,W,4,24,0.6,650.123,M,51.234,M,1.0,0001").toByteArray())
        vm.awaitState { it.snapshot.connection is ConnectionState.Receiving }

        toApp.close()

        // Desde S004 la tarjeta conecta con reconexión: el corte se indica como "Reconectando".
        vm.awaitState { it.snapshot.connection is ConnectionState.Reconnecting }
        runBlocking { withTimeout(2_000) { while (connectionLostAlerts == 0) kotlinx.coroutines.delay(20) } }
        connectionLostAlerts shouldBe 1
    }

    @Test
    fun `S002 RF-23 si el caster es inaccesible se comprueba si hay internet`() {
        internet = false
        val vm = viewModel()
        vm.connect("00:11:22:33:44:55")
        toApp.write(nmea("GNGGA,101530.20,4025.0080000,N,00342.2228000,W,4,24,0.6,650.123,M,51.234,M,1.0,0001").toByteArray())
        vm.awaitState { it.snapshot.connection is ConnectionState.Receiving }

        session.startNtrip(NtripConfig("caster.es", 2101, "VRS3", "", ""))

        // Los dos datos llegan por flujos distintos: se espera a ver ambos en el mismo estado. Mientras,
        // se reintenta (S007/RF-23).
        val ntrip = vm.awaitState { !it.internetAvailable && it.snapshot.ntrip is NtripState.Reconnecting }.snapshot.ntrip
        (ntrip as NtripState.Reconnecting).failure shouldBe NtripFailure.UNREACHABLE
    }

    @Test
    fun `S002 RF-30 S002 RF-31 la altura del jalon valida se guarda y la no valida se marca sin guardar`() {
        val vm = viewModel()
        vm.awaitState { true }

        vm.updatePoleHeight("2,05")
        runBlocking { withTimeout(2_000) { preferences.poleFlow.first { it == 2.05 } } }

        vm.updatePoleHeight("12")
        vm.awaitState { !it.poleHeightValid }.poleHeightText shouldBe "12"
        preferences.poleFlow.value shouldBe 2.05
    }

    @Test
    fun `S002 RF-30 al abrir la pantalla se recupera la altura del jalon guardada`() {
        preferences.poleFlow.value = 2.0

        val state = viewModel().awaitState { it.poleHeightText.replace(',', '.') == "2.000" }

        state.poleHeightText.replace(',', '.') shouldBe "2.000"
        state.snapshot.connection.shouldBeInstanceOf<ConnectionState.Disconnected>()
    }

    @Test
    fun `S004 RF-13 al empezar a reconectar se avisa con vibracion una sola vez`() {
        val reconnecting =
            ReceiverSession(sessionScope, connector, noDataTimeoutMs = 60_000, backoffMs = listOf(50), giveUpAfterMs = 60_000)
        val vm = ReceiverViewModel(
            session = reconnecting,
            bluetooth = bluetooth,
            preferences = preferences,
            calculator = { calculator },
            network = { internet },
            alerts = countingAlerts,
            clock = { System.nanoTime() / 1_000_000 },
            samplePeriodMs = 0,
            replay = ReplayController(reconnecting) { emptyList() },
            connections = ReceiverConnections(reconnecting, bluetooth, preferences, CoroutineScope(Dispatchers.Default)),
        )
        vm.awaitState { true }
        reconnecting.connect(bluetooth.transports.getValue("00:11:22:33:44:55")) { throw java.io.IOException("sin receptor") }
        toApp.write(nmea("GNGGA,101530.20,4025.0080000,N,00342.2228000,W,4,24,0.6,650.123,M,51.234,M,1.0,0001").toByteArray())
        vm.awaitState { it.snapshot.connection is ConnectionState.Receiving }

        toApp.close()

        vm.awaitState { (it.snapshot.connection as? ConnectionState.Reconnecting)?.attempt == 3 }
        connectionLostAlerts shouldBe 1
    }
}
