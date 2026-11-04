package es.topophone.data.point

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.core.model.codes.AttributeType
import es.topophone.core.model.codes.CodeSymbol
import es.topophone.core.model.codes.PointAttribute
import es.topophone.core.model.codes.PointCode
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
}
