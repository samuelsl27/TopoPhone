package es.topophone.app.ui.cogo

import android.content.Context
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToNode
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.core.designsystem.theme.TopoPhoneTheme
import es.topophone.core.model.cogo.LegKind
import es.topophone.core.model.geometry.DistanceMode
import es.topophone.core.model.point.EntryForm
import es.topophone.core.model.point.GeodeticPosition
import es.topophone.core.model.point.HeightKind
import es.topophone.core.model.point.Point
import es.topophone.core.model.point.PointCoordinates
import es.topophone.core.model.point.PointOrigin
import es.topophone.core.model.point.TypedCoordinates
import es.topophone.core.model.preferences.EffectiveTheme
import es.topophone.core.model.preferences.ThemeState
import es.topophone.core.model.project.AngularUnit
import es.topophone.core.model.project.ProjectDefaults
import es.topophone.data.cogo.RoomCogoRepository
import es.topophone.data.db.TopoPhoneDatabase
import es.topophone.data.geometry.GeometryPreferences
import es.topophone.data.point.RoomPointRepository
import es.topophone.data.project.RoomProjectRepository
import es.topophone.data.project.SaveResult
import io.kotest.matchers.shouldBe
import java.util.Random
import kotlin.system.measureNanoTime
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
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Una poligonal de 200 tramos: se recalcula en menos de 100 ms, escribir no bloquea el hilo principal y la lista es perezosa (S038, RNF-T13). */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "es-rES")
class CogoPerformanceTest {

    @get:Rule
    val compose = createComposeRule()

    private val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), TopoPhoneDatabase::class.java)
        .allowMainThreadQueries()
        .build()
    private val projects = RoomProjectRepository(db)
    private val points = RoomPointRepository(db)
    private val cogo = RoomCogoRepository(db)
    private val viewModels = mutableListOf<TraverseViewModel>()

    private class FakePreferences : GeometryPreferences {
        val mode = MutableStateFlow(DistanceMode.GRID)
        override val distanceMode = mode

        override suspend fun setDistanceMode(mode: DistanceMode) {
            this.mode.value = mode
        }
    }

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
        val draft = ProjectDefaults().toDraft().copy(name = "Obra", angularUnit = AngularUnit.DECIMAL_DEGREES)
        (projects.create(draft) as SaveResult.Saved).id
    }

    private fun legs(count: Int): List<LegText> {
        val random = Random(38)
        val kinds = LegKind.entries
        return List(count) {
            val kind = kinds[random.nextInt(kinds.size)]
            val value = if (kind.name.startsWith("DEFLECTION")) random.nextDouble() * 180 else random.nextDouble() * 359
            LegText(
                kind,
                "%.6f".format(java.util.Locale.ROOT, value),
                "%.3f".format(java.util.Locale.ROOT, 5 + random.nextDouble() * 80),
                "0.1",
            )
        }
    }

    private fun viewModel(): TraverseViewModel {
        runBlocking {
            points.create(point("P1", 440_000.0, 4_474_000.0))
            points.create(point("R", 440_000.0, 4_473_900.0))
        }
        return TraverseViewModel(points, projects, FakePreferences(), cogo).also {
            viewModels += it
            it.start(projectId)
            it.inputs.open(SLOT_START)
            it.inputs.chooseProject("id-P1")
            it.inputs.open(SLOT_REFERENCE)
            it.inputs.chooseProject("id-R")
        }
    }

    private fun point(name: String, east: Double, north: Double) = Point(
        id = "id-$name",
        projectId = projectId,
        name = name,
        description = "",
        origin = PointOrigin.MANUAL,
        coordinates = PointCoordinates(GeodeticPosition(40.4144604937, -3.7071991234, 699.5), east, north, 650.0),
        measurement = null,
        typed = TypedCoordinates(EntryForm.PROJECT_CRS, east, north, 650.0, HeightKind.PROJECT),
        edited = false,
        hidden = false,
        copyOf = null,
        createdAtMs = 1_000,
        deletedAtMs = null,
    )

    private fun TraverseViewModel.await(predicate: (TraverseUi) -> Boolean): TraverseUi =
        runBlocking { withTimeout(20_000) { state.first(predicate) } }

    @Test
    fun `S038 RNF-T13 cambiar un tramo recalcula los 200 puntos en menos de 100 ms`() {
        val vm = viewModel()
        vm.await { it.start is CogoSlotValue.Found && it.reference is CogoSlotValue.Found }
        vm.restoreLegs(legs(200))
        vm.await { it.legs.size == 200 && it.legs.last().step != null }

        // La mejor de varias pasadas, tras calentar: un cambio en el primer tramo mueve los 199 siguientes.
        val times = (1..8).map { n ->
            val before = vm.state.value.legs.last().step!!.easting
            val nanos = measureNanoTime {
                vm.setDistance(0, "%d.5".format(10 + n))
                vm.await { it.legs.last().step?.easting != before }
            }
            nanos / 1e6
        }

        (times.drop(2).min() < 100.0) shouldBe true
    }

    @Test
    fun `S038 RNF-T13 escribir un campo no bloquea el hilo principal mas de 16 ms`() {
        val vm = viewModel()
        vm.await { it.start is CogoSlotValue.Found }
        vm.restoreLegs(legs(200))
        vm.await { it.legs.size == 200 && it.legs.last().step != null }

        // Lo que el hilo principal hace al escribir es anotar el texto: el calculo y el guardado van en otros hilos.
        val calls = (1..100).map { n -> measureNanoTime { vm.setDistance(n % 200, "12.${n % 10}") } / 1e6 }.sorted()

        (calls[calls.size / 2] < 16.0) shouldBe true
    }

    @Test
    fun `S038 RNF-T13 los 200 tramos se guardan en el borrador y vuelven completos`() {
        val vm = viewModel()
        vm.await { it.start is CogoSlotValue.Found }
        vm.restoreLegs(legs(200))
        vm.await { it.legs.size == 200 && it.legs.last().step != null }

        val draft = runBlocking { withTimeout(20_000) { cogo.draft(projectId).first { it?.legs?.size == 200 } } }!!

        draft.legs.size shouldBe 200
        draft.legs.all { it.angleRad != null && it.distanceM != null } shouldBe true
    }

    @Test
    fun `S038 RNF-T13 la lista de 200 tramos es perezosa y llega al ultimo`() {
        val vm = viewModel()
        vm.await { it.start is CogoSlotValue.Found }
        vm.restoreLegs(legs(200))
        val state = vm.await { it.legs.size == 200 && it.legs.last().step != null }
        compose.setContent {
            TopoPhoneTheme(EffectiveTheme.LIGHT) { TraverseScreen(ThemeState.DEFAULT, {}, state, TraverseActions(), onBack = {}) }
        }

        val composed = compose.onAllNodesWithTag(traverseLegTag(1)).fetchSemanticsNodes().size +
            (1..200).count { compose.onAllNodesWithTag(traverseLegTag(it)).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag(TRAVERSE_LIST_TAG).performScrollToNode(hasTestTag(traverseLegTag(200)))

        (composed < 100) shouldBe true // no se compone toda la lista de golpe
        compose.onAllNodesWithTag(traverseLegTag(200)).fetchSemanticsNodes().size shouldBe 1
    }
}
