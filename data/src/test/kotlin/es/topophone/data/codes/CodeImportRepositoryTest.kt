package es.topophone.data.codes

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.core.model.codes.AttributeType
import es.topophone.core.model.codes.CodeAttribute
import es.topophone.core.model.codes.CodeError
import es.topophone.core.model.codes.CodeGeometry
import es.topophone.core.model.codes.DuplicatePolicy
import es.topophone.core.model.codes.SurveyCode
import es.topophone.data.db.TopoPhoneDatabase
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.system.measureTimeMillis
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith

/** Importar códigos: biblioteca nueva o existente, duplicados, todo o nada y rendimiento (S023/RF-14 a RF-16). */
@RunWith(AndroidJUnit4::class)
class CodeImportRepositoryTest {

    private val db = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext<Context>(),
        TopoPhoneDatabase::class.java,
    ).build()
    private var counter = 0
    private var failAt = Int.MAX_VALUE
    private val repository = RoomCodeLibraryRepository(db, clock = { 1L }) {
        if (++counter == failAt) error("fallo a medias")
        "id$counter"
    }

    @After
    fun close() = db.close()

    private fun code(text: String, description: String = "", attributes: List<CodeAttribute> = emptyList()) =
        SurveyCode("", text, description, CodeGeometry.POINT, 0x1E88E5, attributes)

    private fun codes(id: String) = runBlocking { repository.library(id).first().shouldNotBeNull().codes }

    private fun imported(result: CodeImportResult) = result.shouldBeInstanceOf<CodeImportResult.Imported>()

    private fun count(table: String): Int = db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM $table").use {
        it.moveToFirst()
        it.getInt(0)
    }

    @Test
    fun `S023 RF-14 se importa en una biblioteca nueva con el nombre del fichero y se cuentan los codigos`() {
        val result = imported(
            runBlocking {
                repository.importCodes(
                    ImportTarget.NewLibrary("Mis codigos"),
                    listOf(code("ARQ", attributes = listOf(CodeAttribute("L", AttributeType.LIST, "x", listOf("x", "y")))), code("FAR")),
                    DuplicatePolicy.SKIP,
                )
            },
        )

        result.entered shouldBe 2
        result.skipped shouldBe 0
        val library = runBlocking { repository.library(result.libraryId).first().shouldNotBeNull() }
        library.name shouldBe "Mis codigos"
        library.codes.map { it.code } shouldBe listOf("ARQ", "FAR")
        library.codes.first().attributes.single().options shouldBe listOf("x", "y")
    }

    @Test
    fun `S023 RF-14 si el nombre del fichero ya existe la biblioteca nueva lleva un numero`() {
        runBlocking { repository.create("Mis codigos") }

        val result =
            imported(
                runBlocking {
                    repository.importCodes(ImportTarget.NewLibrary("mis codigos"), listOf(code("A")), DuplicatePolicy.SKIP)
                },
            )

        runBlocking { repository.library(result.libraryId).first().shouldNotBeNull().name } shouldBe "mis codigos (2)"
    }

    @Test
    fun `S023 RF-14 se importa anadiendo a una biblioteca editable que ya tenia codigos`() {
        val target = runBlocking { (repository.create("Mía") as LibraryResult.Saved).id }
        runBlocking { repository.saveCode(target, code("VER")) }

        val result =
            imported(runBlocking { repository.importCodes(ImportTarget.Existing(target), listOf(code("FAR")), DuplicatePolicy.SKIP) })

        result.libraryId shouldBe target
        codes(target).map { it.code } shouldBe listOf("FAR", "VER")
    }

    @Test
    fun `S023 RF-16 omitir deja lo que habia y reemplazar lo sustituye conservando el id`() {
        val target = runBlocking { (repository.create("Mía") as LibraryResult.Saved).id }
        val oldId = runBlocking { (repository.saveCode(target, code("ARQ", "vieja")) as CodeSaveResult.Saved).id }
        val incoming = listOf(
            code("arq", "nueva", listOf(CodeAttribute("A", AttributeType.TEXT))),
            code("FAR", "primera"),
            code("far", "segunda"),
        )

        val skip = imported(runBlocking { repository.importCodes(ImportTarget.Existing(target), incoming, DuplicatePolicy.SKIP) })
        skip.added shouldBe 1
        skip.skipped shouldBe 2
        codes(target).associate { it.code to it.description } shouldBe mapOf("ARQ" to "vieja", "FAR" to "primera")

        val replace = imported(runBlocking { repository.importCodes(ImportTarget.Existing(target), incoming, DuplicatePolicy.REPLACE) })
        replace.replaced shouldBe 2
        replace.added shouldBe 0
        codes(target).associate { it.code to it.description } shouldBe mapOf("arq" to "nueva", "far" to "segunda")
        codes(target).first { it.code == "arq" }.let {
            it.id shouldBe oldId
            it.attributes shouldBe listOf(CodeAttribute("A", AttributeType.TEXT))
        }
        count("library_codes") shouldBe 2
    }

    @Test
    fun `S023 RF-6 nunca quedan dos codigos con la misma clave en la biblioteca`() {
        val target = runBlocking { (repository.create("Mía") as LibraryResult.Saved).id }

        DuplicatePolicy.entries.forEach { policy ->
            runBlocking { repository.importCodes(ImportTarget.Existing(target), listOf(code("Árbol"), code("ARBOL"), code("x")), policy) }
        }

        codes(target).map { it.code.lowercase() }.size shouldBe 2
    }

    @Test
    fun `S023 RF-14 una importacion que falla a medias no deja nada`() {
        val target = runBlocking { (repository.create("Mía") as LibraryResult.Saved).id }
        val before = counter
        failAt = before + 3

        shouldThrow<IllegalStateException> {
            runBlocking {
                repository.importCodes(ImportTarget.NewLibrary("Nueva"), (1..10).map { code("C$it") }, DuplicatePolicy.SKIP)
            }
        }

        count("code_libraries") shouldBe 1
        count("library_codes") shouldBe 0
        codes(target) shouldBe emptyList()
    }

    @Test
    fun `S023 RF-14 un codigo invalido no deja entrar nada y se indica`() {
        val result = runBlocking {
            repository.importCodes(ImportTarget.NewLibrary("Nueva"), listOf(code("OK"), code("A".repeat(40))), DuplicatePolicy.SKIP)
        }

        result shouldBe CodeImportResult.Rejected("A".repeat(40), CodeError.TooLong)
        count("code_libraries") shouldBe 0
    }

    @Test
    fun `S023 RF-3 no se importa en una por defecto, ni en una que no existe`() {
        runBlocking { repository.importCodes(ImportTarget.Existing("default:general"), listOf(code("A")), DuplicatePolicy.SKIP) } shouldBe
            CodeImportResult.NotEditable
        runBlocking { repository.importCodes(ImportTarget.Existing("nada"), listOf(code("A")), DuplicatePolicy.SKIP) } shouldBe
            CodeImportResult.NotFound
    }

    @Test
    fun `S023 RNF una biblioteca de 2000 codigos con atributos se importa en menos de 3 s`() {
        val big = (1..2000).map {
            code(
                "C%04d".format(it),
                "Descripción $it",
                listOf(
                    CodeAttribute("Material", AttributeType.LIST, "PVC", listOf("PVC", "Hormigón", "Acero")),
                    CodeAttribute("D", AttributeType.NUMBER, "0.25"),
                ),
            )
        }

        val result: CodeImportResult
        val millis = measureTimeMillis {
            result =
                runBlocking { repository.importCodes(ImportTarget.NewLibrary("Grande"), big, DuplicatePolicy.SKIP) }
        }

        imported(result).entered shouldBe 2000
        (millis < 3000) shouldBe true
        count("attribute_options") shouldBe 6000
    }
}
