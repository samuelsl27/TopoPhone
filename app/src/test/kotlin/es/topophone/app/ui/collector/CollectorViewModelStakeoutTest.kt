package es.topophone.app.ui.collector

import android.content.Context
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.app.log.RecordingLog
import es.topophone.app.ui.receiver.FakePreferences
import es.topophone.app.ui.receiver.nmea
import es.topophone.app.ui.stakeout.StakeSaveInfo
import es.topophone.app.ui.stakeout.StakeTarget
import es.topophone.core.model.point.CollectorSettings
import es.topophone.core.model.point.GeodeticPosition
import es.topophone.core.model.point.MeasurementMode
import es.topophone.core.model.point.Point
import es.topophone.core.model.point.PointCoordinates
import es.topophone.core.model.point.PointOrigin
import es.topophone.core.model.point.PrecisionControl
import es.topophone.core.model.point.PrecisionFailure
import es.topophone.core.model.point.StakeoutRef
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
 * El recolector midiendo un punto replanteado (S018/RF-26 a RF-31): el nombre `_stkd`, el guardado con el diseño y las
 * diferencias, el control de precisión, y el recolector que no cambia sin contexto de replanteo. Con la `ReceiverSession`
 * real y las épocas de una en una, a 1 Hz.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class CollectorViewModelStakeoutTest {

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
        noDataTimeoutMs = 60_000, // las épocas se mandan a mano: bajo carga, 2 s sin ninguna dejarían la sesión en "Sin datos"
        watchdogPeriodMs = 1_000,
    )
    private val toApp = PipedOutputStream()
    private val log = RecordingLog()
    private var saved = 0
    private val alerts = object : Alerts {
        override fun connectionLost() = Unit

        override fun correctionsLost() = Unit

        override fun pointSaved() {
            saved++
        }

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

    private fun startMeasuring(vm: CollectorViewModel) = runBlocking {
        withTimeout(10_000) {
            while (!vm.state.value.measuring) {
                vm.measure()
                kotlinx.coroutines.delay(50)
            }
        }
    }

    private fun sendEpoch(second: Int, status: Int = 4, lat: String = "4025.0080000") {
        val t = 36_930 + second
        val time = "%02d%02d%02d.00".format(t / 3_600, t / 60 % 60, t % 60)
        toApp.write(nmea("GNGGA,$time,$lat,N,00342.2228000,W,$status,24,0.6,650.123,M,51.234,M,1.0,0001").toByteArray())
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

    /** El diseño a (+2 cm Este, −3 cm Norte, +5 mm de cota) de lo medido. */
    private fun designOf(measured: Point): StakeTarget {
        val c = measured.coordinates
        val design = PointCoordinates(c.ground, c.easting!! + 0.02, c.northing!! - 0.03, c.height!! + 0.005)
        return StakeTarget("design-12", "12", "esquina norte", design)
    }

    /** Fija el diseño y lo que se guarda de S019, y deja listo el nombre propuesto. */
    private fun stakeWith(vm: CollectorViewModel, design: StakeTarget, info: StakeSaveInfo?) {
        vm.setStakeTarget(design)
        vm.setStakeSaveInfo(info)
        await(vm) { it.name == "12_stkd" }
    }

    private fun measureStaked(vm: CollectorViewModel): StakeoutRef {
        vm.measure()
        sendEpoch(next++)
        await(vm) { it.savedName == "12_stkd" }
        return runBlocking { points.points(projectId).first() }.single { it.name == "12_stkd" }.staked.shouldNotBeNull()
    }

    @Test
    fun `S019 RF-13 y RF-14 se guarda la cota usada, las tolerancias y que cumple en horizontal y en vertical`() {
        val vm = viewModel()
        receiving(vm)
        val design = designOf(measureNormalPoint(vm, "1"))
        stakeWith(vm, design, StakeSaveInfo(design.coordinates.height, toleranceHM = 0.05, toleranceVM = 0.03))

        val ref = measureStaked(vm)

        ref.designElevationM shouldBe design.coordinates.height
        ref.toleranceHM shouldBe 0.05
        ref.toleranceVM shouldBe 0.03
        ref.dHeightM.shouldNotBeNull() shouldBe (-0.005 plusOrMinus 1e-6)
        ref.withinTolerance shouldBe true // 3,6 cm de distancia horizontal y 5 mm de cota
    }

    @Test
    fun `S019 RF-14 fuera de la tolerancia horizontal no cumple aunque la cota este bien`() {
        val vm = viewModel()
        receiving(vm)
        val design = designOf(measureNormalPoint(vm, "1"))
        stakeWith(vm, design, StakeSaveInfo(design.coordinates.height, toleranceHM = 0.025, toleranceVM = 0.03))

        val ref = measureStaked(vm)

        ref.dHorizontalM shouldBe (hypot(0.02, 0.03) plusOrMinus 1e-6)
        ref.withinTolerance shouldBe false
    }

    @Test
    fun `S019 RF-14 dentro en horizontal y fuera en vertical no cumple`() {
        val vm = viewModel()
        receiving(vm)
        val design = designOf(measureNormalPoint(vm, "1"))
        // Una cota escrita 10 cm por encima de la del punto: lo medido queda 10,5 cm por debajo de ella.
        stakeWith(vm, design, StakeSaveInfo(design.coordinates.height!! + 0.1, toleranceHM = 0.05, toleranceVM = 0.03))

        val ref = measureStaked(vm)

        ref.dHeightM.shouldNotBeNull() shouldBe (-0.105 plusOrMinus 1e-6)
        ref.withinTolerance shouldBe false
    }

    @Test
    fun `S019 RF-13 la cota escrita se guarda aparte y el diseno se guarda por valor con su altura`() {
        val vm = viewModel()
        receiving(vm)
        val design = designOf(measureNormalPoint(vm, "1"))
        stakeWith(vm, design, StakeSaveInfo(design.coordinates.height!! - 1.0, toleranceHM = 0.05, toleranceVM = 0.03))

        val ref = measureStaked(vm)

        ref.designElevationM shouldBe design.coordinates.height!! - 1.0
        ref.design shouldBe design.coordinates
        ref.design.height shouldBe design.coordinates.height
        ref.dHeightM.shouldNotBeNull() shouldBe (0.995 plusOrMinus 1e-6)
    }

    @Test
    fun `S019 RF-14 sin cota de diseno no hay diferencia de cota y cumple solo por la horizontal`() {
        val vm = viewModel()
        receiving(vm)
        val design = designOf(measureNormalPoint(vm, "1"))
        stakeWith(vm, design, StakeSaveInfo(null, toleranceHM = 0.05, toleranceVM = 0.03))

        val ref = measureStaked(vm)

        ref.designElevationM.shouldBeNull()
        ref.dHeightM.shouldBeNull()
        ref.toleranceVM shouldBe 0.03
        ref.withinTolerance shouldBe true
    }

    @Test
    fun `S019 RF-26 sin los datos de S019 el replanteo se guarda como los de antes, sin evaluar`() {
        val vm = viewModel()
        receiving(vm)
        val design = designOf(measureNormalPoint(vm, "1"))
        stakeWith(vm, design, null)

        val ref = measureStaked(vm)

        ref.designElevationM shouldBe design.coordinates.height
        ref.toleranceHM.shouldBeNull()
        ref.toleranceVM.shouldBeNull()
        ref.withinTolerance.shouldBeNull()
        ref.notEvaluated shouldBe true
        ref.dHeightM.shouldNotBeNull() shouldBe (-0.005 plusOrMinus 1e-6)
    }

    @Test
    fun `S019 RF-13 se guarda lo ultimo que dio el replanteo aunque cambie entre un punto y el siguiente`() {
        val vm = viewModel()
        receiving(vm)
        val design = designOf(measureNormalPoint(vm, "1"))
        stakeWith(vm, design, StakeSaveInfo(design.coordinates.height, toleranceHM = 0.025, toleranceVM = 0.03))
        measureStaked(vm).withinTolerance shouldBe false
        await(vm) { it.name == "12_stkd2" }

        vm.setStakeSaveInfo(StakeSaveInfo(design.coordinates.height, toleranceHM = 0.05, toleranceVM = 0.03))
        vm.measure()
        sendEpoch(next++)
        await(vm) { it.savedName == "12_stkd2" }

        val second = runBlocking { points.points(projectId).first() }.single { it.name == "12_stkd2" }.staked.shouldNotBeNull()
        second.toleranceHM shouldBe 0.05
        second.withinTolerance shouldBe true
        // Y el primero conserva las tolerancias con las que se midio.
        runBlocking { points.points(projectId).first() }.single { it.name == "12_stkd" }.staked.shouldNotBeNull().toleranceHM shouldBe 0.025
    }

    @Test
    fun `S019 RF-13 al quitar el replanteo los puntos normales no llevan datos de replanteo`() {
        val vm = viewModel()
        receiving(vm)
        val design = designOf(measureNormalPoint(vm, "1"))
        stakeWith(vm, design, StakeSaveInfo(design.coordinates.height, toleranceHM = 0.05, toleranceVM = 0.03))
        vm.setStakeTarget(null)
        vm.setStakeSaveInfo(null)
        await(vm) { it.name == "2" }

        vm.measure()
        sendEpoch(next++)
        await(vm) { it.savedName == "2" }

        val normal = runBlocking { points.points(projectId).first() }.single { it.name == "2" }
        normal.origin shouldBe PointOrigin.MEASURED
        normal.staked.shouldBeNull()
    }

    @Test
    fun `S018 RF-27 con un punto de diseno el recolector propone su nombre con _stkd y su descripcion`() {
        val vm = viewModel()
        receiving(vm)
        val design = designOf(measureNormalPoint(vm, "1"))

        vm.setStakeTarget(design)

        val state = await(vm) { it.name == "12_stkd" }
        state.description shouldBe "esquina norte"
        state.blocker.shouldBeNull()
    }

    @Test
    fun `S018 RF-26 RF-27 y RF-28 medir guarda un punto replanteado con el diseno y las diferencias medido menos diseno`() {
        val vm = viewModel()
        receiving(vm)
        val normal = measureNormalPoint(vm, "1")
        val design = designOf(normal)
        vm.setStakeTarget(design)
        await(vm) { it.name == "12_stkd" }

        vm.measure()
        sendEpoch(next++)
        await(vm) { it.savedName == "12_stkd" }

        val staked = runBlocking { points.points(projectId).first() }.single { it.name == "12_stkd" }
        staked.origin shouldBe PointOrigin.STAKED
        staked.description shouldBe "esquina norte"
        staked.measurement.shouldNotBeNull().quality.mode shouldBe MeasurementMode.INSTANT
        val ref = staked.staked.shouldNotBeNull()
        ref.designId shouldBe "design-12"
        ref.designName shouldBe "12"
        ref.design shouldBe design.coordinates
        // Medido menos diseño: la medida está 2 cm al Oeste, 3 cm al Norte y 5 mm por debajo del diseño.
        ref.dEastM shouldBe (-0.02 plusOrMinus 1e-6)
        ref.dNorthM shouldBe (0.03 plusOrMinus 1e-6)
        ref.dHorizontalM shouldBe (hypot(0.02, 0.03) plusOrMinus 1e-6)
        ref.dHeightM.shouldNotBeNull() shouldBe (-0.005 plusOrMinus 1e-6)
        saved shouldBe 2
    }

    @Test
    fun `S018 RF-29 tras guardar el estado lleva las diferencias y la propuesta siguiente es _stkd2`() {
        val vm = viewModel()
        receiving(vm)
        vm.setStakeTarget(designOf(measureNormalPoint(vm, "1")))
        await(vm) { it.name == "12_stkd" }

        vm.measure()
        sendEpoch(next++)
        val state = await(vm) { it.savedName == "12_stkd" && it.name == "12_stkd2" }

        state.savedStake.shouldNotBeNull().dEastM shouldBe (-0.02 plusOrMinus 1e-6)
        state.measuring shouldBe false
    }

    @Test
    fun `S018 RF-27 replantear otra vez el mismo punto guarda _stkd2 y no sustituye al primero`() {
        val vm = viewModel()
        receiving(vm)
        vm.setStakeTarget(designOf(measureNormalPoint(vm, "1")))
        await(vm) { it.name == "12_stkd" }
        vm.measure()
        sendEpoch(next++)
        await(vm) { it.savedName == "12_stkd" && it.name == "12_stkd2" }

        vm.measure()
        sendEpoch(next++)
        await(vm) { it.savedName == "12_stkd2" }

        runBlocking { points.points(projectId).first() }.map { it.name }.toSet() shouldBe setOf("1", "12_stkd", "12_stkd2")
    }

    @Test
    fun `S018 RF-27 al fijar el diseno el nombre propuesto salta los replanteos que ya hay`() {
        val vm = viewModel()
        receiving(vm)
        val design = designOf(measureNormalPoint(vm, "1"))
        vm.setStakeTarget(design)
        await(vm) { it.name == "12_stkd" }
        vm.measure()
        sendEpoch(next++)
        await(vm) { it.savedName == "12_stkd" }

        vm.setStakeTarget(null)
        vm.setStakeTarget(design)

        await(vm) { it.name == "12_stkd2" }
    }

    @Test
    fun `S018 RF-26 sin contexto de replanteo el recolector mide como siempre y propone el nombre siguiente`() {
        val vm = viewModel()
        receiving(vm)

        val first = measureNormalPoint(vm, "1")

        first.origin shouldBe PointOrigin.MEASURED
        first.staked.shouldBeNull()
        await(vm) { it.name == "2" }
    }

    @Test
    fun `S018 RF-26 al quitar el replanteo vuelven el nombre y la descripcion del recolector`() {
        val vm = viewModel()
        receiving(vm)
        val normal = measureNormalPoint(vm, "1")
        vm.setDescription("jalón sobre la arqueta")
        await(vm) { it.name == "2" && it.description == "jalón sobre la arqueta" }

        vm.setStakeTarget(designOf(normal))
        await(vm) { it.name == "12_stkd" && it.description == "esquina norte" }
        vm.setStakeTarget(null)

        val state = await(vm) { it.name == "2" }
        state.description shouldBe "jalón sobre la arqueta"
    }

    @Test
    fun `S018 RF-26 el control de precision pide confirmar y al confirmar se guarda replanteado y fuera de tolerancia`() {
        val vm = viewModel(CollectorSettings(precision = PrecisionControl(fixOnly = false, horizontalLimitM = 0.001)))
        receiving(vm)
        // Con el límite de 1 mm la medida normal tampoco cumple: se confirma para tener un punto de donde sacar el diseño.
        sendEpoch(next++)
        vm.measure()
        sendEpoch(next++)
        await(vm) { it.pending != null }
        vm.confirmOutOfTolerance()
        await(vm) { it.savedName == "1" }
        val normal = runBlocking { points.points(projectId).first() }.single()
        vm.setStakeTarget(designOf(normal))
        await(vm) { it.name == "12_stkd" }

        vm.measure()
        sendEpoch(next++)
        val pending = await(vm) { it.pending != null && !it.measuring }.pending.shouldNotBeNull()
        pending.failures shouldBe setOf(PrecisionFailure.HORIZONTAL)
        runBlocking { points.points(projectId).first() }.size shouldBe 1

        vm.confirmOutOfTolerance()
        await(vm) { it.savedName == "12_stkd" }

        val staked = runBlocking { points.points(projectId).first() }.single { it.name == "12_stkd" }
        staked.origin shouldBe PointOrigin.STAKED
        staked.outOfTolerance shouldBe true
        staked.staked.shouldNotBeNull().designName shouldBe "12"
    }

    @Test
    fun `S018 RF-30 con una medida en marcha no se cambia de punto de diseno`() {
        val vm = viewModel(CollectorSettings(MeasurementMode.AVERAGED, averagingSeconds = 60))
        receiving(vm)
        val ground = GeodeticPosition(40.4168, -3.7038, 699.0)
        val normalDesign = StakeTarget("d-1", "12", "esquina norte", PointCoordinates(ground, 440_000.0, 4_470_000.0, 650.0))
        vm.setStakeTarget(normalDesign)
        await(vm) { it.name == "12_stkd" }
        startMeasuring(vm)
        sendEpoch(next++)

        vm.setStakeTarget(StakeTarget("d-2", "13", "otra", normalDesign.coordinates))

        vm.state.value.name shouldBe "12_stkd"
        vm.cancel()
    }

    @Test
    fun `S018 RF-28 el diseno se guarda tal como era aunque el punto de diseno cambie despues`() {
        val vm = viewModel()
        receiving(vm)
        val normal = measureNormalPoint(vm, "1")
        val design = designOf(normal)
        vm.setStakeTarget(design)
        await(vm) { it.name == "12_stkd" }
        vm.measure()
        sendEpoch(next++)
        await(vm) { it.savedName == "12_stkd" }

        // El punto de diseño se mueve 1 m después: lo guardado en el replanteo no cambia.
        runBlocking { points.moveToTrash(listOf(normal.id)) }

        val staked = runBlocking { points.points(projectId).first() }.single { it.name == "12_stkd" }
        staked.staked.shouldNotBeNull().design shouldBe design.coordinates
    }
}
