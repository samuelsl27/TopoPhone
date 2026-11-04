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
import es.topophone.core.model.codes.CodeSymbol
import es.topophone.core.model.codes.LibraryChoice
import es.topophone.core.model.codes.PointAttribute
import es.topophone.core.model.codes.PointCode
import es.topophone.core.model.codes.SurveyCode
import es.topophone.core.model.point.GeodeticPosition
import es.topophone.core.model.point.Point
import es.topophone.core.model.point.PointCoordinates
import es.topophone.core.model.point.PointOrigin
import es.topophone.core.model.project.ProjectDefaults
import es.topophone.data.db.TopoPhoneDatabase
import es.topophone.data.point.RoomPointRepository
import es.topophone.data.project.RoomProjectRepository
import es.topophone.data.project.SaveResult
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith

/** Los codigos creados en el proyecto, solo para el (S024/RF-5, RF-6, RF-7). */
@RunWith(AndroidJUnit4::class)
class ProjectCodeRepositoryTest {

    private val db = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext<Context>(),
        TopoPhoneDatabase::class.java,
    ).build()
    private var counter = 0
    private val newId: () -> String = { "id${++counter}" }
    private val general = CodeLibrary(
        "default:general",
        "Topografia general",
        true,
        listOf(
            SurveyCode("g1", "ARQ", "Arqueta", CodeGeometry.POINT, 0x1E88E5),
            SurveyCode("g2", "CAM", "Camino", CodeGeometry.LINE, 0xE64A19),
        ),
    )
    private val defaults = DefaultLibrarySource { listOf(general) }
    private val libraries = RoomCodeLibraryRepository(db, defaults, clock = { 1L }, newId = newId)
    private val linker = ProjectLibraryLinker(db, defaults, newId = newId)
    private val projects = RoomProjectRepository(db, newId = newId, libraries = linker)
    private val points = RoomPointRepository(db)

    @After
    fun close() = db.close()

    private fun newProject(name: String, choice: LibraryChoice = LibraryChoice.Keep): String = runBlocking {
        projects.create(ProjectDefaults().toDraft().copy(name = name, libraryChoice = choice)).shouldBeInstanceOf<SaveResult.Saved>().id
    }

    private fun codesOf(projectId: String) = runBlocking { libraries.projectCodes(projectId).first() }

    private val poste = SurveyCode(
        id = "",
        code = "POS",
        description = "Poste",
        geometry = CodeGeometry.POINT,
        color = 0xD32F2F,
        attributes = listOf(CodeAttribute("Altura", AttributeType.NUMBER, "7.5")),
        symbol = CodeSymbol.TRIANGLE,
    )

    @Test
    fun `S024 RF-5 un codigo del proyecto se guarda con su simbolo y atributos y es de geometria Punto`() {
        val project = newProject("Calle")

        val result = runBlocking { libraries.saveProjectCode(project, poste.copy(geometry = CodeGeometry.LINE)) }

        result.shouldBeInstanceOf<CodeSaveResult.Saved>()
        val saved = codesOf(project).single()
        saved.code shouldBe "POS"
        saved.geometry shouldBe CodeGeometry.POINT
        saved.symbol shouldBe CodeSymbol.TRIANGLE
        saved.attributes.single().defaultValue shouldBe "7.5"
    }

    @Test
    fun `S024 RF-5 no aparece entre las bibliotecas del usuario ni cambia la biblioteca vinculada`() {
        val project = newProject("Calle", LibraryChoice.Library("default:general"))
        runBlocking { libraries.saveProjectCode(project, poste) }

        runBlocking { libraries.libraries().first() }.map { it.id } shouldBe listOf("default:general")
        runBlocking { libraries.projectCopy(project).first() }.shouldNotBeNull().codes.map { it.code } shouldBe listOf("ARQ", "CAM")
    }

    @Test
    fun `S024 RF-6 un codigo que ya esta en la biblioteca vinculada o en el proyecto no se guarda`() {
        val project = newProject("Calle", LibraryChoice.Library("default:general"))
        runBlocking { libraries.saveProjectCode(project, poste) }

        val fromLibrary = runBlocking { libraries.saveProjectCode(project, poste.copy(code = "arq")) }
        val fromProject = runBlocking { libraries.saveProjectCode(project, poste.copy(code = "pos")) }
        val asLine = runBlocking { libraries.saveProjectCode(project, poste.copy(code = "Cam")) }

        fromLibrary shouldBe CodeSaveResult.Invalid(CodeError.Duplicate("ARQ"))
        fromProject shouldBe CodeSaveResult.Invalid(CodeError.Duplicate("POS"))
        asLine shouldBe CodeSaveResult.Invalid(CodeError.Duplicate("CAM"))
        codesOf(project).map { it.code } shouldBe listOf("POS")
    }

    @Test
    fun `S024 RF-6 el mismo codigo puede existir en dos proyectos`() {
        val a = newProject("A")
        val b = newProject("B")

        runBlocking { libraries.saveProjectCode(a, poste).shouldBeInstanceOf<CodeSaveResult.Saved>() }
        runBlocking { libraries.saveProjectCode(b, poste).shouldBeInstanceOf<CodeSaveResult.Saved>() }

        codesOf(a).size shouldBe 1
        codesOf(b).size shouldBe 1
    }

    @Test
    fun `S024 RF-7 editar un codigo del proyecto cambia sus datos y borrarlo no toca los puntos ya guardados`() {
        val project = newProject("Calle")
        val id = (runBlocking { libraries.saveProjectCode(project, poste) } as CodeSaveResult.Saved).id
        val stored = PointCode("POS", "Poste", 0xD32F2F, CodeSymbol.TRIANGLE, listOf(PointAttribute("Altura", AttributeType.NUMBER, "7.5")))
        runBlocking {
            points.create(
                Point(
                    "p1", project, "1", "", PointOrigin.MANUAL,
                    PointCoordinates(GeodeticPosition(40.4, -3.7, null), 1.0, 2.0, null), null, null, false, false, null, 1, null,
                    code = stored,
                ),
            )
        }

        runBlocking { libraries.saveProjectCode(project, poste.copy(id = id, color = 0x1E88E5, symbol = CodeSymbol.STAR)) }
        codesOf(project).single().symbol shouldBe CodeSymbol.STAR
        runBlocking { libraries.deleteProjectCode(project, id) } shouldBe CodeSaveResult.Deleted

        codesOf(project).shouldBeEmpty()
        runBlocking { points.point("p1").first() }.shouldNotBeNull().code shouldBe stored
    }

    @Test
    fun `S024 RF-7 los codigos del proyecto se mantienen al cambiar o quitar la biblioteca vinculada`() {
        val project = newProject("Calle", LibraryChoice.Library("default:general"))
        runBlocking { libraries.saveProjectCode(project, poste) }

        runBlocking { projects.update(project, ProjectDefaults().toDraft().copy(name = "Calle", libraryChoice = LibraryChoice.None)) }

        runBlocking { libraries.projectCopy(project).first() } shouldBe null
        codesOf(project).map { it.code } shouldBe listOf("POS")
    }

    @Test
    fun `S024 RF-7 borrar el proyecto borra sus codigos y un codigo ajeno no se puede borrar`() {
        val a = newProject("A")
        val b = newProject("B")
        val idA = (runBlocking { libraries.saveProjectCode(a, poste) } as CodeSaveResult.Saved).id

        runBlocking { libraries.deleteProjectCode(b, idA) } shouldBe CodeSaveResult.NotFound
        db.openHelper.writableDatabase.execSQL("DELETE FROM projects WHERE id = ?", arrayOf<Any>(a))

        db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM library_codes").use {
            it.moveToFirst()
            it.getInt(0) shouldBe 0
        }
    }

    @Test
    fun `S024 RF-5 con un proyecto que no existe no se guarda nada`() {
        runBlocking { libraries.saveProjectCode("no-existe", poste) } shouldBe CodeSaveResult.NotFound
    }
}
