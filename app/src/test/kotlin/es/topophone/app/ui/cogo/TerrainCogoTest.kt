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
import es.topophone.core.model.project.TerrainPlane
import es.topophone.data.cogo.RoomCogoRepository
import es.topophone.data.db.TopoPhoneDatabase
import es.topophone.data.geometry.GeometryPreferences
import es.topophone.data.point.RoomPointRepository
import es.topophone.data.project.RoomProjectRepository
import es.topophone.data.project.SaveResult
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import java.util.Locale
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

/** COGO con el proyecto en terreno: se calcula en terreno, sin elección de distancia, y lo que se guarda queda en cuadrícula (S058/RF-9, RF-10). */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "es-rES")
class TerrainCogoTest {

    private val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), TopoPhoneDatabase::class.java)
        .allowMainThreadQueries()
        .build()
    private val projects = RoomProjectRepository(db)
    private val points = RoomPointRepository(db)
    private val intersections = mutableListOf<IntersectionViewModel>()
    private val inverses = mutableListOf<InverseViewModel>()
    private val factor = 1.0004

    private class FakePreferences : GeometryPreferences {
        val mode = MutableStateFlow(DistanceMode.GROUND)
        override val distanceMode = mode

        override suspend fun setDistanceMode(mode: DistanceMode) {
            this.mode.value = mode
        }
    }

    // La elección guardada es «Terreno»: en un proyecto en terreno no cuenta, se calcula en el plano de terreno.
    private val preferences = FakePreferences()

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() {
        (intersections.map { it.viewModelScope } + inverses.map { it.viewModelScope }).forEach { it.cancel() }
        runBlocking {
            (intersections.map { it.viewModelScope } + inverses.map { it.viewModelScope }).forEach { it.coroutineContext[Job]?.join() }
        }
        db.close()
        Dispatchers.resetMain()
    }

    private val projectId: String = runBlocking {
        val draft = ProjectDefaults().toDraft().copy(
            name = "Parcela",
            angularUnit = AngularUnit.DECIMAL_DEGREES,
            terrain = TerrainPlane(40.4144604937, -3.7071991234, factor),
        )
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

    // P1, P2 y P3 de fixtures/cogo/puntos.csv (ETRS89 UTM 30), en cuadrícula.
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

    private fun gridDistance(from: Triple<Double, Double, Double>, to: Triple<Double, Double, Double>) =
        hypot(to.first - from.first, to.second - from.second)

    private fun num(value: Double) = String.format(Locale.ROOT, "%.9f", value)

    private fun inverse() = InverseViewModel(points, projects, preferences).also {
        inverses += it
        it.start(projectId)
        it.inputs.open(SLOT_A)
        it.inputs.chooseProject("id-P1")
        it.inputs.open(SLOT_B)
        it.inputs.chooseProject("id-P2")
    }

    private fun intersection() = IntersectionViewModel(points, projects, preferences, RoomCogoRepository(db)).also {
        intersections += it
        it.start(projectId)
        it.inputs.open(SLOT_FIRST)
        it.inputs.chooseProject("id-P1")
        it.inputs.open(SLOT_SECOND)
        it.inputs.chooseProject("id-P2")
    }

    @Test
    fun `S058 RF-9 la inversa en terreno da la distancia de terreno y no ofrece la eleccion`() {
        val vm = inverse()

        val state = runBlocking { withTimeout(10_000) { vm.state.first { it.result != null } } }

        state.terrain shouldBe true
        state.mode shouldBe DistanceMode.GRID
        state.result.shouldNotBeNull().gridDistance shouldBe (gridDistance(p1, p2) * factor plusOrMinus 1e-6)
    }

    @Test
    fun `S058 RF-10 una interseccion en terreno se guarda en cuadricula`() {
        val vm = intersection()
        // Las distancias, como las mediría el usuario en terreno: las de cuadrícula por el factor.
        vm.setValue1(num(gridDistance(p1, p3) * factor))
        vm.setValue2(num(gridDistance(p2, p3) * factor))
        val state = runBlocking { withTimeout(10_000) { vm.state.first { it.solutions.size == 2 } } }
        state.terrain shouldBe true

        vm.toggleMarked(IntersectionSide.LEFT)
        runBlocking { withTimeout(10_000) { vm.state.first { it.markedCount == 1 && it.canSave } } }
        vm.openSave()
        runBlocking { withTimeout(10_000) { vm.save.state.first { it.open } } }
        vm.save.save()
        runBlocking { withTimeout(10_000) { vm.save.state.first { it.status is CogoSaveStatus.Saved } } }

        val saved = runBlocking { points.points(projectId).first() }.single { it.origin == PointOrigin.COGO }
        saved.coordinates.easting.shouldNotBeNull() shouldBe (p3.first plusOrMinus 1e-6)
        saved.coordinates.northing.shouldNotBeNull() shouldBe (p3.second plusOrMinus 1e-6)
    }
}
