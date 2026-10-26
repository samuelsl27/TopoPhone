package es.topophone.app.ui.collector

import android.content.Context
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.app.log.RecordingLog
import es.topophone.app.ui.receiver.FakePreferences
import es.topophone.app.ui.receiver.nmea
import es.topophone.core.model.cad.CadSourceCrs
import es.topophone.core.model.point.CollectorSettings
import es.topophone.core.model.point.Point
import es.topophone.core.model.point.PointOrigin
import es.topophone.core.model.project.LinearUnit
import es.topophone.core.model.project.ProjectDefaults
import es.topophone.core.model.surface.Surface
import es.topophone.core.model.surface.SurfaceExtent
import es.topophone.core.model.surface.SurfaceGuide
import es.topophone.core.model.surface.SurfaceHeight
import es.topophone.core.model.surface.SurfaceHeightSource
import es.topophone.core.ntrip.NtripConnector
import es.topophone.core.ntrip.NtripFailure
import es.topophone.core.survey.ProjectPositionCalculator
import es.topophone.data.db.TopoPhoneDatabase
import es.topophone.data.point.CollectorPreferences
import es.topophone.data.point.RoomPointRepository
import es.topophone.data.project.RoomProjectRepository
import es.topophone.data.project.SaveResult
import es.topophone.data.surface.SurfaceGuides
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
 * El recolector con una superficie activa y sin replantear (S042/RF-33): la cota de la superficie con el desplazamiento bajo la punta, la
 * diferencia con la cota de la punta, fuera de la superficie y cargando, y que no se guarde nada de ello en el punto que se mide.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class CollectorSurfaceTest {

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

    private val guide = MutableStateFlow<SurfaceGuide?>(null)

    private val surfaceModel = Surface(
        "s1", projectId, "terreno", "obra.xml", 1_792_000_000_000L, CadSourceCrs.Project, LinearUnit.METRE,
        SurfaceExtent(0.0, 1.0e7, 0.0, 1.0e7), 90.0, 120.0, 10, 8, 0,
    )

    private fun surface(height: SurfaceHeight = SurfaceHeight.Value(100.0), offset: Double = 0.3) =
        SurfaceGuide(surfaceModel, SurfaceHeightSource { _, _ -> height }, offset)

    private fun reading(vm: CollectorViewModel, predicate: (SurfaceReading?) -> Boolean): SurfaceReading? = runBlocking {
        withTimeout(10_000) { vm.surfaceReading.first(predicate) }
    }

    private fun viewModel(): CollectorViewModel {
        val settings = CollectorSettings()
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
            surfaces = SurfaceGuides { guide },
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

    private fun measureNormalPoint(vm: CollectorViewModel, name: String): Point {
        sendEpoch(next++)
        vm.measure()
        sendEpoch(next++)
        await(vm) { it.savedName == name }
        return runBlocking { points.points(projectId).first() }.single { it.name == name }
    }

    @Test
    fun `S042 RF-33 sin superficie activa no hay cota de la superficie`() {
        val vm = viewModel()
        receiving(vm)

        vm.surfaceReading.value.shouldBeNull()
    }

    @Test
    fun `S042 RF-33 con superficie activa se ve su cota con el desplazamiento y la diferencia con la punta`() {
        guide.value = surface(offset = 0.3)
        val vm = viewModel()
        receiving(vm)

        val r = reading(vm) { it != null }.shouldNotBeNull()

        r.height shouldBe SurfaceHeight.Value(100.0)
        r.offsetM shouldBe 0.3
        r.designM.shouldNotBeNull() shouldBe (100.3 plusOrMinus 1e-12)
        r.tipHeightM.shouldNotBeNull()
        r.dzM.shouldNotBeNull() shouldBe (r.tipHeightM!! - 100.3 plusOrMinus 1e-9)
    }

    @Test
    fun `S042 RF-33 la diferencia es positiva en desmonte y negativa en terraplen`() {
        guide.value = surface(SurfaceHeight.Value(1.0), offset = 0.0) // muy por debajo de la punta: desmonte
        val vm = viewModel()
        receiving(vm)
        (reading(vm) { it != null }.shouldNotBeNull().dzM!! > 0.0) shouldBe true

        guide.value = surface(SurfaceHeight.Value(5_000.0), offset = 0.0) // muy por encima de la punta: terraplen

        (reading(vm) { it?.height == SurfaceHeight.Value(5_000.0) }.shouldNotBeNull().dzM!! < 0.0) shouldBe true
    }

    @Test
    fun `S042 RF-33 fuera de la superficie no hay cota ni diferencia`() {
        guide.value = surface(SurfaceHeight.Outside)
        val vm = viewModel()
        receiving(vm)

        val r = reading(vm) { it != null }.shouldNotBeNull()

        r.height shouldBe SurfaceHeight.Outside
        r.designM.shouldBeNull()
        r.dzM.shouldBeNull()
    }

    @Test
    fun `S042 RF-33 mientras la superficie carga se dice que se calcula y no que esta fuera`() {
        guide.value = surface(SurfaceHeight.Loading)
        val vm = viewModel()
        receiving(vm)

        val r = reading(vm) { it != null }.shouldNotBeNull()

        r.height shouldBe SurfaceHeight.Loading
        r.designM.shouldBeNull()
    }

    @Test
    fun `S042 RF-33 el desplazamiento nuevo se ve en la cota sin reabrir el recolector`() {
        guide.value = surface(offset = 0.0)
        val vm = viewModel()
        receiving(vm)
        reading(vm) { it?.offsetM == 0.0 }

        guide.value = surface(offset = 0.5)

        reading(vm) { it?.offsetM == 0.5 }.shouldNotBeNull().designM.shouldNotBeNull() shouldBe (100.5 plusOrMinus 1e-12)
    }

    @Test
    fun `S042 RF-33 al medir un punto normal no se guarda nada de la superficie`() {
        guide.value = surface()
        val vm = viewModel()
        receiving(vm)
        reading(vm) { it != null }

        val point = measureNormalPoint(vm, "1")

        point.staked.shouldBeNull()
        point.origin shouldBe PointOrigin.MEASURED
    }

    @Test
    fun `S042 RF-33 si se desactiva la superficie la cota desaparece`() {
        guide.value = surface()
        val vm = viewModel()
        receiving(vm)
        reading(vm) { it != null }

        guide.value = null

        reading(vm) { it == null }.shouldBeNull()
    }
}
