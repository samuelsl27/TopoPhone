package es.topophone.data.cogo

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.core.model.cogo.CogoEntry
import es.topophone.core.model.cogo.CogoOperation
import es.topophone.core.model.cogo.CogoRef
import es.topophone.core.model.cogo.DraftPoint
import es.topophone.core.model.cogo.LegKind
import es.topophone.core.model.cogo.TraverseDraft
import es.topophone.core.model.cogo.TraverseLeg
import es.topophone.core.model.log.LogCategory
import es.topophone.core.model.point.EntryForm
import es.topophone.core.model.point.GeodeticPosition
import es.topophone.core.model.point.HeightKind
import es.topophone.core.model.point.Point
import es.topophone.core.model.point.PointCoordinates
import es.topophone.core.model.point.PointOrigin
import es.topophone.core.model.point.TypedCoordinates
import es.topophone.core.model.project.ProjectDefaults
import es.topophone.data.db.TopoPhoneDatabase
import es.topophone.data.log.RecordingLog
import es.topophone.data.point.PointSaveResult
import es.topophone.data.point.RoomPointRepository
import es.topophone.data.project.RoomProjectRepository
import es.topophone.data.project.SaveResult
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith

/** Guardar los puntos de COGO: todo o nada, con el borrador y sin datos en el log (S038/RF-19, RF-20, RF-23, RF-25, RF-30). */
@RunWith(AndroidJUnit4::class)
class CogoSavePointsTest {

    private val db = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext<Context>(),
        TopoPhoneDatabase::class.java,
    ).build()
    private val log = RecordingLog()
    private val cogo = RoomCogoRepository(db, clock = { 7_000 }, log = log)
    private val points = RoomPointRepository(db)
    private val project = runBlocking {
        RoomProjectRepository(
            db,
        ).create(ProjectDefaults().toDraft().copy(name = "Calle Mayor 12")).shouldBeInstanceOf<SaveResult.Saved>().id
    }

    @After
    fun close() = db.close()

    private val ref = CogoRef(CogoOperation.TRAVERSE, listOf(CogoEntry("start", "P1", 440_000.0, 4_474_000.0, null)))

    private fun cogoPoint(name: String, east: Double = 440_036.123456789012) = Point(
        id = "id-$name",
        projectId = project,
        name = name,
        description = "",
        origin = PointOrigin.COGO,
        coordinates = PointCoordinates(GeodeticPosition(40.4168, -3.7037, null), east, 4_474_048.5, null),
        measurement = null,
        typed = TypedCoordinates(EntryForm.PROJECT_CRS, east, 4_474_048.5, null, HeightKind.PROJECT),
        edited = false,
        hidden = false,
        copyOf = null,
        createdAtMs = 1_000,
        deletedAtMs = null,
        cogo = ref,
    )

    private val draft =
        TraverseDraft(project, DraftPoint.Typed(1.0, 2.0), null, null, listOf(TraverseLeg(LegKind.AZIMUTH, 1.0, 5.0, null)), 0L)

    private fun saved() = runBlocking { points.points(project).first() }

    private fun currentDraft() = runBlocking { cogo.draft(project).first() }

    @Test
    fun `S038 RF-19 los puntos se guardan juntos con su origen y su rastro`() {
        val result = runBlocking { cogo.savePoints(listOf(cogoPoint("1"), cogoPoint("2"), cogoPoint("3")), CogoOperation.TRAVERSE, false) }

        result shouldBe PointSaveResult.Saved
        saved().map { it.name }.toSet() shouldBe setOf("1", "2", "3")
        saved().all { it.origin == PointOrigin.COGO && it.cogo == ref } shouldBe true
    }

    @Test
    fun `S038 RF-20 un nombre que ya existe impide guardar ninguno`() {
        runBlocking { points.create(cogoPoint("2", east = 1.0).copy(id = "otro", origin = PointOrigin.MANUAL, cogo = null)) }

        val result = runBlocking { cogo.savePoints(listOf(cogoPoint("1"), cogoPoint("2")), CogoOperation.TRAVERSE, false) }

        result shouldBe PointSaveResult.DuplicateName
        saved().map { it.name } shouldBe listOf("2") // el que ya estaba
    }

    @Test
    fun `S038 RF-20 un nombre repetido dentro del lote impide guardar ninguno`() {
        val result = runBlocking {
            cogo.savePoints(listOf(cogoPoint("1"), cogoPoint("1").copy(id = "id-b")), CogoOperation.INTERSECTION_DISTANCE_DISTANCE, false)
        }

        result shouldBe PointSaveResult.DuplicateName
        saved() shouldBe emptyList()
    }

    @Test
    fun `S038 RF-30 al guardar con vaciado el borrador se borra en la misma transaccion`() {
        runBlocking { cogo.saveDraft(draft) }
        currentDraft().shouldNotBeNull()

        runBlocking { cogo.savePoints(listOf(cogoPoint("1")), CogoOperation.TRAVERSE, clearDraft = true) } shouldBe PointSaveResult.Saved

        currentDraft().shouldBeNull()
    }

    @Test
    fun `S038 RF-30 sin vaciado, o si falla el guardado, el borrador se conserva`() {
        runBlocking { cogo.saveDraft(draft) }

        runBlocking { cogo.savePoints(listOf(cogoPoint("1")), CogoOperation.TRAVERSE, clearDraft = false) }
        currentDraft().shouldNotBeNull()

        val failed = runBlocking { cogo.savePoints(listOf(cogoPoint("1")), CogoOperation.TRAVERSE, clearDraft = true) }
        failed shouldBe PointSaveResult.DuplicateName
        currentDraft().shouldNotBeNull() // se revierte todo: tampoco se vacia
    }

    @Test
    fun `S038 RF-25 el log anota la operacion, el numero de puntos y el proyecto, sin nombres ni coordenadas`() {
        runBlocking { cogo.savePoints(listOf(cogoPoint("11"), cogoPoint("12")), CogoOperation.TRAVERSE, false) }

        val line = log.entries.single { it.second == LogCategory.POINTS }.third

        line shouldContain "COGO"
        line shouldContain "2"
        line shouldContain "Calle Mayor 12"
        line shouldNotContain "11"
        line shouldNotContain "440036"
        line shouldNotContain "4474048"
    }

    @Test
    fun `S038 RF-25 un guardado que falla no deja nada en el log`() {
        runBlocking { cogo.savePoints(listOf(cogoPoint("1"), cogoPoint("1").copy(id = "id-b")), CogoOperation.TRAVERSE, false) }

        log.entries.none { it.second == LogCategory.POINTS } shouldBe true
    }

    @Test
    fun `S038 RF-23 una lista vacia no guarda ni anota nada`() {
        runBlocking { cogo.savePoints(emptyList(), CogoOperation.TRAVERSE, false) } shouldBe PointSaveResult.Saved

        saved() shouldBe emptyList()
        log.entries shouldBe emptyList()
    }
}
