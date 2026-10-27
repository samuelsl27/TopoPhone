package es.topophone.app.ui.alignment

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.app.ui.points.ImportSource
import es.topophone.core.model.alignment.AlignmentIssue
import es.topophone.core.model.cad.CadSourceCrs
import es.topophone.core.model.project.GeodeticDatum
import es.topophone.core.model.project.LinearUnit
import es.topophone.core.model.project.ProjectCrs
import es.topophone.core.model.project.ProjectDefaults
import es.topophone.data.alignment.AlignmentImportFailure
import es.topophone.data.alignment.AlignmentImporter
import es.topophone.data.alignment.RoomAlignmentRepository
import es.topophone.data.db.TopoPhoneDatabase
import es.topophone.data.project.RoomProjectRepository
import es.topophone.data.project.SaveResult
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
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

/** Importar los ejes de un LandXML: reconocer el fichero, listar las alineaciones, marcar y importar (S049/RF-1 a RF-5, RF-11, RF-13). */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class AlignmentImportViewModelTest {

    private val db = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext<Context>(),
        TopoPhoneDatabase::class.java,
    ).build()
    private val projects = RoomProjectRepository(db)
    private val alignments = RoomAlignmentRepository(db)
    private val dispatcher = UnconfinedTestDispatcher()
    private val projectId = newProject(ProjectCrs.ETRS89_UTM30)
    private var projectCount = 0

    private fun newProject(crs: ProjectCrs, unit: LinearUnit = LinearUnit.METRE): String = runBlocking {
        val name = "Proyecto ${crs.name} ${projectCount++}"
        projects.create(ProjectDefaults().toDraft().copy(name = name, crs = crs, linearUnit = unit))
            .shouldBeInstanceOf<SaveResult.Saved>().id
    }

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        db.close()
    }

    private fun open(source: ImportSource, project: String = projectId): AlignmentImportViewModel {
        val vm = AlignmentImportViewModel(AlignmentImporter(alignments), projects, dispatcher)
        vm.start(project, source)
        vm.settled()
        return vm
    }

    private fun open(name: String, project: String = projectId) = open(AlignmentTestFiles.source(name), project)

    private fun AlignmentImportViewModel.importAndWait(): AlignmentImportUiState = runBlocking {
        import()
        withTimeout(10_000) { state.first { it.stage == AlignmentImportStage.RESULT || it.failure != null || it.cancelled } }
    }

    @Test
    fun `S049 RF-3 un fichero que no es LandXML se rechaza con su motivo`() {
        val html = open(AlignmentTestFiles.source("pagina.xml", "<html><body/></html>")).state.value
        val text = open("no-es-landxml.txt").state.value
        val cut = open("mal-formado.xml").state.value

        html.stage shouldBe AlignmentImportStage.PROBLEM
        html.fileProblem shouldBe AlignmentFileProblem.NOT_LANDXML
        text.fileProblem shouldBe AlignmentFileProblem.NOT_XML
        cut.fileProblem shouldBe AlignmentFileProblem.NOT_XML
    }

    @Test
    fun `S049 RF-3 una version distinta de 1_0 a 1_2 se rechaza diciendo la version`() {
        val state = open(AlignmentTestFiles.source("v2.xml", "<LandXML version=\"2.0\"/>")).state.value

        state.fileProblem shouldBe AlignmentFileProblem.UNSUPPORTED_VERSION
        state.version shouldBe "2.0"
    }

    @Test
    fun `S049 RF-3 un LandXML sin alineaciones no abre la lista e indica el motivo`() {
        val state = open("sin-alineaciones.xml").state.value

        state.stage shouldBe AlignmentImportStage.PROBLEM
        state.fileProblem shouldBe AlignmentFileProblem.NO_ALIGNMENT
        state.alignments shouldBe emptyList()
    }

    @Test
    fun `S049 RF-3 un fichero de mas de 100 MB declarados no se abre y uno vacio tampoco`() {
        val big = open(AlignmentTestFiles.source("ejes.xml", declaredSize = 100L * 1024 * 1024 + 1)).state.value
        val empty = open(AlignmentTestFiles.source("ejes.xml", declaredSize = 0)).state.value

        big.fileProblem shouldBe AlignmentFileProblem.TOO_BIG
        empty.fileProblem shouldBe AlignmentFileProblem.EMPTY
    }

    @Test
    fun `S049 RF-3 un fichero que al leerse pasa del limite se rechaza aunque no declarase su tamano`() {
        val source = AlignmentTestFiles.source("ejes.xml", declaredSize = null)
        val vm = AlignmentImportViewModel(AlignmentImporter(alignments), projects, dispatcher).apply { maxBytes = 100 }
        vm.start(projectId, source)

        vm.settled().fileProblem shouldBe AlignmentFileProblem.TOO_BIG
    }

    @Test
    fun `S049 RF-2 la lista trae cada alineacion con su longitud su PK inicial y si lleva rasante`() {
        val state = open("ejes.xml").state.value

        state.stage shouldBe AlignmentImportStage.CONFIG
        state.alignments.map { it.name } shouldBe listOf("Eje A", "Eje B", "Eje C")
        state.alignments.map { it.length } shouldBe listOf(420.0, 380.0, 200.0)
        state.alignments.map { it.startStation } shouldBe listOf(0.0, 1000.5, 0.0)
        state.alignments.map { it.hasProfile } shouldBe listOf(true, true, false)
        state.declaredUnit shouldBe LinearUnit.METRE
        state.projectName.isNotEmpty() shouldBe true
    }

    @Test
    fun `S049 RF-2 por defecto se marcan todas y se puede desmarcar y volver a marcar`() {
        val vm = open("ejes.xml")
        vm.state.value.selected shouldBe setOf(0, 1, 2)
        vm.state.value.canImport shouldBe true

        vm.toggle(1)
        vm.state.value.selected shouldBe setOf(0, 2)
        vm.toggle(0)
        vm.toggle(2)
        vm.state.value.canImport shouldBe false
        vm.toggle(2)
        vm.state.value.selected shouldBe setOf(2)
        vm.toggle(7) // una alineación que no existe no cambia nada
        vm.state.value.selected shouldBe setOf(2)
    }

    @Test
    fun `S049 RF-11 la unidad declarada por el fichero se propone y se puede cambiar`() {
        val vm = open("eje-a-pies.xml")

        vm.state.value.unit shouldBe LinearUnit.INTERNATIONAL_FOOT
        vm.setUnit(LinearUnit.US_SURVEY_FOOT)
        vm.state.value.unit shouldBe LinearUnit.US_SURVEY_FOOT
    }

    @Test
    fun `S049 RF-11 el sistema se propone del proyecto y se puede elegir otro UTM`() {
        val vm = open("ejes.xml")

        vm.state.value.source shouldBe CadSourceCrs.Project
        vm.setSource(CadSourceCrs.Utm(GeodeticDatum.ETRS89, 31))
        vm.state.value.source shouldBe CadSourceCrs.Utm(GeodeticDatum.ETRS89, 31)
        vm.state.value.availableSources.all { it.isUtm } shouldBe true
    }

    @Test
    fun `S049 RF-13 importar guarda los ejes marcados y deja el resumen`() {
        val vm = open("ejes.xml")
        vm.toggle(2)

        val done = vm.importAndWait()

        done.stage shouldBe AlignmentImportStage.RESULT
        val summary = done.summary.shouldNotBeNull()
        summary.imported.map { it.name } shouldBe listOf("Eje A", "Eje B")
        summary.ignored shouldBe emptyList()
        summary.activatedId.shouldNotBeNull()
        runBlocking { alignments.summaries(projectId).first() }.map { it.name } shouldBe listOf("Eje A", "Eje B")
    }

    @Test
    fun `S049 RF-5 el resumen cuenta lo ignorado con su motivo`() {
        val done = open("eje-hueco.xml").importAndWait()

        done.stage shouldBe AlignmentImportStage.RESULT
        done.summary!!.imported shouldBe emptyList()
        done.summary!!.ignored.single().issue.shouldBeInstanceOf<AlignmentIssue.Gap>()
        runBlocking { alignments.summaries(projectId).first() } shouldBe emptyList()
    }

    @Test
    fun `S049 RF-13 un fallo de escritura vuelve a la configuracion sin guardar nada`() {
        val failing = object : es.topophone.data.alignment.AlignmentRepository by alignments {
            override suspend fun add(
                projectId: String,
                items: List<es.topophone.data.alignment.AlignmentToSave>,
                importedAtMs: Long,
            ): es.topophone.data.alignment.AlignmentsAdded = error("disco lleno")
        }
        val vm = AlignmentImportViewModel(AlignmentImporter(failing), projects, dispatcher)
        vm.start(projectId, AlignmentTestFiles.source("ejes.xml"))
        vm.settled()

        val state = vm.importAndWait()

        state.stage shouldBe AlignmentImportStage.CONFIG
        state.failure shouldBe AlignmentImportFailure.WRITE_ERROR
        state.summary.shouldBeNull()
        runBlocking { alignments.summaries(projectId).first() } shouldBe emptyList()
    }

    @Test
    fun `S049 RF-13 sin nada marcado no se importa`() {
        val vm = open("ejes.xml")
        listOf(0, 1, 2).forEach(vm::toggle)

        vm.import()

        vm.state.value.stage shouldBe AlignmentImportStage.CONFIG
    }
}
