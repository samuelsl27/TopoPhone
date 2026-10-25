package es.topophone.app.ui.points

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.app.log.RecordingLog
import es.topophone.app.ui.map.FakePointRepository
import es.topophone.core.model.point.ColumnMapping
import es.topophone.core.model.point.ColumnRole
import es.topophone.core.model.point.ImportDecimal
import es.topophone.core.model.point.ImportEncoding
import es.topophone.core.model.point.ImportErrorReason
import es.topophone.core.model.point.ImportRowError
import es.topophone.core.model.point.ImportRowStatus
import es.topophone.core.model.point.ImportSeparator
import es.topophone.core.model.project.LinearUnit
import es.topophone.core.model.project.ProjectCrs
import es.topophone.core.model.project.ProjectDefaults
import es.topophone.data.db.TopoPhoneDatabase
import es.topophone.data.project.RoomProjectRepository
import es.topophone.data.project.SaveResult
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Importar CSV o TXT, parte 1: leer el fichero, detectar sus opciones y mostrar la vista previa (S028/RF-2 a RF-9, RF-21). */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class PointImportViewModelTest {

    private val db = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext<Context>(),
        TopoPhoneDatabase::class.java,
    ).build()
    private val projects = RoomProjectRepository(db)
    private val projectId = newProject(ProjectCrs.ETRS89_UTM30)
    private val repository = FakePointRepository()
    private val log = RecordingLog()
    private val dispatcher = UnconfinedTestDispatcher()

    private fun newProject(crs: ProjectCrs, unit: LinearUnit = LinearUnit.METRE): String = runBlocking {
        projects.create(ProjectDefaults().toDraft().copy(name = "Proyecto ${crs.name} ${unit.name}", crs = crs, linearUnit = unit))
            .shouldBeInstanceOf<SaveResult.Saved>().id
    }

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        db.close()
    }

    private fun viewModel(source: ImportSource, project: String = projectId) =
        PointImportViewModel(repository, projects, dispatcher, log).also {
            it.debounceMs = 0
            it.start(project, source)
        }

    private fun open(name: String, project: String = projectId) = viewModel(ImportTestFiles.fixture(name).asSource(), project).settled()

    @Test
    fun `S028 RF-4 y RF-5 un CSV UTF-8 con BOM y coma se lee con sus opciones detectadas`() {
        val state = open("etrs89-utm30-utf8-bom-coma.csv")

        state.stage shouldBe ImportStage.WIZARD
        state.fileProblem.shouldBeNull()
        state.fileName shouldBe "etrs89-utm30-utf8-bom-coma.csv"
        state.options.encoding shouldBe ImportEncoding.UTF8
        state.options.separator shouldBe ImportSeparator.COMMA
        state.options.decimal shouldBe ImportDecimal.POINT
        state.options.hasHeader shouldBe true
        state.columnCount shouldBe 5
        state.headerCells shouldBe listOf("Name", "Easting", "Northing", "Elevation", "Description")
    }

    @Test
    fun `S028 RF-4 RF-5 y RF-7 un TXT de Windows-1252 con punto y coma y coma decimal se lee entero`() {
        val state = open("etrs89-utm30-windows1252-puntoycoma.txt")

        state.options.encoding shouldBe ImportEncoding.WINDOWS_1252
        state.options.separator shouldBe ImportSeparator.SEMICOLON
        state.options.decimal shouldBe ImportDecimal.COMMA
        state.options.hasHeader shouldBe true
        state.preview.map { it.name } shouldBe listOf("1", "2", "3", "4", "5", "6")
        state.preview[2].description shouldBe "Árbol ñandú"
        state.counts.valid shouldBe 6
        state.errors shouldBe emptyList()
    }

    @Test
    fun `S028 RF-5 y RF-9 un TXT con tabulador y sin cabecera empieza en su primera fila`() {
        val state = open("etrs89-utm30-tabulador-sin-cabecera.txt")

        state.options.separator shouldBe ImportSeparator.TAB
        state.options.hasHeader shouldBe false
        state.preview.first().line shouldBe 1
        state.preview.first().name shouldBe "1"
        state.counts.rows shouldBe 6
        state.counts.valid shouldBe 6
    }

    @Test
    fun `S028 RF-5 un TXT alineado con espacios se separa por los espacios`() {
        val state = open("etrs89-utm30-espacios-pnezd.txt")

        state.options.separator shouldBe ImportSeparator.SPACES
        state.options.hasHeader shouldBe false
        state.columnCount shouldBe 5
        state.preview.first().name shouldBe "1"
    }

    @Test
    fun `S028 RF-6 los textos entre comillas con saltos de linea dentro se leen como una sola fila`() {
        val state = open("etrs89-utm30-comillas.csv")

        state.options.hasHeader shouldBe true
        state.counts.rows shouldBe 6
        state.preview.map { it.line } shouldBe listOf(2, 3, 5, 6, 7, 8)
        state.preview[1].description shouldBe "Pozo,\nregistro"
        state.counts.valid shouldBe 6
    }

    @Test
    fun `S028 RF-10 el CSV que escribe TopoPhone con todas las columnas se reconoce por su cabecera`() {
        val source = ImportSource("todas.csv", null) { ImportTestFiles.s012("todas_coma_punto.csv").inputStream() }

        val state = viewModel(source).settled()

        state.options.hasHeader shouldBe true
        state.options.mapping.roleOf(0) shouldBe ColumnRole.NAME
        state.options.mapping.problem.shouldBeNull()
        state.columnCount shouldBe state.headerCells.size
    }

    @Test
    fun `S028 RF-21 los recuentos y los errores salen del analisis del fichero entero con su linea y su motivo`() {
        val state = open("etrs89-utm30-errores.csv")

        state.options.separator shouldBe ImportSeparator.SEMICOLON
        state.options.decimal shouldBe ImportDecimal.COMMA
        state.counts.rows shouldBe 9
        state.counts.valid shouldBe 3
        state.counts.skipped shouldBe 1
        state.counts.errors shouldBe 5
        state.counts.isConsistent shouldBe true
        state.errors shouldBe listOf(
            ImportRowError(3, ImportErrorReason.MISSING_NAME),
            ImportRowError(4, ImportErrorReason.NOT_A_NUMBER),
            ImportRowError(5, ImportErrorReason.MISSING_COLUMNS),
            ImportRowError(6, ImportErrorReason.OUTSIDE_CRS_AREA),
            ImportRowError(10, ImportErrorReason.NOT_A_NUMBER),
        )
        state.preview.map { it.status }.take(3) shouldBe listOf(ImportRowStatus.VALID, ImportRowStatus.ERROR, ImportRowStatus.ERROR)
        state.analyzing shouldBe false
    }

    @Test
    fun `S028 RF-17 los nombres que ya estan en el proyecto cuentan como repetidos`() {
        repository.points.value = listOf(PointFixtures.manual("1", projectId = projectId), PointFixtures.manual("3", projectId = projectId))

        val state = open("etrs89-utm30-errores.csv")

        state.counts.valid shouldBe 1
        state.counts.skipped shouldBe 3
        state.preview.first().status shouldBe ImportRowStatus.REPEATED_SKIPPED
    }

    @Test
    fun `S028 RF-30 ver la vista previa no crea ningun punto`() {
        open("etrs89-utm30-utf8-bom-coma.csv")

        repository.points.value shouldBe emptyList()
    }

    @Test
    fun `S028 RF-14 la unidad propuesta es la del proyecto`() {
        val feet = newProject(ProjectCrs.ETRS89_UTM30, LinearUnit.INTERNATIONAL_FOOT)

        open("etrs89-utm30-pies.csv", feet).options.unit shouldBe LinearUnit.INTERNATIONAL_FOOT
        open("etrs89-utm30-utf8-bom-coma.csv").options.unit shouldBe LinearUnit.METRE
    }

    @Test
    fun `S028 RF-13 un proyecto en ED50 lo dice para que no se ofrezca la altura elipsoidal`() {
        val ed50 = newProject(ProjectCrs.ED50_UTM30)

        open("ed50-utm30.csv", ed50).ed50 shouldBe true
        open("ed50-utm30.csv").ed50 shouldBe false
    }

    @Test
    fun `S028 RF-2 un fichero vacio no abre el asistente`() {
        val state = viewModel(ImportTestFiles.Source("vacio.csv", ByteArray(0)).asSource()).settled()

        state.stage shouldBe ImportStage.PROBLEM
        state.fileProblem shouldBe ImportFileProblem.EMPTY
    }

    @Test
    fun `S028 RF-2 un fichero que no es texto no abre el asistente`() {
        val png = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0x0D)

        val state = viewModel(ImportTestFiles.Source("foto.png", png).asSource()).settled()

        state.stage shouldBe ImportStage.PROBLEM
        state.fileProblem shouldBe ImportFileProblem.NOT_TEXT
    }

    @Test
    fun `S028 RF-2 un texto sin ninguna fila con dos columnas no abre el asistente`() {
        val state = viewModel(ImportTestFiles.of("notas.txt", "uno\ndos\ntres\n").asSource()).settled()

        state.stage shouldBe ImportStage.PROBLEM
        state.fileProblem shouldBe ImportFileProblem.NO_COLUMNS
    }

    @Test
    fun `S028 RF-2 un fichero que no se puede abrir no abre el asistente`() {
        viewModel(ImportSource("x.csv", 10) { null }).settled().fileProblem shouldBe ImportFileProblem.UNREADABLE
        viewModel(ImportSource("x.csv", 10) { throw IOException("sin permiso") }).settled().fileProblem shouldBe
            ImportFileProblem.UNREADABLE
        viewModel(ImportSource("x.csv", 10) { throw SecurityException("sin permiso") }).settled().fileProblem shouldBe
            ImportFileProblem.UNREADABLE
    }

    @Test
    fun `S028 RF-2 un proyecto que ya no existe no abre el asistente`() {
        viewModel(
            ImportTestFiles.fixture("etrs89-utm30-utf8-bom-coma.csv").asSource(),
            project = "no-existe",
        ).settled().fileProblem shouldBe
            ImportFileProblem.UNREADABLE
    }

    @Test
    fun `S028 RF-3 un fichero de mas de 20 MB declarado por el selector no se lee`() {
        val source = ImportTestFiles.Source("grande.csv", "a,b\n".toByteArray(), declaredSize = 20L * 1024 * 1024 + 1)

        val state = viewModel(source.asSource()).settled()

        state.fileProblem shouldBe ImportFileProblem.TOO_BIG
        source.opened shouldBe 0
    }

    @Test
    fun `S028 RF-3 un fichero de mas de 20 MB que el selector no declara se corta al leerlo`() {
        val longLine = "x".repeat(1_000) + ",y\n"

        val state = viewModel(ImportTestFiles.generated("grande.csv", 21L * 1024 * 1024, longLine)).settled()

        state.stage shouldBe ImportStage.PROBLEM
        state.fileProblem shouldBe ImportFileProblem.TOO_BIG
    }

    @Test
    fun `S028 RF-3 un fichero de mas de 50000 filas no abre el asistente`() {
        val state = viewModel(ImportTestFiles.generated("largo.csv", 50_001L * 6)).settled()

        state.fileProblem shouldBe ImportFileProblem.TOO_MANY_ROWS
    }

    @Test
    fun `S028 RF-3 un fichero de justo 50000 filas se abre`() {
        val line = "1,2,3\n"
        val state = viewModel(ImportTestFiles.generated("justo.csv", 50_000L * line.length, line)).settled()

        state.stage shouldBe ImportStage.WIZARD
    }

    @Test
    fun `S028 RF-4 el asistente solo se prepara una vez aunque se pida otra`() {
        val source = ImportTestFiles.fixture("etrs89-utm30-utf8-bom-coma.csv")
        val vm = viewModel(source.asSource())
        vm.settled()
        val opened = source.opened

        vm.start(projectId, source.asSource())
        vm.settled()

        source.opened shouldBe opened
    }

    @Test
    fun `S028 RF-11 la asignacion propuesta es la de la cabecera o la de PENZD`() {
        open("etrs89-utm30-utf8-bom-coma.csv").options.mapping shouldBe ColumnMapping.penzd(5)
        open("etrs89-utm30-tabulador-sin-cabecera.txt").options.mapping shouldBe ColumnMapping.penzd(5)
    }

    @Test
    fun `S028 RF-22 el boton Importar solo se activa con un fichero que se puede importar`() {
        open("etrs89-utm30-utf8-bom-coma.csv").canImport shouldBe true
        open("etrs89-utm30-errores.csv").canImport shouldBe true
        viewModel(ImportTestFiles.Source("vacio.csv", ByteArray(0)).asSource()).settled().canImport shouldBe false
    }
}
