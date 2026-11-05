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
import es.topophone.core.model.point.PointChange
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
import es.topophone.data.line.LineUpdate
import es.topophone.data.line.NewLine
import es.topophone.data.line.RoomLineRepository
import es.topophone.data.point.RoomPointRepository
import es.topophone.data.project.RoomProjectRepository
import es.topophone.data.project.SaveResult
import es.topophone.receiver.session.ReceiverSession
import io.kotest.matchers.nulls.shouldBeNull
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

/** El replanteo de una línea del proyecto se cierra, sin recalcular, si la línea o uno de sus vértices cambia o se borra (S027/RF-15). */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "es-rES")
class LineStakeoutClosedTest {

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

    private val a = point("A", 440_000.0, 4_474_000.0, 650.0)
    private val b = point("B", 440_030.0, 4_474_000.0, 650.0)
    private val c = point("C", 440_030.0, 4_474_040.0, 664.0)
    private val other = point("Z", 441_000.0, 4_475_000.0, 700.0)

    private lateinit var lineId: String

    private fun open(): LineStakeoutViewModel {
        runBlocking { listOf(a, b, c, other).forEach { points.create(it) } }
        lineId = (
            runBlocking {
                lines.createFromPoints(NewLine(projectId, "L_1", null, CodeGeometry.LINE, false, listOf("id-A", "id-B", "id-C")))
            } as LineSaveResult.Saved
            ).id
        val vm = viewModel()
        vm.open(projectId, LineSource.ProjectLine(lineId))
        await(vm) { it.element != null && it.target != null }
        return vm
    }

    @Test
    fun `S027 RF-15 editar la linea con el replanteo abierto lo cierra`() {
        val vm = open()

        runBlocking { lines.edit(lineId, LineUpdate(name = "L_RENOMBRADA")) }

        await(vm) { it.closed != null }.closed shouldBe LineClosedReason.LINE_CHANGED
    }

    @Test
    fun `S027 RF-15 mover un punto vertice con el replanteo abierto lo cierra`() {
        val vm = open()

        runBlocking { points.update(b.copy(coordinates = b.coordinates.copy(easting = 440_031.0)), PointChange.EDIT) }

        await(vm) { it.closed != null }.closed shouldBe LineClosedReason.LINE_CHANGED
    }

    @Test
    fun `S027 RF-15 cambiar la altura de un vertice tambien lo cierra`() {
        val vm = open()

        runBlocking { points.update(c.copy(coordinates = c.coordinates.copy(height = 665.0)), PointChange.EDIT) }

        await(vm) { it.closed != null }.closed shouldBe LineClosedReason.LINE_CHANGED
    }

    @Test
    fun `S027 RF-15 mandar a la papelera un punto vertice lo cierra`() {
        val vm = open()

        runBlocking { points.moveToTrash(listOf("id-C")) }

        await(vm) { it.closed != null }.closed shouldBe LineClosedReason.LINE_CHANGED
    }

    @Test
    fun `S027 RF-15 borrar la linea, a la papelera o del todo, lo cierra como borrada`() {
        val vm = open()

        runBlocking { lines.moveToTrash(lineId) }

        await(vm) { it.closed != null }.closed shouldBe LineClosedReason.LINE_DELETED
    }

    @Test
    fun `S027 RF-15 ocultar la linea o cambiar un punto que no es de ella no cierra el replanteo`() {
        val vm = open()

        runBlocking { lines.setHidden(lineId, true) }
        runBlocking { points.update(other.copy(coordinates = other.coordinates.copy(height = 701.0)), PointChange.EDIT) }

        vm.state.value.closed.shouldBeNull()
        vm.state.value.element?.projectLine?.name shouldBe "L_1"
    }

    @Test
    fun `S027 RF-15 cerrado el replanteo ya no se cambia nada ni hay objetivo`() {
        val vm = open()
        runBlocking { lines.edit(lineId, LineUpdate(name = "OTRA")) }
        await(vm) { it.closed != null }

        vm.setMode(LineStakeoutMode.TO_LINE) shouldBe false
        vm.selectVertex(1) shouldBe false
        vm.setOffset("2") shouldBe false
        vm.objectiveProvider.current().shouldBeNull()
    }

    @Test
    fun `S027 RF-15 el panel dice por que se ha cerrado`() {
        val vm = open()
        runBlocking { lines.edit(lineId, LineUpdate(name = "OTRA")) }
        val changed = await(vm) { it.closed != null }
        compose.setContent { TopoPhoneTheme(EffectiveTheme.LIGHT) { LineStakeoutPanel(changed, LineStakeoutActions()) } }

        compose.onNodeWithTag(LINE_CLOSED_TAG).assertTextContains("cambiado", substring = true)
    }

    @Test
    fun `S027 RF-15 el panel dice que la linea se ha borrado`() {
        val vm = open()
        runBlocking { lines.moveToTrash(lineId) }
        val deleted = await(vm) { it.closed != null }
        compose.setContent { TopoPhoneTheme(EffectiveTheme.LIGHT) { LineStakeoutPanel(deleted, LineStakeoutActions()) } }

        compose.onNodeWithTag(LINE_CLOSED_TAG).assertTextContains("borrado", substring = true)
    }
}
