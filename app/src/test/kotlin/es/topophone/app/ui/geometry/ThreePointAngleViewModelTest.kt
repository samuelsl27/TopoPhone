package es.topophone.app.ui.geometry

import android.content.Context
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.core.model.codes.CodeGeometry
import es.topophone.core.model.geometry.DistanceMode
import es.topophone.core.model.point.GeodeticPosition
import es.topophone.core.model.point.Point
import es.topophone.core.model.point.PointCoordinates
import es.topophone.core.model.point.PointOrigin
import es.topophone.core.model.project.ProjectDefaults
import es.topophone.core.survey.geometry.AnglePoint
import es.topophone.core.survey.geometry.AngleResult
import es.topophone.data.db.TopoPhoneDatabase
import es.topophone.data.geometry.GeometryPreferences
import es.topophone.data.line.LineSaveResult
import es.topophone.data.line.NewLine
import es.topophone.data.line.RoomLineRepository
import es.topophone.data.point.RoomPointRepository
import es.topophone.data.project.RoomProjectRepository
import es.topophone.data.project.SaveResult
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
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
import org.robolectric.annotation.Config

/** Elegir A, B y C del proyecto o de una línea y calcular el ángulo en B (S026/RF-13, RF-14, RF-15). */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "es-rES")
class ThreePointAngleViewModelTest {

    private val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), TopoPhoneDatabase::class.java)
        .allowMainThreadQueries()
        .build()
    private val projects = RoomProjectRepository(db)
    private val points = RoomPointRepository(db)
    private val lines = RoomLineRepository(db, points)
    private val viewModels = mutableListOf<ThreePointAngleViewModel>()

    private class FakePreferences : GeometryPreferences {
        val mode = MutableStateFlow(DistanceMode.GRID)
        override val distanceMode = mode

        override suspend fun setDistanceMode(mode: DistanceMode) {
            this.mode.value = mode
        }
    }

    private val preferences = FakePreferences()

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() {
        viewModels.forEach { it.viewModelScope.cancel() }
        // Esperar a que acabe lo que estaba en vuelo: si no, la consulta falla despues de cerrar la base y rompe otro test.
        runBlocking { viewModels.forEach { it.viewModelScope.coroutineContext[Job]?.join() } }
        db.close()
        Dispatchers.resetMain()
    }

    private val projectId: String = runBlocking {
        (projects.create(ProjectDefaults().toDraft().copy(name = "Parcela")) as SaveResult.Saved).id
    }

    private fun point(name: String, east: Double, north: Double) = Point(
        id = "id-$name",
        projectId = projectId,
        name = name,
        description = "",
        origin = PointOrigin.MANUAL,
        coordinates = PointCoordinates(GeodeticPosition(40.4, -3.7, null), east, north, null),
        measurement = null,
        typed = null,
        edited = false,
        hidden = false,
        copyOf = null,
        createdAtMs = 3_000,
        deletedAtMs = null,
    )

    init {
        runBlocking {
            listOf(
                point("A", 440_000.0, 4_472_100.0),
                point("B", 440_000.0, 4_472_000.0),
                point("C", 440_100.0, 4_472_000.0),
                point("D", 440_500.0, 4_472_500.0),
                point("B2", 440_000.0, 4_472_000.0),
            ).forEach { points.create(it) }
        }
    }

    private fun viewModel(lineId: String? = null): ThreePointAngleViewModel {
        val vm = ThreePointAngleViewModel(points, lines, projects, preferences)
        viewModels += vm
        vm.start(projectId, lineId)
        ScopeForTest.launch { vm.state.collect {} }
        await(vm) { it.loaded }
        return vm
    }

    private object ScopeForTest : kotlinx.coroutines.CoroutineScope {
        override val coroutineContext = Dispatchers.Unconfined
    }

    private fun await(vm: ThreePointAngleViewModel, predicate: (ThreePointAngleUi) -> Boolean): ThreePointAngleUi = runBlocking {
        try {
            withTimeout(10_000) { vm.state.first(predicate) }
        } catch (e: TimeoutCancellationException) {
            throw AssertionError("El ángulo no llegó al estado esperado; se quedó en ${vm.state.value}", e)
        }
    }

    private fun choose(vm: ThreePointAngleViewModel, slot: AnglePoint, id: String) {
        vm.openPicker(slot)
        await(vm) { it.picking == slot }
        vm.choose(id)
        await(vm) { ui ->
            val option = when (slot) {
                AnglePoint.A -> ui.a
                AnglePoint.B -> ui.b
                AnglePoint.C -> ui.c
            }
            option?.id == id
        }
    }

    @Test
    fun `S026 RF-13 sin elegir los tres puntos no hay resultado`() {
        val vm = viewModel()

        vm.state.value.result shouldBe null
        choose(vm, AnglePoint.A, "id-A")
        choose(vm, AnglePoint.B, "id-B")

        vm.state.value.result shouldBe null
        vm.state.value.a?.name shouldBe "A"
    }

    @Test
    fun `S026 RF-13 el selector ofrece los puntos del proyecto y se filtra por nombre`() {
        val vm = viewModel()

        vm.openPicker(AnglePoint.A)
        await(vm) { it.candidates.size == 5 }
        vm.setQuery("b2")

        await(vm) { it.candidates.map { c -> c.name } == listOf("B2") }
    }

    @Test
    fun `S026 RF-14 con tres puntos da el angulo en B, los azimuts y las distancias`() {
        val vm = viewModel()
        choose(vm, AnglePoint.A, "id-A")
        choose(vm, AnglePoint.B, "id-B")
        choose(vm, AnglePoint.C, "id-C")

        val result = await(vm) { it.result != null }.result.shouldBeInstanceOf<AngleResult.Angle>()
        result.angleRad shouldBe (Math.PI / 2 plusOrMinus 1e-12)
        result.azimuthBaRad shouldBe (0.0 plusOrMinus 1e-12)
        result.azimuthBcRad shouldBe (Math.PI / 2 plusOrMinus 1e-12)
        result.distanceBa shouldBe (100.0 plusOrMinus 1e-9)
        result.distanceBc shouldBe (100.0 plusOrMinus 1e-9)
    }

    @Test
    fun `S026 RF-14 el terreno cambia las distancias y la eleccion se recuerda`() {
        val vm = viewModel()
        choose(vm, AnglePoint.A, "id-A")
        choose(vm, AnglePoint.B, "id-B")
        choose(vm, AnglePoint.C, "id-C")

        vm.setMode(DistanceMode.GROUND)

        val ground = await(vm) { it.mode == DistanceMode.GROUND && it.result != null }.result.shouldBeInstanceOf<AngleResult.Angle>()
        preferences.mode.value shouldBe DistanceMode.GROUND
        (ground.distanceBa > 100.0) shouldBe true
    }

    @Test
    fun `S026 RF-15 dos puntos con las mismas coordenadas horizontales no dan angulo`() {
        val vm = viewModel()
        choose(vm, AnglePoint.A, "id-A")
        choose(vm, AnglePoint.B, "id-B")
        choose(vm, AnglePoint.C, "id-B2")

        val result = await(vm) { it.result != null }.result
        result shouldBe AngleResult.Coincident(listOf(AnglePoint.B to AnglePoint.C))
    }

    @Test
    fun `S026 RF-13 desde la ficha de una linea solo se ofrecen sus vertices`() {
        val lineId = (
            runBlocking {
                lines.createFromPoints(NewLine(projectId, "L_1", null, CodeGeometry.LINE, false, listOf("id-A", "id-B", "id-C")))
            } as LineSaveResult.Saved
            ).id
        val vm = viewModel(lineId)

        vm.openPicker(AnglePoint.A)

        await(vm) { it.candidates.map { c -> c.name } == listOf("A", "B", "C") }
        vm.state.value.lineName shouldBe "L_1"
    }

    @Test
    fun `S026 RF-5 al mover un punto elegido el angulo se recalcula`() {
        val vm = viewModel()
        choose(vm, AnglePoint.A, "id-A")
        choose(vm, AnglePoint.B, "id-B")
        choose(vm, AnglePoint.C, "id-C")
        await(vm) { it.result != null }

        runBlocking {
            points.update(
                point("C", 440_000.0, 4_471_900.0),
                es.topophone.core.model.point.PointChange.EDIT,
            )
        }

        val result = await(vm) { (it.result as? AngleResult.Angle)?.angleRad?.let { a -> a > 3.0 } == true }.result
        result.shouldBeInstanceOf<AngleResult.Angle>()
    }
}
