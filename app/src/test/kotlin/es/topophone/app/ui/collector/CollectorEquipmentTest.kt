package es.topophone.app.ui.collector

import android.content.Context
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.app.log.RecordingLog
import es.topophone.app.receiver.CurrentEquipment
import es.topophone.app.ui.receiver.nmea
import es.topophone.core.model.guide.EquipmentProfile
import es.topophone.core.model.point.CollectorSettings
import es.topophone.core.model.project.ProjectDefaults
import es.topophone.core.model.receiver.ReceiverKey
import es.topophone.core.ntrip.NtripConnector
import es.topophone.core.ntrip.NtripFailure
import es.topophone.core.survey.ProjectPositionCalculator
import es.topophone.data.db.TopoPhoneDatabase
import es.topophone.data.equipment.RoomEquipmentRepository
import es.topophone.data.point.CollectorPreferences
import es.topophone.data.point.RoomPointRepository
import es.topophone.data.project.RoomProjectRepository
import es.topophone.data.project.SaveResult
import es.topophone.receiver.platform.Alerts
import es.topophone.receiver.session.ConnectionState
import es.topophone.receiver.session.ReceiverSession
import es.topophone.receiver.transport.StreamTransport
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

/**
 * El recolector guarda con cada punto los datos del receptor del momento en que empezó a medir, y no los guarda con el móvil, el
 * virtual ni una reproducción (S043/RF-19, RF-23).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class CollectorEquipmentTest {

    private val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), TopoPhoneDatabase::class.java)
        .allowMainThreadQueries()
        .build()
    private val projects = RoomProjectRepository(db)
    private val points = RoomPointRepository(db)
    private val equipmentRepository = RoomEquipmentRepository(db)
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

    private val key = MutableStateFlow<String?>("AA:BB")
    private val equipmentScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val currentEquipment = CurrentEquipment(key, equipmentRepository, equipmentScope)
    private val viewModels = mutableListOf<CollectorViewModel>()
    private val rs3 = EquipmentProfile("Reach RS3", "RS3-0042", "2026.2")

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() {
        viewModels.forEach { it.viewModelScope.cancel() }
        runCatching { toApp.close() }
        session.disconnect()
        sessionScope.cancel()
        equipmentScope.cancel()
        db.close()
        Dispatchers.resetMain()
    }

    private fun project(): String = runBlocking {
        (projects.create(ProjectDefaults().toDraft().copy(name = "Calle Mayor 12")) as SaveResult.Saved).id
    }

    private fun viewModel(projectId: String): CollectorViewModel {
        val vm = CollectorViewModel(
            projects,
            points,
            collectorPreferences,
            session,
            alerts,
            { ProjectPositionCalculator() },
            MeasurementInProgress(),
            log,
            receiverEquipment = currentEquipment,
        )
        viewModels += vm
        vm.start(projectId)
        CoroutineScope(Dispatchers.Unconfined).launch { vm.state.collect {} }
        await(vm) { it.loaded }
        // El proyecto llega de Room aparte del formulario; hasta entonces `measure()` se sale en silencio.
        runBlocking { withTimeout(10_000) { while (!vm.isProjectLoaded) delay(10) } }
        return vm
    }

    private fun await(vm: CollectorViewModel, predicate: (CollectorUiState) -> Boolean): CollectorUiState = runBlocking {
        try {
            withTimeout(10_000) { vm.state.first(predicate) }
        } catch (e: TimeoutCancellationException) {
            throw AssertionError("El recolector no llegó al estado esperado; se quedó en ${vm.state.value}; log ${log.messages}", e)
        }
    }

    private fun awaitEquipment(expected: EquipmentProfile?) {
        runBlocking { withTimeout(10_000) { currentEquipment.state.first { it == expected } } }
    }

    private fun sendEpoch(second: Int) {
        val time = "1015%02d.00".format(30 + second)
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

    private fun saveOnePoint(vm: CollectorViewModel, projectId: String, beforeEpochs: () -> Unit = {}): EquipmentProfile? {
        vm.measure()
        beforeEpochs()
        sendEpoch(2)
        sendEpoch(3)
        await(vm) { it.savedName == "1" }
        return runBlocking { points.points(projectId).first() }.single().measurement.shouldNotBeNull().equipment
    }

    @Test
    fun `S043 RF-19 el punto guarda los datos del receptor en uso`() {
        runBlocking { equipmentRepository.save("AA:BB", rs3) }
        awaitEquipment(rs3)
        val projectId = project()
        val vm = viewModel(projectId)
        receiving(vm)

        saveOnePoint(vm, projectId) shouldBe rs3
    }

    @Test
    fun `S043 RF-19 editar el perfil a medias no cambia el del punto`() {
        runBlocking { equipmentRepository.save("AA:BB", rs3) }
        awaitEquipment(rs3)
        val projectId = project()
        val vm = viewModel(projectId)
        receiving(vm)

        val saved = saveOnePoint(vm, projectId) {
            runBlocking { equipmentRepository.save("AA:BB", rs3.copy(firmware = "2026.9")) }
            awaitEquipment(rs3.copy(firmware = "2026.9"))
        }

        saved shouldBe rs3
    }

    @Test
    fun `S043 RF-19 sin datos guardados el punto no lleva equipo`() {
        val projectId = project()
        val vm = viewModel(projectId)
        receiving(vm)

        saveOnePoint(vm, projectId).shouldBeNull()
    }

    @Test
    fun `S043 RF-23 con una reproduccion el punto no lleva equipo`() {
        for (fixed in listOf(CurrentEquipment.REPLAY_KEY, ReceiverKey.VIRTUAL, ReceiverKey.PHONE)) {
            runBlocking { equipmentRepository.save(fixed, rs3) }
        }
        key.value = CurrentEquipment.REPLAY_KEY
        awaitEquipment(null)
        val projectId = project()
        val vm = viewModel(projectId)
        receiving(vm)

        saveOnePoint(vm, projectId).shouldBeNull()
    }
}
