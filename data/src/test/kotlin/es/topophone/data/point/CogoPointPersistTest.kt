package es.topophone.data.point

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.core.model.cogo.CogoEntry
import es.topophone.core.model.cogo.CogoOperation
import es.topophone.core.model.cogo.CogoRef
import es.topophone.core.model.cogo.CogoValue
import es.topophone.core.model.cogo.IntersectionSide
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
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith

/** Un punto COGO se guarda y se lee con su origen y el rastro de su cálculo, sin redondear (S038/RF-22, RF-24). */
@RunWith(AndroidJUnit4::class)
class CogoPointPersistTest {

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

    private val ref = CogoRef(
        operation = CogoOperation.INTERSECTION_DISTANCE_DISTANCE,
        entries = listOf(
            CogoEntry("first", "A", 440_000.123456789012, 4_474_000.5, 650.0),
            CogoEntry("second", null, 440_100.25, 4_474_000.5, null),
        ),
        values = listOf(CogoValue("distance1", 60.123456789), CogoValue("distance2", 70.0)),
        solution = IntersectionSide.RIGHT,
    )

    private fun cogoPoint(name: String = "7", cogo: CogoRef? = ref) = Point(
        id = "id-$name",
        projectId = project,
        name = name,
        description = "",
        origin = PointOrigin.COGO,
        coordinates = PointCoordinates(
            GeodeticPosition(40.416801234567891, -3.703712345678901, 699.423),
            440_036.123456789012,
            4_474_048.5,
            650.5,
        ),
        measurement = null,
        typed = TypedCoordinates(EntryForm.PROJECT_CRS, 440_036.123456789012, 4_474_048.5, 650.5, HeightKind.PROJECT),
        edited = false,
        hidden = false,
        copyOf = null,
        createdAtMs = 1_000,
        deletedAtMs = null,
        cogo = cogo,
    )

    private fun read(): Point = runBlocking { points.points(project).first() }.single()

    @Test
    fun `S038 RF-22 el punto COGO vuelve con su origen y el rastro del calculo`() {
        runBlocking { points.create(cogoPoint()) } shouldBe PointSaveResult.Saved

        val point = read()

        point.origin shouldBe PointOrigin.COGO
        point.cogo shouldBe ref
        point.measurement.shouldBeNull()
    }

    @Test
    fun `S038 RF-22 las coordenadas escritas se guardan sin redondear`() {
        runBlocking { points.create(cogoPoint()) }

        val point = read()

        point.coordinates.easting shouldBe 440_036.123456789012
        point.typed!!.first shouldBe 440_036.123456789012
        point.cogo!!.entries[0].easting shouldBe 440_000.123456789012
    }

    @Test
    fun `S038 RF-24 la lista de objetos ve el origen COGO y un punto de otro origen no lleva rastro`() {
        runBlocking {
            points.create(cogoPoint("7"))
            points.create(cogoPoint("8", cogo = null).copy(origin = PointOrigin.MANUAL))
        }

        val summaries = runBlocking { points.summaries(project).first() }
        val manual = runBlocking { points.points(project).first() }.single { it.name == "8" }

        summaries.single { it.name == "7" }.origin shouldBe PointOrigin.COGO
        summaries.single { it.name == "8" }.origin shouldBe PointOrigin.MANUAL
        manual.cogo.shouldBeNull()
    }

    @Test
    fun `S038 RF-22 un rastro ilegible en la base no impide leer el punto`() {
        runBlocking { points.create(cogoPoint()) }
        db.openHelper.writableDatabase.execSQL("UPDATE points SET cogo = 'basura' WHERE id = 'id-7'")

        val point = read()

        point.cogo.shouldBeNull()
        point.origin shouldBe PointOrigin.COGO
        point.coordinates.easting shouldBe 440_036.123456789012
    }
}
