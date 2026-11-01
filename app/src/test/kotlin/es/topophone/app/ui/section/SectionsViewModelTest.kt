package es.topophone.app.ui.section

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.app.ui.alignment.AlignmentTestFiles
import es.topophone.core.formats.section.SuperelevationRole
import es.topophone.core.model.alignment.section.CrossSection
import es.topophone.core.model.alignment.section.HeightKind
import es.topophone.core.model.alignment.section.SectionIssue
import es.topophone.core.model.alignment.section.SectionKind
import es.topophone.core.model.alignment.section.SectionPoint
import es.topophone.core.model.alignment.section.SuperelevationRow
import es.topophone.core.model.cad.CadSourceCrs
import es.topophone.core.model.project.LinearUnit
import es.topophone.core.model.project.ProjectCrs
import es.topophone.core.model.project.ProjectDefaults
import es.topophone.data.alignment.AlignmentImporter
import es.topophone.data.alignment.RoomAlignmentRepository
import es.topophone.data.db.TopoPhoneDatabase
import es.topophone.data.project.RoomProjectRepository
import es.topophone.data.project.SaveResult
import es.topophone.data.section.RoomSectionRepository
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.io.ByteArrayInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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

/** La sección tipo y el peralte escritos a mano o leídos de un CSV, con sus rechazos (S050/RF-1 a RF-3, RF-6, RF-7). */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class SectionsViewModelTest {

    private val db = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext<Context>(),
        TopoPhoneDatabase::class.java,
    ).build()
    private val projects = RoomProjectRepository(db)
    private val alignments = RoomAlignmentRepository(db)
    private val sections = RoomSectionRepository(db)
    private val dispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        db.close()
    }

    private fun project(unit: LinearUnit = LinearUnit.METRE) = runBlocking {
        projects.create(ProjectDefaults().toDraft().copy(name = "Obra", linearUnit = unit)).shouldBeInstanceOf<SaveResult.Saved>().id
    }

    /** El eje C de los ejemplos, PK de 0 a 200, en [projectId]. */
    private fun axisC(projectId: String): String = runBlocking {
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

    private fun open(unit: LinearUnit = LinearUnit.METRE): Pair<SectionsViewModel, String> {
        val projectId = project(unit)
        val axis = axisC(projectId)
        val vm = SectionsViewModel(sections, alignments, projects, dispatcher)
        vm.start(projectId, axis)
        runBlocking { withTimeout(10_000) { vm.state.first { !it.loading } } }
        return vm to axis
    }

    private fun SectionsViewModel.fillTemplate(from: String = "0", to: String = "100", grade: String = "EJE", right: String = "3.5") {
        startTemplate()
        updateTemplate { it.copy(from = from, to = to, grade = grade) }
        updateSegment(true, 0) { it.copy(code = "BI", width = "3.5", slope = "2", follows = true) }
        updateSegment(false, 0) { it.copy(code = "BD", width = right, slope = "2", follows = true) }
    }

    private fun SectionsViewModel.saveTemplateAndWait() {
        saveTemplate()
        runBlocking { withTimeout(10_000) { state.first { !it.saving } } }
    }

    /** Room contesta las listas desde otro hilo: se espera a que el estado las tenga. */
    private fun SectionsViewModel.awaitTemplates(count: Int) {
        runBlocking { withTimeout(10_000) { state.first { it.templates.size == count } } }
    }

    @Test
    fun `S050 RF-1 la pantalla carga el eje y sus listas vacias`() {
        val (vm, axis) = open()

        vm.state.value.alignment?.id shouldBe axis
        vm.state.value.templates shouldBe emptyList()
        vm.state.value.sections shouldBe emptyList()
        vm.state.value.superelevation shouldBe emptyList()
    }

    @Test
    fun `S050 RF-2 una seccion tipo escrita a mano se guarda en metros y con la pendiente en fraccion`() {
        val (vm, _) = open()

        vm.fillTemplate()
        vm.saveTemplateAndWait()
        vm.awaitTemplates(1)

        vm.state.value.templateDraft.shouldBeNull()
        val t = vm.state.value.templates.single().template
        t.fromChainage shouldBe 0.0
        t.toChainage shouldBe 100.0
        t.gradeCode shouldBe "EJE"
        t.left.single() shouldBe es.topophone.core.model.alignment.section.TemplateSegment("BI", 3.5, 0.02, true)
        t.right.single().code shouldBe "BD"
    }

    @Test
    fun `S050 RF-2 los anchos y los PK se escriben en la unidad del proyecto`() {
        val (vm, _) = open(LinearUnit.INTERNATIONAL_FOOT)

        vm.fillTemplate(from = "0", to = "100", right = "10")
        vm.saveTemplateAndWait()
        vm.awaitTemplates(1)

        val t = vm.state.value.templates.single().template
        t.toChainage shouldBe (30.48 plusOrMinus 1e-9)
        t.right.single().widthM shouldBe (3.048 plusOrMinus 1e-9)
    }

    @Test
    fun `S050 RF-2 se pueden anadir y quitar tramos de cada lado y elegir el punto de rasante`() {
        val (vm, _) = open()
        vm.startTemplate()

        vm.addSegment(true)
        vm.addSegment(false)
        vm.addSegment(false)
        vm.state.value.templateDraft!!.left.size shouldBe 2
        vm.state.value.templateDraft!!.right.size shouldBe 3
        vm.removeSegment(false, 0)
        vm.removeSegment(true, 1)

        vm.state.value.templateDraft!!.left.size shouldBe 1
        vm.state.value.templateDraft!!.right.size shouldBe 2
    }

    @Test
    fun `S050 RF-7 un numero mal escrito dice el campo la fila y el lado y no guarda`() {
        val (vm, _) = open()
        vm.fillTemplate()
        vm.updateSegment(false, 0) { it.copy(width = "abc") }

        vm.saveTemplateAndWait()

        vm.state.value.problem shouldBe SectionsProblem.InvalidNumber(SectionField.WIDTH, 1, false)
        vm.state.value.templates shouldBe emptyList()
        vm.state.value.templateDraft.shouldNotBeNull()
    }

    @Test
    fun `S050 RF-7 sin ningun tramo no se guarda`() {
        val (vm, _) = open()
        vm.startTemplate()
        vm.updateTemplate { it.copy(from = "0", to = "10", left = emptyList(), right = emptyList()) }

        vm.saveTemplateAndWait()

        vm.state.value.problem shouldBe SectionsProblem.NoSegments
    }

    @Test
    fun `S050 RF-7 los rechazos de la validacion dicen el punto y el motivo`() {
        val (vm, _) = open()
        vm.fillTemplate()
        vm.saveTemplateAndWait()

        vm.fillTemplate(from = "50", to = "150")
        vm.saveTemplateAndWait()
        vm.state.value.problem shouldBe SectionsProblem.Rejected(SectionIssue.OverlappingTemplates(50.0, 150.0))

        vm.fillTemplate(from = "100", to = "150", grade = "ZZ")
        vm.saveTemplateAndWait()
        vm.state.value.problem shouldBe SectionsProblem.Rejected(SectionIssue.UnknownGradeCode("ZZ"))

        vm.fillTemplate(from = "100", to = "150")
        vm.updateSegment(false, 0) { it.copy(slope = "150") }
        vm.saveTemplateAndWait()
        vm.state.value.problem shouldBe SectionsProblem.Rejected(SectionIssue.SlopeTooSteep("BD", 1.5))

        vm.fillTemplate(from = "100", to = "500")
        vm.saveTemplateAndWait()
        vm.state.value.problem shouldBe SectionsProblem.Rejected(SectionIssue.ChainageOutsideAxis(500.0))
        vm.awaitTemplates(1)
    }

    @Test
    fun `S050 RF-3 se pueden tener varias secciones tipo y borrar una o una seccion explicita`() {
        val (vm, axis) = open()
        vm.fillTemplate(from = "0", to = "50")
        vm.saveTemplateAndWait()
        vm.fillTemplate(from = "50", to = "100")
        vm.saveTemplateAndWait()
        vm.awaitTemplates(2)
        runBlocking {
            sections.addSections(
                axis,
                listOf(CrossSection(SectionKind.DESIGN, 10.0, HeightKind.ABSOLUTE, listOf(SectionPoint("EJE", 0.0, 1.0)))),
            )
        }
        runBlocking { withTimeout(10_000) { vm.state.first { it.sections.size == 1 } } }

        vm.state.value.templates.size shouldBe 2
        vm.deleteTemplate(vm.state.value.templates.first().id)
        vm.deleteSection(vm.state.value.sections.single().id)

        runBlocking { withTimeout(10_000) { vm.state.first { it.templates.size == 1 && it.sections.isEmpty() } } }
    }

    @Test
    fun `S050 RF-6 el peralte escrito a mano se guarda en fraccion y sustituye al anterior`() {
        val (vm, axis) = open()
        vm.startSuperelevation()
        vm.updateSuperelevationRow(0) { it.copy(chainage = "0", left = "2", right = "2") }
        vm.addSuperelevationRow()
        vm.updateSuperelevationRow(1) { it.copy(chainage = "100", left = "-6", right = "6") }

        vm.saveSuperelevation()
        runBlocking { withTimeout(10_000) { vm.state.first { it.superelevation.size == 2 && !it.saving } } }

        runBlocking { sections.superelevation(axis).first() } shouldBe
            listOf(SuperelevationRow(0.0, 0.02, 0.02), SuperelevationRow(100.0, -0.06, 0.06))
        vm.startSuperelevation()
        vm.state.value.superelevationDraft!!.map { it.chainage to it.right } shouldBe listOf("0" to "2", "100" to "6")
    }

    @Test
    fun `S050 RF-7 un peralte con PK repetido o numero mal escrito no se guarda y dice el motivo`() {
        val (vm, _) = open()
        vm.startSuperelevation()
        vm.updateSuperelevationRow(0) { it.copy(chainage = "10", left = "2", right = "2") }
        vm.addSuperelevationRow()
        vm.updateSuperelevationRow(1) { it.copy(chainage = "10", left = "3", right = "3") }

        vm.saveSuperelevation()
        runBlocking { withTimeout(10_000) { vm.state.first { !it.saving } } }
        vm.state.value.problem shouldBe SectionsProblem.Rejected(SectionIssue.RepeatedChainage(10.0))

        vm.updateSuperelevationRow(1) { it.copy(chainage = "20", right = "x") }
        vm.saveSuperelevation()
        vm.state.value.problem shouldBe SectionsProblem.InvalidNumber(SectionField.RIGHT_SLOPE, 2, null)
    }

    @Test
    fun `S050 RF-6 el CSV de peralte propone las columnas y pasa sus filas a la tabla`() {
        val (vm, axis) = open()
        vm.startSuperelevation()

        vm.readCsv(AlignmentTestFiles.source("peralte.csv", "PK,Izquierda,Derecha\n0,2,2\n120,-6,6\n"))

        val csv = vm.state.value.csv.shouldNotBeNull()
        csv.columns.roles shouldBe listOf(SuperelevationRole.CHAINAGE, SuperelevationRole.LEFT, SuperelevationRole.RIGHT)
        csv.rowCount shouldBe 2
        csv.errors shouldBe emptyList()
        vm.applyCsv()
        vm.state.value.csv.shouldBeNull()
        vm.state.value.superelevationDraft!!.map { it.chainage } shouldBe listOf("0", "120")
        vm.saveSuperelevation()
        runBlocking { withTimeout(10_000) { vm.state.first { it.superelevation.size == 2 && !it.saving } } }
        runBlocking { sections.superelevation(axis).first() }[1] shouldBe SuperelevationRow(120.0, -0.06, 0.06)
    }

    @Test
    fun `S050 RF-6 las columnas del CSV de peralte se asignan a mano y un papel no se repite`() {
        val (vm, _) = open()
        vm.startSuperelevation()
        vm.readCsv(AlignmentTestFiles.source("peralte.csv", "a,b,c\n0,2,5\n"))

        vm.setCsvRole(2, SuperelevationRole.LEFT)

        vm.state.value.csv!!.columns.roles shouldBe listOf(SuperelevationRole.CHAINAGE, SuperelevationRole.IGNORE, SuperelevationRole.LEFT)
        vm.state.value.csv!!.rowCount shouldBe 0 // ya no hay columna derecha
        vm.applyCsv()
        vm.state.value.csv.shouldNotBeNull()
    }

    @Test
    fun `S050 RF-6 un CSV de peralte con celdas que no son numeros no se aplica y da las lineas`() {
        val (vm, _) = open()
        vm.startSuperelevation()

        vm.readCsv(AlignmentTestFiles.source("peralte.csv", "PK,Izquierda,Derecha\n0,2,2\n10,x,6\n"))

        vm.state.value.csv!!.errors.single().line shouldBe 3
        vm.applyCsv()
        vm.state.value.csv.shouldNotBeNull()
    }

    @Test
    fun `S050 RF-6 un CSV vacio o con menos de tres columnas se indica como ilegible`() {
        val (vm, _) = open()
        vm.startSuperelevation()

        vm.readCsv(AlignmentTestFiles.source("peralte.csv", "PK,Izquierda\n0,2\n"))

        vm.state.value.csv!!.unreadable shouldBe true
    }
}
