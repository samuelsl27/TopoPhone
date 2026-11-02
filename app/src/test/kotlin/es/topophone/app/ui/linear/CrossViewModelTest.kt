package es.topophone.app.ui.linear

import android.content.Context
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.app.ui.alignment.AlignmentTestFiles
import es.topophone.app.ui.collector.SavedPoint
import es.topophone.app.ui.receiver.repositoryRoot
import es.topophone.core.formats.csv.CsvOptions
import es.topophone.core.model.alignment.section.CrossSection
import es.topophone.core.model.alignment.section.HeightKind
import es.topophone.core.model.alignment.section.SectionKind
import es.topophone.core.model.alignment.section.SectionPoint
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
import es.topophone.data.export.ExportPreferences
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

/** El perfil transversal en el PK 105 del Eje C contra `fixtures/ejes/toma/` (S052/RF-16 a RF-21). */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class CrossViewModelTest {

    private val db = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext<Context>(),
        TopoPhoneDatabase::class.java,
    ).build()
    private val projects = RoomProjectRepository(db)
    private val points = RoomPointRepository(db)
    private val alignments = RoomAlignmentRepository(db)
    private val sections = RoomSectionRepository(db)
    private val surveys = RoomLinearSurveyRepository(db, alignments, sections)
    private val dispatcher = UnconfinedTestDispatcher()
    private val viewModels = mutableListOf<CrossViewModel>()
    private val exportPreferences = object : ExportPreferences {
        override val options = flowOf(CsvOptions())

        override suspend fun save(options: CsvOptions) = Unit
    }

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

    private val ground = table("transversal-puntos.csv").associate { it[0] to Triple(it[1].toDouble(), it[2].toDouble(), it[3].toDouble()) }

    /** La sección de diseño de `seccion-diseno.csv` (PK 105) con los códigos D1 a D5. */
    private val design = CrossSection(
        SectionKind.DESIGN,
        105.0,
        HeightKind.ABSOLUTE,
        table("seccion-diseno.csv").mapIndexed { i, r -> SectionPoint("D${i + 1}", r[0].toDouble(), r[1].toDouble()) },
    )

    private fun open(unit: LinearUnit = LinearUnit.METRE, withDesign: Boolean = true): CrossViewModel {
        projectId = runBlocking {
            projects.create(ProjectDefaults().toDraft().copy(name = "Obra", linearUnit = unit)).shouldBeInstanceOf<SaveResult.Saved>().id
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
        if (withDesign) runBlocking { sections.addSections(axisId, listOf(design)) }
        return again()
    }

    private fun again(): CrossViewModel {
        val vm = CrossViewModel(surveys, alignments, sections, points, projects, exportPreferences, dispatcher)
        viewModels += vm
        vm.start(projectId, axisId)
        runBlocking { withTimeout(10_000) { vm.state.first { !it.loading && it.alignment != null } } }
        return vm
    }

    private fun CrossViewModel.beginAt(chainage: String = "105", tolerance: String = "") {
        updateSetup { it.copy(chainage = chainage, tolerance = tolerance) }
        begin()
        runBlocking { withTimeout(10_000) { state.first { it.survey != null } } }
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

    private fun measure(vm: CrossViewModel, name: String) {
        val p = point(name)
        runBlocking {
            points.create(p)
            vm.pointListener.onSaved(SavedPoint(p.id, p.name, p.coordinates))
        }
        runBlocking { withTimeout(10_000) { vm.state.first { s -> s.rows.any { it.pointId == p.id } } } }
    }

    private fun measureAll(vm: CrossViewModel) = ground.keys.forEach { measure(vm, it) }

    @Test
    fun `S052 RF-16 empezar en un PK abre la toma guiada con 0,10 m de tolerancia de seccion`() {
        val vm = open()

        vm.beginAt("105")

        val survey = vm.state.value.survey.shouldNotBeNull()
        survey.kind shouldBe LinearSurveyKind.CROSS
        survey.chainage shouldBe 105.0
        survey.guided shouldBe true
        survey.toleranceM shouldBe 0.1
        survey.alignmentId shouldBe axisId
    }

    @Test
    fun `S052 RF-16 el PK se puede escribir con mas y la tolerancia en la unidad del proyecto`() {
        val vm = open(unit = LinearUnit.INTERNATIONAL_FOOT)

        vm.beginAt("1+00", tolerance = "0,5")

        vm.state.value.survey.shouldNotBeNull().chainage shouldBe (30.48 plusOrMinus 1e-9)
        vm.state.value.survey.shouldNotBeNull().toleranceM shouldBe (0.1524 plusOrMinus 1e-9)
    }

    @Test
    fun `S052 RF-16 un PK que no es numero o cae fuera del eje o una tolerancia que no vale no abren la toma`() {
        val vm = open()

        vm.updateSetup { it.copy(chainage = "aqui") }
        vm.begin()
        vm.state.value.problem shouldBe CrossProblem.InvalidNumber(CrossField.CHAINAGE)
        vm.updateSetup { it.copy(chainage = "250") }
        vm.begin()
        vm.state.value.problem shouldBe CrossProblem.OutsideAxis
        vm.updateSetup { it.copy(chainage = "105", tolerance = "0") }
        vm.begin()
        vm.state.value.problem shouldBe CrossProblem.NotPositive
        vm.updateSetup { it.copy(tolerance = "poca") }
        vm.begin()
        vm.state.value.problem shouldBe CrossProblem.InvalidNumber(CrossField.TOLERANCE)

        vm.state.value.survey.shouldBeNull()
        runBlocking { surveys.surveys(projectId).first() } shouldBe emptyList()
    }

    @Test
    fun `S052 RF-17 cada punto entra con el error en PK el desplazamiento y la diferencia con la seccion de tools a 1 mm`() {
        val vm = open()
        vm.beginAt()

        measureAll(vm)

        val rows = vm.state.value.rows.associateBy { it.name }
        for (row in table("esperado-transversal.csv")) {
            val r = rows.getValue(row[0])
            (r.chainageM.shouldNotBeNull() - 105.0) shouldBe (row[1].toDouble() plusOrMinus 1e-3)
            r.offsetM.shouldNotBeNull() shouldBe (row[2].toDouble() plusOrMinus 1e-3)
            if (row[3].isEmpty()) r.dHeightM.shouldBeNull() else r.dHeightM.shouldNotBeNull() shouldBe (row[3].toDouble() plusOrMinus 1e-3)
            r.flag shouldBe if (row[4] == "ninguna") SurveyFlag.NONE else SurveyFlag.OUT_OF_SECTION
        }
    }

    @Test
    fun `S052 RF-18 el que pasa la tolerancia de seccion se guarda pero queda fuera del perfil y se dibuja hueco`() {
        val vm = open()
        vm.beginAt()

        measureAll(vm)

        val state = vm.state.value
        state.rejected.map { it.name }.toSet() shouldBe setOf("X4", "X8")
        state.inProfile.size shouldBe 6
        runBlocking { points.points(projectId).first() }.size shouldBe 8
        state.chart.points.size shouldBe 8
        state.chart.profilePoints.size shouldBe 6
        state.chart.axis shouldBe ProfileAxis.OFFSET
    }

    @Test
    fun `S052 RF-18 la tolerancia elegida manda sobre la de omision`() {
        val vm = open()
        vm.beginAt(tolerance = "0,2")

        measure(vm, "X4") // a 15 cm del PK

        vm.state.value.rows.single().flag shouldBe SurveyFlag.NONE
    }

    @Test
    fun `S052 RF-19 el grafico lleva la seccion de diseno del PK como referencia por desplazamiento`() {
        val vm = open()
        vm.beginAt()

        measure(vm, "X1")

        val chart = vm.state.value.chart
        vm.state.value.hasDesign shouldBe true
        chart.reference shouldBe design.points.map { it.offsetM to it.heightM }
        chart.points.single().x shouldBe (8.0 plusOrMinus 1e-3)
    }

    @Test
    fun `S052 RF-19 sin seccion de diseno el grafico solo lleva el terreno y no hay diferencia de cota`() {
        val vm = open(withDesign = false)
        vm.beginAt()

        measure(vm, "X1")

        vm.state.value.hasDesign shouldBe false
        vm.state.value.chart.reference shouldBe emptyList()
        vm.state.value.rows.single().dHeightM.shouldBeNull()
    }

    @Test
    fun `S052 RF-17 el codigo escrito vale para el siguiente punto solo y sin el se toma el de la seccion de diseno cercano`() {
        val vm = open()
        vm.beginAt()
        vm.updateSetup { it.copy(nextCode = "CUN") }

        measure(vm, "X1") // a 8 m: ningun punto de diseno cerca
        measure(vm, "X6") // en el eje: el D3 de la seccion de diseno
        measure(vm, "X5") // a -6 m: sin codigo

        val codes = vm.state.value.rows.associate { it.name to it.code }
        codes shouldBe mapOf("X1" to "CUN", "X6" to "D3", "X5" to null)
        vm.state.value.setup.nextCode shouldBe ""
    }

    @Test
    fun `S052 RF-20 guardar el perfil lo deja como el perfil de terreno de S050 ordenado por desplazamiento y cierra la toma`() {
        val vm = open()
        vm.beginAt()
        measureAll(vm)

        vm.save()
        runBlocking { withTimeout(10_000) { vm.state.first { it.survey == null && it.saved != null } } }

        vm.state.value.saved shouldBe CrossSaved(6, replacedPrevious = false)
        val stored = runBlocking { sections.sections(axisId).first() }.map { it.section }.single { it.kind == SectionKind.GROUND }
        stored.chainage shouldBe 105.0
        stored.heightKind shouldBe HeightKind.ABSOLUTE
        val expected = table("esperado-transversal.csv").filter { it[5].isNotEmpty() }.sortedBy { it[5].toInt() }
        stored.points.map { it.offsetM } shouldBe expected.map { it[2].toDouble() }
        stored.points.map { it.heightM } shouldBe expected.map { ground.getValue(it[0]).third }
        runBlocking { surveys.surveys(projectId).first() }.single().isOpen shouldBe false
    }

    @Test
    fun `S052 RF-20 un segundo perfil en el mismo PK sustituye al vigente de S050 y avisa de que el anterior queda en el historial`() {
        val vm = open()
        vm.beginAt()
        measure(vm, "X1")
        vm.save()
        runBlocking { withTimeout(10_000) { vm.state.first { it.survey == null && it.saved != null } } }
        vm.dismissSaved()
        vm.beginAt()
        measure(vm, "X6")

        vm.save()
        runBlocking { withTimeout(10_000) { vm.state.first { it.survey == null && it.saved != null } } }

        vm.state.value.saved shouldBe CrossSaved(1, replacedPrevious = true)
        val grounds = runBlocking { sections.sections(axisId).first() }.map { it.section }.filter { it.kind == SectionKind.GROUND }
        grounds.size shouldBe 1
        grounds.single().points.single().heightM shouldBe ground.getValue("X6").third
        runBlocking { surveys.surveys(projectId).first() }.count { !it.isOpen } shouldBe 2
    }

    @Test
    fun `S052 RF-20 sin puntos en el perfil o con dos en el mismo desplazamiento no se guarda y la toma sigue abierta`() {
        val vm = open()
        vm.beginAt()

        vm.save()
        vm.state.value.problem shouldBe CrossProblem.NoPoints
        measure(vm, "X4") // fuera de seccion: tampoco entra
        vm.save()
        vm.state.value.problem shouldBe CrossProblem.NoPoints

        vm.state.value.survey.shouldNotBeNull().isOpen shouldBe true
        runBlocking { sections.sections(axisId).first() }.none { it.section.kind == SectionKind.GROUND } shouldBe true
    }

    @Test
    fun `S052 RF-20 dos puntos en el mismo desplazamiento no dejan guardar el perfil`() {
        val vm = open()
        vm.beginAt()
        measure(vm, "X6")
        val twin = point("X6").let { it.copy(id = "id-gemelo", name = "G") }
        runBlocking {
            points.create(twin)
            vm.pointListener.onSaved(SavedPoint(twin.id, twin.name, twin.coordinates))
        }
        runBlocking { withTimeout(10_000) { vm.state.first { it.rows.size == 2 } } }

        vm.save()

        vm.state.value.problem shouldBe CrossProblem.SameOffset(0.0)
        vm.state.value.survey.shouldNotBeNull().isOpen shouldBe true
    }

    @Test
    fun `S052 RF-21 quitar un punto lo saca del perfil sin borrarlo del proyecto y se puede volver a medir con otro`() {
        val vm = open()
        vm.beginAt()
        measure(vm, "X1")
        measure(vm, "X6")

        vm.removePoint("id-X1")
        runBlocking { withTimeout(10_000) { vm.state.first { it.inProfile.size == 1 } } }

        vm.state.value.inProfile.single().name shouldBe "X6"
        vm.state.value.chart.points.map { it.label } shouldBe listOf("X6")
        runBlocking { points.points(projectId).first() }.size shouldBe 2
    }

    @Test
    fun `S052 RF-22 el CSV lleva los puntos del perfil por desplazamiento con el PK real y las cotas de tools`() {
        val vm = open()
        vm.beginAt()
        measureAll(vm)

        val out = ByteArrayOutputStream()
        runBlocking { vm.writeCsv(out) } shouldBe true

        val expected = File(fixture, "esperado-perfil-transversal.csv").readLines().drop(1).map { it.split(",") }
        val written = out.toString(Charsets.UTF_8).trim().lines().drop(1).map { it.split(",") }
        written.size shouldBe expected.size
        for ((w, e) in written.zip(expected)) {
            // Con la seccion de diseno puesta, el codigo es el de S050 donde hay un punto cerca; el resto, las columnas de tools.
            w.drop(1) shouldBe e.drop(1)
        }
    }

    @Test
    fun `S052 RF-22 sin codigos de diseno el CSV lleva P1 a Pn como tools byte a byte`() {
        val vm = open(withDesign = false)
        vm.beginAt()
        measureAll(vm)

        val out = ByteArrayOutputStream()
        runBlocking { vm.writeCsv(out) } shouldBe true

        val written = out.toString(Charsets.UTF_8).trim().lines().drop(1).map { it.split(",") }
        val expected = File(fixture, "esperado-perfil-transversal.csv").readLines().drop(1).map { it.split(",") }
        written.map { it.take(4) } shouldBe expected.map { it.take(4) } // codigo, PK, desplazamiento y cota del terreno
    }

    @Test
    fun `S052 RF-9 un perfil abierto se recupera con su PK y sus puntos al volver a abrir el modelo`() {
        val vm = open()
        vm.beginAt(tolerance = "0,2")
        measure(vm, "X1")

        val reopened = again()
        runBlocking { withTimeout(10_000) { reopened.state.first { it.survey != null && it.rows.size == 1 } } }

        reopened.state.value.survey.shouldNotBeNull().chainage shouldBe 105.0
        reopened.state.value.survey.shouldNotBeNull().toleranceM shouldBe 0.2
        reopened.state.value.rows.single().name shouldBe "X1"
    }

    @Test
    fun `S052 RF-16 descartar borra la toma y deja los puntos`() {
        val vm = open()
        vm.beginAt()
        measure(vm, "X1")

        vm.discard()
        runBlocking { withTimeout(10_000) { vm.state.first { it.survey == null } } }

        runBlocking { surveys.surveys(projectId).first() } shouldBe emptyList()
        runBlocking { points.points(projectId).first() }.size shouldBe 1
    }
}
