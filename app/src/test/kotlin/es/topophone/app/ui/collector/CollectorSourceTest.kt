package es.topophone.app.ui.collector

import android.content.Context
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.app.log.RecordingLog
import es.topophone.core.gnss.nmea.ReplaySpeed
import es.topophone.core.gnss.scenario.ScenarioOrigin
import es.topophone.core.gnss.scenario.ScenarioPlayer
import es.topophone.core.gnss.scenario.Scenarios
import es.topophone.core.model.point.AdmittedSolutions
import es.topophone.core.model.point.CollectorSettings
import es.topophone.core.model.point.PositionSource
import es.topophone.core.model.point.PrecisionControl
import es.topophone.core.model.point.PrecisionFailure
import es.topophone.core.model.point.SolutionType
import es.topophone.core.model.project.ProjectDefaults
import es.topophone.core.ntrip.NtripConnector
import es.topophone.core.ntrip.NtripFailure
import es.topophone.core.survey.ProjectPositionCalculator
import es.topophone.data.db.TopoPhoneDatabase
import es.topophone.data.point.CollectorPreferences
import es.topophone.data.point.RoomPointRepository
import es.topophone.data.project.RoomProjectRepository
import es.topophone.data.project.SaveResult
import es.topophone.receiver.platform.Alerts
import es.topophone.receiver.session.ConnectionState
import es.topophone.receiver.session.ReceiverSession
import es.topophone.receiver.transport.LocationFix
import es.topophone.receiver.transport.LocationSource
import es.topophone.receiver.transport.PhoneGnssTransport
import es.topophone.receiver.transport.VirtualReceiverTransport
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
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
import org.junit.runner.RunWith

/**
 * El recolector con el GNSS del móvil y con el receptor virtual: la fuente se guarda con el punto, el virtual y la ubicación
 * simulada marcan el punto como simulado y con «solo FIX» el móvil no mide sin confirmar (S022/RF-7, RF-10, RF-11, RF-19).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class CollectorSourceTest {

    private val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), TopoPhoneDatabase::class.java)
        .allowMainThreadQueries()
        .build()
    private val projects = RoomProjectRepository(db)
    private val points = RoomPointRepository(db)
    private val collectorPreferences = FakeCollectorPreferences()
    private val sessionScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val session = ReceiverSession(
        sessionScope,
        NtripConnector { _, _, _, _ -> NtripFailure.UNREACHABLE },
        noDataTimeoutMs = 60_000,
        watchdogPeriodMs = 1_000,
    )
    private val log = RecordingLog()
    private val alerts = object : Alerts {
        override fun connectionLost() = Unit

        override fun correctionsLost() = Unit

        override fun pointSaved() = Unit

        override fun solutionLost() = Unit

        override fun tiltLost() = Unit
    }

    private class FakeCollectorPreferences : CollectorPreferences {
        override val settings = MutableStateFlow(CollectorSettings())

        override suspend fun save(settings: CollectorSettings) {
            this.settings.value = settings
        }
    }

    private var callback: ((LocationFix) -> Unit)? = null
    private val location = object : LocationSource {
        override fun hasPermission() = true

        override fun isEnabled() = true

        override fun start(onFix: (LocationFix) -> Unit) {
            callback = onFix
        }

        override fun stop() = Unit
    }
    private val viewModels = mutableListOf<CollectorViewModel>()
    private val t0 = 1_794_736_800_000L // 2026-11-15 10:00:00 UTC

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() {
        viewModels.forEach { it.viewModelScope.cancel() }
        session.disconnect()
        sessionScope.cancel()
        db.close()
        Dispatchers.resetMain()
    }

    private fun project(): String = runBlocking {
        (projects.create(ProjectDefaults().toDraft().copy(name = "Calle Mayor 12")) as SaveResult.Saved).id
    }

    private fun viewModel(settings: CollectorSettings, projectId: String): CollectorViewModel {
        collectorPreferences.settings.value = settings
        val vm = CollectorViewModel(
            projects,
            points,
            collectorPreferences,
            session,
            alerts,
            { ProjectPositionCalculator() },
            MeasurementInProgress(),
            log,
        )
        viewModels += vm
        vm.statsIntervalMs = 0
        vm.start(projectId)
        CoroutineScope(Dispatchers.Unconfined).launch { vm.state.collect {} }
        await(vm) { it.loaded && it.settings == settings }
        runBlocking { withTimeout(10_000) { while (!vm.isProjectLoaded) delay(10) } }
        return vm
    }

    private fun await(vm: CollectorViewModel, predicate: (CollectorUiState) -> Boolean): CollectorUiState = runBlocking {
        try {
            withTimeout(10_000) { vm.state.first(predicate) }
        } catch (e: TimeoutCancellationException) {
            throw AssertionError("El recolector no llegó al estado esperado; se quedó en ${vm.state.value}; log ${log.messages}", e)
        }
    }

    private fun startMeasuring(vm: CollectorViewModel) = runBlocking {
        withTimeout(10_000) {
            while (!vm.state.value.measuring) {
                vm.measure()
                delay(50)
            }
        }
    }

    private fun stored(projectId: String) = runBlocking { points.points(projectId).first() }

    private fun emit(second: Int, mock: Boolean = false) {
        callback!!.invoke(LocationFix(40.41668, -3.70371, 701.357, 3.0, null, t0 + second * 1_000L, mock = mock))
    }

    /** El móvil como receptor: dos posiciones y el recolector listo para medir. */
    private fun phoneReceiving(vm: CollectorViewModel, mock: Boolean = false) {
        session.connect(PhoneGnssTransport("GNSS del móvil", location))
        runBlocking { withTimeout(10_000) { while (callback == null) delay(10) } }
        emit(0, mock)
        emit(1, mock)
        runBlocking { withTimeout(5_000) { session.state.first { it.connection is ConnectionState.Receiving && it.epoch != null } } }
        await(vm) { it.live != null && it.blocker == null }
    }

    @Test
    fun `S022 RF-10 con solo FIX el movil no mide sin confirmar porque siempre es SINGLE`() {
        val projectId = project()
        val vm = viewModel(CollectorSettings(), projectId)
        phoneReceiving(vm)

        emit(2)
        awaitEpoch(session, vm, 36_001_000)
        startMeasuring(vm)
        emit(3)

        val pending = await(vm) { it.pending != null && !it.measuring }.pending.shouldNotBeNull()
        pending.failures shouldBe setOf(PrecisionFailure.NOT_FIX)
        stored(projectId).shouldBeEmpty()
    }

    @Test
    fun `S022 RF-11 confirmado el punto del movil se guarda como real con su fuente y su precision`() {
        val projectId = project()
        val vm = viewModel(CollectorSettings(), projectId)
        phoneReceiving(vm)
        emit(2)
        awaitEpoch(session, vm, 36_001_000)
        startMeasuring(vm)
        emit(3)
        await(vm) { it.pending != null && !it.measuring }

        vm.confirmOutOfTolerance()
        await(vm) { it.savedName == "1" }

        val point = stored(projectId).single().measurement.shouldNotBeNull()
        point.quality.source shouldBe PositionSource.PHONE
        point.quality.fromReplay shouldBe false
        point.quality.solution shouldBe "SINGLE"
        point.quality.horizontalM.shouldNotBeNull()
        point.quality.hdop.shouldBeNull()
        point.quality.correctionAgeS.shouldBeNull()
        point.outOfTolerance shouldBe setOf(PrecisionFailure.NOT_FIX)
    }

    @Test
    fun `S022 RF-10 con SINGLE admitido el movil mide sin pedir confirmacion y guarda su fuente`() {
        val projectId = project()
        val admitted = AdmittedSolutions.of(setOf(SolutionType.FIX, SolutionType.FLOAT, SolutionType.SINGLE))
        val vm = viewModel(CollectorSettings(precision = PrecisionControl(admitted = admitted)), projectId)
        phoneReceiving(vm)
        emit(2)
        awaitEpoch(session, vm, 36_001_000)
        startMeasuring(vm)
        emit(3)
        await(vm) { it.savedName == "1" }

        val point = stored(projectId).single().measurement.shouldNotBeNull()
        point.quality.source shouldBe PositionSource.PHONE
        point.outOfTolerance.shouldBeEmpty()
    }

    @Test
    fun `S022 RF-7 con una ubicacion simulada por otra app el punto se guarda marcado como simulado`() {
        val projectId = project()
        val admitted = AdmittedSolutions.of(setOf(SolutionType.FIX, SolutionType.FLOAT, SolutionType.SINGLE))
        val vm = viewModel(CollectorSettings(precision = PrecisionControl(admitted = admitted)), projectId)
        phoneReceiving(vm, mock = true)
        emit(2, mock = true)
        awaitEpoch(session, vm, 36_001_000)
        startMeasuring(vm)
        emit(3, mock = true)
        await(vm) { it.savedName == "1" }

        val point = stored(projectId).single().measurement.shouldNotBeNull()
        point.quality.source shouldBe PositionSource.PHONE_MOCK
        point.quality.fromReplay shouldBe true
    }

    @Test
    fun `S022 RF-19 un punto del receptor virtual se guarda sin confirmacion y marcado como simulado`() {
        val projectId = project()
        val vm = viewModel(CollectorSettings(), projectId)
        val player = ScenarioPlayer(Scenarios.stableFix, ScenarioOrigin(40.41668, -3.70371, 701.357), t0)
        session.connect(VirtualReceiverTransport("Receptor virtual", player, ReplaySpeed.X5))
        await(vm) { it.live != null && it.blocker == null }

        startMeasuring(vm)
        await(vm) { it.savedName == "1" }

        val point = stored(projectId).single().measurement.shouldNotBeNull()
        point.quality.source shouldBe PositionSource.VIRTUAL
        point.quality.fromReplay shouldBe true
        point.quality.solution shouldBe "FIX"
        point.outOfTolerance.shouldBeEmpty()
    }
}
