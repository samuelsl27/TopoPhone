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
import es.topophone.core.model.point.PointChange
import es.topophone.core.model.point.PointCoordinates
import es.topophone.core.model.point.PointOrigin
import es.topophone.core.model.project.ProjectCrs
import es.topophone.core.model.project.ProjectDefaults
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

/** La información de una línea: modo recordado, recalcular al editar y motivos (S026/RF-5, RF-7, RF-9). */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "es-rES")
class GeometryInfoViewModelTest {

    private val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), TopoPhoneDatabase::class.java)
        .allowMainThreadQueries()
        .build()
    private val projects = RoomProjectRepository(db)
    private val points = RoomPointRepository(db)
    private val lines = RoomLineRepository(db, points)
    private val viewModels = mutableListOf<GeometryInfoViewModel>()

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
        (projects.create(ProjectDefaults().toDraft().copy(name = "Parcela", crs = ProjectCrs.ETRS89_UTM30)) as SaveResult.Saved).id
    }

    private fun point(name: String, easting: Double, northing: Double, height: Double?) = Point(
        id = "id-$name",
        projectId = projectId,
        name = name,
        description = "",
        origin = PointOrigin.MANUAL,
        coordinates = PointCoordinates(GeodeticPosition(40.4, -3.7, height?.plus(50.0)), easting, northing, height),
        measurement = null,
        typed = null,
        edited = false,
        hidden = false,
        copyOf = null,
        createdAtMs = 3_000,
        deletedAtMs = null,
    )

    /** Un triángulo rectángulo de 30 y 40 m (hipotenusa 50 m) con alturas. */
    private fun triangle(closed: Boolean = false, count: Int = 3): String {
        runBlocking {
            listOf(
                point("1", 440_000.0, 4_472_000.0, 650.0),
                point("2", 440_030.0, 4_472_000.0, 650.0),
                point("3", 440_030.0, 4_472_040.0, 664.0),
            ).forEach { points.create(it) }
        }
        val ids = listOf("id-1", "id-2", "id-3").take(count)
        val geometry = if (closed) CodeGeometry.POLYGON else CodeGeometry.LINE
        return (runBlocking { lines.createFromPoints(NewLine(projectId, "L_1", null, geometry, closed, ids)) } as LineSaveResult.Saved).id
    }

    private fun viewModel(lineId: String): GeometryInfoViewModel {
        val vm = GeometryInfoViewModel(lines, projects, preferences)
        viewModels += vm
        vm.start(projectId, lineId)
        CoroutineScopeForTest.launch { vm.state.collect {} }
        await(vm) { it.loaded }
        return vm
    }

    private object CoroutineScopeForTest : kotlinx.coroutines.CoroutineScope {
        override val coroutineContext = Dispatchers.Unconfined
    }

    private fun await(vm: GeometryInfoViewModel, predicate: (GeometryInfoUi) -> Boolean): GeometryInfoUi = runBlocking {
        try {
            withTimeout(10_000) { vm.state.first(predicate) }
        } catch (e: TimeoutCancellationException) {
            throw AssertionError("La información no llegó al estado esperado; se quedó en ${vm.state.value}", e)
        }
    }

    @Test
    fun `S026 RF-1 calcula la longitud 2D y 3D de la linea del proyecto`() {
        val vm = viewModel(triangle())

        val info = vm.state.value.info!!
        info.length2D shouldBe (70.0 plusOrMinus 1e-9)
        info.length3D!! shouldBe (30.0 + Math.hypot(40.0, 14.0) plusOrMinus 1e-9)
        vm.state.value.names shouldBe listOf("1", "2", "3")
        vm.state.value.mode shouldBe DistanceMode.GRID
    }

    @Test
    fun `S026 RF-1 un poligono suma el perimetro y el area`() {
        val vm = viewModel(triangle(closed = true))

        val info = vm.state.value.info!!
        info.perimeter!! shouldBe (120.0 plusOrMinus 1e-9)
        info.area!! shouldBe (600.0 plusOrMinus 1e-9)
    }

    @Test
    fun `S026 RF-9 la eleccion se guarda y el terreno cambia las distancias`() {
        val vm = viewModel(triangle())
        val grid = vm.state.value.info!!.length2D

        vm.setMode(DistanceMode.GROUND)
        val ground = await(vm) { it.mode == DistanceMode.GROUND && it.info != null }.info!!

        preferences.mode.value shouldBe DistanceMode.GROUND
        (ground.length2D > grid) shouldBe true
        ground.mode shouldBe DistanceMode.GROUND
    }

    @Test
    fun `S026 RF-9 el modo recordado se aplica al abrir otra linea`() {
        preferences.mode.value = DistanceMode.GROUND

        val vm = viewModel(triangle())

        vm.state.value.mode shouldBe DistanceMode.GROUND
        vm.state.value.info!!.mode shouldBe DistanceMode.GROUND
    }

    @Test
    fun `S026 RF-5 al editar la altura y las coordenadas de un vertice la informacion se recalcula`() {
        val vm = viewModel(triangle())
        val before = vm.state.value.info!!

        runBlocking { points.update(point("3", 440_030.0, 4_472_080.0, 664.0), PointChange.EDIT) }

        val after = await(vm) { it.info != null && it.info.length2D != before.length2D }.info!!
        after.length2D shouldBe (110.0 plusOrMinus 1e-9)
    }

    @Test
    fun `S026 RF-7 con menos de dos vertices no se calcula y se dice por que`() {
        val lineId = triangle(count = 2)
        runBlocking { points.moveToTrash(listOf("id-2")) }
        val vm = viewModel(lineId)

        vm.state.value.info shouldBe null
        vm.state.value.problem shouldBe GeometryProblem.TOO_FEW_VERTICES
    }

    @Test
    fun `S026 solo lee y no cambia ningun punto ni la linea`() {
        val lineId = triangle(closed = true)
        val pointsBefore = runBlocking { points.summaries(projectId).first() }
        val lineBefore = runBlocking { lines.line(lineId).first() }

        val vm = viewModel(lineId)
        vm.setMode(DistanceMode.GROUND)
        await(vm) { it.mode == DistanceMode.GROUND && it.info != null }

        runBlocking { points.summaries(projectId).first() } shouldBe pointsBefore
        runBlocking { lines.line(lineId).first() } shouldBe lineBefore
    }
}
