package es.topophone.data.point

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.core.model.cogo.CogoEntry
import es.topophone.core.model.cogo.CogoOperation
import es.topophone.core.model.cogo.CogoRef
import es.topophone.core.model.point.EntryForm
import es.topophone.core.model.point.GeodeticPosition
import es.topophone.core.model.point.HeightKind
import es.topophone.core.model.point.Point
import es.topophone.core.model.point.PointChange
import es.topophone.core.model.point.PointCoordinates
import es.topophone.core.model.point.PointOrigin
import es.topophone.core.model.point.TypedCoordinates
import es.topophone.core.model.project.ProjectDefaults
import es.topophone.core.survey.PointCorrections
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

/** Un punto COGO se edita con historial, se duplica, se oculta y va a la papelera como uno manual (S038/RF-24). */
@RunWith(AndroidJUnit4::class)
class CogoPointBehaviorTest {

    private val db = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext<Context>(),
        TopoPhoneDatabase::class.java,
    ).build()
    private var now = 1_000L
    private val points = RoomPointRepository(db, clock = { now })
    private val project = runBlocking {
        RoomProjectRepository(
            db,
        ).create(ProjectDefaults().toDraft().copy(name = "Calle Mayor 12")).shouldBeInstanceOf<SaveResult.Saved>().id
    }

    @After
    fun close() = db.close()

    private val ref = CogoRef(CogoOperation.TRAVERSE, listOf(CogoEntry("start", "P1", 440_000.0, 4_474_000.0, null)))

    private val cogo = Point(
        id = "id-7",
        projectId = project,
        name = "7",
        description = "",
        origin = PointOrigin.COGO,
        coordinates = PointCoordinates(GeodeticPosition(40.4168, -3.7037, 699.4), 440_036.123456789012, 4_474_048.5, 650.5),
        measurement = null,
        typed = TypedCoordinates(EntryForm.PROJECT_CRS, 440_036.123456789012, 4_474_048.5, 650.5, HeightKind.PROJECT),
        edited = false,
        hidden = false,
        copyOf = null,
        createdAtMs = 1_000,
        deletedAtMs = null,
        cogo = ref,
    )

    private fun read(id: String = "id-7") = runBlocking { points.point(id).first() }!!

    @Test
    fun `S038 RF-24 editar un punto COGO guarda el historial y conserva su rastro`() {
        runBlocking { points.create(cogo) }
        now = 5_000
        val edited = cogo.copy(name = "7a", description = "esquina", coordinates = cogo.coordinates.copy(easting = 440_037.0))

        runBlocking { points.update(edited, PointChange.EDIT) } shouldBe PointSaveResult.Saved

        read() shouldBe edited
        read().origin shouldBe PointOrigin.COGO
        read().cogo shouldBe ref
        val history = runBlocking { points.history("id-7").first() }
        history.size shouldBe 1
        history.single().previous.name shouldBe "7"
    }

    @Test
    fun `S038 RF-24 duplicar un punto COGO da un punto manual sin rastro`() {
        runBlocking { points.create(cogo) }

        val copy = PointCorrections().duplicate(read(), "id-8", "8", 2_000)
        runBlocking { points.create(copy) } shouldBe PointSaveResult.Saved

        read("id-8").origin shouldBe PointOrigin.MANUAL
        read("id-8").cogo.shouldBeNull()
        read("id-8").copyOf shouldBe "7"
        read("id-8").coordinates.easting shouldBe 440_036.123456789012
    }

    @Test
    fun `S038 RF-24 un punto COGO se oculta, va a la papelera y vuelve con su rastro`() {
        runBlocking { points.create(cogo) }

        runBlocking { points.setHidden(listOf("id-7"), true) }
        read().hidden shouldBe true
        runBlocking { points.setHidden(listOf("id-7"), false) }

        runBlocking { points.moveToTrash(listOf("id-7")) }
        runBlocking { points.points(project).first() } shouldBe emptyList()
        runBlocking { points.trash(project).first() }.single().point.cogo shouldBe ref
        runBlocking { points.restore("id-7") } shouldBe PointSaveResult.Saved

        read().cogo shouldBe ref
        read().origin shouldBe PointOrigin.COGO
    }

    @Test
    fun `S038 RF-24 un punto COGO sale entre los de la exportacion con su origen`() {
        runBlocking { points.create(cogo) }

        val exported = runBlocking { points.pointsForExport(project) }.single()

        exported.origin shouldBe PointOrigin.COGO
        exported.coordinates.easting shouldBe 440_036.123456789012
    }
}
