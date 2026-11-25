package es.topophone.app.ui.collector

import android.content.Context
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.app.log.RecordingLog
import es.topophone.app.ui.receiver.nmea
import es.topophone.app.ui.stakeout.StakeObjective
import es.topophone.app.ui.stakeout.StakeObjectiveProvider
import es.topophone.app.ui.stakeout.StakeSaveInfo
import es.topophone.app.ui.stakeout.StakeTarget
import es.topophone.core.model.alignment.slope.SlopeKind
import es.topophone.core.model.alignment.slope.SlopeSide
import es.topophone.core.model.alignment.slope.SlopeStakeInfo
import es.topophone.core.model.point.CollectorSettings
import es.topophone.core.model.point.Point
import es.topophone.core.model.point.PointCoordinates
import es.topophone.core.model.point.PointOrigin
import es.topophone.core.model.point.StakeSource
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
import es.topophone.receiver.session.ReceiverSession
import es.topophone.receiver.transport.StreamTransport
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import java.io.ByteArrayOutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
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
 * El recolector guardando un punto de talud replanteado (S051/RF-15): el tipo, el lado, la relación, la altura de talud, la diferencia de
 * cota con el talud de diseño y el desplazamiento de estaca, con el PK y el desplazamiento reales de lo medido. Con la `ReceiverSession`
 * real y las épocas de una en una.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class SlopeStakeMeasureTest {

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
        noDataTimeoutMs = 60_000, // las épocas se mandan a mano
        watchdogPeriodMs = 1_000,
    )
    private val toApp = PipedOutputStream()
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

    private val viewModels = mutableListOf<CollectorViewModel>()

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() {
        viewModels.forEach { it.viewModelScope.cancel() }
        runCatching { toApp.close() }
        session.disconnect()
        sessionScope.cancel()
        db.close()
        Dispatchers.resetMain()
    }

    private val projectId: String = runBlocking {
        (projects.create(ProjectDefaults().toDraft().copy(name = "Calle Mayor 12")) as SaveResult.Saved).id
    }

    private fun viewModel(settings: CollectorSettings = CollectorSettings()): CollectorViewModel {
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
        await(vm) { it.loaded && it.settings.mode == settings.mode && it.settings.averagingSeconds == settings.averagingSeconds }
        runBlocking { withTimeout(10_000) { while (!vm.isProjectLoaded) kotlinx.coroutines.delay(10) } }
        return vm
    }

    private fun await(vm: CollectorViewModel, predicate: (CollectorUiState) -> Boolean): CollectorUiState = runBlocking {
        try {
            withTimeout(10_000) { vm.state.first(predicate) }
        } catch (e: TimeoutCancellationException) {
            throw AssertionError("El recolector no llegó al estado esperado; se quedó en ${vm.state.value}; log ${log.messages}", e)
        }
    }

    private fun sendEpoch(second: Int) {
        val t = 36_930 + second
        val time = "%02d%02d%02d.00".format(t / 3_600, t / 60 % 60, t % 60)
        toApp.write(nmea("GNGGA,$time,4025.0080000,N,00342.2228000,W,4,24,0.6,650.123,M,51.234,M,1.0,0001").toByteArray())
        toApp.write(nmea("GNGST,$time,0.012,0.010,0.007,45.0,0.008,0.006,0.015").toByteArray())
        toApp.flush()
    }

    private fun receiving(vm: CollectorViewModel) {
        session.connect(StreamTransport("reach-RS3", PipedInputStream(toApp, 64 * 1024), ByteArrayOutputStream()))
        sendEpoch(0)
        sendEpoch(1)
        awaitReceiving(session, vm)
        await(vm) { it.live != null && it.blocker == null }
    }

    private var next = 2

    /** Mide y guarda un punto normal en la posición de las épocas, para sacar de él un diseño a una distancia conocida. */
    private fun measureNormalPoint(vm: CollectorViewModel, name: String): Point {
        sendEpoch(next++)
        vm.measure()
        sendEpoch(next++)
        await(vm) { it.savedName == name }
        return runBlocking { points.points(projectId).first() }.single { it.name == name }
    }

    private val source = StakeSource.Alignment("Eje C")

    private fun slopeTarget(design: PointCoordinates) = StakeTarget("slope:Eje C:105000:RIGHT", "Eje C", "", design, source)

    private val info = SlopeStakeInfo(SlopeKind.CUT, SlopeSide.RIGHT, 1.5, 1.0, -0.004, 0.5)

    private fun objectiveOf(measured: Point, slope: SlopeStakeInfo? = info) = measured.coordinates.let { c ->
        StakeObjective(
            design = PointCoordinates(c.ground, c.easting!! + 0.02, c.northing!! - 0.03, c.height!! + 0.005),
            elevationM = c.height!! + 0.005,
            mode = null,
            chainageM = 105.0,
            lateralOffsetM = 5.5,
            lateralErrorOf = { 0.012 },
            chainageErrorOf = { -0.034 },
            slopeOf = slope?.let { value -> { _: PointCoordinates -> value } },
        )
    }

    private fun stakeSlope(vm: CollectorViewModel, design: PointCoordinates, provider: StakeObjectiveProvider?) {
        vm.setStakeTarget(slopeTarget(design))
        vm.setStakeSaveInfo(StakeSaveInfo(null, toleranceHM = 0.05, toleranceVM = 0.03))
        vm.setStakeObjective(provider)
        await(vm) { it.name == "Eje C_1_stkd" }
    }

    private fun measureStaked(vm: CollectorViewModel): Point {
        vm.measure()
        sendEpoch(next++)
        await(vm) { it.savedName == "Eje C_1_stkd" }
        return runBlocking { points.points(projectId).first() }.single { it.name == "Eje C_1_stkd" }
    }

    @Test
    fun `S051 RF-15 un punto de talud se guarda con el tipo el lado la relacion la altura y la diferencia de cota`() {
        val vm = viewModel()
        receiving(vm)
        val measured = measureNormalPoint(vm, "1")
        stakeSlope(vm, measured.coordinates, StakeObjectiveProvider { objectiveOf(measured) })

        val point = measureStaked(vm)
        val ref = point.staked.shouldNotBeNull()

        point.origin shouldBe PointOrigin.STAKED
        ref.slope shouldBe info
        ref.chainageM shouldBe 105.0
        ref.lateralOffsetM shouldBe 5.5
        ref.dChainageM shouldBe -0.034
        ref.fromAlignment shouldBe true
    }

    @Test
    fun `S051 RF-15 el talud se lee de la base de datos tal como se guardo`() {
        val vm = viewModel()
        receiving(vm)
        val measured = measureNormalPoint(vm, "1")
        stakeSlope(vm, measured.coordinates, StakeObjectiveProvider { objectiveOf(measured) })

        val point = measureStaked(vm)

        runBlocking { points.point(point.id).first() }.shouldNotBeNull().staked.shouldNotBeNull().slope shouldBe info
    }

    @Test
    fun `S051 RF-15 sin diferencia de cota por no haber cota se guarda vacia y un replanteo de eje no lleva talud`() {
        val vm = viewModel()
        receiving(vm)
        val measured = measureNormalPoint(vm, "1")
        stakeSlope(vm, measured.coordinates, StakeObjectiveProvider { objectiveOf(measured, info.copy(dHeightM = null)) })

        measureStaked(vm).staked.shouldNotBeNull().slope.shouldNotBeNull().dHeightM.shouldBeNull()
    }

    @Test
    fun `S051 RF-15 un objetivo sin talud no guarda talud`() {
        val vm = viewModel()
        receiving(vm)
        val measured = measureNormalPoint(vm, "1")
        stakeSlope(vm, measured.coordinates, StakeObjectiveProvider { objectiveOf(measured, slope = null) })

        measureStaked(vm).staked.shouldNotBeNull().slope.shouldBeNull()
    }
}
