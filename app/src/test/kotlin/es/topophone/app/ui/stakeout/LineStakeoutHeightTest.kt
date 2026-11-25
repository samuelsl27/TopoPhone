package es.topophone.app.ui.stakeout

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.core.model.cad.CadSourceCrs
import es.topophone.core.model.codes.CodeGeometry
import es.topophone.core.model.point.GeodeticPosition
import es.topophone.core.model.point.LineStakeoutMode
import es.topophone.core.model.point.Point
import es.topophone.core.model.point.PointCoordinates
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
import es.topophone.data.cad.RoomCadRepository
import es.topophone.data.db.TopoPhoneDatabase
import es.topophone.data.line.LineSaveResult
import es.topophone.data.line.NewLine
import es.topophone.data.line.RoomLineRepository
import es.topophone.data.point.RoomPointRepository
import es.topophone.data.project.RoomProjectRepository
import es.topophone.data.project.SaveResult
import es.topophone.data.surface.SurfaceGuides
import es.topophone.receiver.session.ReceiverSession
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
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

/** La cota de diseño de una línea del proyecto con alturas en solo algunos vértices: el aviso, la cota a mano y la superficie (S027/RF-8 a RF-10). */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class LineStakeoutHeightTest {

    private val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), TopoPhoneDatabase::class.java)
        .allowMainThreadQueries()
        .build()
    private val projects = RoomProjectRepository(db)
    private val points = RoomPointRepository(db)
    private val lines = RoomLineRepository(db, points)
    private val cad = RoomCadRepository(db)
    private val sessionScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val session = ReceiverSession(
        sessionScope,
        NtripConnector { _, _, _, _ -> NtripFailure.UNREACHABLE },
        noDataTimeoutMs = 60_000,
        watchdogPeriodMs = 1_000,
    )
    private val calculator = ProjectPositionCalculator()
    private val viewModels = mutableListOf<LineStakeoutViewModel>()
    private val preferences = LineStakeoutTestSupport.FakeStakeoutPreferences()
    private val guide = MutableStateFlow<SurfaceGuide?>(null)

    private val projectId: String = runBlocking {
        (projects.create(ProjectDefaults().toDraft().copy(name = "Calle Mayor 12")) as SaveResult.Saved).id
    }

    private val surfaceGuide = SurfaceGuide(
        Surface(
            "s1", projectId, "terreno", "obra.xml", 1L, CadSourceCrs.Project, LinearUnit.METRE,
            SurfaceExtent(
                0.0,
                1.0e7,
                0.0,
                1.0e7,
            ),
            90.0, 120.0, 10, 8, 0,
        ),
        SurfaceHeightSource { _, _ -> SurfaceHeight.Value(95.0) },
        0.0,
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        preferences.settings.value = preferences.settings.value.copy(lineMode = LineStakeoutMode.BY_SEGMENTS)
    }

    @After
    fun tearDown() {
        LineStakeoutTestSupport.cancelAndWait(viewModels)
        session.disconnect()
        sessionScope.cancel()
        db.close()
        Dispatchers.resetMain()
    }

    private fun viewModel(): LineStakeoutViewModel {
        val vm = LineStakeoutTestSupport.viewModel(
            projects,
            cad,
            preferences,
            session,
            calculator,
            RoomStakeLines(lines),
            surfaces = SurfaceGuides { guide },
        )
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

    private fun point(name: String, east: Double, north: Double, height: Double?) = Point(
        id = "id-$name",
        projectId = projectId,
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

    /** Cuatro vértices a 30, 40 y 30 m: A y D sin altura, B con 650 m y C con 664 m. */
    private fun open(vm: LineStakeoutViewModel, heights: List<Double?> = listOf(null, 650.0, 664.0, null)): LineStakeoutUiState {
        runBlocking {
            listOf(
                point("A", 440_000.0, 4_474_000.0, heights[0]),
                point("B", 440_030.0, 4_474_000.0, heights[1]),
                point("C", 440_030.0, 4_474_040.0, heights[2]),
                point("D", 440_000.0, 4_474_040.0, heights[3]),
            ).forEach { points.create(it) }
        }
        val lineId = (
            runBlocking {
                lines.createFromPoints(NewLine(projectId, "L_1", null, CodeGeometry.LINE, false, listOf("id-A", "id-B", "id-C", "id-D")))
            } as LineSaveResult.Saved
            ).id
        vm.open(projectId, LineSource.ProjectLine(lineId))
        return await(vm) { it.element != null && it.project != null && it.target != null }
    }

    @Test
    fun `S027 RF-9 la ficha de la linea dice que vertices no tienen altura`() {
        val state = open(viewModel())

        state.element.shouldNotBeNull().missingHeightNames shouldBe listOf("A", "D")
    }

    @Test
    fun `S027 RF-9 un vertice sin cota donde no hay con que interpolar dice que el objetivo no tiene cota`() {
        val vm = viewModel()
        open(vm)

        // A esta antes del primer vertice con altura y D despues del ultimo: no tienen cota de diseño.
        await(vm) { it.vertexIndex == 0 }.objectiveWithoutHeight shouldBe true
        vm.selectVertex(1)
        await(vm) { it.vertexIndex == 1 }.objectiveWithoutHeight shouldBe false
        vm.selectVertex(3)
        await(vm) { it.vertexIndex == 3 }.objectiveWithoutHeight shouldBe true
    }

    @Test
    fun `S027 RF-9 la cota escrita a mano vale para el objetivo y quita el aviso`() {
        val vm = viewModel()
        open(vm)

        vm.setDesignElevation("101,5") shouldBe true

        val state = await(vm) { it.elevationOverridden }
        state.objectiveWithoutHeight shouldBe false
        vm.objectiveProvider.current().shouldNotBeNull().elevationM shouldBe 101.5
    }

    @Test
    fun `S027 RF-10 con superficie activa el objetivo sin cota se replantea contra la superficie y el que la tiene no`() {
        guide.value = surfaceGuide
        val vm = viewModel()
        open(vm)

        await(vm) { it.surfaceActive && it.vertexIndex == 0 }
        vm.objectiveProvider.current().shouldNotBeNull().useSurface shouldBe true
        await(vm) { it.vertexIndex == 0 }.objectiveWithoutHeight shouldBe false // la superficie le da la cota
        vm.selectVertex(2)
        await(vm) { it.vertexIndex == 2 }
        vm.objectiveProvider.current().shouldNotBeNull().useSurface shouldBe false
        vm.objectiveProvider.current().shouldNotBeNull().elevationM shouldBe 664.0
    }

    @Test
    fun `S027 RF-10 si el usuario elige la cota del punto la superficie no se usa en ningun objetivo`() {
        guide.value = surfaceGuide
        val vm = viewModel()
        open(vm)
        await(vm) { it.surfaceActive }

        vm.setElevationSource(es.topophone.core.model.point.ElevationSource.POINT) shouldBe true

        vm.objectiveProvider.current().shouldNotBeNull().useSurface shouldBe false
    }
}
