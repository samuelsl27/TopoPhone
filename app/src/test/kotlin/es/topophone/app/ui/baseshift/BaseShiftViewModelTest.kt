package es.topophone.app.ui.baseshift

import android.content.Context
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.app.ui.receiver.nmea
import es.topophone.core.model.point.BaseShiftClass
import es.topophone.core.model.point.CollectorSettings
import es.topophone.core.model.point.GeodeticPosition
import es.topophone.core.model.point.Measurement
import es.topophone.core.model.point.MeasurementMode
import es.topophone.core.model.point.Point
import es.topophone.core.model.point.PointCoordinates
import es.topophone.core.model.point.PointQuality
import es.topophone.core.model.project.HeightSystem
import es.topophone.core.model.project.ProjectDefaults
import es.topophone.core.ntrip.NtripConnector
import es.topophone.core.ntrip.NtripFailure
import es.topophone.core.survey.BaseShiftCalculator
import es.topophone.core.survey.BaseShiftResult
import es.topophone.core.survey.KnownPoint
import es.topophone.core.survey.ProjectPositionCalculator
import es.topophone.data.baseshift.RoomBaseShiftRepository
import es.topophone.data.db.TopoPhoneDatabase
import es.topophone.data.point.CollectorPreferences
import es.topophone.data.point.RoomPointRepository
import es.topophone.data.project.RoomProjectRepository
import es.topophone.data.project.SaveResult
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
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** La pantalla que calcula el desplazamiento de base: punto conocido, medida sin guardar, resultado y lista (S020/RF-1 a RF-6, RF-12). */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class BaseShiftViewModelTest {

    private val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), TopoPhoneDatabase::class.java)
        .allowMainThreadQueries()
        .build()
    private val projects = RoomProjectRepository(db)
    private val points = RoomPointRepository(db)
    private val shifts = RoomBaseShiftRepository(db)
    private val sessionScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val session = ReceiverSession(
        sessionScope,
        NtripConnector { _, _, _, _ -> NtripFailure.UNREACHABLE },
        noDataTimeoutMs = 60_000,
        watchdogPeriodMs = 1_000,
    )
    private val toApp = PipedOutputStream()
    private val preferences = object : CollectorPreferences {
        override val settings = MutableStateFlow(CollectorSettings())

        override suspend fun save(settings: CollectorSettings) {
            this.settings.value = settings
        }
    }
    private val viewModels = mutableListOf<BaseShiftViewModel>()
    private val calculator = ProjectPositionCalculator()

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

    private fun project(): String = runBlocking {
        val draft = ProjectDefaults().toDraft().copy(name = "Calle Mayor 12", heightSystem = HeightSystem.ELLIPSOIDAL)
        (projects.create(draft) as SaveResult.Saved).id
    }

    private fun viewModel(projectId: String): BaseShiftViewModel {
        val vm = BaseShiftViewModel(projects, points, shifts, preferences, session, { calculator })
        viewModels += vm
        vm.start(projectId)
        await(vm) { it.loaded }
        return vm
    }

    private fun await(vm: BaseShiftViewModel, predicate: (BaseShiftUiState) -> Boolean): BaseShiftUiState =
        runBlocking { withTimeout(10_000) { vm.state.first(predicate) } }

    private fun connect() {
        session.connect(StreamTransport("reach-RS3", PipedInputStream(toApp, 64 * 1024), ByteArrayOutputStream()))
    }

    private fun sendEpoch(second: Int, status: Int = 4) {
        val time = "1015%02d.00".format(30 + second)
        toApp.write(nmea("GNGGA,$time,4025.0080000,N,00342.2228000,W,$status,24,0.6,650.123,M,51.234,M,1.0,0001").toByteArray())
        toApp.write(nmea("GNGST,$time,0.012,0.010,0.007,45.0,0.008,0.006,0.015").toByteArray())
        toApp.flush()
    }

    private fun receiving() {
        connect()
        sendEpoch(0)
        sendEpoch(1)
        runBlocking { withTimeout(5_000) { session.state.first { it.connection is ConnectionState.Receiving && it.epoch != null } } }
    }

    /** El punto del suelo del receptor del test (jalón de 1,8 m, antena RS3) en el SC del proyecto. */
    private val tip = GeodeticPosition(40.4168, -(3.0 + 42.2228 / 60.0), 650.123 + 51.234 - 1.934)

    private val tipPosition = calculator.fromGround(tip, es.topophone.core.model.project.ProjectCrs.ETRS89_UTM30, HeightSystem.ELLIPSOIDAL)

    private fun typed(vm: BaseShiftViewModel, dE: Double, dN: Double, dH: Double?) {
        vm.setSource(KnownSource.TYPED)
        vm.setName("V-12")
        vm.setEasting("%.4f".format(Locale.ROOT, tipPosition.easting!! + dE))
        vm.setNorthing("%.4f".format(Locale.ROOT, tipPosition.northing!! + dN))
        vm.setHeight(if (dH == null) "" else "%.4f".format(Locale.ROOT, tipPosition.height!! + dH))
    }

    private fun measureOnce(vm: BaseShiftViewModel) {
        vm.setSeconds("1")
        await(vm) { it.blocker == null }
        vm.measure()
        sendEpoch(2)
        sendEpoch(3)
    }

    private fun expected(vm: BaseShiftViewModel): BaseShiftResult.Computed {
        val s = vm.state.value
        val e = s.eastingText.toDouble()
        val n = s.northingText.toDouble()
        val h = s.heightText.toDoubleOrNull()
        return BaseShiftCalculator(es.topophone.core.survey.ManualCoordinates(calculator), calculator).compute(
            KnownPoint(e, n, h),
            tip,
            es.topophone.core.model.project.ProjectCrs.ETRS89_UTM30,
            HeightSystem.ELLIPSOIDAL,
            acceptHorizontalOnly = true,
        ) as BaseShiftResult.Computed
    }

    private fun measuredPoint(projectId: String) = Point.measured(
        id = "p1",
        projectId = projectId,
        name = "V-1",
        description = "",
        measurement = Measurement(
            antenna = GeodeticPosition(40.4, -3.7, 701.3),
            poleHeightM = 1.8,
            quality = PointQuality("FIX", 24, 0.6, 1.0, 0.009, 0.014, MeasurementMode.INSTANT, 1, 0, fromReplay = false),
            rawNmea = "",
            outOfTolerance = emptySet(),
            measuredFromMs = 1,
            measuredToMs = 2,
            coordinates = PointCoordinates(GeodeticPosition(40.4, -3.7, 699.4), 440_290.4, 4_474_257.4, 648.3),
        ),
        createdAtMs = 3,
    )

    @Test
    fun `S020 RF-1 los puntos del proyecto son candidatos y elegir uno da el punto conocido`() {
        val id = project()
        runBlocking { points.create(measuredPoint(id)) }
        val vm = viewModel(id)

        await(vm) { it.candidates.size == 1 }.candidates.single().name shouldBe "V-1"
        vm.state.value.knownValid shouldBe false
        vm.selectPoint("p1")

        vm.state.value.knownValid shouldBe true
        vm.state.value.blocker shouldBe BaseShiftBlocker.NO_POSITION
    }

    @Test
    fun `S020 RF-1 las coordenadas escritas valen si son numeros y la altura puede quedar vacia`() {
        val vm = viewModel(project())
        vm.setSource(KnownSource.TYPED)
        vm.state.value.knownValid shouldBe false

        vm.setEasting("440290,1234")
        vm.setNorthing("4474257,5")
        vm.state.value.knownValid shouldBe true
        vm.setHeight("abc")
        vm.state.value.knownValid shouldBe false
        vm.setHeight("")
        vm.state.value.knownValid shouldBe true
    }

    @Test
    fun `S020 RF-2 los segundos de la medida van de 1 s a 60 min`() {
        val vm = viewModel(project())
        typed(vm, 0.0, 0.0, 0.0)

        vm.setSeconds("0")
        vm.state.value.blocker shouldBe BaseShiftBlocker.INVALID_SECONDS
        vm.setSeconds("3601")
        vm.state.value.blocker shouldBe BaseShiftBlocker.INVALID_SECONDS
        vm.setSeconds("3600")
        vm.state.value.secondsValid shouldBe true
    }

    @Test
    fun `S020 RF-2 la medida da el vector sin guardar ningun punto`() {
        val id = project()
        val vm = viewModel(id)
        typed(vm, 3.0, -2.0, 0.5)
        receiving()

        measureOnce(vm)
        val result = await(vm) { it.result != null }.result.shouldNotBeNull()

        val want = expected(vm)
        result.vector.dx shouldBe (want.vector.dx plusOrMinus 0.001)
        result.vector.dy shouldBe (want.vector.dy plusOrMinus 0.001)
        result.vector.dz shouldBe (want.vector.dz plusOrMinus 0.001)
        result.classification shouldBe BaseShiftClass.OK
        runBlocking { points.points(id).first() } shouldBe emptyList()
        runBlocking { shifts.shifts(id).first() } shouldBe emptyList()
    }

    @Test
    fun `S020 RF-4 el resultado lleva el equivalente en Este, Norte y altura y el modulo`() {
        val vm = viewModel(project())
        typed(vm, 3.0, -2.0, 0.5)
        receiving()

        measureOnce(vm)
        val result = await(vm) { it.result != null }.result.shouldNotBeNull()

        result.deltaEastM shouldBe (3.0 plusOrMinus 0.01)
        result.deltaNorthM shouldBe (-2.0 plusOrMinus 0.01)
        result.deltaHeightM shouldBe (0.5 plusOrMinus 0.01)
        result.moduleM shouldBe (Math.sqrt(9.0 + 4.0 + 0.25) plusOrMinus 0.02)
    }

    @Test
    fun `S020 RF-4 activar guarda el desplazamiento como el activo del proyecto`() {
        val id = project()
        val vm = viewModel(id)
        typed(vm, 3.0, -2.0, 0.5)
        receiving()
        measureOnce(vm)
        await(vm) { it.result != null }

        vm.activate()

        val saved = await(vm) { it.savedShiftId != null && it.shifts.isNotEmpty() }.shifts.single()
        saved.active shouldBe true
        saved.knownName shouldBe "V-12"
        saved.horizontalOnly shouldBe false
        saved.fromReplay shouldBe false
        saved.measured.ellipsoidalHeightM.shouldNotBeNull()
        runBlocking { shifts.active(id).first() }.shouldNotBeNull().id shouldBe saved.id
    }

    @Test
    fun `S020 RF-5 con mas de 10 m pide una confirmacion aparte antes de activar`() {
        val id = project()
        val vm = viewModel(id)
        typed(vm, 20.0, 0.0, 0.0)
        receiving()
        measureOnce(vm)
        val result = await(vm) { it.result != null }.result.shouldNotBeNull()
        result.classification shouldBe BaseShiftClass.NEEDS_CONFIRMATION

        vm.activate()
        runBlocking { shifts.shifts(id).first() } shouldBe emptyList()
        vm.confirmFar()
        vm.activate()

        await(vm) { it.shifts.size == 1 }.shifts.single().active shouldBe true
    }

    @Test
    fun `S020 RF-5 con mas de 1000 m no se permite activar`() {
        val id = project()
        val vm = viewModel(id)
        typed(vm, 2_000.0, 0.0, 0.0)
        receiving()
        measureOnce(vm)

        val state = await(vm) { it.result != null }
        state.result.shouldNotBeNull().classification shouldBe BaseShiftClass.REJECTED
        state.error shouldBe BaseShiftError.TOO_FAR
        vm.confirmFar()
        vm.activate()

        runBlocking { shifts.shifts(id).first() } shouldBe emptyList()
    }

    @Test
    fun `S020 RF-6 sin altura del punto conocido ofrece aplicar solo el desplazamiento horizontal`() {
        val id = project()
        val vm = viewModel(id)
        typed(vm, 3.0, -2.0, null)
        receiving()
        measureOnce(vm)

        await(vm) { it.step == BaseShiftStep.CHOOSE_HORIZONTAL }
        vm.acceptHorizontalOnly()
        val result = await(vm) { it.result != null }.result.shouldNotBeNull()

        result.horizontalOnly shouldBe true
        vm.activate()
        val saved = await(vm) { it.shifts.size == 1 }.shifts.single()
        saved.horizontalOnly shouldBe true
        saved.knownHeightM.shouldBeNull()
        runBlocking { points.points(id).first() } shouldBe emptyList()
    }

    @Test
    fun `S020 RF-2 la medida ignora el desplazamiento activo`() {
        val id = project()
        val vm = viewModel(id)
        typed(vm, 3.0, -2.0, 0.5)
        receiving()
        measureOnce(vm)
        await(vm) { it.result != null }
        vm.activate()
        await(vm) { it.shifts.size == 1 }

        measureOnce(vm)
        val again = await(vm) { it.result != null }.result.shouldNotBeNull()

        val want = expected(vm)
        again.vector.dx shouldBe (want.vector.dx plusOrMinus 0.001)
        again.vector.dz shouldBe (want.vector.dz plusOrMinus 0.001)
    }

    @Test
    fun `S020 RF-12 se puede desactivar, reactivar y borrar desde la lista`() {
        val id = project()
        val vm = viewModel(id)
        typed(vm, 3.0, -2.0, 0.5)
        receiving()
        measureOnce(vm)
        await(vm) { it.result != null }
        vm.activate()
        val shift = await(vm) { it.shifts.size == 1 }.shifts.single()

        vm.deactivate()
        await(vm) { it.shifts.single().active.not() }
        vm.reactivate(shift.id)
        await(vm) { it.shifts.single().active }
        vm.delete(shift.id)
        await(vm) { it.shifts.isEmpty() }
    }

    @Test
    fun `S020 RF-2 cancelar una medida en curso no guarda nada y vuelve al inicio`() {
        val id = project()
        val vm = viewModel(id)
        typed(vm, 3.0, -2.0, 0.5)
        receiving()
        vm.setSeconds("30")
        await(vm) { it.blocker == null }

        vm.measure()
        await(vm) { it.step == BaseShiftStep.MEASURING }
        vm.cancelMeasuring()

        await(vm) { it.step == BaseShiftStep.SETUP }.result.shouldBeNull()
        runBlocking { shifts.shifts(id).first() } shouldBe emptyList()
    }

    @Test
    fun `S020 RF-3 un punto conocido fuera del ambito del SC no se calcula`() {
        val vm = viewModel(project())
        vm.setSource(KnownSource.TYPED)
        vm.setEasting("1,0")
        vm.setNorthing("1,0")
        vm.setHeight("10,0")
        receiving()
        measureOnce(vm)

        val state = await(vm) { it.error != null }

        state.error shouldBe BaseShiftError.KNOWN_OUTSIDE_AREA
        state.step shouldBe BaseShiftStep.SETUP
        state.result.shouldBeNull()
    }
}
