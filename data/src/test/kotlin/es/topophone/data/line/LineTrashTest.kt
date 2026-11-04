package es.topophone.data.line

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.core.model.codes.AttributeType
import es.topophone.core.model.codes.CodeGeometry
import es.topophone.core.model.codes.CodeSymbol
import es.topophone.core.model.codes.PointAttribute
import es.topophone.core.model.codes.PointCode
import es.topophone.core.model.line.LineChange
import es.topophone.core.model.point.GeodeticPosition
import es.topophone.core.model.point.Point
import es.topophone.core.model.point.PointCoordinates
import es.topophone.core.model.point.PointOrigin
import es.topophone.core.model.project.ProjectDefaults
import es.topophone.data.db.TopoPhoneDatabase
import es.topophone.data.point.RoomPointRepository
import es.topophone.data.project.ProjectRepository
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

/** Ocultar, borrar a la papelera, restaurar y borrar del todo una línea (S025/RF-23, RF-24). */
@RunWith(AndroidJUnit4::class)
class LineTrashTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val db = Room.inMemoryDatabaseBuilder(context, TopoPhoneDatabase::class.java).build()
    private var now = 1_000L
    private var seq = 0
    private val projects = RoomProjectRepository(db, clock = { now })
    private val points = RoomPointRepository(db, clock = { now })
    private val lines = RoomLineRepository(db, points, clock = { now }, newId = { "id${++seq}" })

    @After
    fun close() = db.close()

    private val project: String = runBlocking {
        projects.create(ProjectDefaults().toDraft().copy(name = "Calle Mayor 12")).shouldBeInstanceOf<SaveResult.Saved>().id
    }
    private val bord = PointCode(
        "BORD",
        "Bordillo",
        0x1E88E5,
        CodeSymbol.CIRCLE,
        listOf(PointAttribute("Material", AttributeType.TEXT, "Granito")),
    )

    private fun point(name: String) = Point(
        id = "id-$name",
        projectId = project,
        name = name,
        description = "",
        origin = PointOrigin.MANUAL,
        coordinates = PointCoordinates(GeodeticPosition(40.4, -3.7, null), 440_000.123, 4_472_000.987, null),
        measurement = null,
        typed = null,
        edited = false,
        hidden = false,
        copyOf = null,
        createdAtMs = 3_000,
        deletedAtMs = null,
    )

    private suspend fun setup(name: String = "BORD_1"): String {
        if (points.names(project).isEmpty()) listOf("1", "2", "3").forEach { points.create(point(it)) }
        val result = lines.createFromPoints(NewLine(project, name, bord, CodeGeometry.LINE, false, listOf("id-1", "id-2", "id-3")))
        return (result as LineSaveResult.Saved).id
    }

    @Test
    fun `S025 RF-23 ocultar y mostrar una linea se recuerda y no toca sus puntos`() = runBlocking<Unit> {
        val id = setup()
        val before = points.points(project).first()

        lines.setHidden(id, true)
        lines.line(id).first().shouldNotBeNull().hidden shouldBe true
        lines.summaries(project).first().single().hidden shouldBe true
        lines.setHidden(id, false)

        lines.line(id).first().shouldNotBeNull().hidden shouldBe false
        points.points(project).first() shouldBe before
    }

    @Test
    fun `S025 RF-24 borrar una linea la mueve a la papelera sin tocar sus puntos`() = runBlocking<Unit> {
        val id = setup()

        lines.moveToTrash(id)

        lines.lines(project).first() shouldBe emptyList()
        lines.summaries(project).first() shouldBe emptyList()
        val trashed = lines.trash(project).first().single()
        trashed.line.id shouldBe id
        trashed.daysLeft shouldBe ProjectRepository.TRASH_DAYS
        trashed.line.pointIds shouldBe listOf("id-1", "id-2", "id-3")
        points.points(project).first().size shouldBe 3
    }

    @Test
    fun `S025 RF-24 una linea activa deja de serlo al ir a la papelera`() = runBlocking<Unit> {
        lines.saveMeasured(point("1").copy(code = bord), CodeGeometry.LINE)

        lines.moveToTrash("id1")

        lines.active(project).first().shouldBeNull()
    }

    @Test
    fun `S025 RF-24 restaurar devuelve la linea con todos sus datos y su historial`() = runBlocking<Unit> {
        val id = setup()
        lines.edit(id, LineUpdate(name = "ACERA"))
        lines.moveToTrash(id)

        lines.restore(id) shouldBe LineSaveResult.Saved(id)

        val line = lines.line(id).first().shouldNotBeNull()
        line.name shouldBe "ACERA"
        line.deletedAtMs.shouldBeNull()
        line.pointIds shouldBe listOf("id-1", "id-2", "id-3")
        line.code shouldBe bord
        lines.history(id).first().single().change shouldBe LineChange.NAME
        lines.trash(project).first() shouldBe emptyList()
    }

    @Test
    fun `S025 RF-24 el nombre de una linea en la papelera queda libre y restaurar con ese nombre ocupado pide otro`() = runBlocking<Unit> {
        val first = setup("BORD_1")
        lines.moveToTrash(first)
        val second = setup("BORD_1")

        lines.restore(first) shouldBe LineSaveResult.DuplicateName
        lines.restore(first, "BORD_1 (2)") shouldBe LineSaveResult.Saved(first)

        lines.lines(project).first().map { it.name }.toSet() shouldBe setOf("BORD_1", "BORD_1 (2)")
        lines.line(second).first().shouldNotBeNull().name shouldBe "BORD_1"
    }

    @Test
    fun `S025 RF-24 borrar del todo quita la linea y su historial`() = runBlocking<Unit> {
        val id = setup()
        lines.edit(id, LineUpdate(name = "ACERA"))
        lines.moveToTrash(id)

        lines.deletePermanently(id)

        lines.line(id).first().shouldBeNull()
        lines.history(id).first() shouldBe emptyList()
        points.points(project).first().size shouldBe 3
    }

    @Test
    fun `S025 RF-24 una linea que no esta en la papelera no se borra del todo ni se restaura`() = runBlocking<Unit> {
        val id = setup()

        lines.deletePermanently(id)
        lines.restore(id) shouldBe LineSaveResult.NotFound

        lines.line(id).first().shouldNotBeNull()
    }

    @Test
    fun `S025 RF-24 a los 30 dias la papelera se vacia sola y antes no`() = runBlocking<Unit> {
        val id = setup()
        lines.moveToTrash(id)
        now += 29L * 24 * 60 * 60 * 1000
        lines.purgeTrash() shouldBe 0
        lines.trash(project).first().single().daysLeft shouldBe 1

        now += 2L * 24 * 60 * 60 * 1000

        lines.purgeTrash() shouldBe 1
        lines.line(id).first().shouldBeNull()
    }
}
