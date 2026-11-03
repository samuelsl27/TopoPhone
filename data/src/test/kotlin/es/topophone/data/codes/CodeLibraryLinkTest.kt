package es.topophone.data.codes

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.core.model.codes.AttributeType
import es.topophone.core.model.codes.CodeAttribute
import es.topophone.core.model.codes.CodeGeometry
import es.topophone.core.model.codes.CodeLibrary
import es.topophone.core.model.codes.LibraryChoice
import es.topophone.core.model.codes.SurveyCode
import es.topophone.core.model.point.GeodeticPosition
import es.topophone.core.model.point.Measurement
import es.topophone.core.model.point.MeasurementMode
import es.topophone.core.model.point.Point
import es.topophone.core.model.point.PointCoordinates
import es.topophone.core.model.point.PointQuality
import es.topophone.core.model.project.ProjectDefaults
import es.topophone.data.db.TopoPhoneDatabase
import es.topophone.data.point.PointSaveResult
import es.topophone.data.point.RoomPointRepository
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

/** Vincular una biblioteca a un proyecto: copia propia, cambiar, quitar, duplicar y borrar la original (S023/RF-4, RF-19 a RF-21). */
@RunWith(AndroidJUnit4::class)
class CodeLibraryLinkTest {

    private val db = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext<Context>(),
        TopoPhoneDatabase::class.java,
    ).build()
    private var counter = 0
    private val newId: () -> String = { "id${++counter}" }
    private val general = CodeLibrary(
        "default:general",
        "Topografía general",
        true,
        listOf(
            SurveyCode(
                "g1",
                "ARQ",
                "Arqueta",
                CodeGeometry.POINT,
                0x1E88E5,
                listOf(CodeAttribute("Material", AttributeType.LIST, "PVC", listOf("PVC", "Hormigón"))),
            ),
        ),
    )
    private val defaults = DefaultLibrarySource { listOf(general) }
    private val libraries = RoomCodeLibraryRepository(db, defaults, clock = { 1L }, newId = newId)
    private val projects = RoomProjectRepository(db, newId = newId, libraries = ProjectLibraryLinker(db, defaults, newId = newId))

    @After
    fun close() = db.close()

    private fun newProject(name: String, choice: LibraryChoice = LibraryChoice.Keep): String = runBlocking {
        projects.create(ProjectDefaults().toDraft().copy(name = name, libraryChoice = choice)).shouldBeInstanceOf<SaveResult.Saved>().id
    }

    private fun copyOf(projectId: String): CodeLibrary? = runBlocking { libraries.projectCopy(projectId).first() }

    private fun userLibrary(name: String, code: String): String = runBlocking {
        val id = (libraries.create(name) as LibraryResult.Saved).id
        libraries.saveCode(id, SurveyCode("", code, "", CodeGeometry.LINE, 0xE64A19))
        id
    }

    @Test
    fun `S023 RF-19 un proyecto sin biblioteca vinculada no tiene copia`() {
        copyOf(newProject("Sin")) shouldBe null
    }

    @Test
    fun `S023 RF-20 al crear un proyecto con una biblioteca por defecto lleva una copia propia con sus atributos`() {
        val project = newProject("Camino", LibraryChoice.Library("default:general"))

        val copy = copyOf(project).shouldNotBeNull()

        copy.name shouldBe "Topografía general"
        copy.codes.map { it.code } shouldBe listOf("ARQ")
        copy.codes.single().attributes shouldBe general.codes.single().attributes
        runBlocking { libraries.libraries().first().none { it.name == "Topografía general" && !it.isDefault } } shouldBe true
    }

    @Test
    fun `S023 RF-20 los cambios posteriores de la biblioteca no cambian la copia`() {
        val library = userLibrary("Mía", "FAR")
        val project = newProject("Camino", LibraryChoice.Library(library))

        runBlocking {
            libraries.saveCode(library, SurveyCode("", "POZ", "", CodeGeometry.POINT, 1))
            libraries.rename(library, "Mía renombrada")
        }

        copyOf(project).shouldNotBeNull().let {
            it.name shouldBe "Mía"
            it.codes.map { c -> c.code } shouldBe listOf("FAR")
        }
    }

    @Test
    fun `S023 RF-4 borrar la biblioteca original no toca la copia de ningun proyecto`() {
        val library = userLibrary("Mía", "FAR")
        val project = newProject("Camino", LibraryChoice.Library(library))

        runBlocking { libraries.delete(library) } shouldBe LibraryResult.Deleted

        copyOf(project).shouldNotBeNull().codes.map { it.code } shouldBe listOf("FAR")
    }

    @Test
    fun `S023 RF-21 cambiar la biblioteca sustituye la copia, mantener la deja y quitar la borra, sin tocar los puntos`() {
        val first = userLibrary("Primera", "AAA")
        val second = userLibrary("Segunda", "BBB")
        val project = newProject("Camino", LibraryChoice.Library(first))
        val points = RoomPointRepository(db)
        runBlocking { points.create(point("p1", project)) shouldBe PointSaveResult.Saved }
        val draft = runBlocking { projects.project(project).shouldNotBeNull().toDraft() }

        runBlocking { projects.update(project, draft) } // mantener
        copyOf(project).shouldNotBeNull().codes.map { it.code } shouldBe listOf("AAA")

        runBlocking { projects.update(project, draft.copy(libraryChoice = LibraryChoice.Library(second))) }
        copyOf(project).shouldNotBeNull().codes.map { it.code } shouldBe listOf("BBB")
        count("code_libraries WHERE project_id IS NOT NULL") shouldBe 1

        runBlocking { projects.update(project, draft.copy(libraryChoice = LibraryChoice.None)) }
        copyOf(project) shouldBe null
        count("code_libraries WHERE project_id IS NOT NULL") shouldBe 0
        count("library_codes") shouldBe 2 // solo los de las dos bibliotecas del usuario
        runBlocking { points.points(project).first().map { it.name } } shouldBe listOf("p1")
    }

    @Test
    fun `S023 RF-21 vincular una biblioteca que ya no existe no cambia la copia`() {
        val library = userLibrary("Mía", "FAR")
        val project = newProject("Camino", LibraryChoice.Library(library))
        val draft = runBlocking { projects.project(project).shouldNotBeNull().toDraft() }

        runBlocking { projects.update(project, draft.copy(libraryChoice = LibraryChoice.Library("no-existe"))) }

        copyOf(project).shouldNotBeNull().codes.map { it.code } shouldBe listOf("FAR")
    }

    @Test
    fun `S023 RF-20 duplicar un proyecto duplica tambien su copia, con ids propios`() {
        val project = newProject("Camino", LibraryChoice.Library("default:general"))

        val duplicate = runBlocking { (projects.duplicate(project, "copia") as SaveResult.Saved).id }

        copyOf(duplicate).shouldNotBeNull().codes.map { it.code } shouldBe listOf("ARQ")
        db.codeLibraries().let { runBlocking { it.copyOf(duplicate)!!.id != it.copyOf(project)!!.id } } shouldBe true
        count("code_libraries WHERE project_id IS NOT NULL") shouldBe 2
    }

    @Test
    fun `S023 RF-20 borrar el proyecto para siempre borra su copia`() {
        val project = newProject("Camino", LibraryChoice.Library("default:general"))

        runBlocking { projects.deleteForever(project) }

        count("code_libraries") shouldBe 0
        count("library_codes") shouldBe 0
        copyOf(project).shouldBeNull()
    }

    private fun count(from: String): Int = db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM $from").use {
        it.moveToFirst()
        it.getInt(0)
    }

    private fun point(name: String, projectId: String) = Point.measured(
        id = name,
        projectId = projectId,
        name = name,
        description = "",
        measurement = Measurement(
            antenna = GeodeticPosition(40.416_801_234_567_89, -3.703_712_345_678_9, 701.357_123_456),
            poleHeightM = 1.8,
            quality = PointQuality("FIX", 24, 0.6, 1.0, 0.009, 0.014, MeasurementMode.INSTANT, 1, 0, fromReplay = false),
            rawNmea = "",
            outOfTolerance = emptySet(),
            measuredFromMs = 1,
            measuredToMs = 2,
            coordinates = PointCoordinates(GeodeticPosition(40.4168, -3.7037, 699.4), 440_290.4, 4_474_257.4, 648.3),
        ),
        createdAtMs = 3,
    )
}
