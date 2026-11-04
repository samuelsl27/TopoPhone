package es.topophone.app.ui.collector

import android.content.Context
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.app.log.RecordingLog
import es.topophone.app.ui.receiver.nmea
import es.topophone.core.model.codes.AttributeType
import es.topophone.core.model.codes.CodeAttribute
import es.topophone.core.model.codes.CodeGeometry
import es.topophone.core.model.codes.CodeLibrary
import es.topophone.core.model.codes.LibraryChoice
import es.topophone.core.model.codes.PointAttribute
import es.topophone.core.model.codes.SurveyCode
import es.topophone.core.model.point.CollectorSettings
import es.topophone.core.model.project.ProjectDefaults
import es.topophone.core.ntrip.NtripConnector
import es.topophone.core.ntrip.NtripFailure
import es.topophone.core.survey.ProjectPositionCalculator
import es.topophone.data.codes.DefaultLibrarySource
import es.topophone.data.codes.ProjectLibraryLinker
import es.topophone.data.codes.RoomCodeLibraryRepository
import es.topophone.data.db.TopoPhoneDatabase
import es.topophone.data.line.RoomLineRepository
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

/** Medir con códigos de línea y de polígono en el recolector: línea activa, atributos y selector (S025/RF-1 a RF-3, RF-8, RF-8b, RF-9). */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class CollectorLineViewModelTest {

    private val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), TopoPhoneDatabase::class.java)
        .allowMainThreadQueries()
        .build()
    private val general = CodeLibrary(
        "default:general",
        "General",
        true,
        listOf(
            SurveyCode("g1", "ARB", "Árbol", CodeGeometry.POINT, 0x689F38),
            SurveyCode(
                "g2",
                "BORD",
                "Bordillo",
                CodeGeometry.LINE,
                0x1E88E5,
                listOf(CodeAttribute("Material", AttributeType.TEXT, "Granito"), CodeAttribute("Alto", AttributeType.NUMBER, "0.15")),
            ),
            SurveyCode("g3", "TAPIA", "Tapia", CodeGeometry.LINE, 0xD32F2F),
            SurveyCode("g4", "PARC", "Parcela", CodeGeometry.POLYGON, 0x43A047),
        ),
    )
    private val defaults = DefaultLibrarySource { listOf(general) }
    private val libraries = RoomCodeLibraryRepository(db, defaults)
    private val projects = RoomProjectRepository(db, libraries = ProjectLibraryLinker(db, defaults))
    private val points = RoomPointRepository(db)
    private val lines = RoomLineRepository(db, points)
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
        (
            projects.create(
                ProjectDefaults().toDraft().copy(name = "Calle Mayor 12", libraryChoice = LibraryChoice.Library("default:general")),
            ) as SaveResult.Saved
            ).id
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
            codeSource = RoomCollectorCodeSource(libraries, points),
            lines = RoomCollectorLines(lines),
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

    private fun measure(vm: CollectorViewModel, name: String) {
        sendEpoch(next++)
        vm.measure()
        sendEpoch(next++)
        await(vm) { it.savedName == name }
    }

    private fun pick(vm: CollectorViewModel, text: String) {
        await(vm) { state -> state.code.options.any { it.code == text } }
        vm.selectCode(vm.state.value.code.options.first { it.code == text })
        await(vm) { it.code.selected?.code == text }
    }

    private fun activeLine() = runBlocking { lines.active(projectId).first() }

    @Test
    fun `S025 RF-1 el selector ofrece tambien los codigos de linea y de poligono con su geometria`() {
        val vm = viewModel()
        await(vm) { it.code.totalCodes > 0 }

        val options = vm.state.value.code.options
        options.map { it.code } shouldBe listOf("ARB", "BORD", "PARC", "TAPIA")
        options.map { it.geometry } shouldBe listOf(CodeGeometry.POINT, CodeGeometry.LINE, CodeGeometry.POLYGON, CodeGeometry.LINE)
    }

    @Test
    fun `S025 RF-2 el primer punto con un codigo de linea crea la linea con su nombre y la deja activa`() {
        val vm = viewModel()
        receiving(vm)
        pick(vm, "BORD")

        measure(vm, "1")

        val line = activeLine().shouldNotBeNull()
        line.name shouldBe "BORD_1"
        line.vertices.size shouldBe 1
        await(vm) { it.line?.name == "BORD_1" }.line?.vertexCount shouldBe 1
    }

    @Test
    fun `S025 RF-3 los puntos siguientes se anaden como ultimo vertice y el recolector muestra su numero`() {
        val vm = viewModel()
        receiving(vm)
        pick(vm, "BORD")
        measure(vm, "1")

        measure(vm, "2")
        measure(vm, "3")

        activeLine().shouldNotBeNull().vertices.size shouldBe 3
        val ui = await(vm) { it.line?.vertexCount == 3 }.line.shouldNotBeNull()
        ui.name shouldBe "BORD_1"
    }

    @Test
    fun `S025 RF-8 un punto con otro codigo o sin codigo no entra en la linea y esta sigue activa`() {
        val vm = viewModel()
        receiving(vm)
        pick(vm, "BORD")
        measure(vm, "1")

        pick(vm, "ARB")
        measure(vm, "2")
        vm.selectCode(null)
        measure(vm, "3")

        activeLine().shouldNotBeNull().vertices.size shouldBe 1
        runBlocking { points.names(projectId) } shouldBe setOf("1", "2", "3")
    }

    @Test
    fun `S025 RF-8 un codigo de linea distinto crea otra linea que pasa a ser la activa`() {
        val vm = viewModel()
        receiving(vm)
        pick(vm, "BORD")
        measure(vm, "1")

        pick(vm, "TAPIA")
        measure(vm, "2")

        activeLine().shouldNotBeNull().name shouldBe "TAPIA_1"
        runBlocking { lines.lines(projectId).first() }.size shouldBe 2
    }

    @Test
    fun `S025 RF-8b los atributos se piden al crear la linea y despues quedan ocultos y sin bloquear`() {
        val vm = viewModel()
        receiving(vm)
        pick(vm, "BORD")
        vm.setCodeAttribute(0, "Hormigon")
        vm.setCodeAttribute(1, "0,2")
        measure(vm, "1")

        val ui = await(vm) { it.line != null }
        CollectorLineRules.appendsToActive(ui.code.selected, ui.line) shouldBe true
        vm.setCodeAttribute(1, "no es un numero")
        measure(vm, "2")

        val saved = runBlocking { points.points(projectId).first() }.associateBy { it.name }
        saved.getValue("2").code?.attributes shouldBe
            listOf(PointAttribute("Material", AttributeType.TEXT, "Hormigon"), PointAttribute("Alto", AttributeType.NUMBER, "0.2"))
        saved.getValue("1").code?.code shouldBe "BORD"
        activeLine().shouldNotBeNull().code?.attributes?.first()?.value shouldBe "Hormigon"
    }

    @Test
    fun `S025 RF-8b con un codigo distinto del de la linea activa los atributos vuelven a pedirse`() {
        val vm = viewModel()
        receiving(vm)
        pick(vm, "BORD")
        measure(vm, "1")

        pick(vm, "PARC")

        val ui = vm.state.value
        CollectorLineRules.appendsToActive(ui.code.selected, ui.line) shouldBe false
    }

    @Test
    fun `S025 RF-9 la linea activa sigue al abrir el recolector de nuevo`() {
        val vm = viewModel()
        receiving(vm)
        pick(vm, "BORD")
        measure(vm, "1")
        measure(vm, "2")
        vm.viewModelScope.cancel()

        val again = viewModel()

        await(again) { it.line != null }.line.shouldNotBeNull().vertexCount shouldBe 2
    }

    @Test
    fun `S025 RF-2 un codigo de punto no crea ninguna linea`() {
        val vm = viewModel()
        receiving(vm)
        pick(vm, "ARB")

        measure(vm, "1")

        activeLine().shouldBeNull()
        vm.state.value.line.shouldBeNull()
    }
}
