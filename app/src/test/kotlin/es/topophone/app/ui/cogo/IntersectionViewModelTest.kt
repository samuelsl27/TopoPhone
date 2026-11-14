package es.topophone.app.ui.cogo

import android.content.Context
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.core.model.cogo.IntersectionSide
import es.topophone.core.model.geometry.DistanceMode
import es.topophone.core.model.point.EntryForm
import es.topophone.core.model.point.GeodeticPosition
import es.topophone.core.model.point.HeightKind
import es.topophone.core.model.point.Point
import es.topophone.core.model.point.PointCoordinates
import es.topophone.core.model.point.PointOrigin
import es.topophone.core.model.point.TypedCoordinates
import es.topophone.core.model.project.AngularUnit
import es.topophone.core.model.project.ProjectDefaults
import es.topophone.core.survey.cogo.IntersectionResult
import es.topophone.core.survey.cogo.NoSolutionReason
import es.topophone.data.db.TopoPhoneDatabase
import es.topophone.data.geometry.GeometryPreferences
import es.topophone.data.point.RoomPointRepository
import es.topophone.data.project.RoomProjectRepository
import es.topophone.data.project.SaveResult
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.util.Locale
import kotlin.math.atan2
import kotlin.math.hypot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
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

/** Las tres intersecciones en la app: clases, condiciones, soluciones, motivos, altura y marcas (S038/RF-13 a RF-18). */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "es-rES")
class IntersectionViewModelTest {

    private val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), TopoPhoneDatabase::class.java)
        .allowMainThreadQueries()
        .build()
    private val projects = RoomProjectRepository(db)
    private val points = RoomPointRepository(db)
    private val viewModels = mutableListOf<IntersectionViewModel>()

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
        runBlocking { viewModels.forEach { it.viewModelScope.coroutineContext[Job]?.join() } }
        db.close()
        Dispatchers.resetMain()
    }

    private val projectId: String = runBlocking {
        val draft = ProjectDefaults().toDraft().copy(name = "Parcela", angularUnit = AngularUnit.DECIMAL_DEGREES)
        (projects.create(draft) as SaveResult.Saved).id
    }

    private fun point(name: String, east: Double, north: Double, height: Double?, lat: Double, lon: Double) = Point(
        id = "id-$name",
        projectId = projectId,
        name = name,
        description = "",
        origin = PointOrigin.MANUAL,
        coordinates = PointCoordinates(GeodeticPosition(lat, lon, height?.plus(49.5)), east, north, height),
        measurement = null,
        typed = TypedCoordinates(EntryForm.PROJECT_CRS, east, north, height, HeightKind.PROJECT),
        edited = false,
        hidden = false,
        copyOf = null,
        createdAtMs = 1_000,
        deletedAtMs = null,
    )

    // P1, P2 y P3 de fixtures/cogo/puntos.csv (ETRS89 UTM 30).
    private val p1 = Triple(440_000.0, 4_474_000.0, 650.0)
    private val p2 = Triple(440_150.25, 4_474_040.125, 652.5)
    private val p3 = Triple(440_210.5, 4_474_190.75, 655.25)

    init {
        runBlocking {
            points.create(point("P1", p1.first, p1.second, p1.third, 40.4144604937, -3.7071991234))
            points.create(point("P2", p2.first, p2.second, p2.third, 40.4148327766, -3.7054320814))
            points.create(point("P3", p3.first, p3.second, p3.third, 40.416194008, -3.7047361398))
        }
    }

    private fun viewModel() = IntersectionViewModel(points, projects, preferences).also {
        viewModels += it
        it.start(projectId)
        it.inputs.open(SLOT_FIRST)
        it.inputs.chooseProject("id-P1")
        it.inputs.open(SLOT_SECOND)
        it.inputs.chooseProject("id-P2")
    }

    private fun IntersectionViewModel.await(predicate: (IntersectionUi) -> Boolean): IntersectionUi =
        runBlocking { withTimeout(10_000) { state.first(predicate) } }

    private fun num(value: Double) = String.format(Locale.ROOT, "%.9f", value)

    private fun distance(from: Triple<Double, Double, Double>, to: Triple<Double, Double, Double>) =
        hypot(to.first - from.first, to.second - from.second)

    private fun azimuthDeg(from: Triple<Double, Double, Double>, to: Triple<Double, Double, Double>): Double {
        val deg = Math.toDegrees(atan2(to.first - from.first, to.second - from.second))
        return if (deg < 0) deg + 360.0 else deg
    }

    @Test
    fun `S038 RF-14 distancia-distancia da dos soluciones, la izquierda es el punto conocido`() {
        val vm = viewModel()

        vm.setValue1(num(distance(p1, p3)))
        vm.setValue2(num(distance(p2, p3)))
        val state = vm.await { it.solutions.size == 2 }

        state.kind shouldBe IntersectionKind.DISTANCE_DISTANCE
        state.solutions.map { it.side } shouldBe listOf(IntersectionSide.LEFT, IntersectionSide.RIGHT)
        state.solutions[0].easting shouldBe (p3.first plusOrMinus 1e-6)
        state.solutions[0].northing shouldBe (p3.second plusOrMinus 1e-6)
    }

    @Test
    fun `S038 RF-15 direccion-direccion da la solucion con la distancia desde cada punto`() {
        val vm = viewModel()
        vm.setKind(IntersectionKind.DIRECTION_DIRECTION)

        vm.setValue1(num(azimuthDeg(p1, p3)))
        vm.setValue2(num(azimuthDeg(p2, p3)))
        val solution = vm.await { it.solutions.size == 1 }.solutions.single()

        solution.side shouldBe IntersectionSide.SINGLE
        solution.easting shouldBe (p3.first plusOrMinus 1e-6)
        solution.distance1 shouldBe (distance(p1, p3) plusOrMinus 1e-6)
        solution.distance2 shouldBe (distance(p2, p3) plusOrMinus 1e-6)
        solution.behind shouldBe false
    }

    @Test
    fun `S038 RF-15 una direccion que mira al reves deja la solucion detras y se marca`() {
        val vm = viewModel()
        vm.setKind(IntersectionKind.DIRECTION_DIRECTION)

        vm.setValue1(num((azimuthDeg(p1, p3) + 180.0) % 360.0))
        vm.setValue2(num(azimuthDeg(p2, p3)))
        val solution = vm.await { it.solutions.size == 1 }.solutions.single()

        solution.behind shouldBe true
        (solution.distance1 < 0.0) shouldBe true
    }

    @Test
    fun `S038 RF-16 distancia-direccion da las dos soluciones ordenadas y una es el punto conocido`() {
        val vm = viewModel()
        vm.setKind(IntersectionKind.DISTANCE_DIRECTION)

        vm.setValue1(num(distance(p1, p3)))
        vm.setValue2(num(azimuthDeg(p2, p3)))
        val state = vm.await { it.solutions.size == 2 }

        state.solutions.map { it.side } shouldBe listOf(IntersectionSide.FIRST, IntersectionSide.SECOND)
        state.solutions.any { Math.abs(it.easting - p3.first) < 1e-6 && Math.abs(it.northing - p3.second) < 1e-6 } shouldBe true
        (state.solutions[0].distance2 < state.solutions[1].distance2) shouldBe true
    }

    @Test
    fun `S038 RF-17 sin solucion se dice el motivo y no se calcula nada`() {
        val vm = viewModel()

        vm.setValue1("1")
        vm.setValue2("1")
        val state = vm.await { it.result != null }

        state.result.shouldBeInstanceOf<IntersectionResult.NoSolution>().reason shouldBe NoSolutionReason.TOO_FAR
        state.solutions shouldBe emptyList()
    }

    @Test
    fun `S038 RF-17 direcciones paralelas no dan solucion`() {
        val vm = viewModel()
        vm.setKind(IntersectionKind.DIRECTION_DIRECTION)

        vm.setValue1("40")
        vm.setValue2("40")

        vm.await { it.result != null }.result.shouldBeInstanceOf<IntersectionResult.NoSolution>().reason shouldBe NoSolutionReason.PARALLEL
    }

    @Test
    fun `S038 RF-4 una condicion que no es un numero se marca y no hay resultado`() {
        val vm = viewModel()

        vm.setValue1("abc")
        vm.setValue2("12")
        val state = vm.await { it.value1Invalid }

        state.value2Invalid shouldBe false
        state.result.shouldBeNull()
    }

    @Test
    fun `S038 RF-4 un azimut de 360 grados no vale en direccion-direccion`() {
        val vm = viewModel()
        vm.setKind(IntersectionKind.DIRECTION_DIRECTION)

        vm.setValue1("360")
        vm.setValue2("10")

        vm.await { it.value1Invalid }.result.shouldBeNull()
    }

    @Test
    fun `S038 RF-13 cambiar de clase borra las condiciones escritas`() {
        val vm = viewModel()
        vm.setValue1("10")
        vm.setValue2("20")
        vm.await { it.value1Text == "10" }

        vm.setKind(IntersectionKind.DISTANCE_DIRECTION)
        val state = vm.await { it.kind == IntersectionKind.DISTANCE_DIRECTION }

        state.value1Text shouldBe ""
        state.value2Text shouldBe ""
    }

    @Test
    fun `S038 RF-18 la altura de una solucion es opcional y se lee con coma o con punto`() {
        val vm = viewModel()
        vm.setValue1(num(distance(p1, p3)))
        vm.setValue2(num(distance(p2, p3)))
        vm.await { it.solutions.size == 2 }

        vm.setHeight(IntersectionSide.LEFT, "651,5")
        vm.setHeight(IntersectionSide.RIGHT, "x")
        val state = vm.await { it.solutions.firstOrNull()?.heightText == "651,5" }

        state.solutions[0].height shouldBe 651.5
        state.solutions[0].heightInvalid shouldBe false
        state.solutions[1].height.shouldBeNull()
        state.solutions[1].heightInvalid shouldBe true
        vm.setHeight(IntersectionSide.RIGHT, "")
        vm.await { it.solutions.getOrNull(1)?.heightText == "" }.solutions[1].let {
            it.height.shouldBeNull()
            it.heightInvalid shouldBe false
        }
    }

    @Test
    fun `S038 RF-19 las soluciones se marcan y desmarcan para guardarlas`() {
        val vm = viewModel()
        vm.setValue1(num(distance(p1, p3)))
        vm.setValue2(num(distance(p2, p3)))
        vm.await { it.solutions.size == 2 }

        vm.toggleMarked(IntersectionSide.RIGHT)
        vm.await { it.solutions.getOrNull(1)?.marked == true }.solutions.map { it.marked } shouldBe listOf(false, true)
        vm.toggleMarked(IntersectionSide.RIGHT)

        vm.await { it.solutions.getOrNull(1)?.marked == false }.solutions.map { it.marked } shouldBe listOf(false, false)
    }

    @Test
    fun `S038 RF-5 las mismas distancias en terreno dan otra posicion que en cuadricula`() {
        val vm = viewModel()
        vm.setValue1(num(distance(p1, p3)))
        vm.setValue2(num(distance(p2, p3)))
        val grid = vm.await { it.solutions.size == 2 }.solutions[0]

        vm.setMode(DistanceMode.GROUND)
        val ground = vm.await { it.mode == DistanceMode.GROUND && it.solutions.size == 2 }.solutions[0]

        (Math.hypot(ground.easting - grid.easting, ground.northing - grid.northing) > 0.01) shouldBe true
    }
}
