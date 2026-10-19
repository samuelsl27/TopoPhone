package es.topophone.app.ui.collector

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.app.log.RecordingLog
import es.topophone.app.ui.receiver.FakePreferences
import es.topophone.app.ui.receiver.nmea
import es.topophone.core.gnss.nmea.SolutionStatus
import es.topophone.core.model.log.LogCategory
import es.topophone.core.model.log.LogLevel
import es.topophone.core.model.point.CollectorSettings
import es.topophone.core.model.point.MeasurementMode
import es.topophone.core.model.point.PrecisionControl
import es.topophone.core.model.point.PrecisionFailure
import es.topophone.core.model.project.ProjectCrs
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
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.longs.shouldBeLessThan
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import java.io.ByteArrayOutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
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

/** Recolector: nombre, jalón, medida, control de precisión, guardado y sonidos (S010/RF-2 a RF-5, RF-8 a RF-19). */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class CollectorViewModelTest {

    private val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), TopoPhoneDatabase::class.java)
        .allowMainThreadQueries()
        .build()
    private val projects = RoomProjectRepository(db)
    private val points = RoomPointRepository(db)
    private val receiverPreferences = FakePreferences()
    private val collectorPreferences = FakeCollectorPreferences()
    private val sessionScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val session = ReceiverSession(sessionScope, NtripConnector { _, _, _, _ -> NtripFailure.UNREACHABLE })
    private val toApp = PipedOutputStream()
    private var saved = 0
    private var lost = 0
    private val alerts = object : Alerts {
        override fun connectionLost() = Unit

        override fun correctionsLost() = Unit

        override fun pointSaved() {
            saved++
        }

        override fun solutionLost() {
            lost++
        }
    }

    private class FakeCollectorPreferences : CollectorPreferences {
        override val settings = MutableStateFlow(CollectorSettings())

        override suspend fun save(settings: CollectorSettings) {
            this.settings.value = settings
        }
    }

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() {
        runCatching { toApp.close() }
        session.disconnect()
        sessionScope.cancel()
        db.close()
        Dispatchers.resetMain()
    }

    private fun project(crs: ProjectCrs = ProjectCrs.ETRS89_UTM30): String = runBlocking {
        (projects.create(ProjectDefaults().toDraft().copy(name = "Calle Mayor 12", crs = crs)) as SaveResult.Saved).id
    }

    private val inProgress = MeasurementInProgress()
    private val log = RecordingLog()

    private fun viewModel(projectId: String): CollectorViewModel {
        val vm =
            CollectorViewModel(projects, points, collectorPreferences, receiverPreferences, session, alerts, {
                ProjectPositionCalculator()
            }, inProgress, log)
        vm.start(projectId)
        CoroutineScope(Dispatchers.Unconfined).launch { vm.state.collect {} }
        await(vm) { it.loaded }
        return vm
    }

    private fun await(vm: CollectorViewModel, predicate: (CollectorUiState) -> Boolean): CollectorUiState =
        runBlocking { withTimeout(10_000) { vm.state.first(predicate) } }

    private fun connect() {
        session.connect(StreamTransport("reach-RS3", PipedInputStream(toApp, 64 * 1024), ByteArrayOutputStream()))
    }

    /** Una época a 1 Hz: GGA (con la calidad de [status]) y GST. */
    private fun sendEpoch(second: Int, status: Int = 4, lat: String = "4025.0080000", lon: String = "00342.2228000") {
        val time = "1015%02d.00".format(30 + second)
        toApp.write(nmea("GNGGA,$time,$lat,N,$lon,W,$status,24,0.6,650.123,M,51.234,M,1.0,0001").toByteArray())
        toApp.write(nmea("GNGST,$time,0.012,0.010,0.007,45.0,0.008,0.006,0.015").toByteArray())
        toApp.flush()
    }

    private fun receiving(vm: CollectorViewModel, status: Int = 4) {
        connect()
        sendEpoch(0, status)
        sendEpoch(1, status)
        runBlocking { withTimeout(5_000) { session.state.first { it.connection is ConnectionState.Receiving && it.epoch != null } } }
        await(vm) { it.live != null }
    }

    @Test
    fun `S010 RF-2 se propone 1 en un proyecto sin puntos`() {
        viewModel(project()).state.value.name shouldBe "1"
    }

    @Test
    fun `S010 RF-5 el jalon es el de la pantalla Coordenadas y cambiarlo aqui lo cambia alli`() {
        receiverPreferences.poleFlow.value = 2.05
        val vm = viewModel(project())
        await(vm) { it.poleHeightText == "2,050" || it.poleHeightText == "2.050" }

        vm.setPoleHeight("1,5")

        runBlocking { withTimeout(5_000) { receiverPreferences.poleFlow.first { it == 1.5 } } }
        vm.setPoleHeight("12")
        await(vm) { !it.poleHeightValid }.blocker shouldBe MeasureBlocker.INVALID_POLE
    }

    @Test
    fun `S010 RF-12 sin posicion del receptor no se puede medir`() {
        val vm = viewModel(project())

        vm.state.value.blocker shouldBe MeasureBlocker.NO_POSITION
        vm.measure()
        vm.state.value.measuring shouldBe false
    }

    @Test
    fun `S010 RF-12 fuera del ambito del SC del proyecto no se puede medir`() {
        val vm = viewModel(project(ProjectCrs.REGCAN95_UTM28))
        receiving(vm)

        await(vm) { it.blocker == MeasureBlocker.OUTSIDE_CRS_AREA }
    }

    @Test
    fun `S010 RF-8, RF-16 y RF-19 una medida instantanea en FIX se guarda al momento, suena y se propone el nombre siguiente`() {
        val projectId = project()
        val vm = viewModel(projectId)
        receiving(vm)
        await(vm) { it.blocker == null }

        vm.measure()
        val sentAt = System.currentTimeMillis()
        sendEpoch(2)
        sendEpoch(3)
        val state = await(vm) { it.savedName == "1" }
        System.currentTimeMillis() - sentAt shouldBeLessThan 1_000

        state.name shouldBe "2"
        saved shouldBe 1
        val point = runBlocking { points.points(projectId).first() }.single()
        point.name shouldBe "1"
        val measurement = point.measurement.shouldNotBeNull()
        measurement.outOfTolerance.shouldBeEmpty()
        measurement.quality.mode shouldBe MeasurementMode.INSTANT
        measurement.quality.solution shouldBe SolutionStatus.FIX.name
        measurement.poleHeightM shouldBe 1.8
        measurement.rawNmea.lines().size shouldBe 2
    }

    @Test
    fun `S010 RF-3 un nombre que ya existe no deja medir`() {
        val projectId = project()
        val vm = viewModel(projectId)
        receiving(vm)
        vm.measure()
        sendEpoch(2)
        sendEpoch(3)
        await(vm) { it.savedName == "1" }

        vm.setName("1")

        await(vm) { it.blocker == MeasureBlocker.NAME_TAKEN }
        vm.measure()
        vm.state.value.measuring shouldBe false
    }

    @Test
    fun `S010 RF-9, RF-10 y RF-18 el promediado descarta las epocas sin FIX, avisa una vez y guarda cuantas uso`() {
        collectorPreferences.settings.value = CollectorSettings(MeasurementMode.AVERAGED, averagingSeconds = 3)
        val projectId = project()
        val vm = viewModel(projectId)
        receiving(vm)

        vm.measure()
        sendEpoch(2) // FIX
        await(vm) { it.progress != null }
        sendEpoch(3, status = 5) // FLOAT
        sendEpoch(4, status = 5) // FLOAT
        sendEpoch(5)
        sendEpoch(6)
        sendEpoch(7)
        await(vm) { it.savedName == "1" }

        lost shouldBe 1
        val quality = runBlocking { points.points(projectId).first() }.single().measurement.shouldNotBeNull().quality
        quality.epochsUsed shouldBe 3
        quality.epochsDiscarded shouldBe 2
        quality.mode shouldBe MeasurementMode.AVERAGED
    }

    @Test
    fun `S010 RF-11 cancelar un promediado no guarda nada`() {
        collectorPreferences.settings.value = CollectorSettings(MeasurementMode.AVERAGED, averagingSeconds = 5)
        val projectId = project()
        val vm = viewModel(projectId)
        receiving(vm)
        vm.measure()
        sendEpoch(2)
        await(vm) { it.progress != null }

        vm.cancel()
        (3..8).forEach { sendEpoch(it) }

        await(vm) { !it.measuring }.savedName.shouldBeNull()
        runBlocking { points.points(projectId).first() }.shouldBeEmpty()
    }

    @Test
    fun `S010 RF-14 y RF-15 fuera de tolerancia se pregunta y solo se guarda si se confirma, marcado`() {
        val projectId = project()
        val vm = viewModel(projectId)
        receiving(vm, status = 5)

        vm.measure()
        sendEpoch(2, status = 5)
        sendEpoch(3, status = 5)
        await(vm) { it.pending != null }.pending.shouldNotBeNull().failures shouldBe setOf(PrecisionFailure.NOT_FIX)
        runBlocking { points.points(projectId).first() }.shouldBeEmpty()

        vm.confirmOutOfTolerance()

        await(vm) { it.savedName == "1" }
        runBlocking { points.points(projectId).first() }.single().measurement.shouldNotBeNull().outOfTolerance shouldBe
            setOf(PrecisionFailure.NOT_FIX)
    }

    @Test
    fun `S013 RF-16 promediar es una medida en curso hasta cancelar`() {
        collectorPreferences.settings.value = CollectorSettings(MeasurementMode.AVERAGED, averagingSeconds = 5)
        val vm = viewModel(project())
        receiving(vm)
        inProgress.active.value shouldBe false

        vm.measure()
        sendEpoch(2)
        await(vm) { it.progress != null }
        inProgress.active.value shouldBe true

        vm.cancel()
        await(vm) { !it.measuring }
        inProgress.active.value shouldBe false
    }

    @Test
    fun `S013 RF-16 un punto pendiente de confirmar es una medida en curso`() {
        val vm = viewModel(project())
        receiving(vm, status = 5)
        vm.measure()
        sendEpoch(2, status = 5)
        sendEpoch(3, status = 5)
        await(vm) { it.pending != null && !it.measuring }
        inProgress.active.value shouldBe true

        vm.discardPending()
        await(vm) { it.pending == null }
        inProgress.active.value shouldBe false
    }

    @Test
    fun `S010 RF-14 descartar una medida fuera de tolerancia no guarda nada`() {
        val projectId = project()
        val vm = viewModel(projectId)
        receiving(vm, status = 5)
        vm.measure()
        sendEpoch(2, status = 5)
        sendEpoch(3, status = 5)
        await(vm) { it.pending != null }

        vm.discardPending()

        await(vm) { it.pending == null && !it.measuring }
        runBlocking { points.points(projectId).first() }.shouldBeEmpty()
        saved shouldBe 0
    }

    @Test
    fun `S010 RF-9 los segundos se escriben libremente, solo se guardan de 1 a 60 y fuera de eso no dejan promediar`() {
        val vm = viewModel(project())
        vm.setSettings(CollectorSettings(MeasurementMode.AVERAGED))
        await(vm) { it.settings.mode == MeasurementMode.AVERAGED }

        vm.setAveragingSeconds("")
        await(vm) { it.averagingSecondsText == "" && !it.averagingSecondsValid }.blocker shouldBe MeasureBlocker.INVALID_SECONDS
        vm.setAveragingSeconds("20")
        await(vm) { it.settings.averagingSeconds == 20 }.averagingSecondsValid shouldBe true
        vm.setAveragingSeconds("520")
        await(vm) { it.averagingSecondsText == "520" }.blocker shouldBe MeasureBlocker.INVALID_SECONDS
        collectorPreferences.settings.value.averagingSeconds shouldBe 20

        // En instantáneo los segundos no cuentan.
        vm.setSettings(CollectorSettings(MeasurementMode.INSTANT, averagingSeconds = 20))
        await(vm) { it.settings.mode == MeasurementMode.INSTANT }.blocker shouldNotBe MeasureBlocker.INVALID_SECONDS
    }

    @Test
    fun `S010 RF-13 los ajustes del control de precision se guardan para todos los proyectos`() {
        val vm = viewModel(project())
        val chosen = CollectorSettings(precision = PrecisionControl(fixOnly = false, horizontalLimitM = 0.02))

        vm.setSettings(chosen)

        collectorPreferences.settings.value shouldBe chosen
        await(vm) { it.settings == chosen }
    }

    @Test
    fun `S010 RF-6 el recolector da la solucion y las precisiones H y V de la GST`() {
        val vm = viewModel(project())
        receiving(vm)

        // La GGA llega antes que la GST de su época: se espera a tener las dos.
        val live = await(vm) { it.live?.horizontalM != null }.live.shouldNotBeNull()
        live.solution shouldBe SolutionStatus.FIX
        live.horizontalM.shouldNotBeNull() shouldBe (0.01 plusOrMinus 1e-12)
        live.verticalM shouldBe 0.015
    }

    @Test
    fun `S010 RF-9 un promediado con las epocas espaciadas como en campo no se da por falto de datos`() {
        collectorPreferences.settings.value = CollectorSettings(MeasurementMode.AVERAGED, averagingSeconds = 3)
        val projectId = project()
        val vm = viewModel(projectId)
        receiving(vm)

        vm.measure()
        // Más separadas que la comprobación del plazo (250 ms): la hora de la sesión y la del móvil no se mezclan.
        (2..6).forEach { second ->
            sendEpoch(second)
            Thread.sleep(400)
        }

        await(vm) { it.savedName == "1" }.error shouldBe null
        runBlocking { points.points(projectId).first() }.single().measurement.shouldNotBeNull().quality.epochsUsed shouldBe 3
    }

    @Test
    fun `S014 RF-5 una medida fuera de tolerancia y su descarte quedan en el log`() {
        val vm = viewModel(project())
        receiving(vm, status = 5)
        vm.measure()
        sendEpoch(2, status = 5)
        sendEpoch(3, status = 5)
        await(vm) { it.pending != null }

        vm.discardPending()

        log.entries.map { it.first to it.third } shouldBe listOf(
            LogLevel.WARNING to "Medida de 1 fuera de tolerancia: NOT_FIX",
            LogLevel.INFO to "Medida de 1 descartada",
        )
    }

    @Test
    fun `S014 RF-5 la solucion perdida durante un promediado queda en el log`() {
        collectorPreferences.settings.value = CollectorSettings(MeasurementMode.AVERAGED, averagingSeconds = 5)
        val vm = viewModel(project())
        receiving(vm)
        vm.measure()
        sendEpoch(2, status = 5)

        runBlocking { withTimeout(5_000) { while (log.messages.isEmpty()) kotlinx.coroutines.delay(10) } }
        log.entries.first() shouldBe Triple(LogLevel.WARNING, LogCategory.POINTS, "Solución perdida durante la medida de 1")
        vm.cancel()
    }
}
