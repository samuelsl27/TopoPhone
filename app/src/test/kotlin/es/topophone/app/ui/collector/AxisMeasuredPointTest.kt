package es.topophone.app.ui.collector

import android.content.Context
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.app.log.RecordingLog
import es.topophone.app.ui.alignment.AxisReferenceProvider
import es.topophone.app.ui.receiver.nmea
import es.topophone.core.model.point.AxisHeightRef
import es.topophone.core.model.point.AxisRef
import es.topophone.core.model.point.CollectorSettings
import es.topophone.core.model.point.Point
import es.topophone.core.model.point.PointCoordinates
import es.topophone.core.model.point.PointOrigin
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
 * El recolector guardando un punto replanteado por PK y desplazamiento de un eje (S054/RF-10): el nombre `eje_n_stkd`, el objetivo de
 * cuando termina la medida, el eje, el PK, el desplazamiento, el espesor y las diferencias de PK y de desplazamiento. Con la `ReceiverSession`
 * real y las épocas de una en una.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class AxisMeasuredPointTest {

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

    private fun viewModel(settings: CollectorSettings = CollectorSettings()): CollectorViewModel {
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
        awaitReceiving(session, vm)
        await(vm) { it.live != null && it.blocker == null }
    }

    private var next = 2

    /** Mide y guarda un punto normal en la posición de las épocas, para sacar de él un diseño a una distancia conocida. */
    private fun measureNormalPoint(vm: CollectorViewModel, name: String): Point {
        sendEpoch(next++)
        vm.measure()
        sendEpoch(next++)
        await(vm) { it.savedName == name }
        return runBlocking { points.points(projectId).first() }.single { it.name == name }
    }

    /** Un punto medido normal en la posición de las épocas: la medida que se guarda desde «Analizar». */
    private fun measureWithAxis(vm: CollectorViewModel, provider: AxisReferenceProvider?, name: String = "2"): Point {
        vm.setAxisReference(provider)
        vm.measure()
        sendEpoch(next++)
        await(vm) { it.savedName == name }
        return runBlocking { points.points(projectId).first() }.single { it.name == name }
    }

    @Test
    fun `S054 RF-13 una medida desde Analizar se guarda con su eje, su PK, su desplazamiento y su diferencia de cota`() {
        val vm = viewModel()
        receiving(vm)
        val axis = AxisRef("Eje A", 150.0000000001, 3.5, dHeightM = -0.123456789, heightRef = AxisHeightRef.PROFILE)

        val point = measureWithAxis(vm, AxisReferenceProvider { axis }, name = "1")

        point.onAxis shouldBe axis
        point.origin shouldBe PointOrigin.MEASURED
        point.staked.shouldBeNull()
        point.measurement.shouldNotBeNull()
    }

    @Test
    fun `S054 RF-13 la referencia se calcula con la posicion medida y no con la de la ultima epoca`() {
        val vm = viewModel()
        receiving(vm)
        var seen: PointCoordinates? = null

        val point = measureWithAxis(
            vm,
            AxisReferenceProvider { measured ->
                seen = measured
                AxisRef("Eje A", measured.easting!!, 0.0)
            },
            name = "1",
        )

        seen.shouldNotBeNull() shouldBe point.coordinates
        point.onAxis.shouldNotBeNull().chainageM shouldBe point.coordinates.easting
    }

    @Test
    fun `S054 RF-13 sin proyeccion sobre el eje el punto se guarda como un punto medido normal`() {
        val vm = viewModel()
        receiving(vm)

        val point = measureWithAxis(vm, AxisReferenceProvider { null }, name = "1")

        point.onAxis.shouldBeNull()
        point.origin shouldBe PointOrigin.MEASURED
    }

    @Test
    fun `S054 RF-13 sin analisis abierto la medida no lleva eje`() {
        val vm = viewModel()
        receiving(vm)

        val point = measureWithAxis(vm, null, name = "1")

        point.onAxis.shouldBeNull()
    }

    @Test
    fun `S054 RF-13 cerrar el analisis quita la referencia y la medida siguiente no lleva eje`() {
        val vm = viewModel()
        receiving(vm)
        measureWithAxis(vm, AxisReferenceProvider { AxisRef("Eje A", 1.0, 2.0) }, name = "1")
        await(vm) { it.name == "2" }

        val second = measureWithAxis(vm, null, name = "2")

        second.onAxis.shouldBeNull()
    }

    @Test
    fun `S054 RF-13 una medida con diferencia de cota sin referencia se guarda con el eje y la diferencia vacia`() {
        val vm = viewModel()
        receiving(vm)

        val point = measureWithAxis(vm, AxisReferenceProvider { AxisRef("Eje C", 12.0, -1.5) }, name = "1")

        point.onAxis shouldBe AxisRef("Eje C", 12.0, -1.5)
        point.onAxis.shouldNotBeNull().dHeightM.shouldBeNull()
    }
}
