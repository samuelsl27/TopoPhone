package es.topophone.app.ui.alignment

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.app.ui.points.ImportSource
import es.topophone.core.formats.alignment.ProfileCsvError
import es.topophone.core.formats.alignment.ProfileMappingProblem
import es.topophone.core.formats.alignment.ProfileRole
import es.topophone.core.model.alignment.ProfileIssue
import es.topophone.core.model.alignment.VerticalCurve
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

/** Asociar a un eje la rasante de un CSV: lectura, columnas, vista previa, errores y rechazos (S049/RF-9, RF-10). */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class ProfileCsvImportViewModelTest {

    private val db = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext<Context>(),
        TopoPhoneDatabase::class.java,
    ).build()
    private val projects = RoomProjectRepository(db)
    private val alignments = RoomAlignmentRepository(db)
    private val importer = AlignmentImporter(alignments)
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

    private fun importAxes(vararg indices: Int) = runBlocking {
        importer.importLandXml(
            projectId,
            ProjectCrs.ETRS89_UTM30,
            "ejes.xml",
            indices.toSet(),
            CadSourceCrs.Project,
            LinearUnit.METRE,
            { ByteArrayInputStream(AlignmentTestFiles.bytes("ejes.xml")) },
        )
    }

    private fun settle(vm: ProfileCsvImportViewModel) {
        runBlocking { withTimeout(10_000) { vm.state.first { it.stage != ProfileCsvStage.LOADING && !it.reading } } }
    }

    private fun ProfileCsvImportViewModel.attachAndWait() {
        attach()
        runBlocking {
            withTimeout(10_000) {
                state.first {
                    !it.working &&
                        (it.stage == ProfileCsvStage.DONE || it.rejected != null || it.writeError)
                }
            }
        }
    }

    private fun source(name: String, text: String): ImportSource = AlignmentTestFiles.source(name, text)

    private fun open(source: ImportSource): ProfileCsvImportViewModel {
        val vm = ProfileCsvImportViewModel(importer, alignments, projects, dispatcher)
        vm.start(projectId, source)
        settle(vm)
        return vm
    }

    private fun axisId(name: String) = runBlocking { alignments.summaries(projectId).first() }.first { it.name == name }.id

    @Test
    fun `S049 RF-9 sin ningun eje en el proyecto no se abre el asistente`() {
        val state = open(AlignmentTestFiles.source("rasante-b.csv")).state.value

        state.stage shouldBe ProfileCsvStage.PROBLEM
        state.problem shouldBe ProfileCsvProblem.NO_ALIGNMENT
    }

    @Test
    fun `S049 RF-9 el CSV de ejemplo se lee con sus opciones y sus columnas propuestas`() {
        importAxes(0, 1)

        val state = open(AlignmentTestFiles.source("rasante-b.csv")).state.value

        state.stage shouldBe ProfileCsvStage.CONFIG
        state.encoding shouldBe ImportEncoding.UTF8
        state.separator shouldBe ImportSeparator.SEMICOLON
        state.decimal shouldBe ImportDecimal.COMMA
        state.hasHeader shouldBe true
        state.columns.roles shouldBe listOf(ProfileRole.CHAINAGE, ProfileRole.ELEVATION, ProfileRole.KV)
        state.rowCount shouldBe 3
        state.preview.map { it.chainage } shouldBe listOf(1000.5, 1200.0, 1380.5)
        state.errors shouldBe emptyList()
        state.alignments.map { it.name } shouldBe listOf("Eje A", "Eje B")
        state.selectedAlignment shouldBe runBlocking { alignments.activeAlignmentId(projectId).first() }
    }

    @Test
    fun `S049 RF-9 la rasante se asocia al eje elegido con el Kv convertido a parabola`() {
        importAxes(0, 1)
        val vm = open(AlignmentTestFiles.source("rasante-b.csv"))
        val b = axisId("Eje B")
        vm.selectAlignment(b)

        vm.state.value.canAttach shouldBe true
        vm.attachAndWait()

        vm.state.value.stage shouldBe ProfileCsvStage.DONE
        vm.state.value.attachedTo shouldBe "Eje B"
        val profile = runBlocking { alignments.alignment(b) }!!.profile.shouldNotBeNull()
        profile.vertices.map { it.chainage } shouldBe listOf(1000.5, 1200.0, 1380.5)
        // Kv = 2000: Δi = −3/180,5 − 2/199,5 ≈ −0,026644 y la parábola mide Kv·|Δi|.
        (profile.vertices[1].curve as VerticalCurve.Parabola).length shouldBe (53.2911225432001 plusOrMinus 1e-9)
    }

    @Test
    fun `S049 RF-10 una rasante fuera del PK del eje no se asocia y dice el vertice`() {
        importAxes(0, 1)
        val vm = open(AlignmentTestFiles.source("rasante-b.csv"))
        vm.selectAlignment(axisId("Eje A")) // el eje A va del PK 0 al 420

        vm.attachAndWait()

        vm.state.value.stage shouldBe ProfileCsvStage.CONFIG
        vm.state.value.rejected shouldBe ProfileIssue.OutsideAxis(1, 1000.5)
        runBlocking { alignments.alignment(axisId("Eje A")) }!!.profile.shouldNotBeNull().vertices.size shouldBe 4
    }

    @Test
    fun `S049 RF-9 una celda que no es un numero da su linea y su papel y no deja asociar`() {
        importAxes(0)
        val vm = open(source("r.csv", "PK;Cota;Kv\n0;100;\n150;abc;3000\n420;102;"))

        val state = vm.state.value

        state.errors shouldBe listOf(ProfileCsvError(3, ProfileRole.ELEVATION, ProfileCsvError.Reason.NOT_A_NUMBER))
        state.canAttach shouldBe false
        state.rowCount shouldBe 2
    }

    @Test
    fun `S049 RF-9 cambiar el signo decimal vuelve a leer el fichero`() {
        importAxes(0)
        val vm = open(source("r.csv", "PK;Cota\n0;100,5\n420;102,5"))
        vm.state.value.decimal shouldBe ImportDecimal.COMMA

        vm.setDecimal(ImportDecimal.POINT)

        vm.state.value.errors.size shouldBe 2
        vm.state.value.canAttach shouldBe false
        vm.setDecimal(ImportDecimal.COMMA)
        vm.state.value.canAttach shouldBe true
    }

    @Test
    fun `S049 RF-9 el separador y el decimal no pueden ser los dos la coma`() {
        importAxes(0)
        val vm = open(source("r.csv", "PK;Cota\n0;100,5\n420;102,5"))

        vm.setSeparator(ImportSeparator.COMMA)
        vm.setDecimal(ImportDecimal.COMMA)

        vm.state.value.decimalConflict shouldBe true
        vm.state.value.canAttach shouldBe false
    }

    @Test
    fun `S049 RF-9 las columnas se reasignan sin repetir un papel y sin PK no se asocia`() {
        importAxes(0)
        val vm = open(source("r.csv", "a;b;c\n0;100;3000\n420;102;"))
        vm.state.value.columns.roles shouldBe listOf(ProfileRole.CHAINAGE, ProfileRole.ELEVATION, ProfileRole.KV)

        vm.setRole(2, ProfileRole.CHAINAGE) // el PK pasa a la tercera columna y la primera lo pierde
        vm.state.value.columns.roles shouldBe listOf(ProfileRole.IGNORE, ProfileRole.ELEVATION, ProfileRole.CHAINAGE)
        vm.setRole(2, ProfileRole.IGNORE)

        vm.state.value.columns.problem shouldBe ProfileMappingProblem.MISSING_CHAINAGE
        vm.state.value.canAttach shouldBe false
        vm.state.value.preview shouldBe emptyList()
    }

    @Test
    fun `S049 RF-9 sin cabecera las dos primeras filas son datos y el fichero Windows-1252 se lee`() {
        importAxes(0)
        val vm = open(source("r.csv", "0;100,25\n420;102,5"))

        vm.state.value.hasHeader shouldBe false
        vm.state.value.rowCount shouldBe 2
        vm.setHasHeader(true) // la primera fila se descarta: queda un solo vértice
        vm.state.value.rowCount shouldBe 1
        vm.state.value.canAttach shouldBe false
        vm.setEncoding(ImportEncoding.WINDOWS_1252)
        vm.state.value.encoding shouldBe ImportEncoding.WINDOWS_1252
    }

    @Test
    fun `S049 RF-9 los PK y las cotas se leen en la unidad del proyecto`() {
        importAxes(0)
        val feet = LinearUnit.INTERNATIONAL_FOOT
        runBlocking { projects.update(projectId, projects.project(projectId)!!.toDraft().copy(linearUnit = feet)) }
        val vm = open(source("r.csv", "PK;Cota\n0;100\n1000;110"))

        vm.state.value.projectUnit shouldBe feet
        vm.attachAndWait()

        // 1000 ft son 304,8 m: dentro del eje A, que mide 420 m.
        vm.state.value.stage shouldBe ProfileCsvStage.DONE
        val chainages = runBlocking { alignments.alignment(axisId("Eje A")) }!!.profile!!.vertices.map { it.chainage }
        chainages[0] shouldBe 0.0
        chainages[1] shouldBe (304.8 plusOrMinus 1e-9)
    }

    @Test
    fun `S049 RF-9 un fichero vacio no de texto o enorme se rechaza con su motivo`() {
        importAxes(0)

        open(ImportSource("v.csv", 0) { ByteArrayInputStream(ByteArray(0)) }).state.value.problem shouldBe ProfileCsvProblem.EMPTY
        open(ImportSource("b.csv", 10) { ByteArrayInputStream(byteArrayOf(0, 1, 2, 0)) }).state.value.problem shouldBe
            ProfileCsvProblem.NOT_TEXT
        open(ImportSource("g.csv", 25L * 1024 * 1024) { ByteArrayInputStream(ByteArray(0)) }).state.value.problem shouldBe
            ProfileCsvProblem.TOO_BIG
        open(source("u.csv", "solo\nuna\ncolumna")).state.value.problem shouldBe ProfileCsvProblem.NO_COLUMNS
    }

    @Test
    fun `S049 RF-9 los ejes que ya tienen rasante se marcan para sustituirla`() {
        importAxes(0, 1)

        val state = open(AlignmentTestFiles.source("rasante-b.csv")).state.value

        state.alignments.all { it.hasProfile } shouldBe true // los dos ejes de ejemplo traen rasante: se sustituiría
        state.rejected.shouldBeNull()
    }
}
