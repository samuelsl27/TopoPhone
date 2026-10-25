package es.topophone.data.point

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.core.model.point.EntryForm
import es.topophone.core.model.point.GeodeticPosition
import es.topophone.core.model.point.HeightKind
import es.topophone.core.model.point.Point
import es.topophone.core.model.point.PointCoordinates
import es.topophone.core.model.point.PointOrigin
import es.topophone.core.model.point.TypedCoordinates
import es.topophone.core.model.project.ProjectDefaults
import es.topophone.data.db.TopoPhoneDatabase
import es.topophone.data.project.RoomProjectRepository
import es.topophone.data.project.SaveResult
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith

/** Guardar los puntos de una importación de golpe y deshacerla (S028/RF-19, RF-25, RF-26, RF-27, RF-29). */
@RunWith(AndroidJUnit4::class)
class PointRepositoryImportTest {

    private val db = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext<Context>(),
        TopoPhoneDatabase::class.java,
    ).build()
    private val projects = RoomProjectRepository(db)
    private val points = RoomPointRepository(db, clock = { 50_000 })

    @After
    fun close() = db.close()

    private val project = runBlocking {
        projects.create(ProjectDefaults().toDraft().copy(name = "Calle Mayor 12")).shouldBeInstanceOf<SaveResult.Saved>().id
    }

    private fun imported(name: String, importId: String? = "imp-1", projectId: String = project, createdAtMs: Long = 1_000) = Point(
        id = "id-$projectId-$name-$importId",
        projectId = projectId,
        name = name,
        description = "d $name",
        origin = PointOrigin.IMPORTED,
        coordinates = PointCoordinates(
            GeodeticPosition(40.416801234567891, -3.703712345678901, 699.423),
            440_290.434567891,
            4_474_257.414123456,
            648.340987654,
        ),
        measurement = null,
        typed = TypedCoordinates(EntryForm.PROJECT_CRS, 440_290.434567891, 4_474_257.414123456, 648.340987654, HeightKind.PROJECT),
        edited = false,
        hidden = false,
        copyOf = null,
        createdAtMs = createdAtMs,
        deletedAtMs = null,
        importId = importId,
    )

    private fun active(projectId: String = project) = runBlocking { points.points(projectId).first() }

    @Test
    fun `S028 RF-25 createAll guarda todos los puntos en el orden dado`() {
        val batch = listOf("3", "1", "2").mapIndexed { i, name -> imported(name, createdAtMs = 1_000L + i) }

        runBlocking { points.createAll(batch) } shouldBe PointSaveResult.Saved

        active().map { it.name } shouldBe listOf("3", "1", "2")
        active().all { it.origin == PointOrigin.IMPORTED && it.importId == "imp-1" } shouldBe true
    }

    @Test
    fun `S028 RF-26 si un nombre ya esta en el proyecto no se guarda ninguno`() {
        runBlocking { points.create(imported("2", importId = null)) } shouldBe PointSaveResult.Saved

        runBlocking { points.createAll(listOf(imported("1"), imported("2"), imported("3"))) } shouldBe PointSaveResult.DuplicateName

        active().map { it.name } shouldBe listOf("2")
    }

    @Test
    fun `S028 RF-26 si dos puntos del lote repiten nombre no se guarda ninguno`() {
        runBlocking { points.createAll(listOf(imported("1"), imported("2").copy(id = "x"), imported("2").copy(id = "y"))) } shouldBe
            PointSaveResult.DuplicateName

        active() shouldBe emptyList()
    }

    @Test
    fun `S028 RF-26 un lote vacio no hace nada`() {
        runBlocking { points.createAll(emptyList()) } shouldBe PointSaveResult.Saved

        active() shouldBe emptyList()
    }

    @Test
    fun `S028 RF-27 las coordenadas escritas y las calculadas se guardan sin redondear`() {
        runBlocking { points.createAll(listOf(imported("1"))) }

        val read = active().single()

        read shouldBe imported("1")
        read.typed?.first shouldBe 440_290.434567891
        read.coordinates.ground.latitudeDeg shouldBe 40.416801234567891
        read.measurement shouldBe null
    }

    @Test
    fun `S028 RF-19 importar no modifica los puntos que ya estaban`() {
        val existing = imported("existing", importId = null, createdAtMs = 500)
        runBlocking { points.create(existing) }

        runBlocking { points.createAll(listOf(imported("1"), imported("2"))) }

        active().first { it.name == "existing" } shouldBe existing
    }

    @Test
    fun `S028 RF-29 trashImport manda a la papelera los puntos de esa importacion y devuelve cuantos`() {
        runBlocking {
            points.create(imported("manual", importId = null))
            points.createAll(listOf(imported("1"), imported("2"), imported("3")))
            points.createAll(listOf(imported("A", importId = "imp-2")))
        }

        val moved = runBlocking { points.trashImport("imp-1") }

        moved shouldBe 3
        active().map { it.name }.toSet() shouldBe setOf("manual", "A")
        runBlocking { points.trash(project).first() }.map { it.point.name }.toSet() shouldBe setOf("1", "2", "3")
    }

    @Test
    fun `S028 RF-29 trashImport no cuenta los que ya estaban en la papelera`() {
        runBlocking { points.createAll(listOf(imported("1"), imported("2"), imported("3"))) }
        runBlocking { points.moveToTrash(listOf("id-$project-2-imp-1")) }

        val moved = runBlocking { points.trashImport("imp-1") }

        moved shouldBe 2
        active() shouldBe emptyList()
    }

    @Test
    fun `S028 RF-29 deshacer una importacion que ya no tiene puntos activos devuelve cero`() {
        runBlocking { points.trashImport("imp-1") } shouldBe 0
        runBlocking { points.createAll(listOf(imported("1"))) }
        runBlocking { points.moveToTrash(listOf("id-$project-1-imp-1")) }

        runBlocking { points.trashImport("imp-1") } shouldBe 0
    }

    @Test
    fun `S028 RF-29 los nombres de los puntos deshechos quedan libres para importar de nuevo`() {
        runBlocking { points.createAll(listOf(imported("1"), imported("2"))) }
        runBlocking { points.trashImport("imp-1") }

        runBlocking { points.createAll(listOf(imported("1", importId = "imp-3"), imported("2", importId = "imp-3"))) } shouldBe
            PointSaveResult.Saved

        active().map { it.name } shouldBe listOf("1", "2")
    }

    @Test
    fun `S028 RF-29 un punto de la importacion devuelto de la papelera sigue siendo importado`() {
        runBlocking { points.createAll(listOf(imported("1"))) }
        runBlocking { points.trashImport("imp-1") }

        runBlocking { points.restore("id-$project-1-imp-1") } shouldBe PointSaveResult.Saved

        active().single().importId shouldBe "imp-1"
    }

    @Test
    fun `S028 RF-29 trashImport solo toca la importacion pedida, aunque haya puntos de otro proyecto`() {
        val other = runBlocking {
            projects.create(ProjectDefaults().toDraft().copy(name = "Otro")).shouldBeInstanceOf<SaveResult.Saved>().id
        }
        runBlocking {
            points.createAll(listOf(imported("1")))
            points.createAll(listOf(imported("1", importId = "imp-9", projectId = other)))
        }

        runBlocking { points.trashImport("imp-1") } shouldBe 1

        active(other).map { it.name } shouldBe listOf("1")
    }
}
