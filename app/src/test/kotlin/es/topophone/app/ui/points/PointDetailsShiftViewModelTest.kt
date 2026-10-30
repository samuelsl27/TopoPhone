package es.topophone.app.ui.points

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.app.ui.map.FakePointRepository
import es.topophone.core.model.point.AppliedShift
import es.topophone.core.model.point.BaseShiftVector
import es.topophone.core.model.point.GeodeticPosition
import es.topophone.core.model.project.ProjectDefaults
import es.topophone.core.survey.BaseShiftApplier
import es.topophone.core.survey.ProjectPositionCalculator
import es.topophone.data.db.TopoPhoneDatabase
import es.topophone.data.project.RoomProjectRepository
import es.topophone.data.project.SaveResult
import io.kotest.matchers.doubles.plusOrMinus
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

/** Corregir el jalón de un punto con base shift desde su ficha usa el vector que ese punto guardó (S020/RF-10). */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class PointDetailsShiftViewModelTest {

    private val db = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext<Context>(),
        TopoPhoneDatabase::class.java,
    ).build()
    private val projects = RoomProjectRepository(db)
    private val projectId = runBlocking {
        projects.create(ProjectDefaults().toDraft().copy(name = "Calle Mayor 12")).shouldBeInstanceOf<SaveResult.Saved>().id
    }
    private val vector = BaseShiftVector(2.1, -1.7, 0.4)
    private val unshifted = GeodeticPosition(40.41668, -3.70371, 701.357 - 1.934)
    private val shifted = BaseShiftApplier.apply(unshifted, vector)
    private val point = PointFixtures.measured("12", projectId = projectId).let { base ->
        base.copy(
            measurement = base.measurement!!.copy(
                coordinates = base.measurement!!.coordinates.copy(ground = shifted),
                shift = AppliedShift("s1", "V-12", vector, unshifted),
            ),
        )
    }
    private val repository = FakePointRepository(listOf(point), clock = { 5_000 })
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
    fun `S020 RF-10 al corregir el jalon el suelo se recalcula con el vector del punto`() {
        await { it.point != null && it.project != null }

        vm.correctPole(2.0)

        val measurement = await { it.point?.measurement?.poleHeightM == 2.0 }.point.shouldNotBeNull().measurement.shouldNotBeNull()
        val rawGround = GeodeticPosition(40.41668, -3.70371, 701.357 - 2.134)
        val expected = BaseShiftApplier.apply(rawGround, vector)
        measurement.coordinates.ground.latitudeDeg shouldBe (expected.latitudeDeg plusOrMinus 1e-12)
        measurement.coordinates.ground.ellipsoidalHeightM.shouldNotBeNull() shouldBe (expected.ellipsoidalHeightM!! plusOrMinus 1e-6)
        measurement.shift.shouldNotBeNull().vector shouldBe vector
        measurement.shift.shouldNotBeNull().unshiftedGround.ellipsoidalHeightM.shouldNotBeNull() shouldBe
            (rawGround.ellipsoidalHeightM!! plusOrMinus 1e-9)
    }
}
