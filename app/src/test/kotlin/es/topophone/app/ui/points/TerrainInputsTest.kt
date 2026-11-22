package es.topophone.app.ui.points

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.app.ui.map.FakePointRepository
import es.topophone.app.ui.points.PointFixtures.measured
import es.topophone.core.model.project.ProjectCrs
import es.topophone.core.model.project.ProjectDefaults
import es.topophone.core.model.project.TerrainPlane
import es.topophone.core.survey.ProjectPositionCalculator
import es.topophone.core.survey.TerrainView
import es.topophone.data.db.TopoPhoneDatabase
import es.topophone.data.project.RoomProjectRepository
import es.topophone.data.project.SaveResult
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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

/** Lo que se escribe en un proyecto en terreno es de terreno: el punto queda en cuadrícula y la ida y vuelta cierra (S058/RF-10). */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class TerrainInputsTest {

    private val es = Locale.forLanguageTag("es-ES")
    private val plane = TerrainPlane(40.4, -3.7, 1.0004)
    private val db = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext<Context>(),
        TopoPhoneDatabase::class.java,
    ).build()
    private val projects = RoomProjectRepository(db)
    private val projectId = runBlocking {
        projects.create(ProjectDefaults().toDraft().copy(name = "Terreno", crs = ProjectCrs.ETRS89_UTM30, terrain = plane))
            .shouldBeInstanceOf<SaveResult.Saved>().id
    }
    private val view = TerrainView.of(plane, ProjectCrs.ETRS89_UTM30).shouldNotBeNull()
    private val repository = FakePointRepository(listOf(measured("12", projectId = projectId)), clock = { 9_000 })
    private lateinit var vm: PointFormViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        vm = PointFormViewModel(repository, projects) { ProjectPositionCalculator() }
        CoroutineScope(Dispatchers.Unconfined).launch { vm.state.collect {} }
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        db.close()
    }

    private fun await(predicate: (PointFormUiState) -> Boolean): PointFormUiState =
        runBlocking { withTimeout(5_000) { vm.state.first(predicate) } }

    @Test
    fun `S058 RF-10 las coordenadas de terreno escritas se guardan en cuadricula`() {
        vm.start(projectId, pointId = null, locale = es)
        await { it.loaded }.terrain shouldBe true
        vm.setFirst("440297,988")
        vm.setSecond("4474244,001")
        await { it.canSave }

        vm.save()
        runBlocking { withTimeout(5_000) { vm.events.first() } }

        val point = repository.points.value.single { it.name == "13" }
        val grid = view.toGrid(440_297.988, 4_474_244.001)
        point.coordinates.easting.shouldNotBeNull() shouldBe (grid.easting plusOrMinus 1e-9)
        point.coordinates.northing.shouldNotBeNull() shouldBe (grid.northing plusOrMinus 1e-9)
        point.typed.shouldNotBeNull().first shouldBe (grid.easting plusOrMinus 1e-9)
    }

    @Test
    fun `S058 RF-10 al editar un punto se ven sus coordenadas de terreno y la ida y vuelta cierra`() {
        val original = PointFixtures.sol
        val terrain = view.coordinates(original.easting!!, original.northing!!)
        vm.start(projectId, pointId = "id-12", locale = es)

        val loaded = await { it.loaded }

        loaded.terrain shouldBe true
        loaded.first.replace(',', '.').toDouble() shouldBe (terrain.easting plusOrMinus 1e-3)
        loaded.second.replace(',', '.').toDouble() shouldBe (terrain.northing plusOrMinus 1e-3)
        // Escribir de nuevo lo mismo que se ve deja el punto en su cuadrícula, a la precisión del texto.
        vm.setFirst(loaded.first)
        await { it.canSave && it.first == loaded.first }
        vm.save()
        runBlocking { withTimeout(5_000) { vm.events.first() } }
        val point = repository.points.value.single { it.id == "id-12" }
        point.coordinates.easting.shouldNotBeNull() shouldBe (original.easting!! plusOrMinus 1e-3)
    }
}
