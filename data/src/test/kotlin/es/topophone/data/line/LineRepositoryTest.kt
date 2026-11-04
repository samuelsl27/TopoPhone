package es.topophone.data.line

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.core.model.codes.AttributeType
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

/** Las líneas del proyecto en la base de datos: lectura, resumen, línea activa e historial (S025/RF-3, RF-9, RF-18, RF-21). */
@RunWith(AndroidJUnit4::class)
class LineRepositoryTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val db = Room.inMemoryDatabaseBuilder(context, TopoPhoneDatabase::class.java).build()
    private var now = 1_000L
    private val projects = RoomProjectRepository(db, clock = { now })
    private val points = RoomPointRepository(db, clock = { now })
    private val lines = RoomLineRepository(db, points, clock = { now })
    private val dao = db.lines()

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
        listOf(PointAttribute("Material", AttributeType.TEXT, "Granito"), PointAttribute("Alto", AttributeType.NUMBER, "0.15")),
    )

    private fun point(name: String) = Point(
        id = "id-$name",
        projectId = project,
        name = name,
        description = "",
        origin = PointOrigin.MANUAL,
        coordinates = PointCoordinates(GeodeticPosition(40.4, -3.7, null), 440_000.0 + name.hashCode() % 100, 4_472_000.0, null),
        measurement = null,
        typed = null,
        edited = false,
        hidden = false,
        copyOf = null,
        createdAtMs = 3_000,
        deletedAtMs = null,
    )

    /** Escribe una línea a mano con el DAO: las escrituras del repositorio llegan en las tareas siguientes. */
    private suspend fun insertLine(
        id: String,
        name: String,
        vertexIds: List<String>,
        active: Boolean = false,
        closed: Boolean = false,
        code: PointCode? = bord,
        createdAt: Long = 5_000,
    ) {
        dao.insert(
            ProjectLineEntity(
                id,
                project,
                name,
                "LINE",
                closed,
                false,
                active,
                code?.code,
                code?.description,
                code?.color,
                code?.symbol?.name,
                createdAt,
            ),
        )
        dao.insertVertices(vertexIds.mapIndexed { i, p -> LineVertexEntity(id, i, p) })
        dao.insertAttributes(code?.attributes.orEmpty().mapIndexed { i, a -> LineAttributeEntity(id, i, a.name, a.type.name, a.value) })
    }

    private suspend fun createPoints(vararg names: String) = names.forEach { points.create(point(it)) }

    @Test
    fun `S025 RF-21 la linea vuelve con su codigo, atributos, estado y vertices en orden`() = runBlocking<Unit> {
        createPoints("1", "2", "3")
        insertLine("l1", "BORD_1", listOf("id-3", "id-1", "id-2"), active = true, closed = true)

        val line = lines.line("l1").first().shouldNotBeNull()

        line.name shouldBe "BORD_1"
        line.code shouldBe bord
        line.closed shouldBe true
        line.active shouldBe true
        line.pointIds shouldBe listOf("id-3", "id-1", "id-2")
        lines.lines(project).first().single().pointIds shouldBe listOf("id-3", "id-1", "id-2")
    }

    @Test
    fun `S025 RF-21 el resumen cuenta los vertices y los lleva con su codigo`() = runBlocking<Unit> {
        createPoints("1", "2")
        insertLine("l1", "BORD_1", listOf("id-1", "id-2"))
        insertLine("l2", "SIN", emptyList(), code = null, createdAt = 6_000)

        val summaries = lines.summaries(project).first()

        summaries.map { it.name } shouldBe listOf("BORD_1", "SIN")
        summaries[0].vertexCount shouldBe 2
        summaries[0].code shouldBe bord
        summaries[1].code.shouldBeNull()
    }

    @Test
    fun `S025 RF-9 la linea activa del proyecto se lee y sin ninguna es nula`() = runBlocking<Unit> {
        createPoints("1")
        lines.active(project).first().shouldBeNull()
        insertLine("l1", "BORD_1", listOf("id-1"))
        lines.active(project).first().shouldBeNull()
        dao.clearActive(project)
        dao.update(dao.byId("l1")!!.copy(active = true))

        lines.active(project).first().shouldNotBeNull().id shouldBe "l1"
    }

    @Test
    fun `S025 RF-25 un vertice cuyo punto esta en la papelera se omite al leer y vuelve al restaurarlo`() = runBlocking<Unit> {
        createPoints("1", "2", "3")
        insertLine("l1", "BORD_1", listOf("id-1", "id-2", "id-3"))

        points.moveToTrash(listOf("id-2"))

        lines.line("l1").first().shouldNotBeNull().pointIds shouldBe listOf("id-1", "id-3")
        lines.summaries(project).first().single().vertexCount shouldBe 2
        points.restore("id-2")
        lines.line("l1").first().shouldNotBeNull().pointIds shouldBe listOf("id-1", "id-2", "id-3")
    }

    @Test
    fun `S025 RF-18 el historial vuelve con el estado anterior, sus atributos y sus vertices`() = runBlocking<Unit> {
        createPoints("1", "2")
        insertLine("l1", "BORD_1", listOf("id-1", "id-2"))
        dao.insertHistory(LineHistoryEntity("h1", "l1", 2_000, "NAME", "ACERA", false, "BORD", "Bordillo", 0x1E88E5, "CIRCLE"))
        dao.insertHistoryAttributes(listOf(LineHistoryAttributeEntity("h1", 0, "Material", "TEXT", "Hormigon")))
        dao.insertHistoryVertices(listOf(LineHistoryVertexEntity("h1", 0, "id-1", "1")))

        val entry = lines.history("l1").first().single()

        entry.changedAtMs shouldBe 2_000
        entry.change shouldBe LineChange.NAME
        entry.previous.name shouldBe "ACERA"
        entry.previous.code?.attributes shouldBe listOf(PointAttribute("Material", AttributeType.TEXT, "Hormigon"))
        entry.previous.vertices.map { it.pointName } shouldBe listOf("1")
    }
}
