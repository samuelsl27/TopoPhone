package es.topophone.app.ui.codes

import android.content.Context
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.app.ui.points.ImportFileProblem
import es.topophone.app.ui.points.ImportSource
import es.topophone.core.formats.codes.RowProblem
import es.topophone.core.model.codes.CodeError
import es.topophone.core.model.codes.CodeGeometry
import es.topophone.core.model.codes.DuplicatePolicy
import es.topophone.core.model.codes.SurveyCode
import es.topophone.core.model.point.ImportDecimal
import es.topophone.core.model.point.ImportEncoding
import es.topophone.core.model.point.ImportSeparator
import es.topophone.data.codes.LibraryResult
import es.topophone.data.codes.RoomCodeLibraryRepository
import es.topophone.data.db.TopoPhoneDatabase
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import java.io.ByteArrayInputStream
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** El asistente de importación de bibliotecas: opciones, vista previa, informe, repetidos y confirmación (S023/RF-12 a RF-16). */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class CodeImportViewModelTest {

    private val db = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext<Context>(),
        TopoPhoneDatabase::class.java,
    ).build()
    private val repository = RoomCodeLibraryRepository(db, clock = { 1L })
    private val viewModels = mutableListOf<CodeImportViewModel>()

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() {
        viewModels.forEach { it.viewModelScope.cancel() }
        db.close()
        Dispatchers.resetMain()
    }

    private fun fixture(name: String): ByteArray {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }
            .first { File(it, "settings.gradle.kts").exists() && File(it, "fixtures").isDirectory }
        return File(root, "fixtures/codigos/$name").readBytes()
    }

    private fun source(name: String, bytes: ByteArray, size: Long? = bytes.size.toLong()) =
        ImportSource(name, size) { ByteArrayInputStream(bytes) }

    private fun start(name: String, bytes: ByteArray = fixture(name), preselected: String? = null, size: Long? = bytes.size.toLong()) =
        CodeImportViewModel(repository, Dispatchers.Unconfined).also {
            viewModels += it
            it.start(source(name, bytes, size), "Importada", preselected)
        }

    private fun CodeImportViewModel.await(predicate: (CodeImportUiState) -> Boolean): CodeImportUiState = runBlocking {
        val collector = CoroutineScope(Dispatchers.Unconfined).launch { state.collect {} }
        try {
            withTimeout(5_000) { state.first(predicate) }
        } finally {
            collector.cancel()
        }
    }

    private fun CodeImportViewModel.ready() = await { it.stage != CodeImportStage.LOADING && !it.analyzing }

    private fun libraryNames(): List<String> = runBlocking { repository.libraries().first().filter { !it.isDefault }.map { it.name } }

    @Test
    fun `S023 RF-12 se abre un CSV, se propone su codificacion y separador y se ve una vista previa sin crear nada`() {
        val vm = start("completa.csv")

        val s = vm.ready()

        s.stage shouldBe CodeImportStage.WIZARD
        s.encoding shouldBe ImportEncoding.UTF8
        s.separator shouldBe ImportSeparator.COMMA
        s.validCount shouldBe 4
        s.invalidCount shouldBe 0
        s.preview.map { it.code } shouldBe listOf("ARQ", "BOR", "EDI", "FAR")
        s.preview.first().let {
            it.description shouldBe "Arqueta"
            it.geometry shouldBe CodeGeometry.POINT
            it.attributes shouldBe 2
            it.line shouldBe 2
        }
        s.canImport shouldBe true
        libraryNames() shouldBe emptyList()
    }

    @Test
    fun `S023 RF-12 un fichero en Windows-1252 con punto y coma y coma decimal se reconoce y se lee entero`() {
        val s = start("windows1252-puntoycoma-coma.csv").ready()

        s.encoding shouldBe ImportEncoding.WINDOWS_1252
        s.separator shouldBe ImportSeparator.SEMICOLON
        s.decimal shouldBe ImportDecimal.COMMA
        s.preview.map { it.code } shouldBe listOf("ÁRB", "POZ")
        s.validCount shouldBe 2
    }

    @Test
    fun `S023 RF-12 se puede cambiar la codificacion y el separador y el fichero se vuelve a leer`() {
        val vm = start("windows1252-puntoycoma-coma.csv")
        vm.ready()

        vm.setEncoding(ImportEncoding.UTF8)
        val wrong = vm.await { it.encoding == ImportEncoding.UTF8 && !it.analyzing }
        wrong.preview.first().code.contains('�') shouldBe true
        vm.setEncoding(ImportEncoding.WINDOWS_1252)
        vm.setSeparator(ImportSeparator.COMMA)

        // Con la coma como separador no hay columna Code: la cabecera entera es una sola celda.
        vm.await { it.separator == ImportSeparator.COMMA && !it.analyzing }.missingCode shouldBe true
    }

    @Test
    fun `S023 RF-12 el separador y el decimal no pueden ser los dos la coma`() {
        val vm = start("completa.csv")
        vm.ready()

        vm.setDecimal(ImportDecimal.COMMA)

        val s = vm.await { it.decimal == ImportDecimal.COMMA && !it.analyzing }
        s.decimalConflict shouldBe true
        s.canImport shouldBe false
    }

    @Test
    fun `S023 RF-13 sin la columna Code no se importa nada y se dice por que`() {
        val vm = start("sin-code.csv")

        val s = vm.ready()
        vm.confirm()

        s.missingCode shouldBe true
        s.canImport shouldBe false
        vm.state.value.stage shouldBe CodeImportStage.WIZARD
        libraryNames() shouldBe emptyList()
    }

    @Test
    fun `S023 RF-15 las filas invalidas se listan con su linea y su motivo y las demas se importan`() {
        val vm = start("filas-invalidas.csv")

        val s = vm.ready()

        s.validCount shouldBe 3
        s.invalidCount shouldBe 9
        s.invalid.first().let {
            it.line shouldBe 3
            it.problem shouldBe RowProblem.Code(CodeError.Empty)
        }
        s.invalid.map { it.line } shouldBe (3..11).toList()
        vm.confirm()

        val done = vm.await { it.stage == CodeImportStage.RESULT }
        done.outcome.shouldNotBeNull().let {
            it.invalid shouldBe 9
            it.entered shouldBe 2 // OK1 y OK2; «ok1» del propio fichero se omite
            it.skipped shouldBe 1
        }
    }

    @Test
    fun `S023 RF-14 se importa en una biblioteca nueva con el nombre del fichero`() {
        val vm = start("completa.csv")
        vm.ready()

        vm.confirm()

        val outcome = vm.await { it.stage == CodeImportStage.RESULT }.outcome.shouldNotBeNull()
        outcome.entered shouldBe 4
        libraryNames() shouldBe listOf("completa")
        runBlocking { repository.library(outcome.libraryId).first().shouldNotBeNull().codes.size } shouldBe 4
    }

    @Test
    fun `S023 RF-14 se puede anadir a una biblioteca editable que se eligio antes`() {
        val mine = runBlocking { (repository.create("Mía") as LibraryResult.Saved).id }
        val vm = start("completa.csv", preselected = mine)

        vm.ready().target shouldBe CodeImportTarget.Existing(mine)
        vm.confirm()

        vm.await { it.stage == CodeImportStage.RESULT }.outcome.shouldNotBeNull().libraryId shouldBe mine
        runBlocking { repository.library(mine).first().shouldNotBeNull().codes.size } shouldBe 4
    }

    @Test
    fun `S023 RF-16 los repetidos con la biblioteca de destino se listan y se elige omitir o reemplazar`() {
        val mine = runBlocking { (repository.create("Mía") as LibraryResult.Saved).id }
        runBlocking { repository.saveCode(mine, SurveyCode("", "arq", "vieja", CodeGeometry.POINT, 1)) }
        val vm = start("completa.csv")
        vm.ready()

        vm.setTarget(CodeImportTarget.Existing(mine))
        val s = vm.await { it.target == CodeImportTarget.Existing(mine) && !it.analyzing }

        s.conflicts.withLibrary shouldBe listOf("ARQ")
        s.policy shouldBe DuplicatePolicy.SKIP
        vm.setPolicy(DuplicatePolicy.REPLACE)
        vm.confirm()

        val outcome = vm.await { it.stage == CodeImportStage.RESULT }.outcome.shouldNotBeNull()
        outcome.entered shouldBe 4
        runBlocking { repository.library(mine).first().shouldNotBeNull().codes.first().description } shouldBe "Arqueta"
    }

    @Test
    fun `S023 RF-16 omitir deja lo que habia`() {
        val mine = runBlocking { (repository.create("Mía") as LibraryResult.Saved).id }
        runBlocking { repository.saveCode(mine, SurveyCode("", "arq", "vieja", CodeGeometry.POINT, 1)) }
        val vm = start("completa.csv", preselected = mine)
        vm.ready()

        vm.confirm()

        val outcome = vm.await { it.stage == CodeImportStage.RESULT }.outcome.shouldNotBeNull()
        outcome.entered shouldBe 3
        outcome.skipped shouldBe 1
        runBlocking { repository.library(mine).first().shouldNotBeNull().codes.first().description } shouldBe "vieja"
    }

    @Test
    fun `S023 RF-12 un fichero vacio, binario o demasiado grande no abre el asistente`() {
        start("a.csv", ByteArray(0)).await { it.stage == CodeImportStage.PROBLEM }.fileProblem shouldBe ImportFileProblem.EMPTY
        start("a.csv", byteArrayOf(0, 1, 2, 0, 3)).await { it.stage == CodeImportStage.PROBLEM }.fileProblem shouldBe
            ImportFileProblem.NOT_TEXT
        start("a.csv", "Code\nA\n".toByteArray(), size = 25L * 1024 * 1024).await {
            it.stage == CodeImportStage.PROBLEM
        }.fileProblem shouldBe
            ImportFileProblem.TOO_BIG
    }

    @Test
    fun `S023 RF-12 un fichero que no se puede abrir lo dice`() {
        val vm = CodeImportViewModel(repository, Dispatchers.Unconfined).also { viewModels += it }

        vm.start(ImportSource("roto.csv", null) { null }, "Importada")

        vm.await { it.stage == CodeImportStage.PROBLEM }.fileProblem shouldBe ImportFileProblem.UNREADABLE
    }

    @Test
    fun `S023 RF-12 con mas de 50000 filas no se abre el asistente`() {
        val big = StringBuilder("Code\n").also { sb -> repeat(50_001) { sb.append("C").append(it).append('\n') } }.toString().toByteArray()

        start("grande.csv", big).await { it.stage == CodeImportStage.PROBLEM }.fileProblem shouldBe ImportFileProblem.TOO_MANY_ROWS
    }
}
