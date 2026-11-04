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

/** Cerrar en polígono, abrir y crear una línea desde puntos que ya existen (S025/RF-10, RF-11, RF-14, RF-15). */
@RunWith(AndroidJUnit4::class)
class LineCloseAndCreateTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val db = Room.inMemoryDatabaseBuilder(context, TopoPhoneDatabase::class.java).build()
    private var now = 1_000L
    private var seq = 0
    private val projects = RoomProjectRepository(db, clock = { now })
    private val points = RoomPointRepository(db, clock = { now })
    private val lines = RoomLineRepository(db, points, clock = {
        now += 10L
        now
    }, newId = { "line-${++seq}" })

    @After
    fun close() = db.close()

    private val project: String = runBlocking {
        projects.create(ProjectDefaults().toDraft().copy(name = "Calle Mayor 12")).shouldBeInstanceOf<SaveResult.Saved>().id
    }
    private val parc = PointCode("PARC", "Parcela", 0x43A047, CodeSymbol.CIRCLE)

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

    private suspend fun createPoints(vararg names: String) = names.forEach { points.create(point(it)) }

    private suspend fun create(ids: List<String>, closed: Boolean = false, name: String? = null) =
        lines.createFromPoints(NewLine(project, name, parc, CodeGeometry.POLYGON, closed, ids))

    @Test
    fun `S025 RF-14 la linea se crea con los puntos elegidos en su orden y con el nombre propuesto`() = runBlocking<Unit> {
        createPoints("1", "2", "3")

        create(listOf("id-3", "id-1", "id-2")) shouldBe LineSaveResult.Saved("line-1")

        val line = lines.line("line-1").first().shouldNotBeNull()
        line.pointIds shouldBe listOf("id-3", "id-1", "id-2")
        line.name shouldBe "PARC_1"
        line.active shouldBe false
        line.code shouldBe parc
    }

    @Test
    fun `S025 RF-14 el nombre elegido por el usuario sustituye al propuesto y no puede repetirse`() = runBlocking<Unit> {
        createPoints("1", "2")

        create(listOf("id-1", "id-2"), name = "Mi parcela") shouldBe LineSaveResult.Saved("line-1")
        create(listOf("id-1", "id-2"), name = "Mi parcela") shouldBe LineSaveResult.DuplicateName

        lines.lines(project).first().map { it.name } shouldBe listOf("Mi parcela")
    }

    @Test
    fun `S025 RF-14 crear la linea no modifica ningun punto`() = runBlocking<Unit> {
        createPoints("1", "2")
        val before = points.points(project).first()

        create(listOf("id-1", "id-2"))

        points.points(project).first() shouldBe before
    }

    @Test
    fun `S025 RF-15 con menos puntos de los necesarios o dos iguales seguidos no se crea`() = runBlocking<Unit> {
        createPoints("1", "2", "3")

        create(listOf("id-1")) shouldBe LineSaveResult.TooFewVertices
        create(listOf("id-1", "id-2"), closed = true) shouldBe LineSaveResult.TooFewVertices
        create(listOf("id-1", "id-1", "id-2")) shouldBe LineSaveResult.RepeatedVertex

        lines.lines(project).first() shouldBe emptyList()
    }

    @Test
    fun `S025 RF-15 un punto que no existe o esta en la papelera no se admite`() = runBlocking<Unit> {
        createPoints("1", "2")
        points.moveToTrash(listOf("id-2"))

        create(listOf("id-1", "id-2")) shouldBe LineSaveResult.NotFound
        create(listOf("id-1", "nada")) shouldBe LineSaveResult.NotFound
    }

    @Test
    fun `S025 RF-10 cerrar una linea de 3 vertices la convierte en poligono y queda en el historial`() = runBlocking<Unit> {
        createPoints("1", "2", "3")
        create(listOf("id-1", "id-2", "id-3"))

        lines.close("line-1") shouldBe LineSaveResult.Saved("line-1")

        val line = lines.line("line-1").first().shouldNotBeNull()
        line.closed shouldBe true
        line.pointIds shouldBe listOf("id-1", "id-2", "id-3")
        val entry = lines.history("line-1").first().single()
        entry.change shouldBe LineChange.CLOSED
        entry.previous.closed shouldBe false
        entry.previous.vertices.map { it.pointName } shouldBe listOf("1", "2", "3")
    }

    @Test
    fun `S025 RF-11 una linea de menos de 3 vertices no se cierra`() = runBlocking<Unit> {
        createPoints("1", "2")
        create(listOf("id-1", "id-2"))

        lines.close("line-1") shouldBe LineSaveResult.TooFewVertices

        lines.line("line-1").first().shouldNotBeNull().closed shouldBe false
        lines.history("line-1").first() shouldBe emptyList()
    }

    @Test
    fun `S025 RF-10 un poligono se abre con sus vertices y su sentido intactos y queda en el historial`() = runBlocking<Unit> {
        createPoints("1", "2", "3")
        create(listOf("id-3", "id-1", "id-2"), closed = true)

        lines.open("line-1") shouldBe LineSaveResult.Saved("line-1")

        val line = lines.line("line-1").first().shouldNotBeNull()
        line.closed shouldBe false
        line.pointIds shouldBe listOf("id-3", "id-1", "id-2")
        lines.history("line-1").first().single().previous.closed shouldBe true
    }

    @Test
    fun `S025 RF-10 cerrar una linea que no existe lo dice`() = runBlocking<Unit> {
        lines.close("nada") shouldBe LineSaveResult.NotFound
    }

    @Test
    fun `S025 RF-14 el nombre propuesto sigue la numeracion del codigo`() = runBlocking<Unit> {
        createPoints("1", "2")
        create(listOf("id-1", "id-2"))

        lines.suggestName(project, "PARC") shouldBe "PARC_2"
        lines.suggestName(project, "OTRO") shouldBe "OTRO_1"
    }
}
