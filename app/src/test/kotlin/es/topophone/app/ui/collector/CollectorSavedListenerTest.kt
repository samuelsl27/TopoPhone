package es.topophone.app.ui.collector

import android.content.Context
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.app.log.RecordingLog
import es.topophone.app.ui.receiver.nmea
import es.topophone.core.model.point.CollectorSettings
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
 * El recolector avisa de cada punto que guarda a quien lo pida, para que una toma lineal lo añada (S052/RF-2). Con la `ReceiverSession`
 * real y las épocas de una en una.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class CollectorSavedListenerTest {

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

    private fun viewModel(): CollectorViewModel {
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
        await(vm) { it.loaded }
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

    /** Mide un punto instantáneo y espera a que se guarde con [name]. */
    private fun measure(vm: CollectorViewModel, name: String) {
        sendEpoch(next++)
        vm.measure()
        sendEpoch(next++)
        await(vm) { it.savedName == name }
    }

    @Test
    fun `S052 RF-2 el aviso trae el id el nombre y las coordenadas del punto guardado`() {
        val vm = viewModel()
        receiving(vm)
        val heard = mutableListOf<SavedPoint>()
        vm.setPointSavedListener { heard += it }

        measure(vm, "1")

        val saved = runBlocking { points.points(projectId).first() }.single()
        heard.size shouldBe 1
        heard.single().id shouldBe saved.id
        heard.single().name shouldBe "1"
        heard.single().coordinates shouldBe saved.coordinates
    }

    @Test
    fun `S052 RF-2 hay un aviso por cada punto y ninguno si se quita quien escucha`() {
        val vm = viewModel()
        receiving(vm)
        val heard = mutableListOf<String>()
        vm.setPointSavedListener { heard += it.name }

        measure(vm, "1")
        measure(vm, "2")
        vm.setPointSavedListener(null)
        measure(vm, "3")

        heard shouldBe listOf("1", "2")
        runBlocking { points.points(projectId).first() }.map { it.name } shouldBe listOf("1", "2", "3")
    }

    @Test
    fun `S052 RF-2 si quien escucha falla el punto se guarda igual y el fallo queda en el log`() {
        val vm = viewModel()
        receiving(vm)
        vm.setPointSavedListener { error("fallo de la toma") }

        measure(vm, "1")

        runBlocking { points.points(projectId).first() }.map { it.name } shouldBe listOf("1")
        log.messages.any { it.contains("No se pudo añadir el punto guardado a la toma") } shouldBe true
        log.messages.none { it.contains("fallo de la toma") } shouldBe true
    }
}
