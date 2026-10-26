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
import es.topophone.core.model.cad.CadEntityKind
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
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import java.io.ByteArrayOutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import kotlin.math.hypot
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
 * El recolector guardando un punto replanteado de un elemento de un dibujo (S041/RF-27, RF-28): el nombre `capa_n_stkd`, el
 * objetivo de cuando termina la medida, el origen, el modo, el PK, el desplazamiento y el error lateral. Con la `ReceiverSession`
 * real y las épocas de una en una.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class StakeoutMeasureLineTest {

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

    /** Mide y guarda un punto normal en la posición de las épocas, para sacar de él un diseño a una distancia conocida. */
    private fun measureNormalPoint(vm: CollectorViewModel, name: String): Point {
        sendEpoch(next++)
        vm.measure()
        sendEpoch(next++)
        await(vm) { it.savedName == name }
        return runBlocking { points.points(projectId).first() }.single { it.name == name }
    }

    private val source = StakeSource.CadElement("calle.dxf", "Eje", CadEntityKind.POLYLINE)

    /** Un elemento de un dibujo como punto de diseño: el nombre es el de la capa. */
    private fun cadTarget(design: PointCoordinates) = StakeTarget("cad:l1:7:3", "Eje", "", design, source)

    /** El objetivo a (+2 cm Este, −3 cm Norte, +5 mm de cota) de lo medido, en el PK 45,25 y a 1,5 m a la derecha de la línea. */
    private fun objectiveOf(measured: Point, lateralError: Double? = 0.012): StakeObjective {
        val c = measured.coordinates
        return StakeObjective(
            design = PointCoordinates(c.ground, c.easting!! + 0.02, c.northing!! - 0.03, c.height!! + 0.005),
            elevationM = c.height!! + 0.005,
            mode = LineStakeoutMode.ALONG,
            chainageM = 45.25,
            lateralOffsetM = 1.5,
            lateralErrorOf = { lateralError },
        )
    }

    private fun stakeLine(vm: CollectorViewModel, design: PointCoordinates, provider: StakeObjectiveProvider?) {
        vm.setStakeTarget(cadTarget(design))
        vm.setStakeSaveInfo(StakeSaveInfo(null, toleranceHM = 0.05, toleranceVM = 0.03))
        vm.setStakeObjective(provider)
        await(vm) { it.name == "Eje_1_stkd" }
    }

    private fun measureStaked(vm: CollectorViewModel, name: String = "Eje_1_stkd"): Point {
        vm.measure()
        sendEpoch(next++)
        await(vm) { it.savedName == name }
        return runBlocking { points.points(projectId).first() }.single { it.name == name }
    }

    @Test
    fun `S041 RF-27 el nombre propuesto de un elemento de un dibujo es capa_1_stkd y el siguiente libre es capa_2_stkd`() {
        val vm = viewModel()
        receiving(vm)
        val measured = measureNormalPoint(vm, "1")
        stakeLine(vm, measured.coordinates, StakeObjectiveProvider { objectiveOf(measured) })

        measureStaked(vm)

        await(vm) { it.name == "Eje_2_stkd" }
        measureStaked(vm, "Eje_2_stkd")
        await(vm) { it.name == "Eje_3_stkd" }
    }

    @Test
    fun `S041 RF-27 el nombre propuesto lo puede cambiar el usuario antes de medir`() {
        val vm = viewModel()
        receiving(vm)
        val measured = measureNormalPoint(vm, "1")
        stakeLine(vm, measured.coordinates, StakeObjectiveProvider { objectiveOf(measured) })

        vm.setName("P7")
        measureStaked(vm, "P7").staked.shouldNotBeNull().source shouldBe source
    }

    @Test
    fun `S041 RF-28 se guarda el objetivo de cuando termina la medida y no el primer vertice de la linea`() {
        val vm = viewModel()
        receiving(vm)
        val measured = measureNormalPoint(vm, "1")
        val objective = objectiveOf(measured)
        stakeLine(vm, measured.coordinates, StakeObjectiveProvider { objective })

        val ref = measureStaked(vm).staked.shouldNotBeNull()

        ref.design shouldBe objective.design
        ref.designElevationM shouldBe objective.elevationM
        ref.dEastM shouldBe (-0.02 plusOrMinus 1e-6) // medido menos diseño
        ref.dNorthM shouldBe (0.03 plusOrMinus 1e-6)
        ref.dHorizontalM shouldBe (hypot(0.02, 0.03) plusOrMinus 1e-6)
        ref.dHeightM.shouldNotBeNull() shouldBe (-0.005 plusOrMinus 1e-6)
        ref.toleranceHM shouldBe 0.05
        ref.toleranceVM shouldBe 0.03
        ref.withinTolerance shouldBe true
    }

    @Test
    fun `S041 RF-28 se guarda de que elemento viene y el modo el PK el desplazamiento lateral y el error lateral`() {
        val vm = viewModel()
        receiving(vm)
        val measured = measureNormalPoint(vm, "1")
        stakeLine(vm, measured.coordinates, StakeObjectiveProvider { objectiveOf(measured, lateralError = 0.0123456789) })

        val ref = measureStaked(vm).staked.shouldNotBeNull()

        ref.source shouldBe source
        ref.fromDrawing shouldBe true
        ref.mode shouldBe LineStakeoutMode.ALONG
        ref.chainageM shouldBe 45.25
        ref.lateralOffsetM shouldBe 1.5
        ref.dLateralM shouldBe 0.0123456789
        ref.designId shouldBe "cad:l1:7:3"
        ref.designName shouldBe "Eje"
    }

    @Test
    fun `S041 RF-28 el objetivo que se guarda es el de cuando la medida termina, aunque cambie durante ella`() {
        val vm = viewModel()
        receiving(vm)
        val measured = measureNormalPoint(vm, "1")
        var current = objectiveOf(measured)
        stakeLine(vm, measured.coordinates, StakeObjectiveProvider { current })
        vm.measure()
        await(vm) { it.measuring }

        current = StakeObjective(
            design = current.design.copy(easting = current.design.easting!! + 0.01),
            elevationM = current.elevationM,
            mode = LineStakeoutMode.TO_LINE,
            chainageM = 46.0,
            lateralOffsetM = 0.0,
            lateralErrorOf = { 0.5 },
        )
        sendEpoch(next++)
        await(vm) { it.savedName == "Eje_1_stkd" }

        val ref = runBlocking { points.points(projectId).first() }.single { it.name == "Eje_1_stkd" }.staked.shouldNotBeNull()
        ref.mode shouldBe LineStakeoutMode.TO_LINE
        ref.chainageM shouldBe 46.0
        ref.dLateralM shouldBe 0.5
    }

    @Test
    fun `S041 RF-28 un error lateral sin lado se guarda vacio`() {
        val vm = viewModel()
        receiving(vm)
        val measured = measureNormalPoint(vm, "1")
        stakeLine(vm, measured.coordinates, StakeObjectiveProvider { objectiveOf(measured, lateralError = null) })

        measureStaked(vm).staked.shouldNotBeNull().dLateralM.shouldBeNull()
    }

    @Test
    fun `S041 RF-28 sin el objetivo de la guia no se inventa un replanteo y el punto se guarda como medido`() {
        val vm = viewModel()
        receiving(vm)
        val measured = measureNormalPoint(vm, "1")
        stakeLine(vm, measured.coordinates, StakeObjectiveProvider { null })

        val point = measureStaked(vm)

        point.staked.shouldBeNull()
        point.origin shouldBe PointOrigin.MEASURED
    }

    @Test
    fun `S041 RF-28 un punto fijo de un dibujo se guarda con su origen y sin modo ni datos de linea`() {
        val vm = viewModel()
        receiving(vm)
        val measured = measureNormalPoint(vm, "1")
        val c = measured.coordinates
        val design = PointCoordinates(c.ground, c.easting!! + 0.02, c.northing!! - 0.03, c.height!! + 0.005)
        stakeLine(vm, design, null)

        val ref = measureStaked(vm).staked.shouldNotBeNull()

        ref.source shouldBe source
        ref.mode.shouldBeNull()
        ref.chainageM.shouldBeNull()
        ref.dLateralM.shouldBeNull()
        ref.design shouldBe design
        ref.dEastM shouldBe (-0.02 plusOrMinus 1e-6)
    }

    @Test
    fun `S041 RF-27 un punto fijo del proyecto sigue llamandose como el con _stkd y sin origen de dibujo`() {
        val vm = viewModel()
        receiving(vm)
        val measured = measureNormalPoint(vm, "1")
        vm.setStakeTarget(StakeTarget("design-12", "12", "esquina", measured.coordinates))
        await(vm) { it.name == "12_stkd" }

        val ref = measureStaked(vm, "12_stkd").staked.shouldNotBeNull()

        ref.source shouldBe StakeSource.ProjectPoint
        ref.fromDrawing shouldBe false
    }

    @Test
    fun `S041 RF-27 al cerrar el replanteo el recolector vuelve a medir como siempre y olvida el objetivo`() {
        val vm = viewModel()
        receiving(vm)
        val measured = measureNormalPoint(vm, "1")
        stakeLine(vm, measured.coordinates, StakeObjectiveProvider { objectiveOf(measured) })

        vm.setStakeTarget(null)

        await(vm) { it.name == "2" }
        vm.measure()
        sendEpoch(next++)
        await(vm) { it.savedName == "2" }
        runBlocking { points.points(projectId).first() }.single { it.name == "2" }.staked.shouldBeNull()
    }
}
