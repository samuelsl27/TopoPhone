package es.topophone.app.ui.cogo

import android.content.Context
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.core.model.cogo.LegKind
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
import es.topophone.core.survey.cogo.TraverseStop
import es.topophone.data.db.TopoPhoneDatabase
import es.topophone.data.geometry.GeometryPreferences
import es.topophone.data.point.RoomPointRepository
import es.topophone.data.project.RoomProjectRepository
import es.topophone.data.project.SaveResult
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
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

/** La poligonal en la app: tramos, cálculo en vivo, cierre y visual atrás (S038/RF-10 a RF-12, RF-31). */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "es-rES")
class TraverseViewModelTest {

    private val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), TopoPhoneDatabase::class.java)
        .allowMainThreadQueries()
        .build()
    private val projects = RoomProjectRepository(db)
    private val points = RoomPointRepository(db)
    private val viewModels = mutableListOf<TraverseViewModel>()

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

    private fun project(linear: LinearUnit = LinearUnit.METRE): String = runBlocking {
        val draft = ProjectDefaults().toDraft().copy(name = "Parcela", linearUnit = linear, angularUnit = AngularUnit.DECIMAL_DEGREES)
        (projects.create(draft) as SaveResult.Saved).id
    }

    private fun point(projectId: String, name: String, east: Double, north: Double, height: Double?) = Point(
        id = "id-$name",
        projectId = projectId,
        name = name,
        description = "",
        origin = PointOrigin.MANUAL,
        coordinates = PointCoordinates(GeodeticPosition(40.4144604937, -3.7071991234, height?.plus(49.5)), east, north, height),
        measurement = null,
        typed = TypedCoordinates(EntryForm.PROJECT_CRS, east, north, height, HeightKind.PROJECT),
        edited = false,
        hidden = false,
        copyOf = null,
        createdAtMs = 1_000,
        deletedAtMs = null,
    )

    private fun viewModel(projectId: String, startHeight: Double? = 650.0): TraverseViewModel {
        runBlocking {
            points.create(point(projectId, "P1", 440_000.0, 4_474_000.0, startHeight))
            points.create(point(projectId, "R", 440_000.0, 4_473_900.0, null)) // referencia al sur del inicio
        }
        return TraverseViewModel(points, projects, preferences).also {
            viewModels += it
            it.start(projectId)
            it.inputs.open(SLOT_START)
            it.inputs.chooseProject("id-P1")
        }
    }

    private fun TraverseViewModel.reference() {
        inputs.open(SLOT_REFERENCE)
        inputs.chooseProject("id-R")
    }

    private fun TraverseViewModel.await(predicate: (TraverseUi) -> Boolean): TraverseUi =
        runBlocking { withTimeout(10_000) { state.first(predicate) } }

    @Test
    fun `S038 RF-10 y RF-11 se anaden tramos y cada punto sale en vivo`() {
        val vm = viewModel(project())

        vm.addLeg()
        vm.setValue(0, "90")
        vm.setDistance(0, "100")
        vm.addLeg()
        vm.setValue(1, "0")
        vm.setDistance(1, "50")
        val state = vm.await { it.legs.size == 2 && it.legs.all { leg -> leg.step != null } }

        state.legs[0].step!!.easting shouldBe (440_100.0 plusOrMinus 1e-9)
        state.legs[0].step!!.northing shouldBe (4_474_000.0 plusOrMinus 1e-9)
        state.legs[1].step!!.northing shouldBe (4_474_050.0 plusOrMinus 1e-9)
        state.stoppedAt.shouldBeNull()
        state.totalGridLength shouldBe 150.0
    }

    @Test
    fun `S038 RF-10 un tramo se edita y se quita y los siguientes se recalculan`() {
        val vm = viewModel(project())
        vm.addLeg()
        vm.setValue(0, "90")
        vm.setDistance(0, "100")
        vm.addLeg()
        vm.setValue(1, "0")
        vm.setDistance(1, "50")
        vm.await { it.legs.size == 2 && it.legs[1].step != null }

        vm.setDistance(0, "200")
        vm.await { it.legs.getOrNull(0)?.step?.easting == 440_200.0 }.legs[1].step!!.easting shouldBe (440_200.0 plusOrMinus 1e-9)
        vm.removeLeg(0)

        val state = vm.await { it.legs.size == 1 && it.legs[0].step?.easting == 440_000.0 }
        state.legs[0].step!!.northing shouldBe (4_474_050.0 plusOrMinus 1e-9)
    }

    @Test
    fun `S038 RF-10 los tramos por angulo necesitan el punto de referencia y se dice`() {
        val vm = viewModel(project())

        vm.addLeg(LegKind.ANGLE_RIGHT)
        vm.setValue(0, "90")
        vm.setDistance(0, "100")
        val without = vm.await { it.legs.size == 1 && it.stopReason != null }

        without.needsReference shouldBe true
        without.stopReason shouldBe TraverseStop.NO_REFERENCE
        without.legs[0].step.shouldBeNull()

        vm.reference()
        val with = vm.await { it.legs.getOrNull(0)?.step != null }
        with.stopReason.shouldBeNull()
        // Mirando atras al sur, 90 grados a la derecha es el oeste.
        with.legs[0].step!!.easting shouldBe (439_900.0 plusOrMinus 1e-9)
        with.legs[0].step!!.northing shouldBe (4_474_000.0 plusOrMinus 1e-9)
    }

    @Test
    fun `S038 RF-10 la deflexion a la izquierda y el cambio de clase se calculan`() {
        val vm = viewModel(project())
        vm.reference()
        vm.addLeg(LegKind.DEFLECTION_LEFT)
        vm.setValue(0, "90")
        vm.setDistance(0, "10")

        vm.await { it.legs.getOrNull(0)?.step != null }.legs[0].step!!.easting shouldBe (439_990.0 plusOrMinus 1e-9)

        vm.setKind(0, LegKind.DEFLECTION_RIGHT)
        vm.await { it.legs.getOrNull(0)?.step?.easting == 440_010.0 }
    }

    @Test
    fun `S038 RF-31 con la referencia en el inicio el tramo por angulo no se calcula y se dice por que`() {
        val vm = viewModel(project())
        vm.inputs.setTyped(SLOT_REFERENCE, "440000", "4474000") // el mismo punto que el inicio
        vm.addLeg(LegKind.ANGLE_LEFT)
        vm.setValue(0, "45")
        vm.setDistance(0, "10")

        val state = vm.await { it.stopReason != null }

        state.stopReason shouldBe TraverseStop.BACKSIGHT_UNDEFINED
        state.stoppedAt shouldBe 1
    }

    @Test
    fun `S038 RF-11 la altura sale del inicio y del desnivel escrito en cada tramo`() {
        val vm = viewModel(project())
        vm.addLeg()
        vm.setValue(0, "0")
        vm.setDistance(0, "10")
        vm.setDHeight(0, "1,5")
        vm.addLeg()
        vm.setValue(1, "0")
        vm.setDistance(1, "10")

        val state = vm.await { it.legs.size == 2 && it.legs[1].step != null }

        state.legs[0].height shouldBe 651.5
        state.legs[1].height.shouldBeNull() // sin desnivel escrito no hay altura (no se toma por 0)
    }

    @Test
    fun `S038 RF-12 con punto de cierre sale el error en Este y Norte, el lineal y la precision`() {
        val vm = viewModel(project())
        vm.addLeg()
        vm.setValue(0, "90")
        vm.setDistance(0, "100")
        vm.inputs.setTyped(SLOT_CLOSURE, "440103", "4473996")

        val closure = vm.await { it.closure != null }.closure!!

        closure.dEasting shouldBe (-3.0 plusOrMinus 1e-9) // 440100 menos 440103
        closure.dNorthing shouldBe (4.0 plusOrMinus 1e-9)
        closure.linear shouldBe (5.0 plusOrMinus 1e-9)
        closure.ratio!! shouldBe (20.0 plusOrMinus 1e-9)
    }

    @Test
    fun `S038 RF-4 un campo que no es un numero se marca y el tramo no se calcula`() {
        val vm = viewModel(project())
        vm.addLeg()
        vm.setValue(0, "360") // un azimut de 360 no vale
        vm.setDistance(0, "-5") // una distancia negativa no vale
        vm.setDHeight(0, "x")

        val leg = vm.await { it.legs.size == 1 }.legs[0]

        leg.valueInvalid shouldBe true
        leg.distanceInvalid shouldBe true
        leg.dHeightInvalid shouldBe true
        leg.step.shouldBeNull()
    }

    @Test
    fun `S038 RF-10 un tramo a medio escribir no es un error y no se calcula`() {
        val vm = viewModel(project())
        vm.addLeg()
        vm.setValue(0, "45")

        val state = vm.await { it.legs.size == 1 && it.stopReason != null }

        state.legs[0].valueInvalid shouldBe false
        state.legs[0].distanceInvalid shouldBe false
        state.stopReason shouldBe TraverseStop.INCOMPLETE_LEG
    }

    @Test
    fun `S038 RF-5 en terreno la distancia escrita se pasa a cuadricula con el factor del inicio`() {
        val vm = viewModel(project())
        vm.addLeg()
        vm.setValue(0, "90")
        vm.setDistance(0, "100")
        val grid = vm.await { it.legs.getOrNull(0)?.step != null }.legs[0].step!!.gridDistance

        vm.setMode(DistanceMode.GROUND)
        val ground = vm.await { it.mode == DistanceMode.GROUND && it.legs.getOrNull(0)?.step?.gridDistance != grid }
            .legs[0].step!!.gridDistance

        grid shouldBe 100.0
        (ground < 100.0) shouldBe true
    }

    @Test
    fun `S038 RF-10 la poligonal no escribe nada en la base de datos`() {
        val id = project()
        val vm = viewModel(id)
        vm.addLeg()
        vm.setValue(0, "90")
        vm.setDistance(0, "100")
        vm.await { it.legs.getOrNull(0)?.step != null }

        runBlocking { points.points(id).first() }.size shouldBe 2
    }
}
