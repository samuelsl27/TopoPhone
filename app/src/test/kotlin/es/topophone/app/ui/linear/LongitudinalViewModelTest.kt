package es.topophone.app.ui.linear

import android.content.Context
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.app.ui.alignment.AlignmentTestFiles
import es.topophone.app.ui.collector.SavedPoint
import es.topophone.app.ui.receiver.repositoryRoot
import es.topophone.core.model.cad.CadSourceCrs
import es.topophone.core.model.point.GeodeticPosition
import es.topophone.core.model.point.Measurement
import es.topophone.core.model.point.MeasurementMode
import es.topophone.core.model.point.Point
import es.topophone.core.model.point.PointCoordinates
import es.topophone.core.model.point.PointQuality
import es.topophone.core.model.project.LinearUnit
import es.topophone.core.model.project.ProjectCrs
import es.topophone.core.model.project.ProjectDefaults
import es.topophone.core.model.survey.linear.LinearSurveyKind
import es.topophone.core.model.survey.linear.SurveyFlag
import es.topophone.data.alignment.AlignmentImporter
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
import java.io.ByteArrayInputStream
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

/** El perfil longitudinal: guiado, libre y desde una selección, con PK, desplazamiento y marcas contra `fixtures/ejes/toma/` (S052/RF-10 a RF-15, RF-21). */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class LongitudinalViewModelTest {

    private val db = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext<Context>(),
        TopoPhoneDatabase::class.java,
    ).build()
    private val projects = RoomProjectRepository(db)
    private val points = RoomPointRepository(db)
    private val alignments = RoomAlignmentRepository(db)
    private val surveys = RoomLinearSurveyRepository(db, alignments, RoomSectionRepository(db))
    private val dispatcher = UnconfinedTestDispatcher()
    private val viewModels = mutableListOf<LongitudinalViewModel>()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() {
        viewModels.forEach { it.viewModelScope.cancel() }
        Dispatchers.resetMain()
        db.close()
    }

    private val fixture = File(repositoryRoot, "fixtures/ejes/toma")

    private fun table(name: String) = File(fixture, name).readLines().drop(1).map { it.split(",") }

    private lateinit var projectId: String

    /** Un proyecto con el eje [index] de `ejes.xml` (0: Eje A con rasante; 2: Eje C sin ella) y el modelo abierto sobre él. */
    private fun open(index: Int = 0, unit: LinearUnit = LinearUnit.METRE): LongitudinalViewModel {
        projectId = runBlocking {
            projects.create(ProjectDefaults().toDraft().copy(name = "Obra", linearUnit = unit)).shouldBeInstanceOf<SaveResult.Saved>().id
        }
        val axis = runBlocking {
            AlignmentImporter(alignments).importLandXml(
                projectId,
                ProjectCrs.ETRS89_UTM30,
                "ejes.xml",
                setOf(index),
                CadSourceCrs.Project,
                LinearUnit.METRE,
                { ByteArrayInputStream(AlignmentTestFiles.bytes("ejes.xml")) },
            )
            alignments.summaries(projectId).first().single().id
        }
        val vm = LongitudinalViewModel(surveys, alignments, points, projects, dispatcher)
        viewModels += vm
        vm.start(projectId, axis)
        runBlocking { withTimeout(10_000) { vm.state.first { !it.loading && it.alignment != null } } }
        return vm
    }

    private fun LongitudinalViewModel.configure(
        guided: Boolean = false,
        tolerance: String = "",
        from: String = "0",
        to: String = "420",
        interval: String = "20",
    ) = updateSetup { LongitudinalSetup(guided, from, to, interval, tolerance) }

    private fun LongitudinalViewModel.beginAndWait() {
        begin()
        runBlocking { withTimeout(10_000) { state.first { it.survey != null } } }
    }

    private val ground = table("longitudinal-puntos.csv").associate {
        it[0] to Triple(it[1].toDouble(), it[2].toDouble(), it[3].toDouble())
    }

    private fun point(name: String): Point {
        val (e, n, z) = ground.getValue(name)
        return Point.measured(
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
                coordinates = PointCoordinates(GeodeticPosition(40.4168, -3.7037, 699.4), e, n, z),
            ),
            createdAtMs = 3,
        )
    }

    /** Guarda el punto [name] como el recolector y avisa al perfil. */
    private fun measure(vm: LongitudinalViewModel, name: String) {
        val p = point(name)
        runBlocking {
            points.create(p)
            vm.pointListener.onSaved(SavedPoint(p.id, p.name, p.coordinates))
        }
        runBlocking { withTimeout(10_000) { vm.state.first { s -> s.rows.any { it.pointId == p.id } } } }
    }

    private fun measureAll(vm: LongitudinalViewModel) = ground.keys.forEach { measure(vm, it) }

    @Test
    fun `S052 RF-10 el perfil guiado guarda el PK inicial el final y el intervalo en metros`() {
        val vm = open(unit = LinearUnit.INTERNATIONAL_FOOT)
        vm.configure(guided = true, from = "0", to = "1000", interval = "100", tolerance = "2")

        vm.beginAndWait()

        val survey = vm.state.value.survey.shouldNotBeNull()
        survey.kind shouldBe LinearSurveyKind.LONGITUDINAL
        survey.guided shouldBe true
        survey.chainageFrom shouldBe 0.0
        survey.chainageTo shouldBe (304.8 plusOrMinus 1e-9)
        survey.intervalM shouldBe (30.48 plusOrMinus 1e-9)
        survey.toleranceM shouldBe (0.6096 plusOrMinus 1e-9)
    }

    @Test
    fun `S052 RF-10 el perfil libre no pide PK ni intervalo y usa 0,50 m de tolerancia de eje`() {
        val vm = open()
        vm.configure(guided = false)

        vm.beginAndWait()

        val survey = vm.state.value.survey.shouldNotBeNull()
        survey.guided shouldBe false
        survey.chainageFrom.shouldBeNull()
        survey.toleranceM shouldBe 0.5
    }

    @Test
    fun `S052 RF-10 un PK fuera del eje un intervalo que no es positivo o un numero mal escrito no abren el perfil`() {
        val vm = open()

        vm.configure(guided = true, to = "500")
        vm.begin()
        vm.state.value.problem shouldBe LongitudinalProblem.OutsideAxis(LongitudinalField.TO)
        vm.configure(guided = true, from = "-1")
        vm.begin()
        vm.state.value.problem shouldBe LongitudinalProblem.OutsideAxis(LongitudinalField.FROM)
        vm.configure(guided = true, interval = "0")
        vm.begin()
        vm.state.value.problem shouldBe LongitudinalProblem.NotPositive(LongitudinalField.INTERVAL)
        vm.configure(guided = true, interval = "mucho")
        vm.begin()
        vm.state.value.problem shouldBe LongitudinalProblem.InvalidNumber(LongitudinalField.INTERVAL)
        vm.configure(guided = false, tolerance = "-3")
        vm.begin()
        vm.state.value.problem shouldBe LongitudinalProblem.NotPositive(LongitudinalField.TOLERANCE)
        vm.configure(guided = false, tolerance = "poca")
        vm.begin()
        vm.state.value.problem shouldBe LongitudinalProblem.InvalidNumber(LongitudinalField.TOLERANCE)

        vm.state.value.survey.shouldBeNull()
        runBlocking { surveys.surveys(projectId).first() } shouldBe emptyList()
    }

    @Test
    fun `S052 RF-11 cada punto medido entra con el PK el desplazamiento y la diferencia de cota de tools a 1 mm`() {
        val vm = open()
        vm.configure()
        vm.beginAndWait()

        measureAll(vm)

        val expected = table("esperado-longitudinal.csv")
        val rows = vm.state.value.rows.associateBy { it.name }
        rows.size shouldBe expected.size
        for (row in expected) {
            val r = rows.getValue(row[0])
            r.flag shouldBe when (row[5]) {
                "ninguna" -> SurveyFlag.NONE
                "fuera_del_eje" -> SurveyFlag.OUT_OF_AXIS
                else -> SurveyFlag.NO_PROJECTION
            }
            if (row[1].isEmpty()) {
                r.chainageM.shouldBeNull()
            } else {
                r.chainageM.shouldNotBeNull() shouldBe (row[1].toDouble() plusOrMinus 1e-3)
                r.offsetM.shouldNotBeNull() shouldBe (row[2].toDouble() plusOrMinus 1e-3)
                r.dHeightM.shouldNotBeNull() shouldBe (row[4].toDouble() plusOrMinus 1e-3)
                r.referenceM.shouldNotBeNull() shouldBe (row[3].toDouble() plusOrMinus 1e-3)
            }
        }
    }

    @Test
    fun `S052 RF-12 el que pasa la tolerancia o no se proyecta se guarda pero queda fuera del perfil y del grafico`() {
        val vm = open()
        vm.configure()
        vm.beginAndWait()

        measureAll(vm)

        val state = vm.state.value
        state.rejected.map { it.name to it.flag }.toSet() shouldBe setOf(
            "L8" to SurveyFlag.OUT_OF_AXIS,
            "L11" to SurveyFlag.NO_PROJECTION,
            "L12" to SurveyFlag.NO_PROJECTION,
        )
        state.inProfile.size shouldBe 9
        runBlocking { points.points(projectId).first() }.size shouldBe 12
        state.chart.profilePoints.size shouldBe 9
        state.chart.points.size shouldBe 10 // el fuera del eje se dibuja hueco; el que no se proyecta no tiene x
        state.chart.points.first { it.label == "L8" }.inProfile shouldBe false
    }

    @Test
    fun `S052 RF-12 la tolerancia elegida al empezar manda sobre la de omision`() {
        val vm = open()
        vm.configure(tolerance = "0,4")
        vm.beginAndWait()

        measure(vm, "L6") // a 0,45 m del eje

        vm.state.value.rows.single().flag shouldBe SurveyFlag.OUT_OF_AXIS
    }

    @Test
    fun `S052 RF-13 el grafico lleva la rasante muestreada entre el primer y el ultimo punto del perfil`() {
        val vm = open()
        vm.configure()
        vm.beginAndWait()

        measureAll(vm)

        val chart = vm.state.value.chart
        chart.axis shouldBe ProfileAxis.CHAINAGE
        chart.reference.first().first shouldBe (0.0 plusOrMinus 1e-9)
        chart.reference.last().first shouldBe (420.0 plusOrMinus 1e-9)
        chart.reference.first().second shouldBe (100.0 plusOrMinus 1e-9)
        chart.reference.last().second shouldBe (102.0 plusOrMinus 1e-9)
        chart.points.map { it.x } shouldBe chart.points.map { it.x }.sorted()
    }

    @Test
    fun `S052 RF-13 un eje sin rasante solo dibuja el terreno y no da diferencia de cota`() {
        val vm = open(index = 2)
        vm.configure()
        vm.beginAndWait()
        val p = Point.measured(
            "x1",
            projectId,
            "X",
            "",
            point(
                "L1",
            ).measurement!!.copy(coordinates = PointCoordinates(GeodeticPosition(40.4168, -3.7037, 699.4), 441_050.0, 4_474_000.0, 100.0)),
            1,
        )
        runBlocking {
            points.create(p)
            vm.pointListener.onSaved(SavedPoint(p.id, p.name, p.coordinates))
            points.create(p.copy(id = "x2", name = "Y"))
            vm.pointListener.onSaved(SavedPoint("x2", "Y", p.coordinates.copy(easting = 441_100.0)))
        }
        runBlocking { withTimeout(10_000) { vm.state.first { it.rows.size == 2 } } }

        vm.state.value.hasGrade shouldBe false
        vm.state.value.chart.reference shouldBe emptyList()
        vm.state.value.rows.all { it.dHeightM == null && it.referenceM == null } shouldBe true
        vm.state.value.chart.profilePoints.size shouldBe 2
    }

    @Test
    fun `S052 RF-14 una seleccion de puntos del proyecto se proyecta sobre el eje y abre un perfil libre`() {
        val vm = open()
        vm.configure()
        runBlocking { listOf("L2", "L4", "L9").forEach { points.create(point(it)) } }

        vm.addFromSelection(listOf("id-L9", "id-L2", "id-L4"))
        runBlocking { withTimeout(10_000) { vm.state.first { it.rows.size == 3 } } }

        val state = vm.state.value
        state.survey.shouldNotBeNull().guided shouldBe false
        state.rows.map { it.name } shouldBe listOf("L9", "L2", "L4") // el orden en que se eligieron
        state.chart.points.map { it.label } shouldBe listOf("L2", "L4", "L9") // el grafico va por PK
        state.rows.first { it.name == "L4" }.chainageM.shouldNotBeNull() shouldBe (130.0 plusOrMinus 1e-3)
    }

    @Test
    fun `S052 RF-14 elegir puntos que no existen no abre ningun perfil`() {
        val vm = open()

        vm.addFromSelection(listOf("fantasma"))
        runBlocking { withTimeout(10_000) { vm.state.first { it.problem != null } } }

        vm.state.value.problem shouldBe LongitudinalProblem.NoPoints
        runBlocking { surveys.surveys(projectId).first() } shouldBe emptyList()
    }

    @Test
    fun `S052 RF-14 una seleccion sobre un perfil abierto anade y no repite los que ya estaban`() {
        val vm = open()
        vm.configure()
        vm.beginAndWait()
        measure(vm, "L2")
        runBlocking { listOf("L4").forEach { points.create(point(it)) } }

        vm.addFromSelection(listOf("id-L2", "id-L4"))
        runBlocking { withTimeout(10_000) { vm.state.first { it.rows.size == 2 } } }

        vm.state.value.rows.map { it.name } shouldBe listOf("L2", "L4")
    }

    @Test
    fun `S052 RF-21 quitar un punto del perfil lo saca del grafico sin borrarlo del proyecto`() {
        val vm = open()
        vm.configure()
        vm.beginAndWait()
        measure(vm, "L2")
        measure(vm, "L4")

        vm.removePoint("id-L2")
        runBlocking { withTimeout(10_000) { vm.state.first { it.inProfile.size == 1 } } }

        vm.state.value.inProfile.single().name shouldBe "L4"
        vm.state.value.chart.points.map { it.label } shouldBe listOf("L4")
        runBlocking { points.points(projectId).first() }.size shouldBe 2
    }

    @Test
    fun `S052 RF-15 las filas del CSV son los puntos del perfil por PK con terreno rasante y diferencia`() {
        val vm = open()
        vm.configure()
        vm.beginAndWait()
        measureAll(vm)

        val rows = vm.csvRows()

        rows.size shouldBe 9
        rows.map { it.chainageM } shouldBe rows.map { it.chainageM }.sorted()
        val expected = File(fixture, "esperado-perfil-longitudinal.csv").readLines().drop(1).map { it.split(",") }
        for ((r, e) in rows.zip(expected)) {
            r.chainageM shouldBe (e[0].toDouble() plusOrMinus 1e-3)
            r.offsetM shouldBe (e[1].toDouble() plusOrMinus 1e-3)
            r.groundM.shouldNotBeNull() shouldBe (e[2].toDouble() plusOrMinus 1e-3)
            r.referenceM.shouldNotBeNull() shouldBe (e[3].toDouble() plusOrMinus 1e-3)
            r.dHeightM.shouldNotBeNull() shouldBe (e[4].toDouble() plusOrMinus 1e-3)
        }
    }

    @Test
    fun `S052 RF-22 terminar cierra el perfil y descartar lo borra sin tocar los puntos`() {
        val vm = open()
        vm.configure()
        vm.beginAndWait()
        measure(vm, "L2")

        vm.finish()
        runBlocking { withTimeout(10_000) { vm.state.first { it.survey == null } } }

        runBlocking { surveys.surveys(projectId).first() }.single().isOpen shouldBe false
        vm.beginAndWait()
        vm.discard()
        runBlocking { withTimeout(10_000) { vm.state.first { it.survey == null } } }
        runBlocking { surveys.surveys(projectId).first() }.size shouldBe 1
        runBlocking { points.points(projectId).first() }.size shouldBe 1
    }

    @Test
    fun `S052 RF-9 un perfil abierto se recupera al volver a abrir el modelo`() {
        val vm = open()
        vm.configure(tolerance = "0,3")
        vm.beginAndWait()
        measure(vm, "L2")

        val again = LongitudinalViewModel(surveys, alignments, points, projects, dispatcher)
        viewModels += again
        again.start(projectId, vm.state.value.alignment!!.id)
        runBlocking { withTimeout(10_000) { again.state.first { it.survey != null && it.rows.size == 1 } } }

        again.state.value.survey.shouldNotBeNull().toleranceM shouldBe 0.3
        again.state.value.rows.single().name shouldBe "L2"
    }
}
