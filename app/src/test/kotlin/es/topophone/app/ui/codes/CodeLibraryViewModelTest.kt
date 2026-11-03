package es.topophone.app.ui.codes

import android.content.Context
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.core.formats.codes.CodeCsvResult
import es.topophone.core.formats.codes.CodeLibraryCsvReader
import es.topophone.core.formats.csv.DelimitedReader
import es.topophone.core.model.codes.AttributeType
import es.topophone.core.model.codes.CodeAttribute
import es.topophone.core.model.codes.CodeGeometry
import es.topophone.core.model.codes.CodeLibrary
import es.topophone.core.model.codes.SurveyCode
import es.topophone.core.model.point.ImportDecimal
import es.topophone.core.model.point.ImportSeparator
import es.topophone.data.codes.RoomCodeLibraryRepository
import es.topophone.data.db.TopoPhoneDatabase
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.time.Instant
import java.time.ZoneOffset
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

/** Una biblioteca: buscador, solo lectura de las por defecto y exportar CSV (S023/RF-3, RF-11, RF-17). */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class CodeLibraryViewModelTest {

    private val db = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext<Context>(),
        TopoPhoneDatabase::class.java,
    ).build()
    private val general = CodeLibrary(
        "default:general",
        "Topografía general",
        true,
        listOf(
            SurveyCode("g1", "VER", "Vértice", CodeGeometry.POINT, 0x1E88E5),
            SurveyCode("g2", "ARB", "Árbol", CodeGeometry.POINT, 0xE64A19),
        ),
    )
    private val repository = RoomCodeLibraryRepository(db, { listOf(general) }, clock = { 1L })
    private val viewModels = mutableListOf<CodeLibraryViewModel>()

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() {
        viewModels.forEach { it.viewModelScope.cancel() }
        db.close()
        Dispatchers.resetMain()
    }

    private fun viewModel() = CodeLibraryViewModel(repository).also { viewModels += it }

    private fun <T> CodeLibraryViewModel.awaitState(predicate: (CodeLibraryUiState) -> Boolean, read: (CodeLibraryUiState) -> T): T =
        runBlocking {
            val collector = CoroutineScope(Dispatchers.Unconfined).launch { state.collect {} }
            try {
                read(withTimeout(5_000) { state.first(predicate) })
            } finally {
                collector.cancel()
            }
        }

    private fun userLibrary(): String = runBlocking {
        val id = (repository.create("Mía") as es.topophone.data.codes.LibraryResult.Saved).id
        repository.saveCode(id, SurveyCode("", "ARQ", "Arqueta de saneamiento", CodeGeometry.POINT, 0x1E88E5))
        repository.saveCode(id, SurveyCode("", "BOR", "Bordillo", CodeGeometry.LINE, 0xE64A19))
        repository.saveCode(
            id,
            SurveyCode(
                "",
                "FAR",
                "Farola",
                CodeGeometry.POINT,
                0x689F38,
                listOf(CodeAttribute("Altura", AttributeType.NUMBER, "4.5")),
            ),
        )
        id
    }

    @Test
    fun `S023 RF-11 sin texto salen todos los codigos ordenados`() {
        val vm = viewModel()
        vm.open(userLibrary())

        vm.awaitState({ it.library != null && it.codes.size == 3 }) { s -> s.codes.map { it.code } } shouldBe listOf("ARQ", "BOR", "FAR")
    }

    @Test
    fun `S023 RF-11 el buscador filtra por codigo o descripcion sin mayusculas ni acentos`() {
        val vm = viewModel()
        vm.open(userLibrary())

        vm.search("SANEAMIENTO")
        vm.awaitState({ it.query == "SANEAMIENTO" && it.codes.size == 1 }) { s -> s.codes.map { it.code } } shouldBe listOf("ARQ")
        vm.search("bor")
        vm.awaitState({ it.query == "bor" && it.codes.size == 1 }) { s -> s.codes.map { it.code } } shouldBe listOf("BOR")
        vm.search("zzz")
        vm.awaitState({ it.query == "zzz" }) { it.codes } shouldBe emptyList()
    }

    @Test
    fun `S023 RF-3 una biblioteca por defecto se ve pero no se edita`() {
        val vm = viewModel()
        vm.open("default:general")

        vm.awaitState({ it.library != null }) { it.editable } shouldBe false
        vm.awaitState({ it.library != null }) { s -> s.codes.map { it.code } } shouldBe listOf("ARB", "VER")
    }

    @Test
    fun `S023 RF-3 una biblioteca del usuario se edita`() {
        val vm = viewModel()
        vm.open(userLibrary())

        vm.awaitState({ it.library != null }) { it.editable } shouldBe true
    }

    @Test
    fun `S023 RF-17 el CSV exportado se vuelve a leer con los mismos codigos`() {
        val vm = viewModel()
        vm.open(userLibrary())
        vm.awaitState({ it.codes.size == 3 }) { it }
        val out = ByteArrayOutputStream()

        runBlocking { vm.writeCsv(out) } shouldBe true

        val rows = DelimitedReader.rows(out.toByteArray().inputStream().reader(Charsets.UTF_8), ImportSeparator.COMMA)
        val parsed = CodeLibraryCsvReader.parse(rows, ImportDecimal.POINT).shouldBeInstanceOf<CodeCsvResult.Parsed>().parse
        parsed.codes.map { it.code.code } shouldBe listOf("ARQ", "BOR", "FAR")
        parsed.codes.last().code.attributes shouldBe listOf(CodeAttribute("Altura", AttributeType.NUMBER, "4.5"))
        String(out.toByteArray(), Charsets.UTF_8).startsWith("Code,Description,Type,Color") shouldBe true
    }

    @Test
    fun `S023 RF-17 el nombre propuesto lleva el nombre de la biblioteca y la fecha`() {
        val vm = viewModel()
        vm.open("default:general")
        vm.awaitState({ it.library != null }) { it }

        vm.fileName(Instant.parse("2026-11-03T10:15:00Z"), ZoneOffset.UTC) shouldBe "Topografía general_codes_20261103-1015.csv"
    }

    @Test
    fun `S023 RF-17 si no se puede escribir el fichero se dice y no queda aviso de exito`() {
        val vm = viewModel()
        vm.open("default:general")
        vm.awaitState({ it.library != null }) { it }
        val broken = object : OutputStream() {
            override fun write(b: Int) = throw IOException("sin espacio")
        }

        val saved = runBlocking { vm.writeCsv(broken) }
        vm.exported(saved)

        saved shouldBe false
        vm.awaitState({ it.message != null }) { it.message } shouldBe ExportMessage.FAILED
        vm.dismissMessage()
        vm.awaitState({ it.message == null }) { it.message } shouldBe null
    }

    @Test
    fun `S023 RF-3 una biblioteca que no existe no se carga`() {
        val vm = viewModel()
        vm.open("no-existe")

        vm.awaitState({ it.loaded }) { it.library } shouldBe null
    }
}
