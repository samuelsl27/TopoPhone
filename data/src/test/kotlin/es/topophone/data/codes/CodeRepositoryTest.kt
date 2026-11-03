package es.topophone.data.codes

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.core.model.codes.AttributeType
import es.topophone.core.model.codes.CodeAttribute
import es.topophone.core.model.codes.CodeError
import es.topophone.core.model.codes.CodeGeometry
import es.topophone.core.model.codes.CodeLibrary
import es.topophone.core.model.codes.SurveyCode
import es.topophone.core.model.project.ProjectDefaults
import es.topophone.data.db.TopoPhoneDatabase
import es.topophone.data.project.RoomProjectRepository
import es.topophone.data.project.SaveResult
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith

/** Códigos y atributos de una biblioteca: alta, edición, borrado, orden y rechazos (S023/RF-5 a RF-7, RF-9, RF-10). */
@RunWith(AndroidJUnit4::class)
class CodeRepositoryTest {

    private val db = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext<Context>(),
        TopoPhoneDatabase::class.java,
    ).build()
    private var counter = 0
    private val default = CodeLibrary("default:general", "General", true, listOf(SurveyCode("g", "VER", "", CodeGeometry.POINT, 1)))
    private val repository = RoomCodeLibraryRepository(db, { listOf(default) }, clock = { 1L }) { "id${++counter}" }

    @After
    fun close() = db.close()

    private fun library(): String = runBlocking { (repository.create("Mía") as LibraryResult.Saved).id }

    private fun code(
        text: String,
        description: String = "",
        attributes: List<CodeAttribute> = emptyList(),
        id: String = "",
        geometry: CodeGeometry = CodeGeometry.POINT,
    ) = SurveyCode(id, text, description, geometry, 0x1E88E5, attributes)

    private fun codes(libraryId: String): List<SurveyCode> = runBlocking { repository.library(libraryId).first().shouldNotBeNull().codes }

    @Test
    fun `S023 RF-5 un codigo con descripcion, geometria, color y atributos se guarda y se lee igual`() {
        val libraryId = library()
        val attributes = listOf(
            CodeAttribute("Material", AttributeType.LIST, "PVC", listOf("PVC", "Hormigón")),
            CodeAttribute("Diámetro", AttributeType.NUMBER, "0.25"),
            CodeAttribute("Nota", AttributeType.TEXT),
        )
        val saved = runBlocking { repository.saveCode(libraryId, code(" ARQ ", "Arqueta", attributes, geometry = CodeGeometry.LINE)) }

        val id = saved.shouldBeInstanceOf<CodeSaveResult.Saved>().id
        codes(libraryId) shouldBe listOf(SurveyCode(id, "ARQ", "Arqueta", CodeGeometry.LINE, 0x1E88E5, attributes))
    }

    @Test
    fun `S023 RF-5 editar un codigo cambia sus datos y sustituye sus atributos`() {
        val libraryId = library()
        val id = runBlocking {
            (
                repository.saveCode(
                    libraryId,
                    code("ARQ", attributes = listOf(CodeAttribute("A", AttributeType.TEXT))),
                ) as CodeSaveResult.Saved
                ).id
        }

        runBlocking {
            repository.saveCode(libraryId, code("arq", "Nueva", listOf(CodeAttribute("B", AttributeType.INTEGER, "3")), id = id))
        }

        codes(libraryId).single().let {
            it.id shouldBe id
            it.code shouldBe "arq"
            it.description shouldBe "Nueva"
            it.attributes shouldBe listOf(CodeAttribute("B", AttributeType.INTEGER, "3"))
        }
    }

    @Test
    fun `S023 RF-6 un codigo repetido con otra mayuscula o acento no se guarda y se indica el que choca`() {
        val libraryId = library()
        runBlocking { repository.saveCode(libraryId, code("Árbol")) }

        runBlocking { repository.saveCode(libraryId, code("ARBOL")) } shouldBe CodeSaveResult.Invalid(CodeError.Duplicate("Árbol"))
        codes(libraryId).size shouldBe 1
    }

    @Test
    fun `S023 RF-6 el mismo codigo puede estar en dos bibliotecas`() {
        val a = library()
        val b = runBlocking { (repository.create("Otra") as LibraryResult.Saved).id }

        runBlocking { repository.saveCode(a, code("ARQ")) }.shouldBeInstanceOf<CodeSaveResult.Saved>()
        runBlocking { repository.saveCode(b, code("ARQ")) }.shouldBeInstanceOf<CodeSaveResult.Saved>()
    }

    @Test
    fun `S023 RF-7 un codigo vacio, largo o con salto de linea no se guarda`() {
        val libraryId = library()

        runBlocking { repository.saveCode(libraryId, code("  ")) } shouldBe CodeSaveResult.Invalid(CodeError.Empty)
        runBlocking { repository.saveCode(libraryId, code("A".repeat(33))) } shouldBe CodeSaveResult.Invalid(CodeError.TooLong)
        runBlocking { repository.saveCode(libraryId, code("A\nB")) } shouldBe CodeSaveResult.Invalid(CodeError.NewLine)
        codes(libraryId) shouldBe emptyList()
    }

    @Test
    fun `S023 RF-9 un atributo mal definido no guarda nada y deja el codigo como estaba`() {
        val libraryId = library()
        val id = runBlocking { (repository.saveCode(libraryId, code("ARQ", "Buena")) as CodeSaveResult.Saved).id }

        runBlocking {
            repository.saveCode(libraryId, code("ARQ", "Mala", listOf(CodeAttribute("L", AttributeType.LIST)), id = id))
        } shouldBe CodeSaveResult.Invalid(CodeError.ListOptions(0))

        codes(libraryId).single().description shouldBe "Buena"
    }

    @Test
    fun `S023 RF-9 caben 100 atributos con 50 opciones cada una`() {
        val libraryId = library()
        val options = (1..50).map { "o$it" }
        val attributes = (1..100).map { CodeAttribute("A$it", AttributeType.LIST, "o1", options) }

        runBlocking { repository.saveCode(libraryId, code("GRANDE", attributes = attributes)) }.shouldBeInstanceOf<CodeSaveResult.Saved>()

        codes(libraryId).single().attributes shouldBe attributes
    }

    @Test
    fun `S023 RF-10 reordenar los atributos cambia su posicion y borrar uno deja los demas`() {
        val libraryId = library()
        val a = CodeAttribute("A", AttributeType.TEXT)
        val b = CodeAttribute("B", AttributeType.INTEGER, "1")
        val c = CodeAttribute("C", AttributeType.LIST, "x", listOf("x", "y"))
        val id = runBlocking { (repository.saveCode(libraryId, code("ARQ", attributes = listOf(a, b, c))) as CodeSaveResult.Saved).id }

        runBlocking { repository.reorderAttributes(libraryId, id, listOf(2, 0, 1)) }
        codes(libraryId).single().attributes shouldBe listOf(c, a, b)

        runBlocking { repository.reorderAttributes(libraryId, id, listOf(0, 0, 1)) } // no es una permutación: no hace nada
        codes(libraryId).single().attributes shouldBe listOf(c, a, b)

        runBlocking { repository.saveCode(libraryId, code("ARQ", attributes = listOf(c, b), id = id)) }
        codes(libraryId).single().attributes shouldBe listOf(c, b)
    }

    @Test
    fun `S023 RF-10 cambiar o borrar atributos no toca los proyectos ni sus copias`() {
        val libraryId = library()
        val project = runBlocking {
            RoomProjectRepository(db).create(ProjectDefaults().toDraft().copy(name = "Camino")).shouldBeInstanceOf<SaveResult.Saved>().id
        }
        db.codeLibraries().let { dao ->
            runBlocking {
                dao.insertLibrary(CodeLibraryEntity("copy", "copia", "copia", project, null, 1))
                dao.insertCode(LibraryCodeEntity("cc", "copy", "ARQ", "arq", "", "POINT", 1))
                dao.insertAttribute(CodeAttributeEntity("ca", "cc", 0, "A", "TEXT", null))
            }
        }
        val id = runBlocking {
            (
                repository.saveCode(
                    libraryId,
                    code("ARQ", attributes = listOf(CodeAttribute("A", AttributeType.TEXT))),
                ) as CodeSaveResult.Saved
                ).id
        }

        runBlocking { repository.saveCode(libraryId, code("ARQ", id = id)) }
        runBlocking { repository.deleteCode(libraryId, id) } shouldBe CodeSaveResult.Deleted

        runBlocking { db.codeLibraries().attributes("copy") }.size shouldBe 1
    }

    @Test
    fun `S023 RF-5 borrar un codigo lo quita con sus atributos y opciones`() {
        val libraryId = library()
        val id = runBlocking {
            val attribute = CodeAttribute("L", AttributeType.LIST, "x", listOf("x", "y"))
            (repository.saveCode(libraryId, code("ARQ", attributes = listOf(attribute))) as CodeSaveResult.Saved).id
        }

        runBlocking { repository.deleteCode(libraryId, id) } shouldBe CodeSaveResult.Deleted
        runBlocking { repository.deleteCode(libraryId, id) } shouldBe CodeSaveResult.NotFound

        listOf("library_codes", "code_attributes", "attribute_options").forEach { table ->
            db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM $table").use {
                it.moveToFirst()
                it.getInt(0) shouldBe 0
            }
        }
    }

    @Test
    fun `S023 RF-3 las bibliotecas por defecto y la copia de un proyecto no se editan`() {
        val project = runBlocking {
            RoomProjectRepository(db).create(ProjectDefaults().toDraft().copy(name = "Camino")).shouldBeInstanceOf<SaveResult.Saved>().id
        }
        runBlocking { db.codeLibraries().insertLibrary(CodeLibraryEntity("copy", "copia", "copia", project, null, 1)) }

        runBlocking { repository.saveCode("default:general", code("NUEVO")) } shouldBe CodeSaveResult.NotEditable
        runBlocking { repository.deleteCode("default:general", "g") } shouldBe CodeSaveResult.NotEditable
        runBlocking { repository.saveCode("copy", code("NUEVO")) } shouldBe CodeSaveResult.NotEditable
        runBlocking { repository.saveCode("no-existe", code("NUEVO")) } shouldBe CodeSaveResult.NotFound
    }
}
