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
import es.topophone.core.model.codes.AttributeType
import es.topophone.core.model.codes.CodeSymbol
import es.topophone.core.model.codes.PointAttribute
import es.topophone.core.model.codes.PointCode
import es.topophone.core.model.point.CollectorSettings
import es.topophone.core.model.point.LineStakeoutMode
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
import es.topophone.receiver.session.ConnectionState
import es.topophone.receiver.session.ReceiverSession
import es.topophone.receiver.transport.StreamTransport
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
 * El recolector guardando un punto replanteado de una línea del proyecto: el nombre `<vértice>_stkd` o `<línea>_n_stkd`, el origen con
 * la línea, el modo, el PK y el error lateral, y el código y los atributos de la línea (S027/RF-11, RF-12).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class LineStakeoutSaveTest {

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

    private val bord =
        PointCode("BORD", "Bordillo", 0x1E88E5, CodeSymbol.CIRCLE, listOf(PointAttribute("Material", AttributeType.TEXT, "Granito")))
    private val lineSource = StakeSource.ProjectLine("l1", "BORD_1")

    private fun lineTarget(design: PointCoordinates, name: String = "BORD_1", perTarget: Boolean = false) =
        StakeTarget("line:l1", name, "", design, lineSource, bord, namePerTarget = perTarget)

    private fun objectiveOf(measured: Point, mode: LineStakeoutMode = LineStakeoutMode.ALONG): StakeObjective {
        val c = measured.coordinates
        return StakeObjective(
            design = PointCoordinates(c.ground, c.easting!! + 0.02, c.northing!! - 0.03, c.height!! + 0.005),
            elevationM = c.height!! + 0.005,
            mode = mode,
            chainageM = 45.25,
            lateralOffsetM = 1.5,
            lateralErrorOf = { 0.012 },
        )
    }

    private fun stake(vm: CollectorViewModel, target: StakeTarget, provider: StakeObjectiveProvider?, expectedName: String) {
        vm.setStakeTarget(target)
        vm.setStakeSaveInfo(StakeSaveInfo(null, toleranceHM = 0.05, toleranceVM = 0.03))
        vm.setStakeObjective(provider)
        await(vm) { it.name == expectedName }
    }

    private fun measureStaked(vm: CollectorViewModel, name: String): Point {
        vm.measure()
        sendEpoch(next++)
        await(vm) { it.savedName == name }
        return runBlocking { points.points(projectId).first() }.single { it.name == name }
    }

    @Test
    fun `S027 RF-11 en los modos de linea el nombre propuesto es linea_n_stkd y el siguiente libre es linea_n+1_stkd`() {
        val vm = viewModel()
        receiving(vm)
        val measured = measureNormalPoint(vm, "1")
        stake(vm, lineTarget(measured.coordinates), StakeObjectiveProvider { objectiveOf(measured) }, "BORD_1_1_stkd")

        measureStaked(vm, "BORD_1_1_stkd")

        await(vm) { it.name == "BORD_1_2_stkd" }
        measureStaked(vm, "BORD_1_2_stkd")
        await(vm) { it.name == "BORD_1_3_stkd" }
    }

    @Test
    fun `S027 RF-11 en Por segmentos el nombre propuesto es el del punto del vertice mas _stkd y despues _stkd2`() {
        val vm = viewModel()
        receiving(vm)
        val measured = measureNormalPoint(vm, "1")
        stake(
            vm,
            lineTarget(measured.coordinates, name = "B", perTarget = true),
            StakeObjectiveProvider {
                objectiveOf(measured, LineStakeoutMode.BY_SEGMENTS)
            },
            "B_stkd",
        )

        measureStaked(vm, "B_stkd")

        await(vm) { it.name == "B_stkd2" }
    }

    @Test
    fun `S027 RF-12 se guarda la linea de la que viene el punto con su identificador, su nombre, el modo, el PK y el error lateral`() {
        val vm = viewModel()
        receiving(vm)
        val measured = measureNormalPoint(vm, "1")
        stake(vm, lineTarget(measured.coordinates), StakeObjectiveProvider { objectiveOf(measured) }, "BORD_1_1_stkd")

        val ref = measureStaked(vm, "BORD_1_1_stkd").staked.shouldNotBeNull()

        ref.source shouldBe lineSource
        ref.fromProjectLine shouldBe true
        ref.fromDrawing shouldBe false
        ref.mode shouldBe LineStakeoutMode.ALONG
        ref.chainageM shouldBe 45.25
        ref.lateralOffsetM shouldBe 1.5
        ref.dLateralM shouldBe 0.012
        ref.designId shouldBe "line:l1"
        ref.designName shouldBe "BORD_1"
    }

    @Test
    fun `S027 RF-12 el punto replanteado hereda el codigo y los atributos de la linea por valor`() {
        val vm = viewModel()
        receiving(vm)
        val measured = measureNormalPoint(vm, "1")
        stake(vm, lineTarget(measured.coordinates), StakeObjectiveProvider { objectiveOf(measured) }, "BORD_1_1_stkd")

        val point = measureStaked(vm, "BORD_1_1_stkd")

        point.code shouldBe bord
        point.origin shouldBe PointOrigin.STAKED
    }

    @Test
    fun `S027 RF-12 quitar el objetivo borra el proveedor y al ponerlo de nuevo el punto se guarda con el modo y el PK`() {
        val vm = viewModel()
        receiving(vm)
        val measured = measureNormalPoint(vm, "1")
        val provider = StakeObjectiveProvider { objectiveOf(measured) }
        stake(vm, lineTarget(measured.coordinates), provider, "BORD_1_1_stkd")

        // Al reabrir una linea el objetivo pasa por null: el recolector olvida el proveedor y la pantalla debe darlo otra vez.
        vm.setStakeTarget(null)
        stake(vm, lineTarget(measured.coordinates), provider, "BORD_1_1_stkd")

        val ref = measureStaked(vm, "BORD_1_1_stkd").staked.shouldNotBeNull()
        ref.mode shouldBe LineStakeoutMode.ALONG
        ref.chainageM shouldBe 45.25
    }
}
