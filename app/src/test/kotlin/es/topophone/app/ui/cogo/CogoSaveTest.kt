package es.topophone.app.ui.cogo

import android.content.Context
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.core.model.cogo.CogoEntry
import es.topophone.core.model.cogo.CogoOperation
import es.topophone.core.model.cogo.CogoRef
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
import es.topophone.data.cogo.RoomCogoRepository
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

/** Guardar los puntos calculados: nombres propuestos y editables, todo o nada, ámbito, salir sin guardar y borrador (S038/RF-19 a RF-23, RF-30). */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "es-rES")
class CogoSaveTest {

    private val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), TopoPhoneDatabase::class.java)
        .allowMainThreadQueries()
        .build()
    private val projects = RoomProjectRepository(db)
    private val points = RoomPointRepository(db)
    private val cogo = RoomCogoRepository(db)
    private val intersections = mutableListOf<IntersectionViewModel>()
    private val traverses = mutableListOf<TraverseViewModel>()

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
        val scopes = intersections.map { it.viewModelScope } + traverses.map { it.viewModelScope }
        scopes.forEach { it.cancel() }
        runBlocking { scopes.forEach { it.coroutineContext[Job]?.join() } }
        db.close()
        Dispatchers.resetMain()
    }

    private val projectId: String = runBlocking {
        val draft = ProjectDefaults().toDraft().copy(name = "Parcela", angularUnit = AngularUnit.DECIMAL_DEGREES)
        (projects.create(draft) as SaveResult.Saved).id
    }

    private fun point(name: String, east: Double, north: Double, height: Double?, created: Long) = Point(
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
        createdAtMs = created,
        deletedAtMs = null,
    )

    // P1, P2 y P3 de fixtures/cogo/puntos.csv (ETRS89 UTM 30).
    private val p1 = Triple(440_000.0, 4_474_000.0, 650.0)
    private val p2 = Triple(440_150.25, 4_474_040.125, 652.5)
    private val p3 = Triple(440_210.5, 4_474_190.75, 655.25)

    init {
        runBlocking {
            points.create(point("P1", p1.first, p1.second, p1.third, 1_000))
            points.create(point("P2", p2.first, p2.second, p2.third, 2_000))
            points.create(point("P3", p3.first, p3.second, p3.third, 3_000))
        }
    }

    private fun num(value: Double) = String.format(Locale.ROOT, "%.9f", value)

    private fun distance(a: Triple<Double, Double, Double>, b: Triple<Double, Double, Double>) = hypot(
        b.first - a.first,
        b.second - a.second,
    )

    private fun intersection(): IntersectionViewModel = IntersectionViewModel(points, projects, preferences, cogo).also {
        intersections += it
        it.start(projectId)
        it.inputs.open(SLOT_FIRST)
        it.inputs.chooseProject("id-P1")
        it.inputs.open(SLOT_SECOND)
        it.inputs.chooseProject("id-P2")
        it.setValue1(num(distance(p1, p3)))
        it.setValue2(num(distance(p2, p3)))
    }

    private fun IntersectionViewModel.await(predicate: (IntersectionUi) -> Boolean): IntersectionUi =
        runBlocking { withTimeout(10_000) { state.first(predicate) } }

    private fun CogoSaveController.await(predicate: (CogoSaveUi) -> Boolean): CogoSaveUi =
        runBlocking { withTimeout(10_000) { state.first(predicate) } }

    private fun saved(): List<Point> = runBlocking { points.points(projectId).first() }.filter { it.origin == PointOrigin.COGO }

    private fun traverse(): TraverseViewModel = TraverseViewModel(points, projects, preferences, cogo).also {
        traverses += it
        it.start(projectId)
        it.inputs.open(SLOT_START)
        it.inputs.chooseProject("id-P1")
    }

    private fun TraverseViewModel.await(predicate: (TraverseUi) -> Boolean): TraverseUi =
        runBlocking { withTimeout(10_000) { state.first(predicate) } }

    private fun TraverseViewModel.threeLegs() {
        repeat(3) { addLeg() }
        for (i in 0..2) {
            setValue(i, "${90 * i}")
            setDistance(i, "10")
        }
        await { it.legs.size == 3 && it.legs.all { leg -> leg.step != null } }
    }

    // --- Intersecciones -------------------------------------------------------------------------------------------------------------

    @Test
    fun `S038 RF-19 las soluciones marcadas se guardan con los nombres que siguen a los del proyecto`() {
        val vm = intersection()
        vm.await { it.solutions.size == 2 }
        vm.toggleMarked(IntersectionSide.LEFT)
        vm.toggleMarked(IntersectionSide.RIGHT)
        vm.await { it.markedCount == 2 && it.canSave }

        vm.openSave()
        val ui = vm.save.await { it.open }

        ui.rows.map { it.name } shouldBe listOf("P4", "P5")
        vm.save.save()
        vm.save.await { it.status is CogoSaveStatus.Saved }

        saved().map { it.name }.toSet() shouldBe setOf("P4", "P5")
    }

    @Test
    fun `S038 RF-22 el punto guardado lleva el origen COGO, la altura escrita y el rastro del calculo`() {
        val vm = intersection()
        vm.await { it.solutions.size == 2 }
        vm.setHeight(IntersectionSide.LEFT, "651,5")
        vm.toggleMarked(IntersectionSide.LEFT)
        vm.await { it.canSave && it.solutions[0].height == 651.5 }

        vm.openSave()
        vm.save.await { it.open }
        vm.save.save()
        vm.save.await { it.status is CogoSaveStatus.Saved }

        val point = saved().single()
        point.origin shouldBe PointOrigin.COGO
        point.coordinates.easting!! shouldBe (p3.first plusOrMinus 1e-6)
        point.coordinates.northing!! shouldBe (p3.second plusOrMinus 1e-6)
        point.coordinates.height!! shouldBe (651.5 plusOrMinus 1e-9)
        val ref = point.cogo!!
        ref.operation shouldBe CogoOperation.INTERSECTION_DISTANCE_DISTANCE
        ref.solution shouldBe IntersectionSide.LEFT
        ref.entries.map { it.name } shouldBe listOf("P1", "P2")
        ref.value("distance1")!! shouldBe (distance(p1, p3) plusOrMinus 1e-6)
        ref.value("ground") shouldBe 0.0
    }

    @Test
    fun `S038 RF-18 una solucion sin altura escrita se guarda sin altura`() {
        val vm = intersection()
        vm.await { it.solutions.size == 2 }
        vm.toggleMarked(IntersectionSide.RIGHT)
        vm.await { it.canSave }

        vm.openSave()
        vm.save.await { it.open }
        vm.save.save()
        vm.save.await { it.status is CogoSaveStatus.Saved }

        saved().single().hasHeight shouldBe false
    }

    @Test
    fun `S038 RF-20 un nombre que ya existe indica el problema y no guarda ninguno`() {
        val vm = intersection()
        vm.await { it.solutions.size == 2 }
        vm.toggleMarked(IntersectionSide.LEFT)
        vm.toggleMarked(IntersectionSide.RIGHT)
        // Las dos marcadas: con canSave basta la primera y la hoja se abría con una sola fila (en el runner pasó).
        vm.await { it.markedCount == 2 && it.canSave }
        vm.openSave()
        vm.save.await { it.open }

        vm.save.setName("LEFT", "P1")
        vm.save.save()
        val ui = vm.save.await { it.status == CogoSaveStatus.NameProblems }

        ui.rows.first { it.key == "LEFT" }.problem shouldBe NameProblem.EXISTS
        ui.rows.first { it.key == "RIGHT" }.problem.shouldBeNull()
        saved() shouldBe emptyList()
    }

    @Test
    fun `S038 RF-20 un nombre repetido o vacio entre los resultados no guarda ninguno`() {
        val vm = intersection()
        vm.await { it.solutions.size == 2 }
        vm.toggleMarked(IntersectionSide.LEFT)
        vm.toggleMarked(IntersectionSide.RIGHT)
        // Las dos marcadas: con canSave basta la primera y la hoja se abría con una sola fila (en el runner pasó).
        vm.await { it.markedCount == 2 && it.canSave }
        vm.openSave()
        vm.save.await { it.open }

        vm.save.setName("LEFT", "X")
        vm.save.setName("RIGHT", " X ")
        vm.save.save()
        vm.save.await { it.status == CogoSaveStatus.NameProblems }.rows.map { it.problem } shouldBe
            listOf(NameProblem.REPEATED, NameProblem.REPEATED)

        vm.save.setName("RIGHT", "  ")
        vm.save.save()
        vm.save.await { it.status == CogoSaveStatus.NameProblems && it.rows[1].problem == NameProblem.EMPTY }
        saved() shouldBe emptyList()
    }

    @Test
    fun `S038 RF-23 salir sin guardar no crea ningun punto ni cambia los de entrada`() {
        val vm = intersection()
        vm.await { it.solutions.size == 2 }
        vm.toggleMarked(IntersectionSide.LEFT)
        vm.await { it.canSave }

        vm.openSave()
        vm.save.await { it.open }
        vm.save.dismiss()

        vm.save.state.value.open shouldBe false
        saved() shouldBe emptyList()
        runBlocking { points.points(projectId).first() }.size shouldBe 3
        runBlocking { points.point("id-P1").first() }!!.coordinates.easting shouldBe p1.first
    }

    @Test
    fun `S038 RF-18 una altura mal escrita en una solucion marcada impide guardar`() {
        val vm = intersection()
        vm.await { it.solutions.size == 2 }
        vm.setHeight(IntersectionSide.LEFT, "x")
        vm.toggleMarked(IntersectionSide.LEFT)

        val state = vm.await { it.markedCount == 1 }

        state.canSave shouldBe false
        vm.openSave()
        vm.save.state.value.open shouldBe false
    }

    @Test
    fun `S038 RF-21 un resultado fuera del ambito del sistema no se guarda ni los demas`() {
        val controller = CogoSaveController(points, cogo, projects, kotlinx.coroutines.CoroutineScope(Dispatchers.Unconfined))
        val ref = CogoRef(CogoOperation.TRAVERSE, listOf(CogoEntry("start", "P1", 0.0, 0.0, null)))
        val inside = CogoResult("leg1", 440_010.0, 4_474_000.0, null, ref)
        val outside = CogoResult("leg2", 0.0, 0.0, null, ref)

        controller.open(projectId, listOf(inside, outside), CogoOperation.TRAVERSE, clearDraft = false)
        controller.await { it.open }
        controller.save()
        val ui = controller.await { it.status is CogoSaveStatus.OutOfScope }

        ui.status.shouldBeInstanceOf<CogoSaveStatus.OutOfScope>().keys shouldBe listOf("leg2")
        saved() shouldBe emptyList()
    }

    // --- Poligonal y borrador ------------------------------------------------------------------------------------------------------

    @Test
    fun `S038 RF-30 al guardar todos los puntos de la poligonal el borrador y la pantalla se vacian`() {
        val vm = traverse()
        vm.threeLegs()
        (1..3).forEach { vm.toggleMarked(it) }
        vm.await { it.markedCount == 3 }
        runBlocking { withTimeout(10_000) { cogo.draft(projectId).first { it?.legs?.size == 3 } } }

        vm.openSave()
        vm.save.await { it.open }
        vm.save.save()
        vm.save.await { it.status is CogoSaveStatus.Saved }

        saved().map { it.name } shouldBe listOf("P4", "P5", "P6")
        vm.await { it.legs.isEmpty() && it.start == CogoSlotValue.Empty }
        runBlocking { withTimeout(10_000) { cogo.draft(projectId).first { it == null } } }.shouldBeNull()
    }

    @Test
    fun `S038 RF-30 si se guarda solo una parte el borrador se conserva y se desmarca`() {
        val vm = traverse()
        vm.threeLegs()
        vm.toggleMarked(2)
        vm.await { it.markedCount == 1 }
        runBlocking { withTimeout(10_000) { cogo.draft(projectId).first { it?.legs?.size == 3 } } }

        vm.openSave()
        vm.save.await { it.open }
        vm.save.save()
        vm.save.await { it.status is CogoSaveStatus.Saved }

        saved().size shouldBe 1
        val state = vm.await { it.markedCount == 0 }
        state.legs.size shouldBe 3
        runBlocking { cogo.draft(projectId).first() }!!.legs.size shouldBe 3
    }

    @Test
    fun `S038 RF-22 el punto de la poligonal lleva los tramos que llevan a el y sus puntos de entrada`() {
        val vm = traverse()
        vm.threeLegs()
        vm.toggleMarked(2)
        vm.await { it.markedCount == 1 }

        vm.openSave()
        vm.save.await { it.open }
        vm.save.save()
        vm.save.await { it.status is CogoSaveStatus.Saved }

        val ref = saved().single().cogo!!
        ref.operation shouldBe CogoOperation.TRAVERSE
        ref.legs.size shouldBe 2 // los tramos 1 y 2, no el 3
        ref.entries.map { it.role } shouldBe listOf("start")
        ref.entries.single().name shouldBe "P1"
    }

    @Test
    fun `S038 RF-19 las marcas siguen a sus tramos cuando se quita uno`() {
        val vm = traverse()
        vm.threeLegs()
        vm.toggleMarked(3)
        vm.await { it.markedCount == 1 && it.legs[2].marked }

        vm.removeLeg(0)

        val state = vm.await { it.legs.size == 2 && it.legs[1].marked }
        state.legs[0].marked shouldBe false
        state.markedCount shouldBe 1
    }
}
