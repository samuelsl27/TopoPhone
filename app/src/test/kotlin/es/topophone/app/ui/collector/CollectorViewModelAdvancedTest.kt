package es.topophone.app.ui.collector

import android.content.Context
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.app.log.RecordingLog
import es.topophone.app.ui.receiver.FakePreferences
import es.topophone.app.ui.receiver.nmea
import es.topophone.core.model.point.CollectorSettings
import es.topophone.core.model.point.MeasurementMode
import es.topophone.core.model.point.PrecisionControl
import es.topophone.core.model.point.PrecisionFailure
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

/**
 * El recolector avanzado (S017): el promediado de hasta 60 min con sus estadísticas y "Terminar", la pausa ante un corte
 * y el modo automático. Con la `ReceiverSession` real y las épocas de una en una, a 1 Hz.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class CollectorViewModelAdvancedTest {

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
    private val log = RecordingLog()
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

        override fun tiltLost() = Unit
    }

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
        sessionScope.cancel()
        db.close()
        Dispatchers.resetMain()
    }

    private fun project(): String = runBlocking {
        (projects.create(ProjectDefaults().toDraft().copy(name = "Calle Mayor 12")) as SaveResult.Saved).id
    }

    /** Un recolector que promedia [seconds] s: espera a que sus ajustes lleguen, que si no "Medir" mediría en instantáneo. */
    private fun averagedViewModel(
        seconds: Int,
        precision: PrecisionControl = PrecisionControl(),
        projectId: String = project(),
    ): CollectorViewModel {
        collectorPreferences.settings.value = CollectorSettings(MeasurementMode.AVERAGED, averagingSeconds = seconds, precision = precision)
        val vm = viewModel(projectId)
        await(vm) { it.settings.mode == MeasurementMode.AVERAGED && it.settings.averagingSeconds == seconds }
        return vm
    }

    private fun viewModel(projectId: String): CollectorViewModel {
        val vm = CollectorViewModel(
            projects,
            points,
            collectorPreferences,
            receiverPreferences,
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
        await(vm) { it.loaded }
        return vm
    }

    /** Espera al estado que cumple [predicate]; si no llega, el fallo dice en qué estado se quedó. */
    private fun await(vm: CollectorViewModel, predicate: (CollectorUiState) -> Boolean): CollectorUiState = runBlocking {
        try {
            withTimeout(10_000) { vm.state.first(predicate) }
        } catch (e: TimeoutCancellationException) {
            throw AssertionError(
                "El recolector no llegó al estado esperado; se quedó en ${vm.state.value}; estadísticas ${vm.stats.value}",
                e,
            )
        }
    }

    private fun awaitStats(vm: CollectorViewModel, predicate: (AverageStatsUi) -> Boolean): AverageStatsUi = runBlocking {
        try {
            withTimeout(10_000) { vm.stats.first { it != null && predicate(it) } }!!
        } catch (e: TimeoutCancellationException) {
            throw AssertionError("Las estadísticas no llegaron; el recolector está en ${vm.state.value}; estadísticas ${vm.stats.value}", e)
        }
    }

    /** Una época a 1 Hz: GGA (con la calidad de [status]) y GST; [lat] en minutos con siete decimales (1 cm son 0,0000054). */
    private fun sendEpoch(second: Int, status: Int = 4, lat: String = "4025.0080000") {
        val time = "1015%02d.00".format(30 + second)
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

    private companion object {
        /** La hora UTC de la época 1 de `sendEpoch`: 10:15:31. */
        const val EPOCH_1_UTC_MS = 36_931_000
    }

    @Test
    fun `S017 RF-1 el tiempo se escribe en minutos y segundos y se guarda de 1 s a 60 min`() {
        val vm = viewModel(project())
        vm.state.value.averagingSecondsText shouldBe "0:05"

        vm.setAveragingSeconds("1:30")
        await(vm) { it.settings.averagingSeconds == 90 }.averagingSecondsValid shouldBe true
        vm.state.value.averagingSecondsText shouldBe "1:30"

        vm.setAveragingSeconds("60:00")
        await(vm) { it.settings.averagingSeconds == 3_600 }.averagingSecondsValid shouldBe true

        vm.setAveragingSeconds("45") // un entero son segundos
        await(vm) { it.settings.averagingSeconds == 45 }

        vm.setAveragingSeconds("60:01")
        await(vm) { it.averagingSecondsText == "60:01" }.averagingSecondsValid shouldBe false
        collectorPreferences.settings.value.averagingSeconds shouldBe 45
    }

    @Test
    fun `S017 RF-2 mientras se promedia se ve el tiempo reunido y las epocas usadas y descartadas`() {
        val vm = averagedViewModel(seconds = 10)
        receiving(vm)

        vm.measure()
        sendEpoch(2) // usada
        sendEpoch(3, status = 5) // FLOAT: descartada
        sendEpoch(4) // usada
        val progress = await(vm) { it.progress?.epochsUsed == 2 && it.progress?.epochsDiscarded == 1 }.progress.shouldNotBeNull()

        progress.elapsedS shouldBe (2.0 plusOrMinus 1e-9)
        progress.requiredS shouldBe (10.0 plusOrMinus 1e-9)
    }

    @Test
    fun `S017 RF-3 RF-4 y RF-5 las estadisticas y el grafico salen en su propio flujo`() {
        val vm = averagedViewModel(seconds = 60)
        receiving(vm)
        vm.stats.value.shouldBeNull()

        vm.measure()
        sendEpoch(2, lat = "4025.0080000")
        sendEpoch(3, lat = "4025.0080054") // 1 cm al norte
        sendEpoch(4, lat = "4025.0080000")
        val stats = awaitStats(vm) { it.snapshot.used == 3 }

        stats.snapshot.timeS shouldBe (3.0 plusOrMinus 1e-9)
        stats.snapshot.deviationHorizontalM.shouldNotBeNull() shouldBe (0.0058 plusOrMinus 0.002)
        stats.snapshot.deviationVerticalM.shouldNotBeNull() shouldBe (0.0 plusOrMinus 1e-6)
        stats.snapshot.stabilityHorizontalM.shouldBeNull() // aún no ha pasado un minuto
        stats.dispersion.shouldNotBeNull().count shouldBe 3

        vm.cancel()
        runBlocking { withTimeout(5_000) { vm.stats.first { it == null } } }
    }

    @Test
    fun `S017 RF-6 Terminar guarda el punto con las epocas reunidas`() {
        val projectId = project()
        val vm = averagedViewModel(seconds = 600, projectId = projectId)
        receiving(vm)

        vm.measure()
        (2..5).forEach { sendEpoch(it) }
        await(vm) { it.progress?.epochsUsed == 4 }
        vm.finishNow()
        await(vm) { it.savedName == "1" }

        val measurement = runBlocking { points.points(projectId).first() }.single().measurement.shouldNotBeNull()
        measurement.quality.mode shouldBe MeasurementMode.AVERAGED
        measurement.quality.epochsUsed shouldBe 4
        measurement.rawNmea.lines().size shouldBe 8
        saved shouldBe 1
        vm.state.value.measuring shouldBe false
    }

    @Test
    fun `S017 RF-6 Terminar pasa por el control de precision y pide confirmar si no se cumple`() {
        val projectId = project()
        val vm = averagedViewModel(600, PrecisionControl(fixOnly = false, horizontalLimitM = 0.001), projectId)
        receiving(vm)

        vm.measure()
        sendEpoch(2)
        sendEpoch(3)
        await(vm) { it.progress?.epochsUsed == 2 }
        vm.finishNow()

        val pending = await(vm) { it.pending != null && !it.measuring }.pending.shouldNotBeNull()
        pending.failures shouldBe setOf(PrecisionFailure.HORIZONTAL)
        runBlocking { points.points(projectId).first() }.shouldBeEmpty()
        saved shouldBe 0
    }

    @Test
    fun `S017 RF-7 Terminar sin ninguna epoca usada no guarda nada y la medida sigue`() {
        val projectId = project()
        val vm = averagedViewModel(seconds = 600, projectId = projectId)
        receiving(vm)
        // La época 1 sigue pendiente y es FIX: se completa con la 2, antes de empezar, para que no cuente.
        sendEpoch(2, status = 5)
        runBlocking { withTimeout(5_000) { session.state.first { it.epoch?.utcMillisOfDay == EPOCH_1_UTC_MS } } }

        vm.measure()
        sendEpoch(3, status = 5) // FLOAT con "solo FIX": se descarta
        sendEpoch(4, status = 5)
        await(vm) { it.progress?.epochsDiscarded == 2 }
        vm.finishNow()

        val waiting = await(vm) { it.noEpochsYet }
        waiting.measuring shouldBe true
        runBlocking { points.points(projectId).first() }.shouldBeEmpty()
        saved shouldBe 0

        sendEpoch(5) // FIX: la medida sigue y el aviso se va
        sendEpoch(6)
        await(vm) { it.progress?.epochsUsed == 1 && !it.noEpochsYet }.measuring shouldBe true
    }
}
