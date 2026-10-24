package es.topophone.app.ui.collector

import android.content.Context
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.app.log.RecordingLog
import es.topophone.app.receiver.CurrentTilt
import es.topophone.app.receiver.MemoryTiltPreferences
import es.topophone.app.receiver.currentTilt
import es.topophone.app.ui.receiver.FakePreferences
import es.topophone.app.ui.receiver.nmea
import es.topophone.core.model.point.CollectorSettings
import es.topophone.core.model.point.MeasurementMode
import es.topophone.core.model.point.PointTilt
import es.topophone.core.model.point.PrecisionControl
import es.topophone.core.model.point.PrecisionFailure
import es.topophone.core.model.point.TiltMode
import es.topophone.core.model.project.ProjectDefaults
import es.topophone.core.ntrip.NtripConnector
import es.topophone.core.ntrip.NtripFailure
import es.topophone.core.survey.ProjectPositionCalculator
import es.topophone.core.survey.TiltGuide
import es.topophone.data.db.TopoPhoneDatabase
import es.topophone.data.point.CollectorPreferences
import es.topophone.data.point.RoomPointRepository
import es.topophone.data.project.RoomProjectRepository
import es.topophone.data.project.SaveResult
import es.topophone.data.receiver.ReceiverTiltSettings
import es.topophone.receiver.platform.Alerts
import es.topophone.receiver.session.ConnectionState
import es.topophone.receiver.session.ReceiverSession
import es.topophone.receiver.transport.StreamTransport
import io.kotest.matchers.collections.shouldContain
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
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
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

/** El recolector con la inclinación del RS3 (S016/RF-2, RF-12, RF-15, RF-18 a RF-22, RF-28). */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class CollectorTiltTest {

    private val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), TopoPhoneDatabase::class.java)
        .allowMainThreadQueries()
        .build()
    private val projects = RoomProjectRepository(db)
    private val points = RoomPointRepository(db)
    private val receiverPreferences = FakePreferences()
    private val collectorPreferences = FakeCollectorPreferences()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val session = ReceiverSession(
        scope,
        NtripConnector { _, _, _, _ -> NtripFailure.UNREACHABLE },
        noDataTimeoutMs = 60_000, // las épocas se mandan a mano: bajo carga, 2 s sin ninguna dejarían la sesión en "Sin datos"
        watchdogPeriodMs = 1_000,
    )
    private val toApp = PipedOutputStream()
    private val rs3 = "00:11:22:33:44:55"
    private val log = RecordingLog()
    private var tiltLost = 0
    private var saved = 0
    private val alerts = object : Alerts {
        override fun connectionLost() = Unit

        override fun correctionsLost() = Unit

        override fun pointSaved() {
            saved++
        }

        override fun solutionLost() = Unit

        override fun tiltLost() {
            tiltLost++
        }
    }
    private lateinit var tilt: CurrentTilt
    private lateinit var projectId: String
    private lateinit var tiltPreferences: MemoryTiltPreferences

    private class FakeCollectorPreferences : CollectorPreferences {
        override val settings = MutableStateFlow(CollectorSettings())

        override suspend fun save(settings: CollectorSettings) {
            this.settings.value = settings
        }
    }

    /** Los recolectores de la prueba: un guardado en vuelo al cerrar la base filtraba su excepción al test siguiente. */
    private val viewModels = mutableListOf<CollectorViewModel>()

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() {
        viewModels.forEach { it.viewModelScope.cancel() }
        runCatching { toApp.close() }
        session.disconnect()
        scope.cancel()
        db.close()
        Dispatchers.resetMain()
    }

    private fun viewModel(mode: TiltMode?, verified: Boolean = true): CollectorViewModel {
        tiltPreferences = MemoryTiltPreferences(if (mode == null) emptyMap() else mapOf(rs3 to ReceiverTiltSettings(mode, verified)))
        tilt = currentTilt(scope, tiltPreferences, address = rs3)
        runBlocking { withTimeout(5_000) { tilt.state.first { it.key == rs3 } } }
        projectId = runBlocking { (projects.create(ProjectDefaults().toDraft().copy(name = "Calle Mayor 12")) as SaveResult.Saved).id }
        val vm = CollectorViewModel(
            projects = projects,
            points = points,
            collectorPreferences = collectorPreferences,
            receiverPreferences = receiverPreferences,
            session = session,
            alerts = alerts,
            calculator = { ProjectPositionCalculator() },
            log = log,
            tilt = tilt,
        )
        viewModels += vm
        vm.start(projectId)
        CoroutineScope(Dispatchers.Unconfined).launch { vm.state.collect {} }
        await(vm) { it.loaded }
        // El proyecto llega de Room aparte del formulario; hasta entonces `measure()` se sale en silencio.
        runBlocking { withTimeout(10_000) { while (!vm.isProjectLoaded) kotlinx.coroutines.delay(10) } }
        return vm
    }

    private fun await(vm: CollectorViewModel, predicate: (CollectorUiState) -> Boolean): CollectorUiState =
        runBlocking { withTimeout(10_000) { vm.state.first(predicate) } }

    /** Una época por segundo con su GGA, su GST y, si se da, su ETC. */
    private fun sendEpoch(second: Int, etc: String? = COMPENSATING, status: Int = 4) {
        val time = "1015%02d.00".format(30 + second)
        toApp.write(nmea("GNGGA,$time,4025.0080000,N,00342.2228000,W,$status,24,0.6,650.123,M,51.234,M,1.0,0001").toByteArray())
        toApp.write(nmea("GNGST,$time,0.012,0.010,0.007,45.0,0.008,0.006,0.015").toByteArray())
        etc?.let { toApp.write(nmea("GNETC,$time,$it").toByteArray()) }
        toApp.flush()
    }

    /** Conecta y manda dos épocas con [etc]: la primera queda completa. */
    private fun receiving(vm: CollectorViewModel, etc: String?) {
        session.connect(StreamTransport("reach-RS3", PipedInputStream(toApp, 64 * 1024), ByteArrayOutputStream()))
        sendEpoch(0, etc)
        sendEpoch(1, etc)
        runBlocking { withTimeout(5_000) { session.state.first { it.connection is ConnectionState.Receiving && it.epoch != null } } }
        await(vm) { it.live != null }
    }

    @Test
    fun `S016 RF-2 sin modo y con la inclinacion encendida no se mide`() {
        val vm = viewModel(mode = null)

        receiving(vm, ALIGNMENT)

        await(vm) { it.blocker == MeasureBlocker.TILT_MODE_MISSING }
    }

    @Test
    fun `S016 RF-2 sin modo ni ETC se mide con el jalon vertical`() {
        val vm = viewModel(mode = null)

        receiving(vm, etc = null)

        await(vm) { it.live != null }.blocker shouldBe null
    }

    @Test
    fun `S016 RF-2 sin modo y con la inclinacion apagada se mide`() {
        val vm = viewModel(mode = null)

        receiving(vm, OFF)

        await(vm) { it.live != null }.blocker shouldBe null
    }

    @Test
    fun `S016 RF-18 en APP sin estado 30 no se mide y se guia`() {
        val vm = viewModel(TiltMode.APP)

        receiving(vm, ALIGNMENT)

        // Sin ETC todavía también está "sin estado 30": se espera a la guía de la alineación.
        val state = await(vm) { it.blocker == MeasureBlocker.TILT_NOT_READY && it.tiltGuide == TiltGuide.ALIGNMENT }
        state.tiltGuide shouldBe TiltGuide.ALIGNMENT
    }

    @Test
    fun `S016 RF-12 con un aviso en estado 30 se mide y se guia`() {
        val vm = viewModel(TiltMode.APP)

        receiving(vm, FAST_MOTION)

        val state = await(vm) { it.tiltGuide == TiltGuide.FAST_MOTION }
        state.blocker shouldBe null
    }

    @Test
    fun `S016 RF-12 en estado 30 sin aviso no hay guia`() {
        val vm = viewModel(TiltMode.APP)

        receiving(vm, COMPENSATING)

        val state = await(vm) { it.live != null && it.blocker == null }
        state.tiltGuide shouldBe null
        state.tiltMode shouldBe TiltMode.APP
    }

    @Test
    fun `S016 RF-15 en RECEIVER el recolector conoce el modo`() {
        val vm = viewModel(TiltMode.RECEIVER)

        receiving(vm, COMPENSATING)

        await(vm) { it.live != null }.tiltMode shouldBe TiltMode.RECEIVER
    }

    @Test
    fun `S016 RF-19 las epocas con aviso se descartan con sonido`() {
        val vm = viewModel(TiltMode.APP)
        collectorPreferences.settings.value = CollectorSettings(MeasurementMode.AVERAGED, averagingSeconds = 2)
        receiving(vm, COMPENSATING)

        await(vm) { it.blocker == null }
        vm.measure()
        sendEpoch(2, FAST_MOTION)
        await(vm) { it.tiltLost == TiltGuide.FAST_MOTION }
        (3..5).forEach { sendEpoch(it, COMPENSATING) }

        await(vm) { it.savedName != null }
        tiltLost shouldBe 1
        log.messages.any { it.startsWith("Épocas descartadas por la inclinación") && it.endsWith("movimiento rápido") } shouldBe true
    }

    @Test
    fun `S016 RF-20 superar la inclinacion maxima pide confirmar`() {
        val vm = viewModel(TiltMode.APP)
        collectorPreferences.settings.value = CollectorSettings(precision = PrecisionControl(maxTiltRad = Math.toRadians(10.0)))
        receiving(vm, COMPENSATING)

        await(vm) { it.blocker == null }
        vm.measure()
        sendEpoch(2, COMPENSATING)

        await(vm) { it.pending != null }.pending.shouldNotBeNull().failures shouldBe setOf(PrecisionFailure.TILT)
    }

    @Test
    fun `S016 RF-20 en jalon vertical la inclinacion maxima no cuenta`() {
        val vm = viewModel(TiltMode.VERTICAL)
        collectorPreferences.settings.value = CollectorSettings(precision = PrecisionControl(maxTiltRad = Math.toRadians(10.0)))
        receiving(vm, COMPENSATING)

        await(vm) { it.blocker == null }
        vm.measure()
        sendEpoch(2, COMPENSATING)

        await(vm) { it.savedName != null }.pending shouldBe null
    }

    @Test
    fun `S016 RF-21 la precision en directo suma la de la inclinacion`() {
        val vm = viewModel(TiltMode.APP)

        receiving(vm, COMPENSATING)

        // La época llega antes que el ETC que la completa: hasta entonces la precisión no lleva la de la inclinación.
        val live = await(vm) { (it.live?.horizontalM ?: 0.0) > 0.0101 }.live.shouldNotBeNull()
        val tilt = Math.hypot(0.993, 1.013) * (1.8 + 0.134) / 1000
        live.horizontalM.shouldNotBeNull() shouldBe ((Math.hypot(0.008, 0.006) + tilt) plusOrMinus 1e-9)
        live.verticalM.shouldNotBeNull() shouldBe ((0.015 + 1.021 * 1.934 / 1000) plusOrMinus 1e-9)
    }

    @Test
    fun `S016 RF-28 cambiar el modo durante una medida la cancela`() {
        val vm = viewModel(TiltMode.APP)
        collectorPreferences.settings.value = CollectorSettings(MeasurementMode.AVERAGED, averagingSeconds = 30)
        receiving(vm, COMPENSATING)
        await(vm) { it.blocker == null }
        vm.measure()
        await(vm) { it.measuring }

        runBlocking { tilt.choose(TiltMode.VERTICAL) }

        val state = await(vm) { it.error == CollectorError.TILT_MODE_CHANGED }
        state.measuring shouldBe false
        log.messages shouldContain "Medida de 1 cancelada: ha cambiado el modo de inclinación"
    }

    @Test
    fun `S016 RF-28 abrir el asistente durante una medida la cancela`() {
        val vm = viewModel(TiltMode.APP)
        collectorPreferences.settings.value = CollectorSettings(MeasurementMode.AVERAGED, averagingSeconds = 30)
        receiving(vm, COMPENSATING)
        await(vm) { it.blocker == null }
        vm.measure()
        await(vm) { it.measuring }

        tilt.openWizard()

        await(vm) { it.error == CollectorError.TILT_MODE_CHANGED }
        saved shouldBe 0
    }

    private fun savedPoint() = runBlocking { withTimeout(5_000) { points.points(projectId).first { it.isNotEmpty() } } }.single()

    @Test
    fun `S016 RF-22 el punto guarda su modo y su inclinacion`() {
        val vm = viewModel(TiltMode.APP, verified = true)
        receiving(vm, COMPENSATING)

        await(vm) { it.blocker == null }
        vm.measure()
        sendEpoch(2, COMPENSATING)

        val measurement = savedPoint().measurement.shouldNotBeNull()
        val tilt = measurement.tilt.shouldNotBeNull()
        tilt.mode shouldBe TiltMode.APP
        tilt.verified shouldBe true
        val angles = tilt.angles.shouldNotBeNull()
        angles.tiltRad shouldBe (Math.toRadians(12.0) plusOrMinus 1e-12)
        angles.directionRad shouldBe (Math.toRadians(210.5) plusOrMinus 1e-12)
        angles.headingRad.shouldNotBeNull() shouldBe (Math.toRadians(123.4) plusOrMinus 1e-12)
        angles.maxTiltRad shouldBe (Math.toRadians(12.0) plusOrMinus 1e-12)
        measurement.rawNmea.lines().any { it.startsWith("\$GNETC,") } shouldBe true
        // La punta no está bajo la antena: el jalón está inclinado 12°.
        (measurement.coordinates.ground.latitudeDeg != measurement.antenna.latitudeDeg) shouldBe true
    }

    @Test
    fun `S016 RF-22 en RECEIVER se guarda el jalon declarado`() {
        val vm = viewModel(TiltMode.RECEIVER, verified = false)
        receiving(vm, COMPENSATING)

        await(vm) { it.blocker == null }
        vm.measure()
        sendEpoch(2, COMPENSATING)

        val measurement = savedPoint().measurement.shouldNotBeNull()
        measurement.tilt.shouldNotBeNull().verified shouldBe false
        measurement.tilt!!.angles.shouldNotBeNull().receiverPoleHeightM shouldBe 1.8
        measurement.coordinates.ground shouldBe measurement.antenna
    }

    @Test
    fun `S016 RF-22 sin modo ni ETC el punto queda como jalon vertical`() {
        val vm = viewModel(mode = null)
        receiving(vm, etc = null)

        await(vm) { it.blocker == null }
        vm.measure()
        sendEpoch(2, etc = null)

        savedPoint().measurement.shouldNotBeNull().tilt shouldBe PointTilt(TiltMode.VERTICAL, verified = false, angles = null)
    }

    @Test
    fun `S016 RF-12 la guia de la inclinacion queda en el log`() {
        val vm = viewModel(TiltMode.APP)

        receiving(vm, ALIGNMENT)

        await(vm) { it.tiltGuide == TiltGuide.ALIGNMENT }
        val message = "Guía de la inclinación: la inclinación está en alineación"
        runBlocking { withTimeout(5_000) { while (message !in log.messages) delay(10) } }
    }

    private companion object {
        const val COMPENSATING = "30,0,123.40,210.50,12.00,1.013,0.993,1.021"
        const val FAST_MOTION = "30,10,123.40,210.50,12.00,1.013,0.993,1.021"
        const val ALIGNMENT = "20,0,,,,,,"
        const val OFF = ",,,,,,,"
    }
}
