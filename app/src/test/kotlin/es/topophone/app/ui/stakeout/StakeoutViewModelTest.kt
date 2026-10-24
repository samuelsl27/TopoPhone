package es.topophone.app.ui.stakeout

import android.content.Context
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.app.log.RecordingLog
import es.topophone.app.ui.collector.MeasurementInProgress
import es.topophone.app.ui.receiver.FakePreferences
import es.topophone.app.ui.receiver.nmea
import es.topophone.core.model.point.CollectorSettings
import es.topophone.core.model.point.GeodeticPosition
import es.topophone.core.model.point.HeadingChoice
import es.topophone.core.model.point.Measurement
import es.topophone.core.model.point.MeasurementMode
import es.topophone.core.model.point.Point
import es.topophone.core.model.point.PointCoordinates
import es.topophone.core.model.point.PointQuality
import es.topophone.core.model.point.StakeMode
import es.topophone.core.model.point.StakeoutSettings
import es.topophone.core.model.project.ProjectDefaults
import es.topophone.core.ntrip.NtripConnector
import es.topophone.core.ntrip.NtripFailure
import es.topophone.core.survey.ProjectPositionCalculator
import es.topophone.data.db.TopoPhoneDatabase
import es.topophone.data.point.CollectorPreferences
import es.topophone.data.point.RoomPointRepository
import es.topophone.data.point.StakeoutPreferences
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
import io.kotest.matchers.types.shouldBeInstanceOf
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
 * El replanteo de un punto: el punto de diseño, siguiente y anterior, los ajustes y que la guía siga a la sesión
 * (S018/RF-1 a RF-8, RF-18, RF-30, RF-35).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class StakeoutViewModelTest {

    private val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), TopoPhoneDatabase::class.java)
        .allowMainThreadQueries()
        .build()
    private val projects = RoomProjectRepository(db)
    private val points = RoomPointRepository(db)
    private val sessionScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val session = ReceiverSession(
        sessionScope,
        NtripConnector { _, _, _, _ -> NtripFailure.UNREACHABLE },
        noDataTimeoutMs = 60_000, // las épocas se mandan a mano
        watchdogPeriodMs = 1_000,
    )
    private val toApp = PipedOutputStream()
    private val inProgress = MeasurementInProgress()
    private val calculator = ProjectPositionCalculator()

    private class FakeStakeoutPreferences : StakeoutPreferences {
        override val settings = MutableStateFlow(StakeoutSettings())

        override suspend fun save(settings: StakeoutSettings) {
            this.settings.value = settings.copy(toleranceM = settings.validToleranceM)
        }
    }

    private class FakeCollectorPreferences : CollectorPreferences {
        override val settings = MutableStateFlow(CollectorSettings())

        override suspend fun save(settings: CollectorSettings) {
            this.settings.value = settings
        }
    }

    private val stakeoutPreferences = FakeStakeoutPreferences()
    private val viewModels = mutableListOf<StakeoutViewModel>()
    private val projectId: String = runBlocking {
        (projects.create(ProjectDefaults().toDraft().copy(name = "Calle Mayor 12")) as SaveResult.Saved).id
    }

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

    private fun viewModel(): StakeoutViewModel {
        val vm = StakeoutViewModel(
            projects,
            points,
            stakeoutPreferences,
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
        )
        viewModels += vm
        CoroutineScope(Dispatchers.Unconfined).launch { vm.state.collect {} }
        return vm
    }

    private fun await(vm: StakeoutViewModel, predicate: (StakeoutUiState) -> Boolean): StakeoutUiState = runBlocking {
        try {
            withTimeout(10_000) { vm.state.first(predicate) }
        } catch (e: TimeoutCancellationException) {
            throw AssertionError("El replanteo no llegó al estado esperado; se quedó en ${vm.state.value}", e)
        }
    }

    /** Un punto del proyecto en la posición de las épocas de `sendEpoch`, con el [name] y la [description] dados. */
    private fun addPoint(name: String, description: String = "", id: String = "id-$name", northDegrees: Double = 0.0): Point {
        val ground = GeodeticPosition(40.416800 + northDegrees, -3.703713, 699.0)
        val position = calculator.fromGround(ground, ProjectDefaults().toDraft().crs, ProjectDefaults().toDraft().heightSystem)
        val coordinates = PointCoordinates(ground, position.easting, position.northing, position.height)
        val measurement = Measurement(
            antenna = GeodeticPosition(40.416800, -3.703713, 700.9),
            poleHeightM = 1.8,
            quality = PointQuality("FIX", 24, 0.6, 1.0, 0.01, 0.015, MeasurementMode.INSTANT, 1, 0, fromReplay = false),
            rawNmea = "",
            outOfTolerance = emptySet(),
            measuredFromMs = 1_000,
            measuredToMs = 1_200,
            coordinates = coordinates,
        )
        return Point.measured(id, projectId, name, description, measurement, createdAtMs = 1_000).also {
            runBlocking { points.create(it) }
        }
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
    fun `S018 RF-1 y RF-4 abrir el replanteo con un punto da su nombre, su descripcion y sus coordenadas`() {
        val point = addPoint("12", "esquina norte")
        val vm = viewModel()

        vm.open(projectId, point.id)

        val target = await(vm) { it.target != null }.target.shouldNotBeNull()
        target.id shouldBe point.id
        target.name shouldBe "12"
        target.description shouldBe "esquina norte"
        target.coordinates shouldBe point.coordinates
        vm.state.value.project.shouldNotBeNull().name shouldBe "Calle Mayor 12"
    }

    @Test
    fun `S018 RF-1 un punto que no existe no abre el replanteo`() {
        val vm = viewModel()

        vm.open(projectId, "no-existe")

        await(vm) { it.notFound }.target.shouldBeNull()
    }

    @Test
    fun `S018 RF-3 siguiente y anterior recorren por nombre en orden natural y no pasan de los extremos`() {
        listOf("10", "2", "1").forEach { addPoint(it) }
        val vm = viewModel()
        vm.open(projectId, "id-2")
        // La lista llega aparte del punto: se espera a que tenga a los vecinos.
        await(vm) { it.target?.name == "2" && it.hasPrevious && it.hasNext }

        vm.next()
        await(vm) { it.target?.name == "10" && it.hasPrevious && !it.hasNext }
        vm.next() // ya es el último
        vm.state.value.target?.name shouldBe "10"

        vm.previous()
        await(vm) { it.target?.name == "2" }
        vm.previous()
        await(vm) { it.target?.name == "1" && !it.hasPrevious && it.hasNext }
        vm.previous() // ya es el primero
        vm.state.value.target?.name shouldBe "1"
    }

    @Test
    fun `S018 RF-3 con una busqueda en la lista solo se recorren los puntos que la cumplen`() {
        listOf("P1", "P2", "Q1", "P3").forEach { addPoint(it) }
        val vm = viewModel()

        vm.open(projectId, "id-P1", query = "P")
        await(vm) { it.target?.name == "P1" && it.hasNext } // la lista tarda en llegar: sin ella, "Siguiente" no hace nada
        vm.next()
        await(vm) { it.target?.name == "P2" && it.hasNext }
        vm.next()
        await(vm) { it.target?.name == "P3" }.hasNext shouldBe false
    }

    @Test
    fun `S018 RF-3 abierto desde la ficha, sin busqueda, recorre todos los puntos`() {
        listOf("P1", "Q1").forEach { addPoint(it) }
        val vm = viewModel()

        vm.open(projectId, "id-P1")
        await(vm) { it.target?.name == "P1" && it.hasNext }
        vm.next()

        await(vm) { it.target?.name == "Q1" }
    }

    @Test
    fun `S018 RF-30 con una medida en marcha no se cambia de punto, ni de modo ni de tolerancia`() {
        listOf("1", "2").forEach { addPoint(it) }
        val vm = viewModel()
        vm.open(projectId, "id-1")
        await(vm) { it.target?.name == "1" }

        inProgress.set(true)
        await(vm) { it.measuring }
        vm.next()
        vm.setMode(StakeMode.BY_DISTANCE)
        vm.setHeading(HeadingChoice.IMU)
        vm.setTolerance("0,05") shouldBe false

        vm.state.value.target?.name shouldBe "1"
        stakeoutPreferences.settings.value shouldBe StakeoutSettings()

        inProgress.set(false)
        await(vm) { !it.measuring }
        vm.next()
        await(vm) { it.target?.name == "2" }
    }

    @Test
    fun `S018 RF-5, RF-8 y RF-18 los ajustes se guardan y se ven en el estado`() {
        addPoint("1")
        val vm = viewModel()
        vm.open(projectId, "id-1")
        await(vm) { it.target != null }

        vm.setMode(StakeMode.BY_DISTANCE)
        vm.setHeading(HeadingChoice.IMU)
        vm.setTolerance("0,05") shouldBe true

        val settings = await(vm) { it.settings.toleranceM == 0.05 }.settings
        settings.mode shouldBe StakeMode.BY_DISTANCE
        settings.heading shouldBe HeadingChoice.IMU
        stakeoutPreferences.settings.value shouldBe settings
    }

    @Test
    fun `S018 RF-18 una tolerancia fuera de rango o que no es un numero no se guarda`() {
        addPoint("1")
        val vm = viewModel()
        vm.open(projectId, "id-1")
        await(vm) { it.target != null }

        vm.setTolerance("5") shouldBe false
        vm.setTolerance("0,001") shouldBe false
        vm.setTolerance("abc") shouldBe false

        stakeoutPreferences.settings.value.toleranceM shouldBe 0.025
    }

    @Test
    fun `S018 RF-35 recien abierto y sin posicion la guia esta apagada por falta de posicion`() {
        addPoint("1")
        val vm = viewModel()

        vm.open(projectId, "id-1")

        val off = await(vm) { it.target != null }.guidance.shouldBeInstanceOf<GuidanceState.Off>()
        off.reason shouldBe GuidanceOff.NO_POSITION
    }

    @Test
    fun `S018 RF-6 la guia sigue a las epocas de la sesion y un corte la apaga`() {
        addPoint("1")
        val vm = viewModel()
        vm.open(projectId, "id-1")
        await(vm) { it.target != null }
        connect()
        sendEpoch(2)

        val on = await(vm) { it.guidance is GuidanceState.On }.guidance.shouldBeInstanceOf<GuidanceState.On>()
        on.ui.guidance.distanceM shouldBe (0.0 plusOrMinus 0.05)

        session.disconnect()

        await(vm) { (it.guidance as? GuidanceState.Off)?.reason == GuidanceOff.NO_DATA }
    }

    /** La distancia horizontal de la guía, o infinito si está apagada. */
    private fun distanceOf(state: StakeoutUiState): Double =
        (state.guidance as? GuidanceState.On)?.ui?.guidance?.distanceM ?: Double.POSITIVE_INFINITY

    @Test
    fun `S018 RF-3 al cambiar de punto la guia sigue al punto nuevo`() {
        addPoint("1")
        addPoint("2", northDegrees = 0.0001) // unos 11 m al norte
        val vm = viewModel()
        vm.open(projectId, "id-1")
        await(vm) { it.target?.name == "1" && it.hasNext }
        connect()
        sendEpoch(2)
        await(vm) { distanceOf(it) < 0.1 }

        vm.next()
        sendEpoch(3)

        val on = await(vm) { it.target?.name == "2" && distanceOf(it) > 5 }.guidance.shouldBeInstanceOf<GuidanceState.On>()
        on.ui.guidance.distanceM shouldBe (11.1 plusOrMinus 0.2)
    }

    @Test
    fun `S018 RF-1 al cerrar el replanteo el estado vuelve a vacio`() {
        addPoint("1")
        val vm = viewModel()
        vm.open(projectId, "id-1")
        await(vm) { it.target != null }

        vm.close()

        await(vm) { it.target == null }
    }
}
