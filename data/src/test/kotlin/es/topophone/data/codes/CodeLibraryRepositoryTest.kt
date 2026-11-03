package es.topophone.data.codes

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.core.model.codes.AttributeType
import es.topophone.core.model.codes.CodeAttribute
import es.topophone.core.model.codes.CodeGeometry
import es.topophone.core.model.codes.CodeLibrary
import es.topophone.core.model.codes.SurveyCode
import es.topophone.core.model.log.AppLog
import es.topophone.core.model.log.LogCategory
import es.topophone.core.model.log.LogLevel
import es.topophone.core.model.project.NameError
import es.topophone.core.model.project.ProjectDefaults
import es.topophone.data.db.TopoPhoneDatabase
import es.topophone.data.project.RoomProjectRepository
import es.topophone.data.project.SaveResult
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith

/** Las bibliotecas de códigos: lista, crear, renombrar, duplicar, borrar y las por defecto de solo lectura (S023/RF-1 a RF-4). */
@RunWith(AndroidJUnit4::class)
class CodeLibraryRepositoryTest {

    private val db = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext<Context>(),
        TopoPhoneDatabase::class.java,
    ).build()
    private val logged = mutableListOf<String>()
    private val log = object : AppLog {
        override fun record(level: LogLevel, category: LogCategory, message: String) {
            logged += message
        }
    }
    private var counter = 0
    private val general = CodeLibrary(
        "default:general",
        "Topografía general",
        true,
        listOf(
            SurveyCode("g1", "VER", "Vértice", CodeGeometry.POINT, 0x1E88E5),
            SurveyCode(
                "g2",
                "ARQ",
                "Arqueta",
                CodeGeometry.POINT,
                0xE64A19,
                listOf(CodeAttribute("Material", AttributeType.LIST, "PVC", listOf("PVC", "Hormigón"))),
            ),
        ),
    )
    private val urban = CodeLibrary("default:urbanizacion", "Urbanización", true, emptyList())
    private val repository = RoomCodeLibraryRepository(db, { listOf(general, urban) }, log, clock = { 1_000L }) { "id${++counter}" }

    @After
    fun close() = db.close()

    private fun saved(result: LibraryResult): String = result.shouldBeInstanceOf<LibraryResult.Saved>().id

    @Test
    fun `S023 RF-1 la lista trae las por defecto marcadas y luego las del usuario con su numero de codigos`() {
        runBlocking {
            repository.libraries().first() shouldBe listOf(
                LibrarySummary("default:general", "Topografía general", true, 2),
                LibrarySummary("default:urbanizacion", "Urbanización", true, 0),
            )
            val mine = saved(repository.create("Zona norte"))
            val other = saved(repository.create("Aceras"))

            repository.libraries().first().drop(2) shouldBe listOf(
                LibrarySummary(other, "Aceras", false, 0),
                LibrarySummary(mine, "Zona norte", false, 0),
            )
        }
    }

    @Test
    fun `S023 RF-2 un nombre vacio o repetido no se guarda, tampoco el de una por defecto`() {
        runBlocking {
            saved(repository.create("Zona norte"))

            repository.create("  ") shouldBe LibraryResult.Invalid(NameError.EMPTY)
            repository.create("zona NORTE") shouldBe LibraryResult.Invalid(NameError.DUPLICATE)
            repository.create("topografia general") shouldBe LibraryResult.Invalid(NameError.DUPLICATE)
            repository.libraries().first().size shouldBe 3
        }
    }

    @Test
    fun `S023 RF-2 renombrar conserva el id, admite su propio nombre con otra mayuscula y rechaza el de otra`() {
        runBlocking {
            val a = saved(repository.create("Zona norte"))
            saved(repository.create("Zona sur"))

            repository.rename(a, "ZONA NORTE") shouldBe LibraryResult.Saved(a)
            repository.rename(a, "zona sur") shouldBe LibraryResult.Invalid(NameError.DUPLICATE)
            repository.rename(a, "") shouldBe LibraryResult.Invalid(NameError.EMPTY)
            repository.library(a).first().shouldNotBeNull().name shouldBe "ZONA NORTE"
            repository.rename("no-existe", "X") shouldBe LibraryResult.NotFound
        }
    }

    @Test
    fun `S023 RF-3 una por defecto no se renombra ni se borra pero se duplica y la copia se edita`() {
        runBlocking {
            repository.rename("default:general", "Otra") shouldBe LibraryResult.NotEditable
            repository.delete("default:general") shouldBe LibraryResult.NotEditable

            val copyId = saved(repository.duplicate("default:general", "copia"))
            val copy = repository.library(copyId).first().shouldNotBeNull()

            copy.name shouldBe "Topografía general (copia)"
            copy.isDefault shouldBe false
            copy.codes.map { it.code } shouldBe listOf("ARQ", "VER")
            copy.codes.first().attributes shouldBe general.codes[1].attributes
            repository.rename(copyId, "Mi copia") shouldBe LibraryResult.Saved(copyId)
            repository.library("default:general").first() shouldBe general
        }
    }

    @Test
    fun `S023 RF-2 duplicar una del usuario da ids nuevos y un nombre libre`() {
        runBlocking {
            val a = saved(repository.create("Zona norte"))
            val first = saved(repository.duplicate(a, "copia"))
            val second = saved(repository.duplicate(a, "copia"))

            repository.library(first).first().shouldNotBeNull().name shouldBe "Zona norte (copia)"
            repository.library(second).first().shouldNotBeNull().name shouldBe "Zona norte (copia 2)"
            repository.duplicate("no-existe", "copia") shouldBe LibraryResult.NotFound
        }
    }

    @Test
    fun `S023 RF-2 borrar quita la biblioteca con sus codigos`() {
        runBlocking {
            val copyId = saved(repository.duplicate("default:general", "copia"))
            repository.delete(copyId) shouldBe LibraryResult.Deleted

            repository.library(copyId).first().shouldBeNull()
            repository.libraries().first().size shouldBe 2
            db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM library_codes").use {
                it.moveToFirst()
                it.getInt(0) shouldBe 0
            }
        }
    }

    @Test
    fun `S023 RF-4 borrar una biblioteca no toca la copia de ningun proyecto`() {
        runBlocking {
            val project = RoomProjectRepository(db).create(ProjectDefaults().toDraft().copy(name = "Camino"))
                .shouldBeInstanceOf<SaveResult.Saved>().id
            val mine = saved(repository.duplicate("default:general", "copia"))
            db.codeLibraries().insertLibrary(CodeLibraryEntity("copy", "copia", "copia", project, "x", 1))
            db.codeLibraries().insertCode(LibraryCodeEntity("cc", "copy", "VER", "ver", "", "POINT", 1))

            repository.delete(mine) shouldBe LibraryResult.Deleted

            db.codeLibraries().copyOf(project).shouldNotBeNull().id shouldBe "copy"
            db.codeLibraries().codes("copy").size shouldBe 1
            // La copia de un proyecto no sale en la lista ni se puede borrar como biblioteca.
            repository.libraries().first().none { it.id == "copy" } shouldBe true
            repository.delete("copy") shouldBe LibraryResult.NotFound
        }
    }

    @Test
    fun `S023 RNF-T6 el log lleva el hecho y nunca nombres ni codigos`() {
        runBlocking {
            val id = saved(repository.create("Zona secreta"))
            repository.rename(id, "Otra secreta")
            saved(repository.duplicate(id, "copia"))
            repository.delete(id)
        }

        logged.isNotEmpty() shouldBe true
        logged.none { it.contains("secreta", ignoreCase = true) } shouldBe true
    }
}
