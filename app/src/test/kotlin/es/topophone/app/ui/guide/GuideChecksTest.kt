package es.topophone.app.ui.guide

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.app.receiver.CheckRunner
import es.topophone.app.receiver.CurrentTilt
import es.topophone.app.receiver.ProjectDatumSource
import es.topophone.app.receiver.ReceiverCheckRunner
import es.topophone.app.receiver.ReceiverWindowRecorder
import es.topophone.app.ui.receiver.nmea
import es.topophone.core.gnss.nmea.ReplaySpeed
import es.topophone.core.gnss.scenario.ScenarioOrigin
import es.topophone.core.gnss.scenario.ScenarioPlayer
import es.topophone.core.gnss.scenario.Scenarios
import es.topophone.core.model.guide.CheckCause
import es.topophone.core.model.guide.CheckId
import es.topophone.core.model.guide.CheckStatus
import es.topophone.core.model.guide.ConfigListId
import es.topophone.core.model.guide.ConfigLists
import es.topophone.core.model.guide.ReceiverCheck
import es.topophone.core.model.receiver.ReceiverKey
import es.topophone.core.ntrip.NtripConnector
import es.topophone.core.ntrip.NtripFailure
import es.topophone.data.config.RoomConfigProgressRepository
import es.topophone.data.db.TopoPhoneDatabase
import es.topophone.data.receiver.ReceiverTiltSettings
import es.topophone.data.receiver.TiltPreferences
import es.topophone.receiver.session.ConnectionState
import es.topophone.receiver.session.ReceiverSession
import es.topophone.receiver.transport.StreamTransport
import es.topophone.receiver.transport.VirtualReceiverTransport
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.longs.shouldBeLessThan
import io.kotest.matchers.shouldBe
import java.io.ByteArrayOutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * «Comprobar»: las comprobaciones con una sesión real (el receptor virtual y un flujo de frases), el resultado por paso en la Guía y
 * lo que el ViewModel deja ver (S043/RF-5, RF-9, RF-10, RF-11).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class GuideChecksTest {

    private val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), TopoPhoneDatabase::class.java)
        .allowMainThreadQueries()
        .build()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val sessionScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val session = ReceiverSession(
        sessionScope,
        NtripConnector { _, _, _, _ -> NtripFailure.UNREACHABLE },
        noDataTimeoutMs = 600_000,
        watchdogPeriodMs = 1_000,
    )
    private val recorder = ReceiverWindowRecorder(session, CoroutineScope(SupervisorJob() + Dispatchers.IO))
    private val address = MutableStateFlow<String?>("AA:BB")
    private val tilt = CurrentTilt(
        MutableStateFlow(false),
        address,
        object : TiltPreferences {
            override fun settings(key: String): Flow<ReceiverTiltSettings> = flowOf(ReceiverTiltSettings.NONE)

            override suspend fun save(key: String, settings: ReceiverTiltSettings) = Unit
        },
        scope,
    )
    private val runner = ReceiverCheckRunner(recorder, session, tilt, ProjectDatumSource.NONE)
    private val toApp = PipedOutputStream()
    private val written = ByteArrayOutputStream()
    private val jobs = mutableListOf<Job>()

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() {
        jobs.forEach { it.cancel() }
        runCatching { toApp.close() }
        session.disconnect()
        sessionScope.cancel()
        scope.cancel()
        db.close()
        Dispatchers.resetMain()
    }

    private fun viewModel(check: CheckRunner = runner): GuideViewModel {
        val vm = GuideViewModel(RoomConfigProgressRepository(db), tilt, check)
        jobs += CoroutineScope(Dispatchers.Unconfined).launch { vm.state.collect {} }
        return vm
    }

    private fun GuideViewModel.await(predicate: (GuideUiState) -> Boolean): GuideUiState =
        runBlocking { withTimeout(10_000) { state.first(predicate) } }

    private fun virtualWindowFilled() {
        val player = ScenarioPlayer(Scenarios.stableFix, ScenarioOrigin(40.41668, -3.70371, 701.357), 1_794_736_800_000L)
        session.connect(VirtualReceiverTransport("Receptor virtual", player, ReplaySpeed.X5))
        runBlocking { withTimeout(30_000) { while (recorder.snapshot().epochs.size < 4) delay(10) } }
    }

    @Test
    fun `S043 RF-5 con el receptor virtual comprueba en menos de un segundo y da un resultado por comprobacion`() {
        virtualWindowFilled()

        val started = System.nanoTime()
        val checks = runBlocking { runner.run(ConfigListId.NTRIP_ROVER) }
        val elapsedMs = (System.nanoTime() - started) / 1_000_000

        elapsedMs shouldBeLessThan 1_000L
        checks.shouldNotBeEmpty()
        checks.map { it.id }.toSet().size shouldBe checks.size
        checks.first { it.id == CheckId.GGA_RATE }.status shouldBe CheckStatus.OK
        checks.first { it.id == CheckId.SOLUTION }.status shouldBe CheckStatus.OK
    }

    @Test
    fun `S043 RF-5 solo salen las comprobaciones de la lista pedida y el paso que las resuelve es el de esa lista`() {
        virtualWindowFilled()

        val wifi = runBlocking { runner.run(ConfigListId.WIFI_ROVER) }

        val allowed = ConfigLists.wifiRover.steps.flatMap { it.checks }.toSet()
        (wifi.map { it.id }.toSet() - allowed) shouldBe emptySet()
        wifi.filter { it.status != CheckStatus.OK }.all { it.stepId in ConfigLists.wifiRover.steps.map { s -> s.id } } shouldBe true
    }

    @Test
    fun `S043 RF-10 sin datos todas las comprobaciones quedan en Sin datos con la remision al diagnostico`() {
        val checks = runBlocking { runner.run(ConfigListId.NTRIP_ROVER) }

        checks.shouldNotBeEmpty()
        checks.all { it.status == CheckStatus.NO_DATA && CheckCause.NO_DATA in it.causes } shouldBe true
    }

    @Test
    fun `S043 RF-11 comprobar no escribe nada en el receptor`() {
        session.connect(StreamTransport("reach-RS3", PipedInputStream(toApp, 64 * 1024), written))
        for (second in 0..3) {
            val time = "1015%02d.00".format(30 + second)
            toApp.write(nmea("GNGGA,$time,4025.0080000,N,00342.2228000,W,4,24,0.6,650.123,M,51.234,M,1.0,0001").toByteArray())
            toApp.flush()
        }
        runBlocking { withTimeout(10_000) { session.state.first { it.connection is ConnectionState.Receiving } } }
        runBlocking { withTimeout(10_000) { while (recorder.snapshot().epochs.isEmpty()) delay(10) } }

        runBlocking { runner.run(null) }
        runBlocking { runner.run(ConfigListId.BASE) }

        written.size() shouldBe 0
    }

    @Test
    fun `S043 RF-5 el ViewModel guarda el resultado y da el peor estado de cada paso`() {
        val fake = CheckRunner { list ->
            listOf(
                ReceiverCheck(CheckId.GGA_RATE, CheckStatus.OK),
                ReceiverCheck(CheckId.SENTENCES, CheckStatus.WARNING, null, listOf(CheckCause.MISSING_GSV), "ntrip_messages"),
                ReceiverCheck(CheckId.SOLUTION, CheckStatus.FAIL, null, listOf(CheckCause.SOLUTION_NOT_FIX), "ntrip_fix"),
                ReceiverCheck(CheckId.UTC_TIME, CheckStatus.NO_DATA, null, listOf(CheckCause.NO_DATA)),
            ).also { list shouldBe ConfigListId.NTRIP_ROVER }
        }
        val vm = viewModel(fake)
        vm.await { it.applies }

        vm.check(ConfigListId.NTRIP_ROVER)

        val state = vm.await { it.results != null }
        state.checkedList shouldBe ConfigListId.NTRIP_ROVER
        state.stepStatus["ntrip_rate"] shouldBe CheckStatus.OK
        state.stepStatus["ntrip_messages"] shouldBe CheckStatus.WARNING
        state.stepStatus["ntrip_fix"] shouldBe CheckStatus.FAIL
        state.stepStatus["ntrip_time"] shouldBe CheckStatus.NO_DATA
        state.counts(ConfigLists.ntripRover).checked shouldBe 1
    }

    @Test
    fun `S043 RF-5 el resultado es del receptor con que se comprobo y no sale con otro`() {
        val vm = viewModel(CheckRunner { listOf(ReceiverCheck(CheckId.GGA_RATE, CheckStatus.OK)) })
        vm.await { it.applies }
        vm.check(ConfigListId.NTRIP_ROVER)
        vm.await { it.results != null }

        address.value = ReceiverKey.wifi("w1")

        vm.await { it.receiverKey == ReceiverKey.wifi("w1") && it.results == null }
    }

    @Test
    fun `S043 RF-22 y RF-23 se comprueba con un receptor real, una reproduccion y el virtual, no con el movil`() {
        val vm = viewModel()
        val cases = listOf(
            "AA:BB" to true,
            ReceiverKey.wifi("w1") to true,
            ReceiverKey.VIRTUAL to true,
            TiltPreferences.REPLAY to true,
            ReceiverKey.PHONE to false,
        )
        for ((key, expected) in cases) {
            address.value = key
            vm.await { it.receiverKey == key }.canCheck shouldBe expected
        }
    }

    @Test
    fun `S043 RF-23 con el virtual no hay listas pero Comprobar da todas las comprobaciones`() {
        virtualWindowFilled()
        address.value = ReceiverKey.VIRTUAL
        val vm = viewModel()
        vm.await { it.receiverKey == ReceiverKey.VIRTUAL }.applies shouldBe false

        vm.check(null)

        val state = vm.await { it.results != null }
        state.checkedList shouldBe null
        state.results!!.shouldNotBeEmpty()
    }

    @Test
    fun `S043 RF-22 con el movil no se comprueba nada`() {
        address.value = ReceiverKey.PHONE
        var called = false
        val vm = viewModel(
            CheckRunner {
                called = true
                emptyList()
            },
        )
        vm.await { it.receiverKey == ReceiverKey.PHONE }

        vm.check(null)

        vm.state.value.results shouldBe null
        called shouldBe false
    }
}
