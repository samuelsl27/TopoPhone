package es.topophone.app.ui.collector

import android.content.Context
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.app.log.RecordingLog
import es.topophone.app.ui.receiver.FakePreferences
import es.topophone.app.ui.receiver.nmea
import es.topophone.app.ui.stakeout.StakeObjective
import es.topophone.app.ui.stakeout.StakeObjectiveProvider
import es.topophone.app.ui.stakeout.StakeSaveInfo
import es.topophone.app.ui.stakeout.StakeTarget
import es.topophone.core.model.cad.CadSourceCrs
import es.topophone.core.model.point.CollectorSettings
import es.topophone.core.model.point.ElevationSource
import es.topophone.core.model.point.LineStakeoutMode
import es.topophone.core.model.point.Point
import es.topophone.core.model.point.PointCoordinates
import es.topophone.core.model.project.LinearUnit
import es.topophone.core.model.project.ProjectDefaults
import es.topophone.core.model.surface.Surface
import es.topophone.core.model.surface.SurfaceExtent
import es.topophone.core.model.surface.SurfaceGuide
import es.topophone.core.model.surface.SurfaceHeight
import es.topophone.core.model.surface.SurfaceHeightSource
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
import es.topophone.receiver.transport.StreamTransport
import io.kotest.matchers.doubles.plusOrMinus
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
 * El recolector guardando un punto replanteado contra una superficie (S042/RF-28, RF-29, RF-32): el origen de la cota, la superficie,
 * su cota en la posición medida, el desplazamiento, la cota de diseño y la diferencia; y fuera de la superficie, solo su nombre.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class StakeoutMeasureSurfaceTest {

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

    private fun viewModel(): CollectorViewModel {
        val settings = CollectorSettings()
        collectorPreferences.settings.value = settings
        val vm = CollectorViewModel(
            projects,
            points,
            collectorPreferences,
            FakePreferences(),
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
        runBlocking { withTimeout(5_000) { session.state.first { it.connection is ConnectionState.Receiving && it.epoch != null } } }
        await(vm) { it.live != null && it.blocker == null }
    }

    private var next = 2

    private fun measureNormalPoint(vm: CollectorViewModel, name: String): Point {
        sendEpoch(next++)
        vm.measure()
        sendEpoch(next++)
        await(vm) { it.savedName == name }
        return runBlocking { points.points(projectId).first() }.single { it.name == name }
    }

    private val surfaceModel = Surface(
        "s1", projectId, "terreno", "obra.xml", 1_792_000_000_000L, CadSourceCrs.Project, LinearUnit.METRE,
        SurfaceExtent(0.0, 1.0e7, 0.0, 1.0e7), 90.0, 120.0, 10, 8, 0,
    )

    private val queried = mutableListOf<Pair<Double, Double>>()

    private fun guide(height: SurfaceHeight = SurfaceHeight.Value(100.0), offset: Double = 0.3) = SurfaceGuide(
        surfaceModel,
        SurfaceHeightSource { e, n ->
            queried += e to n
            height
        },
        offset,
    )

    private fun stake(vm: CollectorViewModel, design: PointCoordinates, info: StakeSaveInfo, provider: StakeObjectiveProvider? = null) {
        vm.setStakeTarget(StakeTarget("design-1", "12", "", design))
        vm.setStakeSaveInfo(info)
        vm.setStakeObjective(provider)
        await(vm) { it.name == "12_stkd" }
    }

    private fun measureStaked(vm: CollectorViewModel, name: String = "12_stkd"): Point {
        vm.measure()
        sendEpoch(next++)
        await(vm) { it.savedName == name }
        return runBlocking { points.points(projectId).first() }.single { it.name == name }
    }

    private fun info(source: ElevationSource?, guide: SurfaceGuide?, elevationM: Double? = null) =
        StakeSaveInfo(elevationM, toleranceHM = 0.05, toleranceVM = 0.03, elevationSource = source, surface = guide)

    @Test
    fun `S042 RF-28 contra la superficie se guarda el origen la superficie su cota en lo medido el desplazamiento y la diferencia`() {
        val vm = viewModel()
        receiving(vm)
        val design = measureNormalPoint(vm, "1").coordinates
        val g = guide(offset = 0.3)
        stake(vm, design, info(ElevationSource.SURFACE, g))

        val point = measureStaked(vm)

        val ref = point.staked.shouldNotBeNull()
        ref.elevationSource shouldBe ElevationSource.SURFACE
        ref.surfaceName shouldBe "terreno"
        ref.surfaceImportedAtMs shouldBe 1_792_000_000_000L
        ref.surfaceElevationM shouldBe 100.0
        ref.verticalOffsetM shouldBe 0.3
        ref.designElevationM shouldBe (100.3 plusOrMinus 1e-12)
        ref.dHeightM.shouldNotBeNull() shouldBe (point.coordinates.height!! - 100.3 plusOrMinus 1e-9)
        ref.usedSurface shouldBe true
        ref.outsideSurface shouldBe false
    }

    @Test
    fun `S042 RF-28 la cota de la superficie se toma en la posicion medida`() {
        val vm = viewModel()
        receiving(vm)
        val design = measureNormalPoint(vm, "1").coordinates
        stake(vm, design, info(ElevationSource.SURFACE, guide()))

        val point = measureStaked(vm)

        val measured = point.coordinates
        queried.any { (e, n) -> e == measured.easting && n == measured.northing } shouldBe true
    }

    @Test
    fun `S042 RF-28 lo guardado de la superficie sobrevive en la base de datos sin redondear`() {
        val vm = viewModel()
        receiving(vm)
        val design = measureNormalPoint(vm, "1").coordinates
        stake(vm, design, info(ElevationSource.SURFACE, guide(SurfaceHeight.Value(601.2512345678), 0.1234567891)))

        measureStaked(vm)

        val read =
            runBlocking { points.point("${runBlocking { points.points(projectId).first() }.single { it.name == "12_stkd" }.id}").first() }
        val ref = read.shouldNotBeNull().staked.shouldNotBeNull()
        ref.surfaceElevationM shouldBe 601.2512345678
        ref.verticalOffsetM shouldBe 0.1234567891
    }

    @Test
    fun `S042 RF-29 fuera de la superficie se guarda sin cota ni diferencia vertical y solo con su nombre`() {
        val vm = viewModel()
        receiving(vm)
        val design = measureNormalPoint(vm, "1").coordinates
        stake(vm, design, info(ElevationSource.SURFACE, guide(SurfaceHeight.Outside), elevationM = 648.0))

        val ref = measureStaked(vm).staked.shouldNotBeNull()

        ref.elevationSource.shouldBeNull()
        ref.surfaceElevationM.shouldBeNull()
        ref.designElevationM.shouldBeNull()
        ref.dHeightM.shouldBeNull()
        ref.surfaceName shouldBe "terreno"
        ref.verticalOffsetM shouldBe 0.3
        ref.outsideSurface shouldBe true
        ref.hasElevation shouldBe false
    }

    @Test
    fun `S042 RF-29 fuera de la superficie la tolerancia horizontal sigue decidiendo si cumple`() {
        val vm = viewModel()
        receiving(vm)
        val design = measureNormalPoint(vm, "1").coordinates
        stake(vm, design, info(ElevationSource.SURFACE, guide(SurfaceHeight.Outside)))

        val ref = measureStaked(vm).staked.shouldNotBeNull()

        ref.withinTolerance shouldBe true
    }

    @Test
    fun `S042 RF-28 si la tesela aun no estaba cargada no se afirma nada de la superficie ni se inventa una cota`() {
        val vm = viewModel()
        receiving(vm)
        val design = measureNormalPoint(vm, "1").coordinates
        stake(vm, design, info(ElevationSource.SURFACE, guide(SurfaceHeight.Loading), elevationM = 648.0))

        val ref = measureStaked(vm).staked.shouldNotBeNull()

        ref.elevationSource.shouldBeNull()
        ref.surfaceName.shouldBeNull()
        ref.surfaceElevationM.shouldBeNull()
        ref.dHeightM.shouldBeNull()
        ref.outsideSurface shouldBe false
    }

    @Test
    fun `S042 RF-28 con una superficie activa y la cota del punto como origen se guarda ese origen y nada mas de la superficie`() {
        val vm = viewModel()
        receiving(vm)
        val measured = measureNormalPoint(vm, "1").coordinates
        val design = measured.copy(height = measured.height!! + 0.005)
        stake(vm, design, info(ElevationSource.POINT, guide(), elevationM = design.height))

        val ref = measureStaked(vm).staked.shouldNotBeNull()

        ref.elevationSource shouldBe ElevationSource.POINT
        ref.surfaceName.shouldBeNull()
        ref.surfaceElevationM.shouldBeNull()
        ref.verticalOffsetM.shouldBeNull()
        ref.usedSurface shouldBe false
        ref.designElevationM.shouldNotBeNull() shouldBe (design.height!! plusOrMinus 1e-9)
        ref.dHeightM.shouldNotBeNull()
    }

    @Test
    fun `S042 RF-28 sin superficie no se guarda nada de ella y el replanteo es como el de antes`() {
        val vm = viewModel()
        receiving(vm)
        val design = measureNormalPoint(vm, "1").coordinates
        stake(vm, design, info(null, null, elevationM = design.height))

        val ref = measureStaked(vm).staked.shouldNotBeNull()

        ref.elevationSource.shouldBeNull()
        ref.surfaceName.shouldBeNull()
        ref.surfaceImportedAtMs.shouldBeNull()
        ref.surfaceElevationM.shouldBeNull()
        ref.verticalOffsetM.shouldBeNull()
    }

    @Test
    fun `S042 RF-28 con una linea contra la superficie la cota del objetivo se cambia por la de la superficie`() {
        val vm = viewModel()
        receiving(vm)
        val measured = measureNormalPoint(vm, "1").coordinates
        val objective = StakeObjective(
            design = measured.copy(height = measured.height!! + 5.0),
            elevationM = measured.height!! + 5.0,
            mode = LineStakeoutMode.ALONG,
            chainageM = 45.25,
            lateralOffsetM = 1.5,
            lateralErrorOf = { 0.012 },
        )
        stake(vm, measured, info(ElevationSource.SURFACE, guide(offset = 0.3)), StakeObjectiveProvider { objective })

        val ref = measureStaked(vm).staked.shouldNotBeNull()

        ref.designElevationM.shouldNotBeNull() shouldBe (100.3 plusOrMinus 1e-12)
        ref.surfaceElevationM shouldBe 100.0
        ref.mode shouldBe LineStakeoutMode.ALONG
        ref.chainageM shouldBe 45.25
        ref.dLateralM shouldBe 0.012
    }
}
