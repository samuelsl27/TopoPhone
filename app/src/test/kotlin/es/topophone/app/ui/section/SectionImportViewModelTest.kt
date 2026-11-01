package es.topophone.app.ui.section

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.app.ui.alignment.AlignmentTestFiles
import es.topophone.app.ui.points.ImportSource
import es.topophone.core.formats.section.SectionMappingProblem
import es.topophone.core.formats.section.SectionRole
import es.topophone.core.model.alignment.section.HeightKind
import es.topophone.core.model.alignment.section.SectionIssue
import es.topophone.core.model.alignment.section.SectionKind
import es.topophone.core.model.cad.CadSourceCrs
import es.topophone.core.model.point.ImportDecimal
import es.topophone.core.model.point.ImportEncoding
import es.topophone.core.model.point.ImportSeparator
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

/** Importar las secciones de un CSV o de un LandXML a un eje, con sus rechazos (S050/RF-4, RF-5, RF-7). */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class SectionImportViewModelTest {

    private val db = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext<Context>(),
        TopoPhoneDatabase::class.java,
    ).build()
    private val projects = RoomProjectRepository(db)
    private val alignments = RoomAlignmentRepository(db)
    private val sections = RoomSectionRepository(db)
    private val dispatcher = UnconfinedTestDispatcher()
    private val projectId = runBlocking {
        projects.create(ProjectDefaults().toDraft().copy(name = "Obra")).shouldBeInstanceOf<SaveResult.Saved>().id
    }

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        db.close()
    }

    /** El eje C de los ejemplos (PK de 0 a 200), el único del proyecto. */
    private val axisC: String by lazy {
        runBlocking {
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
    }

    private fun settle(vm: SectionImportViewModel) {
        runBlocking { withTimeout(10_000) { vm.state.first { it.stage != SectionImportStage.LOADING && !it.reading } } }
    }

    private fun open(kind: SectionImportKind, source: ImportSource, alignment: String = axisC): SectionImportViewModel {
        val vm = SectionImportViewModel(sections, alignments, projects, dispatcher)
        vm.start(projectId, alignment, kind, source)
        settle(vm)
        return vm
    }

    private fun SectionImportViewModel.importAndWait() {
        import()
        runBlocking { withTimeout(10_000) { state.first { !it.working && (it.stage == SectionImportStage.DONE || it.writeError) } } }
    }

    private fun csv(text: String) = AlignmentTestFiles.source("secciones.csv", text)

    private fun saved() = runBlocking { sections.sections(axisC).first() }

    @Test
    fun `S050 RF-4 un eje que no existe no abre el asistente`() {
        axisC
        val state = open(SectionImportKind.CSV, AlignmentTestFiles.source("secciones/secciones-eje-c.csv"), alignment = "otro").state.value

        state.stage shouldBe SectionImportStage.PROBLEM
        state.problem shouldBe SectionImportProblem.NO_ALIGNMENT
    }

    @Test
    fun `S050 RF-4 el CSV de ejemplo se lee con sus opciones y sus columnas propuestas`() {
        val state = open(SectionImportKind.CSV, AlignmentTestFiles.source("secciones/secciones-eje-c.csv")).state.value

        state.stage shouldBe SectionImportStage.CONFIG
        state.separator shouldBe ImportSeparator.COMMA
        state.decimal shouldBe ImportDecimal.POINT
        state.encoding shouldBe ImportEncoding.UTF8
        state.hasHeader shouldBe true
        state.columns.roles shouldBe
            listOf(SectionRole.CHAINAGE, SectionRole.CODE, SectionRole.OFFSET, SectionRole.HEIGHT, SectionRole.KIND)
        state.rowCount shouldBe 20
        state.preview.size shouldBe 8
        state.unit shouldBe LinearUnit.METRE
        state.heightKind shouldBe HeightKind.ABSOLUTE
        state.canImport shouldBe true
    }

    @Test
    fun `S050 RF-4 importar el CSV guarda cuatro secciones de diseno y un perfil de terreno`() {
        val vm = open(SectionImportKind.CSV, AlignmentTestFiles.source("secciones/secciones-eje-c.csv"))

        vm.importAndWait()

        vm.state.value.savedCount shouldBe 5
        vm.state.value.rejected shouldBe emptyList()
        saved().map { it.section.chainage to it.section.kind } shouldBe listOf(
            0.0 to SectionKind.DESIGN,
            50.0 to SectionKind.GROUND,
            100.0 to SectionKind.DESIGN,
            150.0 to SectionKind.DESIGN,
            190.0 to SectionKind.DESIGN,
        )
        saved().first().section.heightKind shouldBe HeightKind.ABSOLUTE
    }

    @Test
    fun `S050 RF-4 la cota puede ser una diferencia con la rasante`() {
        val vm = open(SectionImportKind.CSV, csv("pk,codigo,desplazamiento,cota\n10,EJE,0,0\n10,BD,3.5,-0.07\n"))

        vm.setHeightKind(HeightKind.RELATIVE)
        vm.importAndWait()

        saved().single().section.heightKind shouldBe HeightKind.RELATIVE
    }

    @Test
    fun `S050 RF-7 las secciones que no valen quedan en el resumen con su PK y el motivo y las demas se guardan`() {
        val text = "pk,codigo,desplazamiento,cota\n10,EJE,0,5\n10,BD,3,5\n500,EJE,0,5\n20,A,0,1\n20,A,2,1\n30,B,1,1\n30,C,1,1\n"
        val vm = open(SectionImportKind.CSV, csv(text))

        vm.importAndWait()

        val state = vm.state.value
        state.savedCount shouldBe 1
        state.rejected.map { it.chainageM to it.issue } shouldBe listOf(
            20.0 to SectionIssue.RepeatedCode("A"),
            30.0 to SectionIssue.Unordered("C"),
            500.0 to SectionIssue.ChainageOutsideAxis(500.0),
        ).sortedBy { it.first }
        saved().single().section.chainage shouldBe 10.0
    }

    @Test
    fun `S050 RF-4 sin ninguna seccion valida no se guarda nada y el resumen lo dice`() {
        val vm = open(SectionImportKind.CSV, csv("pk,codigo,desplazamiento,cota\n999,EJE,0,5\n"))

        vm.importAndWait()

        vm.state.value.savedCount shouldBe 0
        vm.state.value.rejected.size shouldBe 1
        saved() shouldBe emptyList()
    }

    @Test
    fun `S050 RF-4 la unidad del fichero se convierte a metros`() {
        val vm = open(SectionImportKind.CSV, csv("pk,codigo,desplazamiento,cota\n100,EJE,0,10\n100,BD,10,5\n"))

        vm.setUnit(LinearUnit.INTERNATIONAL_FOOT)
        vm.importAndWait()

        val section = saved().single().section
        section.chainage shouldBe (30.48 plusOrMinus 1e-9)
        section.points.last().offsetM shouldBe (3.048 plusOrMinus 1e-9)
    }

    @Test
    fun `S050 RF-4 el CSV sin codigo da P1 P2 por desplazamiento`() {
        val vm = open(SectionImportKind.CSV, csv("pk,desplazamiento,cota\n10,3,1\n10,-3,1\n10,0,2\n"))

        vm.state.value.columns.roles shouldBe listOf(SectionRole.CHAINAGE, SectionRole.OFFSET, SectionRole.HEIGHT)
        vm.importAndWait()

        saved().single().section.points.map { it.code } shouldBe listOf("P1", "P2", "P3")
    }

    @Test
    fun `S050 RF-4 sin cota asignada no se puede importar y se dice que falta`() {
        val vm = open(SectionImportKind.CSV, csv("pk,codigo,desplazamiento,cota\n10,EJE,0,5\n"))

        vm.setRole(3, SectionRole.IGNORE)
        settle(vm)

        vm.state.value.columns.problem shouldBe SectionMappingProblem.MISSING_HEIGHT
        vm.state.value.canImport shouldBe false
    }

    @Test
    fun `S050 RF-4 una celda que no es numero impide importar y da la linea`() {
        val vm = open(SectionImportKind.CSV, csv("pk,codigo,desplazamiento,cota\n10,EJE,0,5\n20,EJE,x,5\n"))

        vm.state.value.errors.single().line shouldBe 3
        vm.state.value.canImport shouldBe false
    }

    @Test
    fun `S050 RF-4 un fichero binario o vacio no abre el asistente`() {
        open(SectionImportKind.CSV, ImportSource("a.csv", 4) { ByteArrayInputStream(byteArrayOf(0, 1, 2, 0)) }).state.value.problem shouldBe
            SectionImportProblem.NOT_TEXT
        open(SectionImportKind.CSV, csv("")).state.value.problem shouldBe SectionImportProblem.EMPTY
    }

    @Test
    fun `S050 RF-5 el LandXML lista sus secciones por PK con diseno y terreno y las marca todas`() {
        val state = open(SectionImportKind.LANDXML, AlignmentTestFiles.source("secciones/secciones-eje-c.xml")).state.value

        state.stage shouldBe SectionImportStage.CONFIG
        state.sections.map { it.station } shouldBe listOf(0.0, 100.0, 150.0, 190.0, 50.0)
        state.selected.size shouldBe 5
        state.declaredUnit shouldBe LinearUnit.METRE
        state.canImport shouldBe true
    }

    @Test
    fun `S050 RF-5 se pueden elegir solo las de diseno o solo las de terreno`() {
        val vm = open(SectionImportKind.LANDXML, AlignmentTestFiles.source("secciones/secciones-eje-c.xml"))

        vm.selectKind(SectionKind.GROUND, false)
        vm.importAndWait()

        vm.state.value.savedCount shouldBe 4
        saved().all { it.section.kind == SectionKind.DESIGN } shouldBe true
    }

    @Test
    fun `S050 RF-5 los puntos sin nombre del LandXML se guardan como P1 P2 P3`() {
        val vm = open(SectionImportKind.LANDXML, AlignmentTestFiles.source("secciones/secciones-eje-c.xml"))

        vm.importAndWait()

        saved().first { it.section.chainage == 150.0 }.section.points.map { it.code } shouldBe listOf("P1", "P2", "P3")
        saved().first { it.section.chainage == 0.0 }.section.points.map { it.code } shouldBe listOf("CI", "BI", "EJE", "BD", "CD")
    }

    @Test
    fun `S050 RF-5 sin ninguna seccion marcada no se puede importar`() {
        val vm = open(SectionImportKind.LANDXML, AlignmentTestFiles.source("secciones/secciones-eje-c.xml"))

        vm.state.value.sections.forEach { vm.toggle(it.index) }

        vm.state.value.canImport shouldBe false
    }

    @Test
    fun `S050 RF-5 un fichero que no es LandXML o sin secciones o de otra version se rechaza`() {
        open(SectionImportKind.LANDXML, AlignmentTestFiles.source("a.xml", "<otro/>")).state.value.problem shouldBe
            SectionImportProblem.NOT_LANDXML
        open(SectionImportKind.LANDXML, AlignmentTestFiles.source("ejes.xml")).state.value.problem shouldBe SectionImportProblem.NO_SECTIONS
        val v2 = open(
            SectionImportKind.LANDXML,
            AlignmentTestFiles.source("a.xml", "<LandXML version=\"2.0\"><CrossSects/></LandXML>"),
        ).state.value
        v2.problem shouldBe SectionImportProblem.UNSUPPORTED_VERSION
        v2.version shouldBe "2.0"
        open(
            SectionImportKind.LANDXML,
            AlignmentTestFiles.source("a.xml", "<LandXML version=\"1.2\"><CrossSects"),
        ).state.value.problem shouldBe
            SectionImportProblem.NOT_XML
    }

    @Test
    fun `S050 RF-5 un LandXML de mas del maximo se rechaza sin abrir la lista`() {
        val vm = SectionImportViewModel(sections, alignments, projects, dispatcher).also { it.maxBytes = 100 }
        vm.start(projectId, axisC, SectionImportKind.LANDXML, AlignmentTestFiles.source("secciones/secciones-eje-c.xml"))
        settle(vm)

        vm.state.value.problem shouldBe SectionImportProblem.TOO_BIG
    }
}
