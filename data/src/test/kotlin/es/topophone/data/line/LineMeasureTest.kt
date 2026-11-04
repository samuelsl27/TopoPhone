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
import es.topophone.core.model.point.GeodeticPosition
import es.topophone.core.model.point.Point
import es.topophone.core.model.point.PointCoordinates
import es.topophone.core.model.point.PointOrigin
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

/** Medir con códigos de línea: crear, añadir, deshacer, terminar y continuar, todo en la base de datos (S025/RF-2 a RF-9). */
@RunWith(AndroidJUnit4::class)
class LineMeasureTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val db = Room.inMemoryDatabaseBuilder(context, TopoPhoneDatabase::class.java).build()
    private var now = 1_000L
    private var seq = 0
    private val projects = RoomProjectRepository(db, clock = { now })
    private val points = RoomPointRepository(db, clock = { now })
    private val lines = RoomLineRepository(db, points, clock = { now }, newId = { "line-${++seq}" })

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
    private val tapia = PointCode("TAPIA", "", 0xD32F2F, CodeSymbol.CIRCLE)

    private fun point(name: String, code: PointCode?) = Point(
        id = "id-$name",
        projectId = project,
        name = name,
        description = "",
        origin = PointOrigin.MANUAL,
        coordinates = PointCoordinates(GeodeticPosition(40.4, -3.7, null), 440_000.0, 4_472_000.0, null),
        measurement = null,
        typed = null,
        edited = false,
        hidden = false,
        copyOf = null,
        createdAtMs = 3_000,
        deletedAtMs = null,
        code = code,
    )

    private suspend fun measure(name: String, code: PointCode? = bord, geometry: CodeGeometry? = CodeGeometry.LINE) =
        lines.saveMeasured(point(name, code), geometry)

    @Test
    fun `S025 RF-2 el primer punto con codigo de linea crea la linea con su nombre y la deja activa`() = runBlocking<Unit> {
        measure("1") shouldBe MeasuredResult.Added("line-1", "BORD_1", 1, created = true)

        val line = lines.active(project).first().shouldNotBeNull()
        line.pointIds shouldBe listOf("id-1")
        line.code shouldBe bord
        line.geometry shouldBe CodeGeometry.LINE
    }

    @Test
    fun `S025 RF-3 los puntos siguientes con su codigo se anaden como ultimo vertice`() = runBlocking<Unit> {
        measure("1")

        measure("2") shouldBe MeasuredResult.Added("line-1", "BORD_1", 2, created = false)
        (measure("3") as MeasuredResult.Added).vertexCount shouldBe 3
        lines.active(project).first().shouldNotBeNull().pointIds shouldBe listOf("id-1", "id-2", "id-3")
        lines.lines(project).first().size shouldBe 1
    }

    @Test
    fun `S025 RF-8 un punto con otro codigo o sin codigo no entra en la linea y esta sigue activa`() = runBlocking<Unit> {
        measure("1")

        measure("2", code = null, geometry = null) shouldBe MeasuredResult.PointOnly
        measure("3", PointCode("ARQ", "", 0, CodeSymbol.CIRCLE), CodeGeometry.POINT) shouldBe MeasuredResult.PointOnly

        lines.active(project).first().shouldNotBeNull().pointIds shouldBe listOf("id-1")
        points.point("id-2").first().shouldNotBeNull()
    }

    @Test
    fun `S025 RF-8 un codigo de linea distinto crea otra linea que pasa a ser la unica activa`() = runBlocking<Unit> {
        measure("1")

        measure("2", tapia) shouldBe MeasuredResult.Added("line-2", "TAPIA_1", 1, created = true)

        lines.active(project).first().shouldNotBeNull().id shouldBe "line-2"
        lines.lines(project).first().count { it.active } shouldBe 1
    }

    @Test
    fun `S025 RF-2 el nombre sigue la numeracion del codigo entre las lineas del proyecto`() = runBlocking<Unit> {
        measure("1")
        lines.finish("line-1")

        (measure("2") as MeasuredResult.Added).name shouldBe "BORD_2"
    }

    @Test
    fun `S025 RF-8b la linea guarda los atributos del codigo y el punto el codigo por valor`() = runBlocking<Unit> {
        measure("1")

        lines.line("line-1").first().shouldNotBeNull().code?.attributes shouldBe bord.attributes
        points.point("id-1").first().shouldNotBeNull().code shouldBe bord
    }

    @Test
    fun `S025 RNF-T6 si el punto no se guarda no queda ninguna linea ni vertice`() = runBlocking<Unit> {
        points.create(point("1", null))

        measure("1") shouldBe MeasuredResult.PointNotSaved(PointSaveResult.DuplicateName)

        lines.lines(project).first() shouldBe emptyList()
    }

    @Test
    fun `S025 RF-4 terminar deja de tener linea activa y un codigo de poligono con 3 vertices la cierra`() = runBlocking<Unit> {
        measure("1", geometry = CodeGeometry.POLYGON)
        measure("2", geometry = CodeGeometry.POLYGON)
        lines.finish("line-1") shouldBe LineActionResult.DONE
        lines.line("line-1").first().shouldNotBeNull().closed shouldBe false
        lines.active(project).first().shouldBeNull()

        lines.continueLine("line-1")
        measure("3", geometry = CodeGeometry.POLYGON)
        lines.finish("line-1")

        lines.line("line-1").first().shouldNotBeNull().closed shouldBe true
    }

    @Test
    fun `S025 RF-4 una linea con codigo de linea no se cierra al terminar`() = runBlocking<Unit> {
        listOf("1", "2", "3", "4").forEach { measure(it) }

        lines.finish("line-1")

        lines.line("line-1").first().shouldNotBeNull().closed shouldBe false
    }

    @Test
    fun `S025 RF-5 continuar deja activa una linea abierta y desactiva la anterior`() = runBlocking<Unit> {
        measure("1")
        lines.finish("line-1")
        measure("2", tapia)

        lines.continueLine("line-1") shouldBe LineActionResult.DONE

        lines.active(project).first().shouldNotBeNull().id shouldBe "line-1"
        measure("3")
        lines.line("line-1").first().shouldNotBeNull().pointIds shouldBe listOf("id-1", "id-3")
    }

    @Test
    fun `S025 RF-5 un poligono no se puede continuar y una linea que no existe tampoco`() = runBlocking<Unit> {
        listOf("1", "2", "3").forEach { measure(it, geometry = CodeGeometry.POLYGON) }
        lines.finish("line-1")

        lines.continueLine("line-1") shouldBe LineActionResult.NOT_OPEN
        lines.continueLine("nada") shouldBe LineActionResult.NOT_FOUND
    }

    @Test
    fun `S025 RF-6 deshacer quita el ultimo vertice y deja el punto en el proyecto`() = runBlocking<Unit> {
        measure("1")
        measure("2")

        lines.undoVertex("line-1") shouldBe LineActionResult.DONE

        lines.line("line-1").first().shouldNotBeNull().pointIds shouldBe listOf("id-1")
        points.point("id-2").first().shouldNotBeNull().deletedAtMs.shouldBeNull()
        measure("3")
        lines.line("line-1").first().shouldNotBeNull().pointIds shouldBe listOf("id-1", "id-3")
    }

    @Test
    fun `S025 RF-6 sin vertices que deshacer lo dice`() = runBlocking<Unit> {
        measure("1")
        lines.undoVertex("line-1")

        lines.undoVertex("line-1") shouldBe LineActionResult.NOTHING_TO_UNDO
    }

    @Test
    fun `S025 RF-7 el mismo punto que el ultimo vertice no se repite`() = runBlocking<Unit> {
        measure("1")

        // Un reintento de guardado del mismo punto: el punto ya existe, el vertice no se duplica.
        measure("1") shouldBe MeasuredResult.PointNotSaved(PointSaveResult.DuplicateName)

        lines.line("line-1").first().shouldNotBeNull().pointIds shouldBe listOf("id-1")
    }

    @Test
    fun `S025 RF-9 la linea activa se conserva al abrir otro repositorio sobre la misma base`() = runBlocking<Unit> {
        measure("1")
        measure("2")

        RoomLineRepository(db, points).active(project).first().shouldNotBeNull().pointIds shouldBe listOf("id-1", "id-2")
    }

    @Test
    fun `S025 RNF anadir un vertice a la linea activa cuesta menos de 100 ms con 500 vertices`() = runBlocking<Unit> {
        repeat(500) { measure("p$it") }
        val start = System.nanoTime()

        repeat(10) { measure("q$it") }

        val perPointMs = (System.nanoTime() - start) / 10 / 1_000_000
        (perPointMs < 100) shouldBe true
    }
}
