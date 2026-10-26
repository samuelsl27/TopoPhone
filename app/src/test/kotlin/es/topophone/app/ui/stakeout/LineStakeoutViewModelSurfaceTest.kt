package es.topophone.app.ui.stakeout

import android.content.Context
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.app.log.RecordingLog
import es.topophone.app.ui.cad.DesignSelection
import es.topophone.app.ui.collector.MeasurementInProgress
import es.topophone.app.ui.receiver.FakePreferences
import es.topophone.app.ui.receiver.nmea
import es.topophone.core.model.cad.CadColor
import es.topophone.core.model.cad.CadDrawing
import es.topophone.core.model.cad.CadEntity
import es.topophone.core.model.cad.CadEntityKind
import es.topophone.core.model.cad.CadEntityRef
import es.topophone.core.model.cad.CadExtent
import es.topophone.core.model.cad.CadSourceCrs
import es.topophone.core.model.cad.DesignLayer
import es.topophone.core.model.cad.DesignLayerKind
import es.topophone.core.model.point.CollectorSettings
import es.topophone.core.model.point.ElevationSource
import es.topophone.core.model.point.GeodeticPosition
import es.topophone.core.model.point.StakeoutSettings
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
import es.topophone.data.cad.RoomCadRepository
import es.topophone.data.db.TopoPhoneDatabase
import es.topophone.data.point.CollectorPreferences
import es.topophone.data.point.StakeoutPreferences
import es.topophone.data.project.RoomProjectRepository
import es.topophone.data.project.SaveResult
import es.topophone.data.surface.SurfaceGuides
import es.topophone.receiver.platform.Alerts
import es.topophone.receiver.session.ConnectionState
import es.topophone.receiver.session.ReceiverSession
import es.topophone.receiver.transport.StreamTransport
import io.kotest.matchers.doubles.plusOrMinus
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
 * El replanteo de una línea contra una superficie: qué cota manda, el conmutador, la cota a mano, la superficie que se retira y que
 * la guía lleve la cota de la superficie en la punta (S042/RF-23 a RF-27).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class LineStakeoutViewModelSurfaceTest {

    private val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), TopoPhoneDatabase::class.java)
        .allowMainThreadQueries()
        .build()
    private val projects = RoomProjectRepository(db)
    private val cad = RoomCadRepository(db)
    private val sessionScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val session = ReceiverSession(
        sessionScope,
        NtripConnector { _, _, _, _ -> NtripFailure.UNREACHABLE },
        noDataTimeoutMs = 60_000,
        watchdogPeriodMs = 1_000,
    )
    private val toApp = PipedOutputStream()
    private val inProgress = MeasurementInProgress()
    private val calculator = ProjectPositionCalculator()

    private class FakeStakeoutPreferences : StakeoutPreferences {
        override val settings = MutableStateFlow(StakeoutSettings())

        override suspend fun save(settings: StakeoutSettings) {
            this.settings.value = settings
        }
    }

    private class FakeCollectorPreferences : CollectorPreferences {
        override val settings = MutableStateFlow(CollectorSettings())

        override suspend fun save(settings: CollectorSettings) {
            this.settings.value = settings
        }
    }

    private val guide = MutableStateFlow<SurfaceGuide?>(null)
    private val viewModels = mutableListOf<LineStakeoutViewModel>()
    private val projectId: String = runBlocking {
        (projects.create(ProjectDefaults().toDraft().copy(name = "Calle Mayor 12")) as SaveResult.Saved).id
    }

    private val draft = ProjectDefaults().toDraft()
    private val tip = calculator.fromGround(GeodeticPosition(40.416800, -3.703713, 650.123 + 51.234), draft.crs, draft.heightSystem)
    private val e0 = tip.easting!!
    private val n0 = tip.northing!!

    private val surfaceModel = Surface(
        "s1", projectId, "terreno", "obra.xml", 1L, CadSourceCrs.Project, LinearUnit.METRE,
        SurfaceExtent(0.0, 1.0e7, 0.0, 1.0e7), 90.0, 120.0, 10, 8, 0,
    )

    private fun surface(height: SurfaceHeight = SurfaceHeight.Value(100.0), offset: Double = 0.0) =
        SurfaceGuide(surfaceModel, SurfaceHeightSource { _, _ -> height }, offset)

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

    private fun viewModel(): LineStakeoutViewModel {
        val vm = LineStakeoutViewModel(
            projects,
            cad,
            FakeStakeoutPreferences(),
            FakeCollectorPreferences(),
            FakePreferences(),
            session,
            object : Alerts {
                override fun connectionLost() = Unit

                override fun correctionsLost() = Unit

                override fun pointSaved() = Unit

                override fun solutionLost() = Unit

                override fun tiltLost() = Unit
            },
            { calculator },
            inProgress,
            RecordingLog(),
            surfaces = SurfaceGuides { guide },
        )
        viewModels += vm
        CoroutineScope(Dispatchers.Unconfined).launch { vm.state.collect {} }
        return vm
    }

    private fun await(vm: LineStakeoutViewModel, predicate: (LineStakeoutUiState) -> Boolean): LineStakeoutUiState = runBlocking {
        try {
            withTimeout(10_000) { vm.state.first(predicate) }
        } catch (e: TimeoutCancellationException) {
            throw AssertionError("El replanteo no llegó al estado esperado; se quedó en ${vm.state.value}", e)
        }
    }

    private var drawingInserted = false

    private fun insertDrawing() = runBlocking {
        if (drawingInserted) return@runBlocking
        drawingInserted = true
        cad.begin(CadDrawing("d1", projectId, "calle.dxf", 1L, CadSourceCrs.Project, LinearUnit.METRE, true, true))
        cad.addLayer(DesignLayer("l1", "d1", "Eje", DesignLayerKind.LINES, CadColor.rgb(0xFF0000)))
        cad.finish("d1", mapOf("l1" to 1), hasElevations = true, incomplete = false, extent = CadExtent(0.0, 1.0, 0.0, 1.0))
    }

    private fun selection(zs: DoubleArray? = null) = DesignSelection(
        ref = CadEntityRef("l1", 7L, 3),
        entity = CadEntity(CadEntityKind.POLYLINE, doubleArrayOf(e0 - 50.0, e0 + 50.0), doubleArrayOf(n0, n0), zs, null),
        layerName = "Eje",
        drawingName = "calle.dxf",
        hasElevation = zs != null,
    )

    private fun open(vm: LineStakeoutViewModel, zs: DoubleArray? = null): LineStakeoutUiState {
        insertDrawing()
        vm.open(projectId, selection(zs))
        return await(vm) { it.element != null && it.project != null }
    }

    private fun sendEpoch(second: Int) {
        val t = 36_930 + second
        val time = "%02d%02d%02d.00".format(t / 3_600, t / 60 % 60, t % 60)
        toApp.write(nmea("GNGGA,$time,4025.0080000,N,00342.2228000,W,4,24,0.6,650.123,M,51.234,M,1.0,0001").toByteArray())
        toApp.write(nmea("GNGST,$time,0.012,0.010,0.007,45.0,0.008,0.006,0.015").toByteArray())
        toApp.flush()
    }

    private fun connect() {
        session.connect(StreamTransport("reach-RS3", PipedInputStream(toApp, 64 * 1024), ByteArrayOutputStream()))
        sendEpoch(0)
        sendEpoch(1)
        runBlocking { withTimeout(5_000) { session.state.first { it.connection is ConnectionState.Receiving && it.epoch != null } } }
    }

    @Test
    fun `S042 RF-24 sin superficie activa la cota es la de la linea y no hay conmutador`() {
        val vm = viewModel()

        val state = open(vm, zs = doubleArrayOf(100.0, 110.0))

        state.surfaceActive shouldBe false
        state.elevationSource shouldBe ElevationSource.POINT
        state.canChooseSource shouldBe false
    }

    @Test
    fun `S042 RF-24 una linea sin cota con superficie activa se replantea contra la superficie`() {
        guide.value = surface()
        val vm = viewModel()

        open(vm)

        val state = await(vm) { it.surfaceActive }
        state.elevationSource shouldBe ElevationSource.SURFACE
        state.canChooseSource shouldBe false
    }

    @Test
    fun `S042 RF-25 una linea con cota sigue con ella aunque haya superficie y se puede elegir`() {
        guide.value = surface()
        val vm = viewModel()

        open(vm, zs = doubleArrayOf(100.0, 110.0))

        val state = await(vm) { it.surfaceActive }
        state.elevationSource shouldBe ElevationSource.POINT
        state.canChooseSource shouldBe true
    }

    @Test
    fun `S042 RF-25 el conmutador cambia el origen de la cota y se puede volver`() {
        guide.value = surface()
        val vm = viewModel()
        open(vm, zs = doubleArrayOf(100.0, 110.0))
        await(vm) { it.surfaceActive }

        vm.setElevationSource(ElevationSource.SURFACE) shouldBe true
        await(vm) { it.elevationSource == ElevationSource.SURFACE }.canChooseSource shouldBe true
        vm.setElevationSource(ElevationSource.POINT) shouldBe true

        await(vm) { it.elevationSource == ElevationSource.POINT }
    }

    @Test
    fun `S042 RF-25 con una medida en marcha o sin linea abierta no se cambia de origen`() {
        guide.value = surface()
        val vm = viewModel()
        vm.setElevationSource(ElevationSource.SURFACE) shouldBe false
        open(vm, zs = doubleArrayOf(100.0, 110.0))
        await(vm) { it.surfaceActive }
        inProgress.set(true)

        vm.setElevationSource(ElevationSource.SURFACE) shouldBe false
        inProgress.set(false)

        await(vm) { !it.measuring }.elevationSource shouldBe ElevationSource.POINT
    }

    @Test
    fun `S042 RF-25 al abrir otra linea se empieza de nuevo sin la eleccion anterior`() {
        guide.value = surface()
        val vm = viewModel()
        open(vm, zs = doubleArrayOf(100.0, 110.0))
        await(vm) { it.surfaceActive }
        vm.setElevationSource(ElevationSource.SURFACE)
        await(vm) { it.elevationSource == ElevationSource.SURFACE }

        vm.open(projectId, selection(zs = doubleArrayOf(100.0, 110.0)))

        await(vm) { it.element != null }.elevationSource shouldBe ElevationSource.POINT
    }

    @Test
    fun `S042 RF-23 escribir una cota a mano la usa y se puede volver a la superficie`() {
        guide.value = surface()
        val vm = viewModel()
        open(vm)
        await(vm) { it.elevationSource == ElevationSource.SURFACE }

        vm.setDesignElevation("650,5") shouldBe true
        val manual = await(vm) { it.elevationOverridden }
        manual.elevationSource shouldBe ElevationSource.POINT
        manual.canChooseSource shouldBe true

        vm.useLineElevation() shouldBe true

        await(vm) { !it.elevationOverridden }.elevationSource shouldBe ElevationSource.SURFACE
    }

    @Test
    fun `S042 RF-23 una cota que no es un numero no cambia el origen`() {
        guide.value = surface()
        val vm = viewModel()
        open(vm)
        await(vm) { it.elevationSource == ElevationSource.SURFACE }

        vm.setDesignElevation("abc") shouldBe false

        vm.state.value.elevationSource shouldBe ElevationSource.SURFACE
        vm.state.value.elevationOverridden shouldBe false
    }

    @Test
    fun `S042 RF-27 si la superficie deja de estar activa se avisa y se sigue con la cota de la linea`() {
        guide.value = surface()
        val vm = viewModel()
        open(vm)
        await(vm) { it.elevationSource == ElevationSource.SURFACE }

        guide.value = null

        val state = await(vm) { it.surfaceRemoved }
        state.surfaceActive shouldBe false
        state.elevationSource shouldBe ElevationSource.POINT
        guide.value = surface()
        await(vm) { !it.surfaceRemoved && it.surfaceActive }
    }

    @Test
    fun `S042 RF-24 la guia de la linea lleva la cota de la superficie con el desplazamiento en la punta`() {
        guide.value = surface(offset = 0.3)
        val vm = viewModel()
        open(vm)
        connect()

        val state = await(vm) { (it.guidance as? GuidanceState.On)?.ui?.surface != null }

        val ui = (state.guidance as GuidanceState.On).ui
        ui.surface.shouldNotBeNull().designM.shouldNotBeNull() shouldBe (100.3 plusOrMinus 1e-9)
        ui.level.shouldNotBeNull()
    }

    @Test
    fun `S042 RF-23 fuera de la superficie la guia de la linea dice que no hay cota y sigue guiando`() {
        guide.value = surface(height = SurfaceHeight.Outside)
        val vm = viewModel()
        open(vm)
        connect()

        val state = await(vm) { (it.guidance as? GuidanceState.On)?.ui?.surface != null }

        val ui = (state.guidance as GuidanceState.On).ui
        ui.levelUnavailable shouldBe LevelUnavailable.OUTSIDE_SURFACE
        ui.line.shouldNotBeNull()
    }
}
