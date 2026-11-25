package es.topophone.app.ui.collector

import android.content.Context
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.app.log.RecordingLog
import es.topophone.app.ui.receiver.nmea
import es.topophone.core.model.point.AdmittedSolutions
import es.topophone.core.model.point.AutoTrigger
import es.topophone.core.model.point.CollectorSettings
import es.topophone.core.model.point.MeasurementMode
import es.topophone.core.model.point.PrecisionControl
import es.topophone.core.model.point.PrecisionFailure
import es.topophone.core.model.point.SolutionType
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
import es.topophone.receiver.session.ReceiverSession
import es.topophone.receiver.transport.StreamTransport
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
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
 * El recolector con los tipos de solución admitidos (S047/RF-6, RF-7, RF-10, RF-11, RF-15), con la `ReceiverSession` real y las
 * épocas de una en una, a 1 Hz.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class CollectorViewModelAdmittedTest {

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
    private var lost = 0
    private val alerts = object : Alerts {
        override fun connectionLost() = Unit

        override fun correctionsLost() = Unit

        override fun pointSaved() = Unit

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

    private fun viewModel(settings: CollectorSettings, projectId: String): CollectorViewModel {
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
        await(vm) { it.loaded && it.settings == settings }
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

    private fun sendEpoch(second: Int, status: Int = 4) {
        val t = 36_930 + second
        val time = "%02d%02d%02d.00".format(t / 3_600, t / 60 % 60, t % 60)
        toApp.write(nmea("GNGGA,$time,4025.0080000,N,00342.2228000,W,$status,24,0.6,650.123,M,51.234,M,1.0,0001").toByteArray())
        toApp.write(nmea("GNGST,$time,0.012,0.010,0.007,45.0,0.008,0.006,0.015").toByteArray())
        toApp.flush()
    }

    private fun receiving(vm: CollectorViewModel) {
        session.connect(StreamTransport("reach-RS3", PipedInputStream(toApp, 64 * 1024), ByteArrayOutputStream()))
        sendEpoch(0)
        sendEpoch(1)
        awaitReceiving(session, vm)
        await(vm) { it.live != null && it.blocker == null }
    }

    /**
     * Manda la época [second] en [status] y espera a que la anterior esté completa: la medida que empiece después usará la [second],
     * y no la última de `receiving`, que sigue abierta hasta que llegue otra.
     */
    private fun sendThenSettle(vm: CollectorViewModel, second: Int, status: Int) {
        sendEpoch(second, status)
        awaitEpoch(session, vm, (36_930 + second - 1) * 1_000)
    }

    private fun startMeasuring(vm: CollectorViewModel) = runBlocking {
        withTimeout(10_000) {
            while (!vm.state.value.measuring) {
                vm.measure()
                kotlinx.coroutines.delay(50)
            }
        }
    }

    private fun stored(projectId: String) = runBlocking { points.points(projectId).first() }

    @Test
    fun `S047 RNF cambiar los tipos admitidos se refleja en el recolector en menos de 200 ms`() {
        val vm = viewModel(CollectorSettings(), project())
        val admitted = AdmittedSolutions.of(setOf(SolutionType.FIX, SolutionType.FLOAT))

        val start = System.nanoTime()
        vm.setSettings(CollectorSettings(precision = PrecisionControl(admitted = admitted)))
        await(vm) { it.settings.precision.admitted == admitted }

        ((System.nanoTime() - start) / 1_000_000 < 200) shouldBe true
    }

    @Test
    fun `S047 RF-15 con FLOAT admitido la instantanea en FLOAT se guarda con los tipos admitidos`() {
        val projectId = project()
        val admitted = AdmittedSolutions.of(setOf(SolutionType.FIX, SolutionType.FLOAT))
        val vm = viewModel(CollectorSettings(precision = PrecisionControl(admitted = admitted)), projectId)
        receiving(vm)

        sendThenSettle(vm, 2, status = 5)
        startMeasuring(vm)
        sendEpoch(3, status = 5)
        await(vm) { it.savedName == "1" }

        val quality = stored(projectId).single().measurement.shouldNotBeNull()
        quality.quality.solution shouldBe "FLOAT"
        quality.quality.admitted shouldBe admitted
        quality.quality.epochsDiscardedByType shouldBe 0
        quality.outOfTolerance.shouldBeEmpty()
    }

    @Test
    fun `S047 RF-6 y RF-7 con solo FIX una instantanea en FLOAT pide confirmar y se guarda fuera de tolerancia`() {
        val projectId = project()
        val vm = viewModel(CollectorSettings(), projectId)
        receiving(vm)

        sendThenSettle(vm, 2, status = 5)
        startMeasuring(vm)
        sendEpoch(3, status = 5)
        val pending = await(vm) { it.pending != null && !it.measuring }.pending.shouldNotBeNull()
        pending.failures shouldBe setOf(PrecisionFailure.NOT_FIX)
        stored(projectId).shouldBeEmpty()

        vm.confirmOutOfTolerance()
        await(vm) { it.savedName == "1" }
        stored(projectId).single().measurement.shouldNotBeNull().outOfTolerance shouldBe setOf(PrecisionFailure.NOT_FIX)
    }

    @Test
    fun `S047 RF-10 en un promediado una epoca peor que el mejor tipo avisa de solucion degradada y se cuenta por tipo`() {
        val projectId = project()
        val vm = viewModel(CollectorSettings(MeasurementMode.AVERAGED, averagingSeconds = 600, precision = PrecisionControl()), projectId)
        receiving(vm)

        startMeasuring(vm)
        sendEpoch(2)
        sendEpoch(3, status = 5)
        sendEpoch(4, status = 5)

        await(vm) { it.solutionLost && it.progress?.epochsDiscarded == 2 }
        lost shouldBe 1
        log.messages shouldContain "Solución degradada durante la medida de 1"
    }

    @Test
    fun `S047 RF-11 el modo automatico omite el punto cuya epoca no es de un tipo admitido`() {
        val projectId = project()
        val settings = CollectorSettings(autoTrigger = AutoTrigger.TIME, autoIntervalS = 2)
        val vm = viewModel(settings, projectId)
        vm.setAutoEnabled(true)
        await(vm) { it.autoEnabled }
        receiving(vm)
        sendThenSettle(vm, 2, status = 5)

        runBlocking {
            withTimeout(10_000) {
                while (!vm.state.value.auto.running) {
                    vm.startAuto()
                    kotlinx.coroutines.delay(50)
                }
            }
        }
        (3..9).forEach { sendEpoch(it, status = 5) }
        await(vm) { it.auto.omitted >= 2 }

        stored(projectId).shouldBeEmpty()
        log.messages shouldContain "Punto omitido del modo automático: no cumple el control de precisión: NOT_FIX"
    }
}
