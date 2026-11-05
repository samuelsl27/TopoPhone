package es.topophone.app.ui.stakeout

import android.content.Context
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.core.designsystem.theme.TopoPhoneTheme
import es.topophone.core.model.codes.CodeGeometry
import es.topophone.core.model.point.GeodeticPosition
import es.topophone.core.model.point.LineStakeoutMode
import es.topophone.core.model.point.Point
import es.topophone.core.model.point.PointCoordinates
import es.topophone.core.model.point.PointOrigin
import es.topophone.core.model.preferences.EffectiveTheme
import es.topophone.core.model.project.ProjectDefaults
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
import es.topophone.receiver.session.ReceiverSession
import io.kotest.matchers.doubles.plusOrMinus
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
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** "Por segmentos" con una línea del proyecto: el nombre del punto de cada vértice, su PK, la vuelta de un polígono y la papelera (S027/RF-5 a RF-8). */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "es-rES")
class LineStakeoutSegmentsTest {

    @get:Rule
    val compose = createComposeRule()

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

    private val projectId: String = runBlocking {
        (projects.create(ProjectDefaults().toDraft().copy(name = "Calle Mayor 12")) as SaveResult.Saved).id
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        preferences.settings.value = preferences.settings.value.copy(lineMode = LineStakeoutMode.BY_SEGMENTS)
    }

    @After
    fun tearDown() {
        viewModels.forEach { it.viewModelScope.cancel() }
        session.disconnect()
        sessionScope.cancel()
        db.close()
        Dispatchers.resetMain()
    }

    private fun viewModel(): LineStakeoutViewModel {
        val vm = LineStakeoutTestSupport.viewModel(projects, cad, preferences, session, calculator, RoomStakeLines(lines))
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

    /** Un rectángulo de 30 x 40 m por A, B, C y D, con las alturas dadas (null = sin altura). */
    private fun createLine(
        closed: Boolean,
        heights: List<Double?> = listOf(650.0, 650.0, 664.0, 655.0),
        names: List<String> = listOf("A", "B", "C", "D"),
    ): String {
        runBlocking {
            listOf(
                point("A", 440_000.0, 4_474_000.0, heights[0]),
                point("B", 440_030.0, 4_474_000.0, heights[1]),
                point("C", 440_030.0, 4_474_040.0, heights[2]),
                point("D", 440_000.0, 4_474_040.0, heights[3]),
            ).forEach { points.create(it) }
        }
        val geometry = if (closed) CodeGeometry.POLYGON else CodeGeometry.LINE
        return (
            runBlocking {
                lines.createFromPoints(NewLine(projectId, "L_1", null, geometry, closed, names.map { "id-$it" }))
            } as LineSaveResult.Saved
            ).id
    }

    private fun open(vm: LineStakeoutViewModel, lineId: String): LineStakeoutUiState {
        vm.open(projectId, LineSource.ProjectLine(lineId))
        return await(vm) { it.element != null && it.project != null && it.target != null }
    }

    @Test
    fun `S027 RF-5 la lista da el nombre del punto de cada vertice y su PK`() {
        val vm = viewModel()
        val state = open(vm, createLine(closed = false, names = listOf("A", "B", "C")))

        val vertices = state.vertices.shouldNotBeNull()
        val labels = state.vertexLabels.shouldNotBeNull()
        vertices.entries.map { labels.name(it.vertex) } shouldBe listOf("A", "B", "C")
        vertices.entries.map { it.number } shouldBe listOf(1, 2, 3)
        vertices.entries[1].chainageM shouldBe (30.0 plusOrMinus 1e-9)
        vertices.entries[2].chainageM shouldBe (70.0 plusOrMinus 1e-9)
    }

    @Test
    fun `S027 RF-5 en un poligono Siguiente pasa del ultimo vertice al primero y Anterior no pasa del primero`() {
        val vm = viewModel()
        open(vm, createLine(closed = true))
        vm.selectVertex(3)

        vm.next()
        await(vm) { it.vertexIndex == 0 }
        vm.previous()

        vm.state.value.vertexIndex shouldBe 0
    }

    @Test
    fun `S027 RF-5 en una linea abierta Siguiente no pasa del ultimo vertice`() {
        val vm = viewModel()
        open(vm, createLine(closed = false))
        vm.selectVertex(3)

        vm.next()

        vm.state.value.vertexIndex shouldBe 3
    }

    @Test
    fun `S027 RF-6 todos los vertices se ofrecen, sin curvas ni reimportar`() {
        val vm = viewModel()
        val state = open(vm, createLine(closed = false))

        state.vertices.shouldNotBeNull().size shouldBe 4
        state.vertices.shouldNotBeNull().needsReimport shouldBe false
    }

    @Test
    fun `S027 RF-7 un vertice cuyo punto esta en la papelera no se ofrece`() {
        val lineId = createLine(closed = false)
        runBlocking { points.moveToTrash(listOf("id-C")) }
        val vm = viewModel()

        val state = open(vm, lineId)

        val labels = state.vertexLabels.shouldNotBeNull()
        state.vertices.shouldNotBeNull().entries.map { labels.name(it.vertex) } shouldBe listOf("A", "B", "D")
    }

    @Test
    fun `S027 RF-8 el objetivo de un vertice con altura es su punto y el de uno sin altura, la interpolada`() {
        val lineId = createLine(closed = false, heights = listOf(650.0, null, 664.0, null))
        val vm = viewModel()
        open(vm, lineId)

        vm.state.value.target.shouldNotBeNull().coordinates.height shouldBe 650.0
        vm.selectVertex(1)
        val interpolated = await(vm) { it.vertexIndex == 1 && it.target?.coordinates?.height != 650.0 }
        // B esta a 30 m de A (650) y C a 40 m mas (664): 650 + 14 * 30 / 70 = 656.
        interpolated.target.shouldNotBeNull().coordinates.height.shouldNotBeNull() shouldBe (656.0 plusOrMinus 1e-6)
        interpolated.vertexLabels.shouldNotBeNull().isInterpolated(1) shouldBe true
        interpolated.vertexLabels.shouldNotBeNull().isInterpolated(0) shouldBe false
        // Despues del ultimo vertice con altura (C) no hay cota que interpolar.
        vm.selectVertex(3)
        await(vm) { it.vertexIndex == 3 }.vertexLabels.shouldNotBeNull().isInterpolated(3) shouldBe false
    }

    @Test
    fun `S027 RF-5 RF-8 la lista del panel dice el nombre y que la cota es interpolada`() {
        val lineId = createLine(closed = false, heights = listOf(650.0, null, 664.0, null))
        val vm = viewModel()
        val state = open(vm, lineId)
        compose.setContent {
            TopoPhoneTheme(EffectiveTheme.LIGHT) { LineStakeoutPanel(state, LineStakeoutActions()) }
        }

        compose.onNodeWithTag(lineVertexTag(0)).assertTextContains("A", substring = true)
        compose.onNodeWithTag(lineVertexTag(1)).assertTextContains("B", substring = true)
        compose.onNodeWithTag(lineVertexTag(1)).assertTextContains("cota interpolada", substring = true)
    }

    @Test
    fun `S027 RF-9 el panel dice que vertices no tienen altura y cuando el objetivo no tiene cota`() {
        val lineId = createLine(closed = false, heights = listOf(null, 650.0, 664.0, null))
        val vm = viewModel()
        val state = open(vm, lineId)
        compose.setContent {
            TopoPhoneTheme(EffectiveTheme.LIGHT) { LineStakeoutPanel(state, LineStakeoutActions()) }
        }

        compose.onNodeWithTag(LINE_MISSING_HEIGHTS_TAG).assertTextContains("A, D", substring = true)
        compose.onNodeWithTag(LINE_NO_HEIGHT_TAG).assertTextContains("Sin cota de diseño", substring = true)
    }
}
