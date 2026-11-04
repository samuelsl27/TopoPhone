package es.topophone.data.line

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.core.model.codes.AttributeType
import es.topophone.core.model.codes.CodeAttribute
import es.topophone.core.model.codes.CodeGeometry
import es.topophone.core.model.codes.CodeSymbol
import es.topophone.core.model.codes.PointAttribute
import es.topophone.core.model.codes.PointCode
import es.topophone.core.model.codes.SurveyCode
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

/** Editar los vértices, el nombre, el código y los atributos de una línea con su historial (S025/RF-16 a RF-20). */
@RunWith(AndroidJUnit4::class)
class LineEditTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val db = Room.inMemoryDatabaseBuilder(context, TopoPhoneDatabase::class.java).build()
    private var now = 1_000L
    private var seq = 0
    private val projects = RoomProjectRepository(db, clock = { now })
    private val points = RoomPointRepository(db, clock = { now })
    private val lines = RoomLineRepository(db, points, clock = {
        now += 10L
        now
    }, newId = { "id${++seq}" })

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
        coordinates = PointCoordinates(GeodeticPosition(40.4, -3.7, null), 440_000.123, 4_472_000.987, null),
        measurement = null,
        typed = null,
        edited = false,
        hidden = false,
        copyOf = null,
        createdAtMs = 3_000,
        deletedAtMs = null,
    )

    private suspend fun setup(): String {
        listOf("1", "2", "3", "4").forEach { points.create(point(it)) }
        return (
            lines.createFromPoints(
                NewLine(project, "BORD_1", bord, CodeGeometry.LINE, false, listOf("id-1", "id-2", "id-3")),
            ) as LineSaveResult.Saved
            ).id
    }

    @Test
    fun `S025 RF-16 se guardan los vertices nuevos y los anteriores quedan en el historial con la fecha`() = runBlocking<Unit> {
        val id = setup()

        lines.edit(id, LineUpdate(pointIds = listOf("id-3", "id-4", "id-1"))) shouldBe LineSaveResult.Saved(id)

        lines.line(id).first().shouldNotBeNull().pointIds shouldBe listOf("id-3", "id-4", "id-1")
        val entry = lines.history(id).first().single()
        entry.change shouldBe LineChange.VERTICES
        entry.previous.vertices.map { it.pointName } shouldBe listOf("1", "2", "3")
        (entry.changedAtMs > 1_000) shouldBe true
    }

    @Test
    fun `S025 RF-17 editar los vertices no modifica ningun punto`() = runBlocking<Unit> {
        val id = setup()
        val before = points.points(project).first()

        lines.edit(id, LineUpdate(pointIds = listOf("id-4", "id-3")))

        points.points(project).first() shouldBe before
    }

    @Test
    fun `S025 RF-17 la linea refleja al momento los cambios de un punto`() = runBlocking<Unit> {
        val id = setup()
        val moved = points.point("id-2").first().shouldNotBeNull()

        points.update(moved.copy(name = "dos"), es.topophone.core.model.point.PointChange.EDIT)

        lines.line(id).first().shouldNotBeNull().pointIds shouldBe listOf("id-1", "id-2", "id-3")
    }

    @Test
    fun `S025 RF-19 una edicion con menos vertices de los necesarios no se guarda`() = runBlocking<Unit> {
        val id = setup()

        lines.edit(id, LineUpdate(pointIds = listOf("id-1"))) shouldBe LineSaveResult.TooFewVertices
        lines.edit(id, LineUpdate(pointIds = listOf("id-1", "id-1", "id-2"))) shouldBe LineSaveResult.RepeatedVertex
        lines.close(id)
        lines.edit(id, LineUpdate(pointIds = listOf("id-1", "id-2"))) shouldBe LineSaveResult.TooFewVertices

        lines.line(id).first().shouldNotBeNull().pointIds shouldBe listOf("id-1", "id-2", "id-3")
        lines.history(id).first().size shouldBe 1 // solo el cierre
    }

    @Test
    fun `S025 RF-18 cambiar el nombre y los atributos queda en el historial con los valores anteriores`() = runBlocking<Unit> {
        val id = setup()

        lines.edit(id, LineUpdate(name = "ACERA")) shouldBe LineSaveResult.Saved(id)
        lines.edit(id, LineUpdate(attributes = listOf(PointAttribute("Material", AttributeType.TEXT, "Hormigon")))) shouldBe
            LineSaveResult.Saved(id)

        val history = lines.history(id).first()
        history.map { it.change } shouldBe listOf(LineChange.ATTRIBUTES, LineChange.NAME)
        history[0].previous.code?.attributes?.first()?.value shouldBe "Granito"
        history[1].previous.name shouldBe "BORD_1"
        lines.line(id).first().shouldNotBeNull().code?.attributes shouldBe
            listOf(PointAttribute("Material", AttributeType.TEXT, "Hormigon"))
    }

    @Test
    fun `S025 RF-18 un nombre que ya usa otra linea se rechaza y no deja historial`() = runBlocking<Unit> {
        val id = setup()
        lines.createFromPoints(NewLine(project, "OTRA", bord, CodeGeometry.LINE, false, listOf("id-3", "id-4")))

        lines.edit(id, LineUpdate(name = "OTRA")) shouldBe LineSaveResult.DuplicateName

        lines.line(id).first().shouldNotBeNull().name shouldBe "BORD_1"
        lines.history(id).first() shouldBe emptyList()
    }

    @Test
    fun `S025 RF-20 cambiar el codigo conserva los atributos del mismo nombre y tipo y anota el cambio`() = runBlocking<Unit> {
        val id = setup()
        val tapia = SurveyCode(
            "c1",
            "TAPIA",
            "Tapia",
            CodeGeometry.POLYGON,
            0xD32F2F,
            listOf(
                CodeAttribute("Material", AttributeType.TEXT, "Ladrillo", emptyList()),
                CodeAttribute("Grosor", AttributeType.NUMBER, "0.3", emptyList()),
            ),
            CodeSymbol.SQUARE,
        )

        lines.changeCode(id, tapia) shouldBe LineSaveResult.Saved(id)

        val line = lines.line(id).first().shouldNotBeNull()
        line.code?.code shouldBe "TAPIA"
        line.code?.symbol shouldBe CodeSymbol.SQUARE
        line.geometry shouldBe CodeGeometry.POLYGON
        line.code?.attributes shouldBe
            listOf(PointAttribute("Material", AttributeType.TEXT, "Granito"), PointAttribute("Grosor", AttributeType.NUMBER, "0.3"))
        lines.history(id).first().single().change shouldBe LineChange.CODE
    }

    @Test
    fun `S025 RF-20 un codigo de punto no se admite para una linea`() = runBlocking<Unit> {
        val id = setup()

        lines.changeCode(id, SurveyCode("c2", "ARQ", "", CodeGeometry.POINT, 0)) shouldBe LineSaveResult.WrongGeometry

        lines.line(id).first().shouldNotBeNull().code?.code shouldBe "BORD"
    }
}
