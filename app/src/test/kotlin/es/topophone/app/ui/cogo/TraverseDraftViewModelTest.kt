package es.topophone.app.ui.cogo

import android.content.Context
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.core.model.cogo.DraftPoint
import es.topophone.core.model.cogo.LegKind
import es.topophone.core.model.cogo.TraverseDraft
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
import es.topophone.data.cogo.RoomCogoRepository
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

/** El borrador de la poligonal: se guarda al escribir, se recupera recalculado, se descarta y avisa de lo que falta (S038/RF-26 a RF-28). */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "es-rES")
class TraverseDraftViewModelTest {

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

    private fun project(linear: LinearUnit = LinearUnit.METRE, angular: AngularUnit = AngularUnit.DECIMAL_DEGREES): String = runBlocking {
        val draft = ProjectDefaults().toDraft().copy(name = "Parcela", linearUnit = linear, angularUnit = angular)
        (projects.create(draft) as SaveResult.Saved).id
    }

    private fun point(projectId: String, name: String, east: Double, north: Double) = Point(
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

    private fun withPoints(projectId: String) = runBlocking {
        points.create(point(projectId, "P1", 440_000.0, 4_474_000.0))
        points.create(point(projectId, "R", 440_000.0, 4_473_900.0))
    }

    private fun open(projectId: String): TraverseViewModel = TraverseViewModel(points, projects, preferences, cogo).also {
        viewModels += it
        it.start(projectId)
    }

    private fun TraverseViewModel.await(predicate: (TraverseUi) -> Boolean): TraverseUi =
        runBlocking { withTimeout(10_000) { state.first(predicate) } }

    private fun TraverseViewModel.choose(slot: String, pointId: String) {
        inputs.open(slot)
        inputs.chooseProject(pointId)
    }

    private fun savedDraft(projectId: String, predicate: (TraverseDraft?) -> Boolean): TraverseDraft? =
        runBlocking { withTimeout(10_000) { cogo.draft(projectId).first(predicate) } }

    private fun TraverseViewModel.ready() = await { it.loaded }

    @Test
    fun `S038 RF-26 al escribir un tramo y un punto se guarda el borrador del proyecto`() {
        val id = project()
        withPoints(id)
        val vm = open(id)
        vm.ready()

        vm.choose(SLOT_START, "id-P1")
        vm.addLeg()
        vm.setValue(0, "90")
        vm.setDistance(0, "100")
        vm.setDHeight(0, "1,5")

        val draft = savedDraft(id) { it?.start != null && it.legs.singleOrNull()?.dHeightM == 1.5 }!!
        draft.start shouldBe DraftPoint.OfProject("id-P1", "P1")
        draft.legs.single().kind shouldBe LegKind.AZIMUTH
        draft.legs.single().angleRad!! shouldBe (Math.PI / 2 plusOrMinus 1e-12)
        draft.legs.single().distanceM shouldBe 100.0
    }

    @Test
    fun `S038 RF-26 sin escribir nada no se crea ningun borrador`() {
        val id = project()
        val vm = open(id)

        vm.ready()
        vm.await { !it.hasDraft }

        savedDraft(id) { true }.shouldBeNull()
    }

    @Test
    fun `S038 RF-27 al volver a abrir la poligonal se recupera el borrador y se recalcula`() {
        val id = project()
        withPoints(id)
        val first = open(id)
        first.ready()
        first.choose(SLOT_START, "id-P1")
        first.choose(SLOT_REFERENCE, "id-R")
        first.addLeg(LegKind.ANGLE_RIGHT)
        first.setValue(0, "90")
        first.setDistance(0, "100")
        first.addLeg()
        first.setValue(1, "0")
        first.setDistance(1, "50")
        first.await { it.legs.size == 2 && it.legs[1].step != null }
        savedDraft(id) { it?.legs?.size == 2 && it.legs[1].distanceM == 50.0 && it.start != null && it.reference != null }
        first.viewModelScope.cancel()

        val second = open(id)
        val state = second.await { it.legs.size == 2 && it.legs[1].step != null }

        state.legs.map { it.kind } shouldBe listOf(LegKind.ANGLE_RIGHT, LegKind.AZIMUTH)
        state.legs[0].valueText shouldBe "90"
        state.legs[0].distanceText shouldBe "100"
        state.legs[0].step!!.easting shouldBe (439_900.0 plusOrMinus 1e-9)
        state.legs[1].step!!.northing shouldBe (4_474_050.0 plusOrMinus 1e-9)
        (state.start as CogoSlotValue.Found).name shouldBe "P1"
        (state.reference as CogoSlotValue.Found).name shouldBe "R"
        state.hasDraft shouldBe true
    }

    @Test
    fun `S038 RF-27 un borrador que ya existe no se pisa al abrir la poligonal`() {
        val id = project()
        withPoints(id)
        val first = open(id)
        first.ready()
        first.choose(SLOT_START, "id-P1")
        first.addLeg()
        first.setValue(0, "45")
        first.setDistance(0, "10")
        savedDraft(id) { it?.start != null && it.legs.singleOrNull()?.distanceM == 10.0 }
        first.viewModelScope.cancel()

        val second = open(id)
        second.ready()
        second.await { it.legs.size == 1 }

        savedDraft(id) { true }!!.legs.single().distanceM shouldBe 10.0
    }

    @Test
    fun `S038 RF-27 lo escrito en pies y en DMS vuelve en las mismas unidades`() {
        val id = project(LinearUnit.INTERNATIONAL_FOOT, AngularUnit.DMS)
        withPoints(id)
        val first = open(id)
        first.ready()
        first.choose(SLOT_START, "id-P1")
        first.inputs.setTyped(SLOT_CLOSURE, "1000", "2000")
        first.addLeg()
        first.setValue(0, "45 30 15.5")
        first.setDistance(0, "100")
        first.await { it.legs.size == 1 && it.legs[0].step != null }
        savedDraft(id) { it?.start != null && it.legs.singleOrNull()?.distanceM != null && it.closure != null }
        first.viewModelScope.cancel()

        val state = open(id).await { it.legs.size == 1 && it.legs[0].step != null && it.closurePoint is CogoSlotValue.Found }

        state.legs[0].valueText shouldBe "45 30 15.5"
        state.legs[0].distanceText shouldBe "100"
        state.legs[0].step!!.gridDistance shouldBe (30.48 plusOrMinus 1e-9)
        (state.closurePoint as CogoSlotValue.Found).point.easting shouldBe (304.8 plusOrMinus 1e-9)
        (state.closureSelection as CogoSelection.Typed).easting shouldBe "1000"
    }

    @Test
    fun `S038 RF-26 un campo que no es un numero se guarda como vacio`() {
        val id = project()
        withPoints(id)
        val vm = open(id)
        vm.ready()
        vm.choose(SLOT_START, "id-P1")

        vm.addLeg()
        vm.setValue(0, "90")
        vm.setDistance(0, "abc")

        val draft = savedDraft(id) { it?.legs?.singleOrNull()?.angleRad != null }!!
        draft.legs.single().distanceM.shouldBeNull()
    }

    @Test
    fun `S038 RF-27 descartar el borrador vacia lo escrito y lo borra del proyecto`() {
        val id = project()
        withPoints(id)
        val vm = open(id)
        vm.ready()
        vm.choose(SLOT_START, "id-P1")
        vm.addLeg()
        vm.setValue(0, "90")
        vm.setDistance(0, "100")
        savedDraft(id) { it?.start != null && it.legs.singleOrNull()?.distanceM == 100.0 }

        vm.discardDraft()

        val state = vm.await { it.legs.isEmpty() && !it.hasDraft }
        state.start shouldBe CogoSlotValue.Empty
        savedDraft(id) { it == null }.shouldBeNull()
    }

    @Test
    fun `S038 RF-28 un punto del borrador que ya no esta activo se dice y no se calcula`() {
        val id = project()
        withPoints(id)
        val first = open(id)
        first.ready()
        first.choose(SLOT_START, "id-P1")
        first.addLeg()
        first.setValue(0, "90")
        first.setDistance(0, "100")
        savedDraft(id) { it?.start != null && it.legs.singleOrNull()?.distanceM == 100.0 }
        first.viewModelScope.cancel()
        runBlocking { points.moveToTrash(listOf("id-P1")) }

        val state = open(id).await { it.start is CogoSlotValue.Missing && it.legs.isNotEmpty() }

        (state.start as CogoSlotValue.Missing).name shouldBe "P1"
        state.legs[0].step.shouldBeNull()
        // El borrador sigue guardado con el punto: al restaurarlo del papelera vuelve a calcularse.
        savedDraft(id) { true }!!.start shouldBe DraftPoint.OfProject("id-P1", "P1")
    }
}
