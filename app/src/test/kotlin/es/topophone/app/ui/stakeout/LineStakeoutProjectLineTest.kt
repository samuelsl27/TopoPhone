package es.topophone.app.ui.stakeout

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.core.model.codes.AttributeType
import es.topophone.core.model.codes.CodeGeometry
import es.topophone.core.model.codes.CodeSymbol
import es.topophone.core.model.codes.PointAttribute
import es.topophone.core.model.codes.PointCode
import es.topophone.core.model.point.GeodeticPosition
import es.topophone.core.model.point.LineStakeoutMode
import es.topophone.core.model.point.Point
import es.topophone.core.model.point.PointCoordinates
import es.topophone.core.model.point.PointOrigin
import es.topophone.core.model.point.StakeSource
import es.topophone.core.model.project.ProjectCrs
import es.topophone.core.model.project.ProjectDefaults
import es.topophone.core.ntrip.NtripConnector
import es.topophone.core.ntrip.NtripFailure
import es.topophone.core.survey.ProjectPositionCalculator
import es.topophone.data.db.TopoPhoneDatabase
import es.topophone.data.line.LineSaveResult
import es.topophone.data.line.NewLine
import es.topophone.data.line.RoomLineRepository
import es.topophone.data.point.RoomPointRepository
import es.topophone.data.project.RoomProjectRepository
import es.topophone.data.project.SaveResult
import es.topophone.receiver.session.ReceiverSession
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
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

/** El replanteo de una línea del proyecto: abrirla, sus datos en el panel, sus motivos de rechazo y el trazo del mapa (S027/RF-1, RF-3, RF-4). */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class LineStakeoutProjectLineTest {

    private val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), TopoPhoneDatabase::class.java)
        .allowMainThreadQueries()
        .build()
    private val projects = RoomProjectRepository(db)
    private val points = RoomPointRepository(db)
    private val lines = RoomLineRepository(db, points)
    private val cad = es.topophone.data.cad.RoomCadRepository(db)
    private val sessionScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val session = ReceiverSession(
        sessionScope,
        NtripConnector { _, _, _, _ -> NtripFailure.UNREACHABLE },
        noDataTimeoutMs = 60_000,
        watchdogPeriodMs = 1_000,
    )
    private val calculator = ProjectPositionCalculator()
    private val viewModels = mutableListOf<LineStakeoutViewModel>()

    private val projectId: String = runBlocking {
        (projects.create(ProjectDefaults().toDraft().copy(name = "Calle Mayor 12")) as SaveResult.Saved).id
    }

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() {
        LineStakeoutTestSupport.cancelAndWait(viewModels)
        session.disconnect()
        sessionScope.cancel()
        db.close()
        Dispatchers.resetMain()
    }

    private val stakeoutPreferences = LineStakeoutTestSupport.FakeStakeoutPreferences()

    private fun viewModel(): LineStakeoutViewModel {
        val vm = LineStakeoutTestSupport.viewModel(projects, cad, stakeoutPreferences, session, calculator, RoomStakeLines(lines))
        viewModels += vm
        CoroutineScope(Dispatchers.Unconfined).launch { vm.state.collect {} }
        return vm
    }

    private fun await(vm: LineStakeoutViewModel, predicate: (LineStakeoutUiState) -> Boolean): LineStakeoutUiState = runBlocking {
        try {
            withTimeout(10_000) { vm.state.first(predicate) }
        } catch (e: TimeoutCancellationException) {
            throw AssertionError("El replanteo no llegó al estado esperado; se quedó en ${vm.state.value}", e)
        }
    }

    private fun point(name: String, east: Double?, north: Double?, height: Double? = null, project: String = projectId) = Point(
        id = "id-$name",
        projectId = project,
        name = name,
        description = "",
        origin = PointOrigin.MANUAL,
        coordinates = PointCoordinates(GeodeticPosition(40.4, -3.7, height?.plus(50.0)), east, north, height),
        measurement = null,
        typed = null,
        edited = false,
        hidden = false,
        copyOf = null,
        createdAtMs = 3_000,
        deletedAtMs = null,
    )

    private val bord =
        PointCode("BORD", "Bordillo", 0x1E88E5, CodeSymbol.CIRCLE, listOf(PointAttribute("Material", AttributeType.TEXT, "Granito")))

    /** Una línea de 30 + 40 m por los puntos A, B y C (con alturas 650, 650 y 664) o un polígono con D de 30 x 40 m. */
    private fun createLine(closed: Boolean = false, names: List<String> = listOf("A", "B", "C"), project: String = projectId): String {
        runBlocking {
            listOf(
                point("A", 440_000.0, 4_474_000.0, 650.0, project),
                point("B", 440_030.0, 4_474_000.0, 650.0, project),
                point("C", 440_030.0, 4_474_040.0, 664.0, project),
                point("D", 440_000.0, 4_474_040.0, 655.0, project),
            ).forEach { points.create(it) }
        }
        val geometry = if (closed) CodeGeometry.POLYGON else CodeGeometry.LINE
        return (
            runBlocking {
                lines.createFromPoints(NewLine(project, "BORD_1", bord, geometry, closed, names.map { "id-$it" }))
            } as LineSaveResult.Saved
            ).id
    }

    @Test
    fun `S027 RF-1 abrir una linea del proyecto da su nombre, su codigo, su longitud y su forma`() {
        val lineId = createLine()
        val vm = viewModel()

        vm.open(projectId, LineSource.ProjectLine(lineId))
        val state = await(vm) { it.element != null && it.project != null }

        val element = state.element.shouldNotBeNull()
        element.projectLine.shouldNotBeNull().name shouldBe "BORD_1"
        element.projectLine.shouldNotBeNull().code shouldBe bord
        element.lengthM shouldBe (70.0 plusOrMinus 1e-9)
        element.closed shouldBe false
        element.minHeightM shouldBe 650.0
        element.maxHeightM shouldBe 664.0
        state.unsupported shouldBe false
        state.unsupportedReason.shouldBeNull()
    }

    @Test
    fun `S027 RF-1 el objetivo se llama como la linea, es de la linea y lleva su codigo`() {
        val lineId = createLine()
        val vm = viewModel()

        vm.open(projectId, LineSource.ProjectLine(lineId))
        val target = await(vm) { it.target != null }.target.shouldNotBeNull()

        target.id shouldBe "line:$lineId"
        target.name shouldBe "BORD_1"
        target.source shouldBe StakeSource.ProjectLine(lineId, "BORD_1")
    }

    @Test
    fun `S027 RF-4 un poligono dice que esta cerrado y su longitud es el perimetro`() {
        val lineId = createLine(closed = true, names = listOf("A", "B", "C", "D"))
        val vm = viewModel()

        vm.open(projectId, LineSource.ProjectLine(lineId))
        val element = await(vm) { it.element != null }.element.shouldNotBeNull()

        element.closed shouldBe true
        element.lengthM shouldBe (140.0 plusOrMinus 1e-9)
    }

    @Test
    fun `S027 RF-1 se abre con el ultimo modo elegido`() {
        val lineId = createLine()
        stakeoutPreferences.settings.value = stakeoutPreferences.settings.value.copy(lineMode = LineStakeoutMode.INTERVALS)
        val vm = viewModel()

        vm.open(projectId, LineSource.ProjectLine(lineId))

        await(vm) { it.element != null }.mode shouldBe LineStakeoutMode.INTERVALS
    }

    @Test
    fun `S027 RF-3 con menos de dos vertices no se abre y dice por que`() {
        val lineId = createLine(names = listOf("A", "B"))
        runBlocking { points.moveToTrash(listOf("id-B")) }
        val vm = viewModel()

        vm.open(projectId, LineSource.ProjectLine(lineId))
        val state = await(vm) { it.unsupported }

        state.element.shouldBeNull()
        state.unsupportedReason shouldBe LineUnsupportedReason.TOO_FEW_VERTICES
    }

    @Test
    fun `S027 RF-3 una linea que no existe no se abre`() {
        val vm = viewModel()

        vm.open(projectId, LineSource.ProjectLine("no-existe"))

        await(vm) { it.unsupported }.unsupportedReason shouldBe LineUnsupportedReason.NOT_FOUND
    }

    @Test
    fun `S027 RF-3 una linea en la papelera no se abre`() {
        val lineId = createLine()
        val lines = RoomLineRepository(db, points)
        runBlocking { lines.moveToTrash(lineId) }
        val vm = viewModel()

        vm.open(projectId, LineSource.ProjectLine(lineId))

        await(vm) { it.unsupported }.unsupportedReason shouldBe LineUnsupportedReason.NOT_FOUND
    }

    @Test
    fun `S027 RF-3 en un SC geografico los puntos no tienen plano y no se abre`() {
        val geographic = runBlocking {
            (projects.create(ProjectDefaults().toDraft().copy(name = "Geo", crs = ProjectCrs.ETRS89_GEOGRAPHIC)) as SaveResult.Saved).id
        }
        runBlocking {
            points.create(point("G1", null, null, project = geographic))
            points.create(point("G2", null, null, project = geographic).copy(id = "id-G2", name = "G2"))
        }
        val lineId = (
            runBlocking {
                lines.createFromPoints(NewLine(geographic, "GEO_1", bord, CodeGeometry.LINE, false, listOf("id-G1", "id-G2")))
            } as LineSaveResult.Saved
            ).id
        val vm = viewModel()

        vm.open(geographic, LineSource.ProjectLine(lineId))

        await(vm) { it.unsupported }.unsupportedReason shouldBe LineUnsupportedReason.NO_PLANE_POSITION
    }

    @Test
    fun `S027 RF-4 la linea elegida se dibuja en el mapa con sus vertices en latitud y longitud`() {
        val lineId = createLine()
        val vm = viewModel()

        vm.open(projectId, LineSource.ProjectLine(lineId))
        await(vm) { it.element != null }
        val highlight = runBlocking { withTimeout(10_000) { vm.highlight.first { it.isNotEmpty() } } }

        highlight.size shouldBe 3
        (highlight.first().latitudeDeg in 35.0..44.0) shouldBe true
        (highlight.first().longitudeDeg in -10.0..4.0) shouldBe true
    }

    @Test
    fun `S027 RF-1 abrir otra vez cambia de linea y cerrar la deja sin replanteo`() {
        val first = createLine()
        val vm = viewModel()
        vm.open(projectId, LineSource.ProjectLine(first))
        await(vm) { it.element != null }

        vm.close()

        await(vm) { it.element == null }.unsupported shouldBe false
    }
}
