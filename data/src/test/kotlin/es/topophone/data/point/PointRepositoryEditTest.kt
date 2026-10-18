package es.topophone.data.point

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.core.model.point.EntryForm
import es.topophone.core.model.point.GeodeticPosition
import es.topophone.core.model.point.HeightKind
import es.topophone.core.model.point.Measurement
import es.topophone.core.model.point.MeasurementMode
import es.topophone.core.model.point.Point
import es.topophone.core.model.point.PointChange
import es.topophone.core.model.point.PointCoordinates
import es.topophone.core.model.point.PointOrigin
import es.topophone.core.model.point.PointQuality
import es.topophone.core.model.point.PointSnapshot
import es.topophone.core.model.point.PrecisionFailure
import es.topophone.core.model.point.TypedCoordinates
import es.topophone.core.model.project.ProjectDefaults
import es.topophone.data.db.TopoPhoneDatabase
import es.topophone.data.project.RoomProjectRepository
import es.topophone.data.project.SaveResult
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith

/** Edición con historial, visibilidad y papelera de los puntos (S011/RF-1, RF-4, RF-9 a RF-11, RF-13 a RF-15, RF-20 a RF-26). */
@RunWith(AndroidJUnit4::class)
class PointRepositoryEditTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val db = Room.inMemoryDatabaseBuilder(context, TopoPhoneDatabase::class.java).build()
    private var now = 1_000L
    private val projects = RoomProjectRepository(db, clock = { now })
    private val points = RoomPointRepository(db, clock = { now })
    private val day = 24L * 60 * 60 * 1000

    @After
    fun close() = db.close()

    private val project: String = runBlocking {
        projects.create(ProjectDefaults().toDraft().copy(name = "Calle Mayor 12")).shouldBeInstanceOf<SaveResult.Saved>().id
    }

    private val sol =
        PointCoordinates(
            GeodeticPosition(40.416_801_234_567_89, -3.703_712_345_678_9, 699.423_123_456),
            440_290.434_567_891,
            4_474_257.414_123_456,
            648.340_987_654,
        )

    private fun measured(name: String, createdAtMs: Long = 2_000) = Point.measured(
        id = "id-$name",
        projectId = project,
        name = name,
        description = "arqueta",
        measurement = Measurement(
            antenna = GeodeticPosition(40.416_801_234_567_89, -3.703_712_345_678_9, 701.357_123_456),
            poleHeightM = 1.8,
            quality = PointQuality("FIX", 18, 0.7, 2.0, 0.031, 0.052, MeasurementMode.AVERAGED, 5, 2, fromReplay = true),
            rawNmea = "\$GNGGA,1*00\n\$GNGST,1*00",
            outOfTolerance = setOf(PrecisionFailure.VERTICAL),
            measuredFromMs = 1_500,
            measuredToMs = 1_900,
            coordinates = sol,
        ),
        createdAtMs = createdAtMs,
    )

    private fun manual(name: String, createdAtMs: Long = 3_000) = Point(
        id = "id-$name",
        projectId = project,
        name = name,
        description = "esquina de parcela",
        origin = PointOrigin.MANUAL,
        coordinates = PointCoordinates(GeodeticPosition(40.4, -3.7, null), 440_000.123_456_789, 4_472_000.987_654_321, null),
        measurement = null,
        typed = TypedCoordinates(EntryForm.PROJECT_CRS, 440_000.123_456_789, 4_472_000.987_654_321, null, HeightKind.PROJECT),
        edited = false,
        hidden = false,
        copyOf = null,
        createdAtMs = createdAtMs,
        deletedAtMs = null,
    )

    private fun save(vararg list: Point) = runBlocking { list.forEach { points.create(it) shouldBe PointSaveResult.Saved } }

    private fun read(id: String): Point = runBlocking { points.point(id).first() }.shouldNotBeNull()

    @Test
    fun `S011 RF-18 y RF-20 un punto manual sin altura se guarda y se lee tal cual, con lo escrito y sin medida`() {
        val flat = manual("E1")
        save(flat)

        read("id-E1") shouldBe flat
    }

    @Test
    fun `S011 RF-1 los resumenes van del mas reciente al mas antiguo, con sus marcas`() {
        save(measured("1", createdAtMs = 2_000), manual("E1", createdAtMs = 3_000))

        val summaries = runBlocking { points.summaries(project).first() }

        summaries.map { it.name } shouldBe listOf("E1", "1")
        summaries.first().hasHeight shouldBe false
        summaries.first().origin shouldBe PointOrigin.MANUAL
        summaries.last().outOfTolerance shouldBe true
    }

    @Test
    fun `S011 RF-10 y RF-11 cada edicion guarda la version anterior, y el historial va del cambio mas reciente al mas antiguo`() {
        val original = measured("1")
        save(original)
        now = 10_000
        val renamed = original.copy(name = "1a")
        runBlocking { points.update(renamed, PointChange.EDIT) } shouldBe PointSaveResult.Saved
        now = 20_000
        val moved = renamed.copy(coordinates = sol.copy(easting = 440_291.0), edited = true)
        runBlocking { points.update(moved, PointChange.EDIT) } shouldBe PointSaveResult.Saved

        read("id-1") shouldBe moved
        val history = runBlocking { points.history("id-1").first() }
        history.map { it.changedAtMs } shouldBe listOf(20_000L, 10_000L)
        history.last().previous shouldBe PointSnapshot.of(original)
        history.first().previous shouldBe PointSnapshot.of(renamed)
        // La observación bruta no cambia nunca (RNF-T6).
        read("id-1").measurement shouldBe original.measurement
    }

    @Test
    fun `S011 RF-9 un nombre que ya existe no se guarda ni deja rastro en el historial`() {
        save(measured("1"), measured("2"))

        runBlocking { points.update(read("id-2").copy(name = "1"), PointChange.EDIT) } shouldBe PointSaveResult.DuplicateName

        read("id-2").name shouldBe "2"
        runBlocking { points.history("id-2").first() }.shouldBeEmpty()
    }

    @Test
    fun `S011 RF-14 y RF-15 la correccion de una seleccion se guarda en todos o en ninguno`() {
        save(measured("1"), measured("2"), measured("3"))
        val corrected = listOf("id-1", "id-2").map { read(it).copy(description = "jalón corregido") }

        runBlocking { points.updateAll(corrected, PointChange.POLE) } shouldBe PointSaveResult.Saved
        read("id-1").description shouldBe "jalón corregido"
        runBlocking { points.history("id-2").first() }.single().change shouldBe PointChange.POLE

        // Uno que no se puede guardar deshace los demás.
        val clash = listOf(read("id-1").copy(description = "otra vez"), read("id-3").copy(name = "2"))
        runBlocking { points.updateAll(clash, PointChange.EDIT) } shouldBe PointSaveResult.DuplicateName
        read("id-1").description shouldBe "jalón corregido"
        runBlocking { points.history("id-1").first() }.size shouldBe 1
    }

    @Test
    fun `S011 RF-4 ocultar y mostrar se recuerda`() {
        save(measured("1"), measured("2"))

        runBlocking { points.setHidden(listOf("id-1", "id-2"), hidden = true) }
        runBlocking { points.setHidden(listOf("id-2"), hidden = false) }

        runBlocking { points.summaries(project).first() }.associate { it.name to it.hidden } shouldBe mapOf("1" to true, "2" to false)
        read("id-1").hidden shouldBe true
    }

    @Test
    fun `S011 RF-22 y RF-23 los puntos borrados pasan a la papelera con sus dias y dejan de contar`() {
        save(measured("1"), measured("2"))
        now = 5 * day

        runBlocking { points.moveToTrash(listOf("id-2")) }

        runBlocking { points.summaries(project).first() }.map { it.name } shouldBe listOf("1")
        runBlocking { points.points(project).first() }.map { it.name } shouldBe listOf("1")
        runBlocking { points.count(project).first() } shouldBe 1
        runBlocking { points.lastName(project) } shouldBe "1"
        runBlocking { points.nameExists(project, "2") } shouldBe false
        runBlocking { points.names(project) } shouldBe setOf("1")
        now = 7 * day
        val trash = runBlocking { points.trash(project).first() }.single()
        trash.point.name shouldBe "2"
        trash.point.deletedAtMs shouldBe 5 * day
        trash.daysLeft shouldBe 28
    }

    @Test
    fun `S011 RF-24 restaurar devuelve el punto con todos sus datos y su historial`() {
        val original = measured("1")
        save(original)
        runBlocking { points.update(original.copy(description = "pozo"), PointChange.EDIT) }
        runBlocking { points.moveToTrash(listOf("id-1")) }

        runBlocking { points.restore("id-1") } shouldBe PointSaveResult.Saved

        read("id-1") shouldBe original.copy(description = "pozo")
        runBlocking { points.history("id-1").first() }.size shouldBe 1
        runBlocking { points.trash(project).first() }.shouldBeEmpty()
    }

    @Test
    fun `S011 RF-25 si su nombre lo tiene otro punto se restaura solo con un nombre nuevo`() {
        save(measured("1"))
        runBlocking { points.moveToTrash(listOf("id-1")) }
        save(measured("1").copy(id = "id-otro"))

        runBlocking { points.restore("id-1") } shouldBe PointSaveResult.DuplicateName
        read("id-1").deletedAtMs.shouldNotBeNull()

        runBlocking { points.restore("id-1", newName = "1b") } shouldBe PointSaveResult.Saved
        read("id-1").name shouldBe "1b"
        read("id-1").deletedAtMs.shouldBeNull()
    }

    @Test
    fun `S011 RF-26 se borra definitivamente desde la papelera y se purga a los 30 dias, con su historial`() {
        save(measured("1"), measured("2"), measured("3"))
        runBlocking { points.update(read("id-1").copy(description = "x"), PointChange.EDIT) }
        now = 1 * day
        runBlocking { points.moveToTrash(listOf("id-1", "id-2")) }
        now = 2 * day
        runBlocking { points.moveToTrash(listOf("id-3")) }

        runBlocking { points.deletePermanently("id-3") }
        runBlocking { points.point("id-3").first() }.shouldBeNull()

        now = 31 * day + 1
        runBlocking { points.purgeTrash() } shouldBe 2
        runBlocking { points.point("id-1").first() }.shouldBeNull()
        runBlocking { points.history("id-1").first() }.shouldBeEmpty()
    }

    @Test
    fun `S011 RF-26 borrar definitivamente solo vale para los puntos de la papelera`() {
        save(measured("1"))

        runBlocking { points.deletePermanently("id-1") }

        runBlocking { points.summaries(project).first() }.map { it.name } shouldContainExactly listOf("1")
    }

    @Test
    fun `S011 RF-4 y RF-22 un punto oculto sigue contando en los datos del proyecto y uno de la papelera no`() {
        save(measured("1"), measured("2"), measured("3"))

        runBlocking { points.setHidden(listOf("id-1"), hidden = true) }
        runBlocking { points.moveToTrash(listOf("id-2")) }

        runBlocking { points.count(project).first() } shouldBe 2
        runBlocking { points.summaries(project).first() }.map { it.name }.toSet() shouldBe setOf("1", "3")
    }
}
