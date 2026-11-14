package es.topophone.app.ui.cogo

import android.content.Context
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.core.model.geometry.DistanceMode
import es.topophone.core.model.point.EntryForm
import es.topophone.core.model.point.GeodeticPosition
import es.topophone.core.model.point.HeightKind
import es.topophone.core.model.point.Point
import es.topophone.core.model.point.PointCoordinates
import es.topophone.core.model.point.PointOrigin
import es.topophone.core.model.point.TypedCoordinates
import es.topophone.core.model.project.AngularUnit
import es.topophone.core.model.project.LinearUnit
import es.topophone.core.model.project.ProjectDefaults
import es.topophone.data.db.TopoPhoneDatabase
import es.topophone.data.geometry.GeometryPreferences
import es.topophone.data.point.RoomPointRepository
import es.topophone.data.project.RoomProjectRepository
import es.topophone.data.project.SaveResult
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlin.math.PI
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

/** La inversa de dos puntos: resultado, modo compartido con S026, unidades, intercambio y puntos escritos (S038/RF-5 a RF-9). */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "es-rES")
class InverseViewModelTest {

    private val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), TopoPhoneDatabase::class.java)
        .allowMainThreadQueries()
        .build()
    private val projects = RoomProjectRepository(db)
    private val points = RoomPointRepository(db)
    private val viewModels = mutableListOf<InverseViewModel>()

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

    private fun project(linear: LinearUnit = LinearUnit.METRE, angular: AngularUnit = AngularUnit.DMS): String = runBlocking {
        val draft = ProjectDefaults().toDraft().copy(name = "Parcela", linearUnit = linear, angularUnit = angular)
        (projects.create(draft) as SaveResult.Saved).id
    }

    private fun point(projectId: String, name: String, east: Double, north: Double, height: Double?, lat: Double, lon: Double) = Point(
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

    /** P1 y P2 de `fixtures/cogo/puntos.csv` (ETRS89 UTM 30). */
    private fun twoPoints(projectId: String, h2: Double? = 652.5) = runBlocking {
        points.create(point(projectId, "P1", 440_000.0, 4_474_000.0, 650.0, 40.4144604937, -3.7071991234))
        points.create(point(projectId, "P2", 440_150.25, 4_474_040.125, h2, 40.4148327766, -3.7054320814))
    }

    private fun viewModel(id: String) = InverseViewModel(points, projects, preferences).also {
        viewModels += it
        it.start(id)
    }

    private fun InverseViewModel.choose(slot: String, pointId: String) {
        inputs.open(slot)
        inputs.chooseProject(pointId)
    }

    private fun InverseViewModel.await(predicate: (InverseUi) -> Boolean): InverseUi =
        runBlocking { withTimeout(10_000) { state.first(predicate) } }

    @Test
    fun `S038 RF-6 con dos puntos elegidos sale la inversa con las dos distancias, los azimuts y las diferencias`() {
        val id = project()
        twoPoints(id)
        val vm = viewModel(id)

        vm.choose(SLOT_A, "id-P1")
        vm.choose(SLOT_B, "id-P2")
        val result = vm.await { it.result != null }.result!!

        result.gridDistance shouldBe (Math.hypot(150.25, 40.125) plusOrMinus 1e-9)
        (result.groundDistance > result.gridDistance) shouldBe true
        result.azimuthRad!! shouldBe (Math.atan2(150.25, 40.125) plusOrMinus 1e-12)
        result.dEasting shouldBe 150.25
        result.dNorthing shouldBe 40.125
        result.dHeight!! shouldBe (2.5 plusOrMinus 1e-9)
    }

    @Test
    fun `S038 RF-6 hasta que no estan los dos puntos no hay resultado`() {
        val id = project()
        twoPoints(id)
        val vm = viewModel(id)

        vm.await { it.loaded }.result.shouldBeNull()
        vm.choose(SLOT_A, "id-P1")

        vm.await { it.a is CogoSlotValue.Found }.result.shouldBeNull()
    }

    @Test
    fun `S038 RF-5 la distancia activa es la de cuadricula o la de terreno segun la eleccion compartida con S026`() {
        val id = project()
        twoPoints(id)
        val vm = viewModel(id)
        vm.choose(SLOT_A, "id-P1")
        vm.choose(SLOT_B, "id-P2")
        vm.await { it.result?.mode == DistanceMode.GRID }.result!!.let { it.horizontalDistance shouldBe it.gridDistance }

        vm.setMode(DistanceMode.GROUND)
        val ground = vm.await { it.mode == DistanceMode.GROUND && it.result?.mode == DistanceMode.GROUND }.result!!

        preferences.mode.value shouldBe DistanceMode.GROUND // la elección global de S026
        ground.horizontalDistance shouldBe ground.groundDistance
    }

    @Test
    fun `S038 RF-6 el proyecto en pies y en gon lo dice el estado y el resultado sigue en metros`() {
        val id = project(LinearUnit.INTERNATIONAL_FOOT, AngularUnit.GON)
        twoPoints(id)
        val vm = viewModel(id)

        vm.choose(SLOT_A, "id-P1")
        vm.choose(SLOT_B, "id-P2")
        val state = vm.await { it.result != null }

        state.linearUnit shouldBe LinearUnit.INTERNATIONAL_FOOT
        state.angularUnit shouldBe AngularUnit.GON
        state.result!!.gridDistance shouldBe (Math.hypot(150.25, 40.125) plusOrMinus 1e-9)
    }

    @Test
    fun `S038 RF-8 dos puntos coincidentes dan distancia 0 y azimut indefinido sin error`() {
        val id = project()
        twoPoints(id)
        val vm = viewModel(id)

        vm.choose(SLOT_A, "id-P1")
        vm.choose(SLOT_B, "id-P1")
        val result = vm.await { it.result != null }.result!!

        result.coincident shouldBe true
        result.gridDistance shouldBe 0.0
        result.azimuthRad.shouldBeNull()
    }

    @Test
    fun `S038 RF-7 un punto sin altura da la diferencia de altura y la pendiente vacias`() {
        val id = project()
        twoPoints(id, h2 = null)
        val vm = viewModel(id)

        vm.choose(SLOT_A, "id-P1")
        vm.choose(SLOT_B, "id-P2")
        val result = vm.await { it.result != null }.result!!

        result.dHeight.shouldBeNull()
        result.slantDistance.shouldBeNull()
        result.slopePercent.shouldBeNull()
    }

    @Test
    fun `S038 RF-9 intercambiar los puntos da el azimut inverso`() {
        val id = project()
        twoPoints(id)
        val vm = viewModel(id)
        vm.choose(SLOT_A, "id-P1")
        vm.choose(SLOT_B, "id-P2")
        val before = vm.await { it.result != null }.result!!

        vm.swap()
        val after = vm.await { it.result != null && it.result!!.dEasting < 0 }.result!!

        after.azimuthRad!! shouldBe (before.reverseAzimuthRad!! plusOrMinus 1e-12)
        after.gridDistance shouldBe (before.gridDistance plusOrMinus 1e-9)
        (after.azimuthRad!! - before.azimuthRad!!).let { Math.abs(it) shouldBe (PI plusOrMinus 1e-12) }
    }

    @Test
    fun `S038 RF-3 un punto escrito a mano vale como entrada y no se guarda en el proyecto`() {
        val id = project()
        twoPoints(id)
        val vm = viewModel(id)

        vm.choose(SLOT_A, "id-P1")
        vm.inputs.setTyped(SLOT_B, "440100", "4474100")
        val result = vm.await { it.result != null }.result!!

        result.dEasting shouldBe (100.0 plusOrMinus 1e-9)
        result.dHeight.shouldBeNull() // el escrito no tiene altura
        runBlocking { points.points(id).first() }.size shouldBe 2
    }
}
