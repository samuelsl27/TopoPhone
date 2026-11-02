package es.topophone.app.ui.linear

import android.content.Context
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.app.ui.collector.SavedPoint
import es.topophone.app.ui.receiver.repositoryRoot
import es.topophone.core.model.alignment.AlignmentSource
import es.topophone.core.model.alignment.ElementKind
import es.topophone.core.model.point.GeodeticPosition
import es.topophone.core.model.point.Measurement
import es.topophone.core.model.point.MeasurementMode
import es.topophone.core.model.point.Point
import es.topophone.core.model.point.PointCoordinates
import es.topophone.core.model.point.PointQuality
import es.topophone.core.model.project.LinearUnit
import es.topophone.core.model.project.ProjectDefaults
import es.topophone.data.alignment.RoomAlignmentRepository
import es.topophone.data.db.TopoPhoneDatabase
import es.topophone.data.linear.RoomLinearSurveyRepository
import es.topophone.data.point.RoomPointRepository
import es.topophone.data.project.RoomProjectRepository
import es.topophone.data.project.SaveResult
import es.topophone.data.section.RoomSectionRepository
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
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

/** Tomar un eje: añadir puntos, quitar el último, terminar con rechazos y reanudar (S052/RF-1 a RF-9) contra `fixtures/ejes/toma/`. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class AxisSurveyViewModelTest {

    private val db = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext<Context>(),
        TopoPhoneDatabase::class.java,
    ).build()
    private val projects = RoomProjectRepository(db)
    private val points = RoomPointRepository(db)
    private val alignments = RoomAlignmentRepository(db)
    private val surveys = RoomLinearSurveyRepository(db, alignments, RoomSectionRepository(db))
    private val dispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    private val viewModels = mutableListOf<AxisSurveyViewModel>()

    @After
    fun tearDown() {
        // Las colecciones de la toma siguen vivas: se paran antes de cerrar la base para que no fallen en el test siguiente.
        viewModels.forEach { it.viewModelScope.cancel() }
        Dispatchers.resetMain()
        db.close()
    }

    private val fixture = File(repositoryRoot, "fixtures/ejes/toma")

    private fun table(name: String) = File(fixture, name).readLines().drop(1).map { it.split(",") }

    private lateinit var projectId: String

    private fun viewModel(unit: LinearUnit = LinearUnit.METRE): AxisSurveyViewModel {
        projectId = runBlocking {
            projects.create(ProjectDefaults().toDraft().copy(name = "Obra", linearUnit = unit)).shouldBeInstanceOf<SaveResult.Saved>().id
        }
        return again()
    }

    private fun again(): AxisSurveyViewModel {
        val vm = AxisSurveyViewModel(surveys, alignments, points, projects, dispatcher)
        viewModels += vm
        vm.start(projectId)
        runBlocking { withTimeout(10_000) { vm.state.first { !it.loading } } }
        return vm
    }

    private fun point(name: String, easting: Double, northing: Double, height: Double?) = Point.measured(
        id = "id-$name",
        projectId = projectId,
        name = name,
        description = "",
        measurement = Measurement(
            antenna = GeodeticPosition(40.416_801_234_567_89, -3.703_712_345_678_9, 701.357_123_456),
            poleHeightM = 1.8,
            quality = PointQuality("FIX", 24, 0.6, 1.0, 0.009, 0.014, MeasurementMode.INSTANT, 1, 0, fromReplay = false),
            rawNmea = "",
            outOfTolerance = emptySet(),
            measuredFromMs = 1,
            measuredToMs = 2,
            coordinates = PointCoordinates(GeodeticPosition(40.4168, -3.7037, 699.4), easting, northing, height),
        ),
        createdAtMs = 3,
    )

    /** Guarda un punto como lo hace el recolector y avisa a la toma. */
    private fun measure(vm: AxisSurveyViewModel, name: String, easting: Double, northing: Double, height: Double? = 100.0) {
        val p = point(name, easting, northing, height)
        runBlocking {
            points.create(p)
            vm.pointListener.onSaved(SavedPoint(p.id, p.name, p.coordinates))
        }
        runBlocking { withTimeout(10_000) { vm.state.first { s -> s.rows.any { it.pointId == p.id } } } }
    }

    /** Los cinco puntos de `puntos-eje.csv` medidos en orden. */
    private fun measureAll(vm: AxisSurveyViewModel) {
        vm.begin()
        runBlocking { withTimeout(10_000) { vm.state.first { it.survey != null } } }
        for (row in table("puntos-eje.csv")) measure(vm, row[0], row[1].toDouble(), row[2].toDouble(), row[3].toDouble())
    }

    private fun AxisSurveyViewModel.openFinish(base: String = "Eje tomado") {
        startFinish(base)
        runBlocking { withTimeout(10_000) { state.first { it.finish != null } } }
    }

    private fun AxisSurveyViewModel.finishAndWait() {
        finish()
        runBlocking { withTimeout(10_000) { state.first { !it.saving && (it.result != null || it.problem != null) } } }
    }

    @Test
    fun `S052 RF-1 sin toma abierta no hay toma y begin abre una`() {
        val vm = viewModel()

        vm.state.value.survey.shouldBeNull()
        vm.begin()
        runBlocking { withTimeout(10_000) { vm.state.first { it.survey != null } } }

        vm.state.value.survey.shouldNotBeNull().isOpen shouldBe true
    }

    @Test
    fun `S052 RF-2 y RF-3 cada punto medido entra en la toma en orden y la longitud es la de la poligonal`() {
        val vm = viewModel()

        measureAll(vm)

        vm.state.value.rows.map { it.name } shouldBe listOf("T1", "T2", "T3", "T4", "T5")
        val expected = table("esperado-longitud.csv").last()[1].toDouble()
        vm.state.value.lengthM shouldBe (expected plusOrMinus 1e-9)
        runBlocking { points.points(projectId).first() }.size shouldBe 5
    }

    @Test
    fun `S052 RF-2 un punto medido sin toma abierta no entra en ninguna`() {
        val vm = viewModel()

        runBlocking { vm.pointListener.onSaved(SavedPoint("x", "X", point("X", 1.0, 1.0, null).coordinates)) }

        vm.state.value.rows shouldBe emptyList()
        runBlocking { surveys.surveys(projectId).first() } shouldBe emptyList()
    }

    @Test
    fun `S052 RF-4 quitar el ultimo lo excluye de la toma sin borrar el punto y repetir quita el anterior`() {
        val vm = viewModel()
        measureAll(vm)

        vm.removeLast()
        runBlocking { withTimeout(10_000) { vm.state.first { it.included.size == 4 } } }

        vm.state.value.included.map { it.name } shouldBe listOf("T1", "T2", "T3", "T4")
        vm.state.value.rows.size shouldBe 5
        runBlocking { points.points(projectId).first() }.size shouldBe 5
        vm.removeLast()
        runBlocking { withTimeout(10_000) { vm.state.first { it.included.size == 3 } } }
        vm.state.value.included.map { it.name } shouldBe listOf("T1", "T2", "T3")
        vm.state.value.lengthM shouldBe (table("esperado-longitud.csv")[1][1].toDouble() plusOrMinus 1e-9)
    }

    @Test
    fun `S052 RF-5 terminar con radios crea el eje tomado como el de tools y lo deja activo`() {
        val vm = viewModel()
        measureAll(vm)
        vm.openFinish()
        vm.updateFinish { it.copy(startChainage = "250", radii = mapOf(2 to "150", 3 to "200", 4 to "100")) }

        vm.finishAndWait()

        val result = vm.state.value.result.shouldNotBeNull()
        result.name shouldBe "Eje tomado"
        result.activated shouldBe true
        result.hasGrade shouldBe false
        val axis = runBlocking { alignments.alignments(projectId).first() }.single()
        axis.source shouldBe AlignmentSource.FIELD
        val expected = table("esperado-eje.csv").filter { it[0] == "radios" }
        axis.elements.size shouldBe expected.size
        axis.elements.map { it.kind == ElementKind.ARC } shouldBe expected.map { it[2] == "arc" }
        axis.elements.first().startChainage shouldBe 250.0
        axis.lengthM shouldBe (expected.sumOf { it[4].toDouble() } plusOrMinus 1e-6)
        vm.state.value.survey.shouldBeNull() // la toma está terminada
    }

    @Test
    fun `S052 RF-7 con la rasante pedida los vertices llevan la cota de los puntos y sin pedirla no hay rasante`() {
        val vm = viewModel()
        measureAll(vm)
        vm.state.value.canGrade shouldBe true
        vm.openFinish()
        vm.updateFinish { it.copy(startChainage = "250", radii = mapOf(2 to "150", 3 to "200", 4 to "100"), withGrade = true) }

        vm.finishAndWait()

        vm.state.value.result.shouldNotBeNull().hasGrade shouldBe true
        val profile = runBlocking { alignments.alignments(projectId).first() }.single().profile.shouldNotBeNull()
        val expected = table("esperado-rasante.csv")
        profile.vertices.map { it.elevation } shouldBe expected.map { it[2].toDouble() }
        profile.vertices.zip(expected).forEach { (v, row) -> v.chainage shouldBe (row[1].toDouble() plusOrMinus 1e-6) }
    }

    @Test
    fun `S052 RF-7 si un punto no tiene cota no se puede pedir la rasante`() {
        val vm = viewModel()
        vm.begin()
        runBlocking { withTimeout(10_000) { vm.state.first { it.survey != null } } }
        measure(vm, "A", 440000.0, 4474000.0, 100.0)
        measure(vm, "B", 440100.0, 4474000.0, null)

        vm.state.value.canGrade shouldBe false
    }

    @Test
    fun `S052 RF-6 con menos de dos puntos no se abre el formulario ni se crea el eje`() {
        val vm = viewModel()
        vm.begin()
        runBlocking { withTimeout(10_000) { vm.state.first { it.survey != null } } }
        measure(vm, "A", 440000.0, 4474000.0)

        vm.startFinish("Eje tomado")

        vm.state.value.finish.shouldBeNull()
        vm.state.value.problem shouldBe AxisSurveyProblem.TooFewPoints
    }

    @Test
    fun `S052 RF-6 dos puntos a menos de 1 cm no crean el eje y dicen el vertice`() {
        val vm = viewModel()
        vm.begin()
        runBlocking { withTimeout(10_000) { vm.state.first { it.survey != null } } }
        measure(vm, "A", 440000.0, 4474000.0)
        measure(vm, "B", 440000.005, 4474000.0)
        vm.openFinish()

        vm.finishAndWait()

        vm.state.value.problem shouldBe AxisSurveyProblem.CoincidentPoints(2)
        runBlocking { alignments.alignments(projectId).first() } shouldBe emptyList()
        vm.state.value.survey.shouldNotBeNull().isOpen shouldBe true
    }

    @Test
    fun `S052 RF-6 un radio que no cabe entre los vertices no crea el eje y dice el vertice`() {
        val vm = viewModel()
        vm.begin()
        runBlocking { withTimeout(10_000) { vm.state.first { it.survey != null } } }
        measure(vm, "A", 440000.0, 4474000.0)
        measure(vm, "B", 440100.0, 4474000.0)
        measure(vm, "C", 440100.0, 4474100.0)
        vm.openFinish()
        vm.updateFinish { it.copy(radii = mapOf(2 to "150")) }

        vm.finishAndWait()

        vm.state.value.problem shouldBe AxisSurveyProblem.RadiusDoesNotFit(2)
        runBlocking { alignments.alignments(projectId).first() } shouldBe emptyList()
    }

    @Test
    fun `S052 RF-6 un radio o un PK inicial que no son numeros se senalan y no crean el eje`() {
        val vm = viewModel()
        measureAll(vm)
        vm.openFinish()

        vm.updateFinish { it.copy(radii = mapOf(3 to "mucho")) }
        vm.finishAndWait()
        vm.state.value.problem shouldBe AxisSurveyProblem.InvalidNumber(3)

        vm.updateFinish { it.copy(radii = emptyMap(), startChainage = "abc") }
        vm.finishAndWait()
        vm.state.value.problem shouldBe AxisSurveyProblem.InvalidNumber(null)
        runBlocking { alignments.alignments(projectId).first() } shouldBe emptyList()
    }

    @Test
    fun `S052 RF-5 el nombre no puede estar vacio ni repetir el de otro eje y se propone uno libre`() {
        val vm = viewModel()
        measureAll(vm)
        vm.openFinish()
        vm.state.value.finish.shouldNotBeNull().name shouldBe "Eje tomado"
        vm.finishAndWait()
        vm.state.value.result.shouldNotBeNull()
        vm.dismissResult()
        runBlocking { withTimeout(10_000) { vm.state.first { it.survey == null } } }
        vm.begin()
        runBlocking { withTimeout(10_000) { vm.state.first { it.survey != null } } }
        measure(vm, "U1", 440000.0, 4474000.0)
        measure(vm, "U2", 440100.0, 4474000.0)

        vm.openFinish()
        vm.state.value.finish.shouldNotBeNull().name shouldBe "Eje tomado 2"
        vm.updateFinish { it.copy(name = "eje TOMADO") }
        vm.finishAndWait()
        vm.state.value.problem shouldBe AxisSurveyProblem.NameTaken
        vm.updateFinish { it.copy(name = "  ") }
        vm.finishAndWait()
        vm.state.value.problem shouldBe AxisSurveyProblem.EmptyName
    }

    @Test
    fun `S052 RF-8 descartar la toma no borra los puntos`() {
        val vm = viewModel()
        measureAll(vm)

        vm.discard()
        runBlocking { withTimeout(10_000) { vm.state.first { it.survey == null } } }

        runBlocking { points.points(projectId).first() }.size shouldBe 5
        runBlocking { surveys.surveys(projectId).first() } shouldBe emptyList()
    }

    @Test
    fun `S052 RF-9 al volver a abrir la app la toma y sus puntos siguen y se puede terminar`() {
        val first = viewModel()
        measureAll(first)

        val reopened = again()

        reopened.state.value.survey.shouldNotBeNull().isOpen shouldBe true
        reopened.state.value.rows.map { it.name } shouldBe listOf("T1", "T2", "T3", "T4", "T5")
        reopened.openFinish()
        reopened.finishAndWait()
        reopened.state.value.result.shouldNotBeNull()
    }

    @Test
    fun `S052 RF-5 los radios y el PK inicial se escriben en la unidad del proyecto`() {
        val vm = viewModel(LinearUnit.INTERNATIONAL_FOOT)
        vm.begin()
        runBlocking { withTimeout(10_000) { vm.state.first { it.survey != null } } }
        measure(vm, "A", 440000.0, 4474000.0)
        measure(vm, "B", 440100.0, 4474000.0)
        vm.openFinish()
        vm.updateFinish { it.copy(startChainage = "1000") }

        vm.finishAndWait()

        runBlocking { alignments.alignments(projectId).first() }.single().startChainage shouldBe (304.8 plusOrMinus 1e-9)
    }
}
