package es.topophone.data.line

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.core.model.codes.CodeGeometry
import es.topophone.core.model.codes.CodeSymbol
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
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith

/** Un punto que es vértice de líneas va a la papelera, se restaura o se borra del todo (S025/RF-25). */
@RunWith(AndroidJUnit4::class)
class LinePointTrashTest {

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
    private val bord = PointCode("BORD", "", 0x1E88E5, CodeSymbol.CIRCLE)

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

    private suspend fun line(name: String, vararg ids: String): String {
        val result = lines.createFromPoints(NewLine(project, name, bord, CodeGeometry.LINE, false, ids.toList()))
        return (result as LineSaveResult.Saved).id
    }

    private suspend fun setup(): Pair<String, String> {
        listOf("1", "2", "3", "4").forEach { points.create(point(it)) }
        return line("BORD_1", "id-1", "id-2", "id-3") to line("TAPIA_1", "id-2", "id-4")
    }

    @Test
    fun `S025 RF-25 el aviso nombra las lineas activas que usan el punto`() = runBlocking<Unit> {
        val (_, other) = setup()

        lines.linesUsing(listOf("id-2")) shouldBe listOf("BORD_1", "TAPIA_1")
        lines.linesUsing(listOf("id-1", "id-4")) shouldBe listOf("BORD_1", "TAPIA_1")
        lines.linesUsing(listOf("nada")) shouldBe emptyList()
        lines.moveToTrash(other)
        lines.linesUsing(listOf("id-2")) shouldBe listOf("BORD_1")
    }

    @Test
    fun `S025 RF-25 mientras el punto esta en la papelera las lineas lo omiten y lo recuperan en su sitio al restaurarlo`() =
        runBlocking<Unit> {
            val (first, second) = setup()

            points.moveToTrash(listOf("id-2"))

            lines.line(first).first().shouldNotBeNull().pointIds shouldBe listOf("id-1", "id-3")
            lines.line(second).first().shouldNotBeNull().pointIds shouldBe listOf("id-4")
            points.restore("id-2")
            lines.line(first).first().shouldNotBeNull().pointIds shouldBe listOf("id-1", "id-2", "id-3")
            lines.line(second).first().shouldNotBeNull().pointIds shouldBe listOf("id-2", "id-4")
        }

    @Test
    fun `S025 RF-25 al borrar el punto del todo las lineas lo quitan y lo anotan en su historial`() = runBlocking<Unit> {
        val (first, second) = setup()
        points.moveToTrash(listOf("id-2"))

        points.deletePermanently("id-2")

        lines.line(first).first().shouldNotBeNull().pointIds shouldBe listOf("id-1", "id-3")
        lines.line(second).first().shouldNotBeNull().pointIds shouldBe listOf("id-4")
        val entry = lines.history(first).first().single()
        entry.change shouldBe LineChange.VERTEX_REMOVED
        entry.previous.vertices.map { it.pointName } shouldBe listOf("1", "2", "3")
        lines.history(second).first().single().change shouldBe LineChange.VERTEX_REMOVED
    }

    @Test
    fun `S025 RF-25 un punto que no esta en la papelera no se borra del todo ni deja historial`() = runBlocking<Unit> {
        val (first, _) = setup()

        points.deletePermanently("id-2")

        lines.line(first).first().shouldNotBeNull().pointIds shouldBe listOf("id-1", "id-2", "id-3")
        lines.history(first).first() shouldBe emptyList()
    }

    @Test
    fun `S025 RF-25 al vaciarse la papelera de puntos a los 30 dias tambien se anota en las lineas`() = runBlocking<Unit> {
        val (first, _) = setup()
        points.moveToTrash(listOf("id-3"))
        now += (ProjectRepository.TRASH_DAYS + 1L) * 24 * 60 * 60 * 1000

        points.purgeTrash() shouldBe 1

        lines.line(first).first().shouldNotBeNull().pointIds shouldBe listOf("id-1", "id-2")
        lines.history(first).first().single().change shouldBe LineChange.VERTEX_REMOVED
    }

    @Test
    fun `S025 RF-25 borrar una linea no borra sus puntos y borrar un punto no borra la linea`() = runBlocking<Unit> {
        val (first, _) = setup()
        points.moveToTrash(listOf("id-1", "id-2", "id-3"))
        points.deletePermanently("id-1")
        points.deletePermanently("id-2")
        points.deletePermanently("id-3")

        lines.line(first).first().shouldNotBeNull().pointIds shouldBe emptyList()
        points.points(project).first().map { it.id } shouldBe listOf("id-4")
    }
}
