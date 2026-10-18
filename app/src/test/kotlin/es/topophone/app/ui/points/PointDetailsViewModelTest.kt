package es.topophone.app.ui.points

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.app.ui.map.FakePointRepository
import es.topophone.app.ui.points.PointFixtures.measured
import es.topophone.core.model.point.PointChange
import es.topophone.core.model.point.PointOrigin
import es.topophone.core.model.project.ProjectDefaults
import es.topophone.core.survey.ProjectPositionCalculator
import es.topophone.data.db.TopoPhoneDatabase
import es.topophone.data.project.RoomProjectRepository
import es.topophone.data.project.SaveResult
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
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

/** Ficha del punto: el punto y su proyecto, y volver a la medida (S011/RF-5, RF-12, RF-13). */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class PointDetailsViewModelTest {

    private val db = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext<Context>(),
        TopoPhoneDatabase::class.java,
    ).build()
    private val projects = RoomProjectRepository(db)
    private val projectId = runBlocking {
        projects.create(ProjectDefaults().toDraft().copy(name = "Calle Mayor 12")).shouldBeInstanceOf<SaveResult.Saved>().id
    }
    private val original = measured("12", projectId = projectId)
    private val edited = original.copy(coordinates = PointFixtures.sol.copy(easting = 440_300.0), edited = true)
    private val repository = FakePointRepository(listOf(edited), clock = { 5_000 })
    private lateinit var vm: PointDetailsViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        vm = PointDetailsViewModel(repository, projects) { ProjectPositionCalculator() }
        CoroutineScope(Dispatchers.Unconfined).launch { vm.state.collect {} }
        vm.start(projectId, "id-12")
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        db.close()
    }

    private fun await(predicate: (PointDetailsUiState) -> Boolean): PointDetailsUiState =
        runBlocking { withTimeout(5_000) { vm.state.first(predicate) } }

    @Test
    fun `S011 RF-5 la ficha tiene el punto pedido y su proyecto`() {
        val state = await { it.point != null && it.project != null }

        state.point shouldBe edited
        state.project.shouldNotBeNull().id shouldBe projectId
    }

    @Test
    fun `S011 RF-13 volver a la medida guarda sus coordenadas como un cambio mas del historial`() {
        await { it.point != null }

        vm.revertToMeasurement()

        val state = await { it.point?.edited == false }
        state.point.shouldNotBeNull().coordinates shouldBe original.coordinates
        repository.historyEntries.value.single().change shouldBe PointChange.REVERT
        repository.historyEntries.value.single().previous.edited shouldBe true
    }

    @Test
    fun `S011 RF-11 la ficha tiene el historial del punto`() {
        await { it.point != null }
        vm.revertToMeasurement()

        await { it.history.size == 1 }.history.single().change shouldBe PointChange.REVERT
    }

    @Test
    fun `S011 RF-14 el jalon se corrige desde la ficha y se avisa si se mantienen las coordenadas editadas`() {
        await { it.point != null }

        vm.correctPole(2.0)

        val state = await { it.keptEdited }
        state.point.shouldNotBeNull().measurement.shouldNotBeNull().poleHeightM shouldBe 2.0
        state.point.shouldNotBeNull().coordinates shouldBe edited.coordinates
        repository.historyEntries.value.single().change shouldBe PointChange.POLE

        vm.dismissNotice()
        await { !it.keptEdited }
    }

    @Test
    fun `S011 RF-21 duplicar crea un punto manual con el siguiente nombre libre y abre su ficha`() {
        await { it.point != null }

        vm.duplicate()

        val event = runBlocking { withTimeout(5_000) { vm.events.first() } }.shouldBeInstanceOf<PointDetailsEvent.Open>()
        val copy = repository.points.value.single { it.id == event.pointId }
        copy.name shouldBe "13"
        copy.origin shouldBe PointOrigin.MANUAL
        copy.measurement shouldBe null
        copy.copyOf shouldBe "12"
        copy.coordinates shouldBe edited.coordinates
    }

    @Test
    fun `S011 RF-22 borrar mueve el punto a la papelera y cierra la ficha`() {
        await { it.point != null }

        vm.delete()

        runBlocking { withTimeout(5_000) { vm.events.first() } } shouldBe PointDetailsEvent.Close
        repository.points.value.single().deletedAtMs shouldBe 5_000L
    }
}
