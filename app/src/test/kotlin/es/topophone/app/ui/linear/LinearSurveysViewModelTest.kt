package es.topophone.app.ui.linear

import android.content.Context
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.app.ui.alignment.AlignmentTestFiles
import es.topophone.app.ui.receiver.repositoryRoot
import es.topophone.core.formats.csv.ColumnSeparator
import es.topophone.core.formats.csv.CsvOptions
import es.topophone.core.formats.csv.DecimalSeparator
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
import es.topophone.core.model.survey.linear.LinearSurvey
import es.topophone.core.model.survey.linear.LinearSurveyKind
import es.topophone.core.model.survey.linear.SurveyFlag
import es.topophone.core.model.survey.linear.SurveyPoint
import es.topophone.data.alignment.AlignmentImporter
import es.topophone.data.alignment.RoomAlignmentRepository
import es.topophone.data.db.TopoPhoneDatabase
import es.topophone.data.export.ExportPreferences
import es.topophone.data.linear.RoomLinearSurveyRepository
import es.topophone.data.point.RoomPointRepository
import es.topophone.data.project.RoomProjectRepository
import es.topophone.data.project.SaveResult
import es.topophone.data.section.RoomSectionRepository
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** La lista de tomas del proyecto: tipo, eje, PK, fecha y puntos, ver, exportar el CSV y borrar sin tocar los puntos (S052/RF-22). */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class LinearSurveysViewModelTest {

    private val db = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext<Context>(),
        TopoPhoneDatabase::class.java,
    ).build()
    private val projects = RoomProjectRepository(db)
    private val points = RoomPointRepository(db)
    private val alignments = RoomAlignmentRepository(db)
    private val surveys = RoomLinearSurveyRepository(db, alignments, RoomSectionRepository(db))
    private val dispatcher = UnconfinedTestDispatcher()
    private var options = CsvOptions()
    private val exportPreferences = object : ExportPreferences {
        override val options get() = flowOf(this@LinearSurveysViewModelTest.options)

        override suspend fun save(options: CsvOptions) = Unit
    }
    private val viewModels = mutableListOf<LinearSurveysViewModel>()

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
    private lateinit var axisId: String

    private fun open(): LinearSurveysViewModel {
        projectId = runBlocking {
            projects.create(ProjectDefaults().toDraft().copy(name = "Obra")).shouldBeInstanceOf<SaveResult.Saved>().id
        }
        axisId = runBlocking {
            AlignmentImporter(alignments).importLandXml(
                projectId,
                ProjectCrs.ETRS89_UTM30,
                "ejes.xml",
                setOf(2),
                CadSourceCrs.Project,
                LinearUnit.METRE,
                { ByteArrayInputStream(AlignmentTestFiles.bytes("ejes.xml")) },
            )
            alignments.summaries(projectId).first().single().id
        }
        val vm = LinearSurveysViewModel(surveys, alignments, points, projects, exportPreferences, dispatcher)
        viewModels += vm
        vm.start(projectId)
        runBlocking { withTimeout(10_000) { vm.state.first { !it.loading } } }
        return vm
    }

    private fun point(name: String, height: Double?): Point = Point.measured(
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
            coordinates = PointCoordinates(GeodeticPosition(40.4168, -3.7037, 699.4), 441_105.0, 4_474_000.0, height),
        ),
        createdAtMs = 3,
    )

    private fun survey(id: String, kind: LinearSurveyKind, at: Long, chainage: Double? = null, from: Double? = null, to: Double? = null) =
        LinearSurvey(id, projectId, kind, axisId, "Toma", from, to, null, chainage, createdAtMs = at)

    private fun add(vm: LinearSurveysViewModel, id: String, member: SurveyPoint, name: String = member.pointId, height: Double? = 100.0) {
        runBlocking {
            points.create(point(name, height).copy(id = member.pointId))
            surveys.addPoint(id, member)
        }
    }

    private fun member(
        id: String,
        chainage: Double?,
        offset: Double?,
        dz: Double?,
        flag: SurveyFlag = SurveyFlag.NONE,
        code: String? = null,
    ) = SurveyPoint(id, 0, true, chainage, offset, dz, flag, code)

    private fun awaitItems(vm: LinearSurveysViewModel, count: Int) {
        runBlocking { withTimeout(10_000) { vm.state.first { it.items.size == count } } }
    }

    @Test
    fun `S052 RF-22 la lista trae cada toma con su tipo eje PK y fecha de la mas reciente a la mas antigua`() {
        val vm = open()
        runBlocking {
            surveys.open(survey("a", LinearSurveyKind.AXIS, at = 10))
            surveys.open(survey("l", LinearSurveyKind.LONGITUDINAL, at = 30, from = 0.0, to = 200.0))
            surveys.open(survey("c", LinearSurveyKind.CROSS, at = 20, chainage = 105.0))
        }
        awaitItems(vm, 3)

        val items = vm.state.value.items
        items.map { it.id } shouldBe listOf("l", "c", "a")
        items.map { it.kind } shouldBe listOf(LinearSurveyKind.LONGITUDINAL, LinearSurveyKind.CROSS, LinearSurveyKind.AXIS)
        items.first { it.id == "c" }.chainageM shouldBe 105.0
        items.first { it.id == "l" }.chainageToM shouldBe 200.0
        items.all { it.alignmentName == "Eje C" } shouldBe true
        items.all { it.open } shouldBe true
        vm.state.value.projectName shouldBe "Obra"
    }

    @Test
    fun `S052 RF-22 el numero de puntos es el del perfil sin los excluidos ni los marcados`() {
        val vm = open()
        runBlocking { surveys.open(survey("c", LinearSurveyKind.CROSS, at = 20, chainage = 105.0)) }
        add(vm, "c", member("p1", 105.0, 1.0, 0.1))
        add(vm, "c", member("p2", 105.0, 2.0, 0.1))
        add(vm, "c", member("p3", 105.2, 3.0, 0.1, SurveyFlag.OUT_OF_SECTION))
        runBlocking { surveys.setIncluded("c", "p2", false) }
        awaitItems(vm, 1)

        runBlocking { withTimeout(10_000) { vm.state.first { it.items.single().points == 1 } } }

        vm.state.value.items.single().points shouldBe 1
    }

    @Test
    fun `S052 RF-22 si se borra el eje la toma sigue en la lista sin nombre de eje`() {
        val vm = open()
        runBlocking { surveys.open(survey("l", LinearSurveyKind.LONGITUDINAL, at = 5)) }
        awaitItems(vm, 1)

        runBlocking { alignments.delete(axisId) }
        runBlocking { withTimeout(10_000) { vm.state.first { it.items.singleOrNull()?.alignmentName == null && it.items.isNotEmpty() } } }

        vm.state.value.items.single().alignmentName.shouldBeNull()
    }

    @Test
    fun `S052 RF-22 ver una toma trae sus puntos y el alzado con lo guardado`() {
        val vm = open()
        runBlocking { surveys.open(survey("l", LinearSurveyKind.LONGITUDINAL, at = 5)) }
        add(vm, "l", member("p1", 50.0, 0.1, 0.3), height = 101.0)
        add(vm, "l", member("p2", 10.0, 0.2, 0.1), height = 100.5)
        add(vm, "l", member("p3", 70.0, 0.9, 0.2, SurveyFlag.OUT_OF_AXIS), height = 105.0)
        awaitItems(vm, 1)

        vm.view("l")
        runBlocking { withTimeout(10_000) { vm.state.first { it.detail != null } } }

        val detail = vm.state.value.detail.shouldNotBeNull()
        detail.rows.map { it.name } shouldBe listOf("p1", "p2", "p3")
        detail.chart.axis shouldBe ProfileAxis.CHAINAGE
        detail.chart.points.map { it.label } shouldBe listOf("p2", "p1", "p3") // por PK
        detail.chart.profilePoints.map { it.label } shouldBe listOf("p2", "p1")
        detail.chart.reference shouldBe listOf(10.0 to 100.4, 50.0 to 100.7)
        vm.closeDetail()
        vm.state.value.detail.shouldBeNull()
    }

    @Test
    fun `S052 RF-22 un perfil transversal se ve por desplazamiento`() {
        val vm = open()
        runBlocking { surveys.open(survey("c", LinearSurveyKind.CROSS, at = 5, chainage = 105.0)) }
        add(vm, "c", member("p1", 105.0, 4.0, 0.0), height = 100.0)
        add(vm, "c", member("p2", 105.0, -3.0, 0.0), height = 101.0)
        awaitItems(vm, 1)

        vm.view("c")
        runBlocking { withTimeout(10_000) { vm.state.first { it.detail != null } } }

        vm.state.value.detail.shouldNotBeNull().chart.let {
            it.axis shouldBe ProfileAxis.OFFSET
            it.points.map { p -> p.x } shouldBe listOf(-3.0, 4.0)
        }
    }

    /** Las tomas de los ficheros de tools: `longitudinal-puntos.csv` y `transversal-puntos.csv` con lo esperado como lo guardado. */
    private fun fillLongitudinal(vm: LinearSurveysViewModel) {
        runBlocking { surveys.open(survey("l", LinearSurveyKind.LONGITUDINAL, at = 5)) }
        val grounds = table("longitudinal-puntos.csv").associate { it[0] to it[3].toDouble() }
        for (row in table("esperado-longitudinal.csv")) {
            val flag = when (row[5]) {
                "ninguna" -> SurveyFlag.NONE
                "fuera_del_eje" -> SurveyFlag.OUT_OF_AXIS
                else -> SurveyFlag.NO_PROJECTION
            }
            val dz = row[4].takeIf { it.isNotEmpty() }?.toDouble()
            add(
                vm,
                "l",
                member(
                    "id-${row[0]}",
                    row[1].takeIf {
                        it.isNotEmpty()
                    }?.toDouble(),
                    row[2].takeIf { it.isNotEmpty() }?.toDouble(),
                    dz,
                    flag,
                ),
                row[0],
                grounds.getValue(row[0]),
            )
        }
    }

    @Test
    fun `S052 RF-22 el CSV de un longitudinal ya hecho es el de tools byte a byte`() {
        val vm = open()
        fillLongitudinal(vm)
        awaitItems(vm, 1)
        vm.view("l")
        runBlocking { withTimeout(10_000) { vm.state.first { it.detail != null } } }

        val out = ByteArrayOutputStream()
        runBlocking { vm.writeCsv(out) } shouldBe true

        out.toByteArray().toList() shouldBe File(fixture, "esperado-perfil-longitudinal.csv").readBytes().toList()
    }

    @Test
    fun `S052 RF-22 el CSV de un transversal ya hecho es el de tools byte a byte`() {
        val vm = open()
        runBlocking { surveys.open(survey("c", LinearSurveyKind.CROSS, at = 5, chainage = 105.0)) }
        val grounds = table("transversal-puntos.csv").associate { it[0] to it[3].toDouble() }
        for (row in table("esperado-transversal.csv")) {
            val flag = if (row[4] == "ninguna") SurveyFlag.NONE else SurveyFlag.OUT_OF_SECTION
            val dz = row[3].takeIf { it.isNotEmpty() }?.toDouble()
            add(vm, "c", member("id-${row[0]}", 105.0 + row[1].toDouble(), row[2].toDouble(), dz, flag), row[0], grounds.getValue(row[0]))
        }
        awaitItems(vm, 1)
        vm.view("c")
        runBlocking { withTimeout(10_000) { vm.state.first { it.detail != null } } }

        val out = ByteArrayOutputStream()
        runBlocking { vm.writeCsv(out) } shouldBe true

        out.toByteArray().toList() shouldBe File(fixture, "esperado-perfil-transversal.csv").readBytes().toList()
    }

    @Test
    fun `S052 RF-22 no se exporta un eje tomado ni con coma de separador y de decimal ni sin ver ninguna toma`() {
        val vm = open()
        runBlocking { surveys.open(survey("a", LinearSurveyKind.AXIS, at = 5)) }
        awaitItems(vm, 1)

        runBlocking { vm.writeCsv(ByteArrayOutputStream()) } shouldBe false // sin toma abierta para ver
        options = CsvOptions(separator = ColumnSeparator.COMMA, decimal = DecimalSeparator.COMMA)
        runBlocking { vm.writeCsv(ByteArrayOutputStream()) } shouldBe false
    }

    @Test
    fun `S052 RF-22 borrar pide confirmacion y cancelar no borra nada`() {
        val vm = open()
        runBlocking { surveys.open(survey("l", LinearSurveyKind.LONGITUDINAL, at = 5)) }
        awaitItems(vm, 1)

        vm.requestDelete("l")
        vm.state.value.toDelete.shouldNotBeNull().id shouldBe "l"
        vm.cancelDelete()

        vm.state.value.toDelete.shouldBeNull()
        runBlocking { surveys.surveys(projectId).first() }.size shouldBe 1
    }

    @Test
    fun `S052 RF-22 confirmar borra la toma y no los puntos`() {
        val vm = open()
        runBlocking { surveys.open(survey("l", LinearSurveyKind.LONGITUDINAL, at = 5)) }
        add(vm, "l", member("p1", 10.0, 0.0, 0.0))
        awaitItems(vm, 1)
        vm.view("l")
        runBlocking { withTimeout(10_000) { vm.state.first { it.detail != null } } }

        vm.requestDelete("l")
        vm.confirmDelete()
        runBlocking { withTimeout(10_000) { vm.state.first { it.items.isEmpty() } } }

        vm.state.value.detail.shouldBeNull()
        runBlocking { surveys.surveys(projectId).first() } shouldBe emptyList()
        runBlocking { points.points(projectId).first() }.size shouldBe 1
    }
}
