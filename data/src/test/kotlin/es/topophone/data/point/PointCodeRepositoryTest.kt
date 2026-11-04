package es.topophone.data.point

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
import es.topophone.core.model.point.GeodeticPosition
import es.topophone.core.model.point.Point
import es.topophone.core.model.point.PointChange
import es.topophone.core.model.point.PointCoordinates
import es.topophone.core.model.point.PointOrigin
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

/** El punto guarda su codigo y los valores de sus atributos con el punto (S024/RF-10, RF-12, RF-14). */
@RunWith(AndroidJUnit4::class)
class PointCodeRepositoryTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val db = Room.inMemoryDatabaseBuilder(context, TopoPhoneDatabase::class.java).build()
    private var now = 1_000L
    private val projects = RoomProjectRepository(db, clock = { now })
    private val points = RoomPointRepository(db, clock = { now })

    @After
    fun close() = db.close()

    private val project: String = runBlocking {
        projects.create(ProjectDefaults().toDraft().copy(name = "Calle Mayor 12")).shouldBeInstanceOf<SaveResult.Saved>().id
    }

    private val arqueta = PointCode(
        code = "ARQ",
        description = "Arqueta",
        color = 0x1E88E5,
        symbol = CodeSymbol.SQUARE,
        attributes = listOf(
            PointAttribute("Material", AttributeType.LIST, "PVC"),
            PointAttribute("Unidades", AttributeType.INTEGER, "3"),
            PointAttribute("Diametro", AttributeType.NUMBER, "0.123456789012345"),
            PointAttribute("Nota", AttributeType.TEXT, ""),
        ),
    )

    private fun manual(name: String, code: PointCode? = null) = Point(
        id = "id-$name",
        projectId = project,
        name = name,
        description = "esquina",
        origin = PointOrigin.MANUAL,
        coordinates = PointCoordinates(GeodeticPosition(40.4, -3.7, null), 440_000.123_456_789, 4_472_000.987_654_321, null),
        measurement = null,
        typed = null,
        edited = false,
        hidden = false,
        copyOf = null,
        createdAtMs = 3_000,
        deletedAtMs = null,
        code = code,
    )

    private fun count(table: String): Int = db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM $table").use {
        it.moveToFirst()
        it.getInt(0)
    }

    @Test
    fun `S024 RF-10 el punto vuelve con su codigo, color, simbolo y atributos tal cual`() {
        runBlocking { points.create(manual("1", arqueta)) shouldBe PointSaveResult.Saved }

        val read = runBlocking { points.point("id-1").first() }.shouldNotBeNull()
        read.code shouldBe arqueta
        runBlocking { points.points(project).first() }.single().code shouldBe arqueta
        runBlocking { points.pointsForExport(project) }.single().code shouldBe arqueta
    }

    @Test
    fun `S024 RF-10 el resumen lleva el codigo, su color y su simbolo`() {
        runBlocking { points.create(manual("1", arqueta)) }
        runBlocking { points.create(manual("2")) }

        val summaries = runBlocking { points.summaries(project).first() }.associateBy { it.name }
        summaries.getValue("1").code shouldBe "ARQ"
        summaries.getValue("1").codeColor shouldBe 0x1E88E5
        summaries.getValue("1").codeSymbol shouldBe CodeSymbol.SQUARE
        summaries.getValue("2").code.shouldBeNull()
        summaries.getValue("2").codeSymbol.shouldBeNull()
    }

    @Test
    fun `S024 RF-10 un punto sin codigo no guarda atributos`() {
        runBlocking { points.create(manual("1")) }

        runBlocking { points.point("id-1").first() }.shouldNotBeNull().code.shouldBeNull()
        count("point_attributes") shouldBe 0
    }

    @Test
    fun `S024 RNF-T6 si el punto no se guarda por nombre repetido no queda ningun atributo suelto`() {
        runBlocking { points.create(manual("1", arqueta)) }
        count("point_attributes") shouldBe 4

        runBlocking { points.create(manual("1", arqueta).copy(id = "id-otro")) shouldBe PointSaveResult.DuplicateName }

        count("points") shouldBe 1
        count("point_attributes") shouldBe 4
    }

    @Test
    fun `S024 RF-12 corregir un atributo guarda el punto y deja los valores anteriores en el historial`() {
        runBlocking { points.create(manual("1", arqueta)) }
        val corrected = arqueta.copy(
            attributes = arqueta.attributes.map { if (it.name == "Material") it.copy(value = "Hormigon") else it },
        )
        now = 5_000

        runBlocking { points.update(manual("1", corrected), PointChange.EDIT) shouldBe PointSaveResult.Saved }

        runBlocking { points.point("id-1").first() }.shouldNotBeNull().code shouldBe corrected
        val history = runBlocking { points.history("id-1").first() }.single()
        history.change shouldBe PointChange.EDIT
        history.previous.code shouldBe arqueta
        count("point_attributes") shouldBe 4
        count("point_history_attributes") shouldBe 4
    }

    @Test
    fun `S024 RF-14 quitar el codigo borra sus atributos y el historial guarda el codigo anterior`() {
        runBlocking { points.create(manual("1", arqueta)) }

        runBlocking { points.update(manual("1"), PointChange.CODE) shouldBe PointSaveResult.Saved }

        runBlocking { points.point("id-1").first() }.shouldNotBeNull().code.shouldBeNull()
        count("point_attributes") shouldBe 0
        runBlocking { points.history("id-1").first() }.single().previous.code shouldBe arqueta
    }

    @Test
    fun `S024 RF-10 un punto en la papelera conserva su codigo y vuelve con el`() {
        runBlocking { points.create(manual("1", arqueta)) }
        runBlocking { points.moveToTrash(listOf("id-1")) }

        runBlocking { points.trash(project).first() }.single().point.code shouldBe arqueta
        runBlocking { points.restore("id-1") }
        runBlocking { points.point("id-1").first() }.shouldNotBeNull().code shouldBe arqueta
    }

    @Test
    fun `S024 RF-10 el codigo del punto no depende de la biblioteca`() {
        runBlocking { points.create(manual("1", arqueta)) }
        db.openHelper.writableDatabase.execSQL("DELETE FROM library_codes")

        runBlocking { points.point("id-1").first() }.shouldNotBeNull().code shouldBe arqueta
    }

    private val pozo = SurveyCode(
        id = "c-poz",
        code = "POZ",
        description = "Pozo",
        geometry = CodeGeometry.POINT,
        color = 0xD32F2F,
        attributes = listOf(
            CodeAttribute("material", AttributeType.LIST, "Acero", listOf("Acero", "PVC")),
            CodeAttribute("Profundidad", AttributeType.NUMBER, "2.5"),
        ),
        symbol = CodeSymbol.STAR,
    )

    @Test
    fun `S024 RF-13 cambiar el codigo de una seleccion lo aplica a todos y conserva los atributos que coinciden`() {
        runBlocking {
            points.create(manual("1", arqueta))
            points.create(manual("2"))
        }
        now = 7_000

        val result = runBlocking { points.changeCode(listOf("id-1", "id-2"), pozo) }

        result shouldBe CodeChangeResult.Changed(2)
        val one = runBlocking { points.point("id-1").first() }.shouldNotBeNull().code.shouldNotBeNull()
        one.code shouldBe "POZ"
        one.description shouldBe "Pozo"
        one.color shouldBe 0xD32F2F
        one.symbol shouldBe CodeSymbol.STAR
        one.attributes shouldBe listOf(
            PointAttribute("material", AttributeType.LIST, "PVC"),
            PointAttribute("Profundidad", AttributeType.NUMBER, "2.5"),
        )
        runBlocking { points.point("id-2").first() }.shouldNotBeNull().code.shouldNotBeNull().attributes shouldBe listOf(
            PointAttribute("material", AttributeType.LIST, "Acero"),
            PointAttribute("Profundidad", AttributeType.NUMBER, "2.5"),
        )
    }

    @Test
    fun `S024 RF-14 cada punto guarda en su historial el codigo y los valores anteriores`() {
        runBlocking { points.create(manual("1", arqueta)) }
        now = 7_000

        runBlocking { points.changeCode(listOf("id-1"), pozo) }

        val entry = runBlocking { points.history("id-1").first() }.single()
        entry.change shouldBe PointChange.CODE
        entry.changedAtMs shouldBe 7_000
        entry.previous.code shouldBe arqueta
        count("point_history_attributes") shouldBe 4
    }

    @Test
    fun `S024 RF-14 sin codigo nuevo el punto queda sin codigo ni atributos`() {
        runBlocking { points.create(manual("1", arqueta)) }

        runBlocking { points.changeCode(listOf("id-1"), null) } shouldBe CodeChangeResult.Changed(1)

        runBlocking { points.point("id-1").first() }.shouldNotBeNull().code.shouldBeNull()
        count("point_attributes") shouldBe 0
    }

    @Test
    fun `S024 RF-13 si un punto de la seleccion no existe no se cambia ninguno`() {
        runBlocking { points.create(manual("1", arqueta)) }

        runBlocking { points.changeCode(listOf("id-1", "no-existe"), pozo) } shouldBe CodeChangeResult.NotFound

        runBlocking { points.point("id-1").first() }.shouldNotBeNull().code shouldBe arqueta
        runBlocking { points.history("id-1").first() }.size shouldBe 0
    }

    @Test
    fun `S024 RF-16 cambiar el codigo no toca las coordenadas ni la medida ni el indicador de editado`() {
        val original = manual("1", arqueta)
        runBlocking { points.create(original) }

        runBlocking { points.changeCode(listOf("id-1"), pozo) }

        val read = runBlocking { points.point("id-1").first() }.shouldNotBeNull()
        read.copy(code = null) shouldBe original.copy(code = null)
    }

    private fun withCode(name: String, code: String?, at: Long) =
        manual(name, code?.let { PointCode(it, "", 0x1E88E5, CodeSymbol.CIRCLE) }).copy(createdAtMs = at)

    @Test
    fun `S024 RF-1 los recientes son los codigos distintos de los puntos, el mas reciente primero`() {
        runBlocking {
            points.create(withCode("1", "ARB", 1_000))
            points.create(withCode("2", "POS", 2_000))
            points.create(withCode("3", "ARB", 3_000))
            points.create(withCode("4", null, 4_000))
        }

        runBlocking { points.recentCodes(project).first() } shouldBe listOf("ARB", "POS")
    }

    @Test
    fun `S024 RF-1 los recientes no cuentan los puntos de la papelera y paran en 10`() {
        runBlocking {
            (1..12).forEach { points.create(withCode("p$it", "C$it", it * 1_000L)) }
            points.moveToTrash(listOf("id-p12"))
        }

        val recent = runBlocking { points.recentCodes(project).first() }
        recent.size shouldBe 10
        recent.first() shouldBe "C11"
        recent.last() shouldBe "C2"
    }
}
