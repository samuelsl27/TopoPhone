package es.topophone.app.ui.collector

import android.content.Context
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.app.log.RecordingLog
import es.topophone.app.ui.receiver.nmea
import es.topophone.app.ui.stakeout.StakeObjectiveProvider
import es.topophone.app.ui.stakeout.StakeSaveInfo
import es.topophone.app.ui.stakeout.StakeTarget
import es.topophone.core.model.cad.CadSourceCrs
import es.topophone.core.model.point.CollectorSettings
import es.topophone.core.model.point.ElevationSource
import es.topophone.core.model.point.GeodeticPosition
import es.topophone.core.model.point.Point
import es.topophone.core.model.point.PointCoordinates
import es.topophone.core.model.point.StakeSource
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
import es.topophone.data.surface.SurfaceGuides
import es.topophone.receiver.platform.Alerts
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

/** El recolector replanteando la superficie solo en cota: SUP1, SUP2…, lo guardado y el bloqueo fuera de ella (S062/RF-14, RF-16 a RF-19). */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class SurfaceStakeCollectorTest {

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
            session,
            alerts,
            { ProjectPositionCalculator() },
            MeasurementInProgress(),
            log,
            surfaces = SurfaceGuides { activeGuide },
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

    /** La superficie activa que ve el recolector, para la cota bajo la punta y el bloqueo (S042/RF-33, S062/RF-14). */
    private val activeGuide = MutableStateFlow<SurfaceGuide?>(null)

    /** Abre el replanteo de la superficie en el recolector, como lo hace la pantalla con el replanteo abierto. */
    private fun stakeSurface(vm: CollectorViewModel, g: SurfaceGuide) {
        activeGuide.value = g
        vm.setStakeTarget(
            StakeTarget(
                "surface:s1",
                "terreno",
                "",
                PointCoordinates(GeodeticPosition(0.0, 0.0, null), null, null, null),
                StakeSource.Surface("terreno"),
            ),
        )
        vm.setStakeSaveInfo(info(ElevationSource.SURFACE, g))
        vm.setStakeObjective(null)
    }

    @Test
    fun `S062 RF-16 RF-19 los puntos se llaman SUP1 y SUP2 y se sigue en el replanteo`() {
        val vm = viewModel()
        receiving(vm)
        stakeSurface(vm, guide(offset = 0.3))
        await(vm) { it.name == "SUP1" }

        measureStaked(vm, "SUP1")
        await(vm) { it.name == "SUP2" }
        sendEpoch(next++)
        measureStaked(vm, "SUP2")

        runBlocking { points.points(projectId).first() }.map { it.name }.sorted() shouldBe listOf("SUP1", "SUP2")
    }

    @Test
    fun `S062 RF-17 RF-18 lo guardado es la cota de la superficie en lo medido sin planta y con el veredicto vertical`() {
        val vm = viewModel()
        receiving(vm)
        stakeSurface(vm, guide(SurfaceHeight.Value(100.0), offset = 0.3))
        await(vm) { it.name == "SUP1" }

        val point = measureStaked(vm, "SUP1")

        val ref = point.staked.shouldNotBeNull()
        ref.source shouldBe StakeSource.Surface("terreno")
        ref.designName shouldBe "terreno"
        ref.designId shouldBe point.id
        listOf(ref.dEastM, ref.dNorthM, ref.dHorizontalM) shouldBe listOf(0.0, 0.0, 0.0)
        ref.design.easting shouldBe point.coordinates.easting
        ref.design.northing shouldBe point.coordinates.northing
        ref.designElevationM.shouldNotBeNull() shouldBe (100.3 plusOrMinus 1e-12)
        ref.dHeightM.shouldNotBeNull() shouldBe (point.coordinates.height!! - 100.3 plusOrMinus 1e-9)
        ref.toleranceHM.shouldBeNull()
        ref.toleranceVM shouldBe 0.03
        ref.withinTolerance shouldBe false // la punta está a unos 550 m de la cota 100,3
        ref.surfaceElevationM shouldBe 100.0
        ref.verticalOffsetM shouldBe 0.3
        queried.any { (e, n) -> e == point.coordinates.easting && n == point.coordinates.northing } shouldBe true
    }

    @Test
    fun `S062 RF-14 con la punta fuera de la superficie no se puede medir`() {
        val vm = viewModel()
        receiving(vm)
        stakeSurface(vm, guide(SurfaceHeight.Outside))
        sendEpoch(next++)

        await(vm) { it.blocker == MeasureBlocker.OUTSIDE_SURFACE }
    }
}
