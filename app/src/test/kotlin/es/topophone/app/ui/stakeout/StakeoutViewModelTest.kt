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
import es.topophone.core.model.point.EntryForm
import es.topophone.core.model.point.GeodeticPosition
import es.topophone.core.model.point.HeadingChoice
import es.topophone.core.model.point.HeightKind
import es.topophone.core.model.point.Measurement
import es.topophone.core.model.point.MeasurementMode
import es.topophone.core.model.point.Point
import es.topophone.core.model.point.PointCoordinates
import es.topophone.core.model.point.PointOrigin
import es.topophone.core.model.point.PointQuality
import es.topophone.core.model.point.StakeMode
import es.topophone.core.model.point.StakeoutSettings
import es.topophone.core.model.point.TypedCoordinates
import es.topophone.core.model.project.LinearUnit
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
            this.settings.value = settings.copy(toleranceM = settings.validToleranceM, toleranceVM = settings.validToleranceVM)
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
    private fun addPoint(
        name: String,
        description: String = "",
        id: String = "id-$name",
        northDegrees: Double = 0.0,
        inProject: String = projectId,
    ): Point {
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
        return Point.measured(id, inProject, name, description, measurement, createdAtMs = 1_000).also {
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

        // El punto y el proyecto llegan de la base de datos por rutas distintas: hay que esperar a los dos.
        val loaded = await(vm) { it.target != null && it.project != null }
        val target = loaded.target.shouldNotBeNull()
        target.id shouldBe point.id
        target.name shouldBe "12"
        target.description shouldBe "esquina norte"
        target.coordinates shouldBe point.coordinates
        loaded.project.shouldNotBeNull().name shouldBe "Calle Mayor 12"
    }

    @Test
    fun `S018 RF-2 se replantea cualquier punto activo, medido o manual, y uno en la papelera no`() {
        val measured = addPoint("12")
        val ground = GeodeticPosition(40.4169, -3.7036, 750.0)
        val manual = Point(
            id = "id-M1",
            projectId = projectId,
            name = "M1",
            description = "esquina",
            origin = PointOrigin.MANUAL,
            coordinates = PointCoordinates(ground, 440_310.0, 4_474_256.0, 698.9),
            measurement = null,
            typed = TypedCoordinates(EntryForm.PROJECT_CRS, 440_310.0, 4_474_256.0, 698.9, HeightKind.PROJECT),
            edited = false,
            hidden = false,
            copyOf = null,
            createdAtMs = 2_000,
            deletedAtMs = null,
        )
        runBlocking { points.create(manual) }
        val trashed = addPoint("13")
        runBlocking { points.moveToTrash(listOf(trashed.id)) }
        val vm = viewModel()

        vm.open(projectId, measured.id)
        await(vm) { it.target?.id == measured.id }
        vm.open(projectId, manual.id)
        await(vm) { it.target?.id == manual.id }.target.shouldNotBeNull().coordinates shouldBe manual.coordinates
        vm.open(projectId, trashed.id)
        await(vm) { it.notFound }.target.shouldBeNull()
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

    private fun open(name: String = "1"): StakeoutViewModel {
        val vm = viewModel()
        vm.open(projectId, "id-$name")
        await(vm) { it.target?.name == name && it.project != null }
        return vm
    }

    private fun designHeight(name: String): Double = addedHeights.getValue(name)

    private val addedHeights = mutableMapOf<String, Double>()

    private fun addHeightPoint(name: String, northDegrees: Double = 0.0): Point =
        addPoint(name, northDegrees = northDegrees).also { addedHeights[name] = it.coordinates.height!! }

    @Test
    fun `S019 RF-1 la cota de diseno es la altura del punto de diseno mientras no se escriba otra`() {
        addHeightPoint("1")

        val state = open().state.value

        state.designElevationM.shouldNotBeNull() shouldBe (designHeight("1") plusOrMinus 1e-9)
        state.elevationOverridden shouldBe false
    }

    @Test
    fun `S019 RF-1 un punto de diseno sin altura no tiene cota de diseno`() {
        val withHeight = addPoint("1")
        runBlocking {
            points.create(
                withHeight.copy(id = "id-2", name = "2", coordinates = withHeight.coordinates.copy(height = null)),
            )
        }

        val vm = viewModel()
        vm.open(projectId, "id-2")
        val state = await(vm) { it.target?.name == "2" }

        state.designElevationM.shouldBeNull()
        state.elevationOverridden shouldBe false
    }

    @Test
    fun `S019 RF-2 una cota escrita sustituye a la del punto y no modifica el punto de diseno`() {
        val point = addPoint("1")
        val vm = open()

        vm.setDesignElevation("650,25") shouldBe true

        val state = await(vm) { it.elevationOverridden }
        state.designElevationM shouldBe 650.25
        runBlocking { points.point("id-1").first() }.shouldNotBeNull().coordinates.height shouldBe point.coordinates.height
    }

    @Test
    fun `S019 RF-2 una cota escrita da cota de diseno a un punto sin altura`() {
        val withHeight = addPoint("1")
        runBlocking { points.create(withHeight.copy(id = "id-2", name = "2", coordinates = withHeight.coordinates.copy(height = null))) }
        val vm = viewModel()
        vm.open(projectId, "id-2")
        await(vm) { it.target?.name == "2" }

        vm.setDesignElevation("100") shouldBe true

        await(vm) { it.elevationOverridden }.designElevationM shouldBe 100.0
    }

    @Test
    fun `S019 RF-2 la cota escrita se entiende en la unidad del proyecto y vale negativa`() {
        val feetProject = runBlocking {
            (
                projects.create(
                    ProjectDefaults().toDraft().copy(name = "En pies", linearUnit = LinearUnit.INTERNATIONAL_FOOT),
                ) as SaveResult.Saved
                ).id
        }
        addPoint("f", inProject = feetProject)
        val vm = viewModel()
        vm.open(feetProject, "id-f")
        await(vm) { it.target?.name == "f" && it.project != null }

        vm.setDesignElevation("100") shouldBe true
        await(vm) { it.elevationOverridden }.designElevationM.shouldNotBeNull() shouldBe (30.48 plusOrMinus 1e-9)

        vm.setDesignElevation("-3,5") shouldBe true
        await(vm) { state -> state.designElevationM?.let { kotlin.math.abs(it + 1.0668) < 1e-9 } == true }
    }

    @Test
    fun `S019 RF-3 usar la del punto descarta la cota escrita`() {
        addHeightPoint("1")
        val vm = open()
        vm.setDesignElevation("650") shouldBe true
        await(vm) { it.elevationOverridden }

        vm.useDesignPointElevation()

        val state = await(vm) { !it.elevationOverridden }
        state.designElevationM.shouldNotBeNull() shouldBe (designHeight("1") plusOrMinus 1e-9)
    }

    @Test
    fun `S019 RF-3 al pasar a otro punto de diseno la cota escrita se descarta`() {
        addHeightPoint("1")
        addHeightPoint("2", northDegrees = 0.0001)
        val vm = viewModel()
        vm.open(projectId, "id-1")
        await(vm) { it.target?.name == "1" && it.hasNext }
        vm.setDesignElevation("650") shouldBe true
        await(vm) { it.elevationOverridden }

        vm.next()

        val state = await(vm) { it.target?.name == "2" }
        state.elevationOverridden shouldBe false
        state.designElevationM.shouldNotBeNull() shouldBe (designHeight("2") plusOrMinus 1e-9)
        // Y al volver al primero tampoco se recuerda.
        vm.previous()
        await(vm) { it.target?.name == "1" }.elevationOverridden shouldBe false
    }

    @Test
    fun `S019 RF-3 al cerrar el replanteo la cota escrita se descarta`() {
        addHeightPoint("1")
        val vm = open()
        vm.setDesignElevation("650") shouldBe true
        await(vm) { it.elevationOverridden }

        vm.close()
        vm.open(projectId, "id-1")

        await(vm) { it.target?.name == "1" }.elevationOverridden shouldBe false
    }

    @Test
    fun `S019 RF-4 un valor que no es un numero no se aplica`() {
        addHeightPoint("1")
        val vm = open()

        listOf("", "abc", "1e3", "-", "12,3,4", "4.47.244", "NaN", "Infinity").forEach { text ->
            vm.setDesignElevation(text) shouldBe false
        }

        vm.state.value.elevationOverridden shouldBe false
        vm.state.value.designElevationM.shouldNotBeNull() shouldBe (designHeight("1") plusOrMinus 1e-9)
    }

    @Test
    fun `S019 RF-4 una cota no valida no borra la que ya estaba escrita`() {
        addHeightPoint("1")
        val vm = open()
        vm.setDesignElevation("650") shouldBe true
        await(vm) { it.designElevationM == 650.0 }

        vm.setDesignElevation("abc") shouldBe false

        vm.state.value.designElevationM shouldBe 650.0
    }

    @Test
    fun `S019 RF-10 la tolerancia vertical se escribe en la unidad del proyecto y se guarda aparte de la horizontal`() {
        addHeightPoint("1")
        val vm = open()

        vm.setToleranceV("0,05") shouldBe true

        val settings = await(vm) { it.settings.toleranceVM == 0.05 }.settings
        settings.toleranceM shouldBe 0.025
        stakeoutPreferences.settings.value.toleranceVM shouldBe 0.05
        vm.setTolerance("0,04") shouldBe true
        await(vm) { it.settings.toleranceM == 0.04 }.settings.toleranceVM shouldBe 0.05
    }

    @Test
    fun `S019 RF-10 una tolerancia vertical fuera de rango o que no es un numero no se guarda`() {
        addHeightPoint("1")
        val vm = open()

        vm.setToleranceV("5") shouldBe false
        vm.setToleranceV("0,001") shouldBe false
        vm.setToleranceV("abc") shouldBe false

        stakeoutPreferences.settings.value.toleranceVM shouldBe 0.03
    }

    @Test
    fun `S019 RF-16 con una medida en marcha no se cambia la cota escrita ni la tolerancia vertical`() {
        addHeightPoint("1")
        val vm = open()
        vm.setDesignElevation("650") shouldBe true
        await(vm) { it.elevationOverridden }

        inProgress.set(true)
        await(vm) { it.measuring }
        vm.setDesignElevation("700") shouldBe false
        vm.useDesignPointElevation()
        vm.setToleranceV("0,05") shouldBe false

        vm.state.value.designElevationM shouldBe 650.0
        vm.state.value.elevationOverridden shouldBe true
        stakeoutPreferences.settings.value.toleranceVM shouldBe 0.03

        inProgress.set(false)
        await(vm) { !it.measuring }
        vm.setDesignElevation("700") shouldBe true
        await(vm) { it.designElevationM == 700.0 }
    }

    @Test
    fun `S019 RF-13 lo que se guarda con el replanteo lleva la cota usada y las dos tolerancias`() {
        addHeightPoint("1")
        val vm = open()
        vm.setToleranceV("0,05") shouldBe true
        vm.setTolerance("0,04") shouldBe true
        await(vm) { it.settings.toleranceVM == 0.05 && it.settings.toleranceM == 0.04 }

        val info = vm.state.value.saveInfo.shouldNotBeNull()

        info.elevationM.shouldNotBeNull() shouldBe (designHeight("1") plusOrMinus 1e-9)
        info.toleranceHM shouldBe 0.04
        info.toleranceVM shouldBe 0.05

        vm.setDesignElevation("650") shouldBe true
        await(vm) { it.saveInfo?.elevationM == 650.0 }
    }

    @Test
    fun `S019 RF-13 sin punto de diseno no hay datos que guardar`() {
        val vm = viewModel()

        vm.state.value.saveInfo.shouldBeNull()
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
        // El punto nuevo llega de la base de datos de forma asíncrona: sin él, la época se descartaría y la guía no se actualizaría.
        await(vm) { it.target?.name == "2" }
        sendEpoch(3)

        // distanceOf da infinito con la guía apagada: hay que pedir una distancia finita, no solo mayor de 5 m.
        val on = await(vm) { it.target?.name == "2" && distanceOf(it).let { d -> d.isFinite() && d > 5 } }
            .guidance.shouldBeInstanceOf<GuidanceState.On>()
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
