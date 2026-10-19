package es.topophone.data.log

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.core.model.log.LogCategory
import es.topophone.core.model.log.LogLevel
import es.topophone.core.model.point.GeodeticPosition
import es.topophone.core.model.point.Measurement
import es.topophone.core.model.point.MeasurementMode
import es.topophone.core.model.point.Point
import es.topophone.core.model.point.PointCoordinates
import es.topophone.core.model.point.PointOrigin
import es.topophone.core.model.point.PointQuality
import es.topophone.core.model.project.ProjectDefaults
import es.topophone.data.db.TopoPhoneDatabase
import es.topophone.data.point.PointSaveResult
import es.topophone.data.point.RoomPointRepository
import es.topophone.data.project.RoomProjectRepository
import es.topophone.data.project.SaveResult
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith

/** Cada punto guardado queda en el log con su nombre, su proyecto y su solución, sin coordenadas (S014/RF-3, RF-7). */
@RunWith(AndroidJUnit4::class)
class PointRepositoryLogTest {

    private val db = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext<Context>(),
        TopoPhoneDatabase::class.java,
    ).build()
    private val log = RecordingLog()
    private val points = RoomPointRepository(db, log = log)
    private val projectId = runBlocking {
        (RoomProjectRepository(db).create(ProjectDefaults().toDraft().copy(name = "Calle Mayor 12")) as SaveResult.Saved).id
    }

    @After
    fun close() = db.close()

    private val coordinates =
        PointCoordinates(GeodeticPosition(40.416815093712345, -3.70353301678, 699.4231), 440_313.1221, 4_474_258.8769, 648.3114)

    private fun measured(name: String) = Point.measured(
        id = "id-$name",
        projectId = projectId,
        name = name,
        description = "",
        measurement = Measurement(
            antenna = GeodeticPosition(40.416815093712345, -3.70353301678, 701.357),
            poleHeightM = 1.8,
            quality = PointQuality("FIX", 24, 0.6, 1.0, 0.009, 0.014, MeasurementMode.INSTANT, 1, 0, fromReplay = false),
            rawNmea = "",
            outOfTolerance = emptySet(),
            measuredFromMs = 0,
            measuredToMs = 0,
            coordinates = coordinates,
        ),
        createdAtMs = 0,
    )

    @Test
    fun `S014 RF-3 un punto medido se anota con su nombre, su proyecto y su solucion`() {
        runBlocking { points.create(measured("12")) } shouldBe PointSaveResult.Saved

        log.entries.single() shouldBe Triple(LogLevel.INFO, LogCategory.POINTS, "Guardado 12 en «Calle Mayor 12»: FIX")
    }

    @Test
    fun `S014 RF-3 un punto manual se anota como manual`() {
        val manual = Point(
            id = "id-E1", projectId = projectId, name = "E1", description = "", origin = PointOrigin.MANUAL, coordinates = coordinates,
            measurement = null, typed = null, edited = false, hidden = false, copyOf = null, createdAtMs = 0, deletedAtMs = null,
        )

        runBlocking { points.create(manual) }

        log.messages.single() shouldBe "Guardado E1 en «Calle Mayor 12»: punto manual"
    }

    @Test
    fun `S014 RF-7 el log no lleva las coordenadas del punto`() {
        runBlocking { points.create(measured("12")) }

        for (digits in listOf("440313", "4474258", "40.4168", "648.31", "699.42")) log.messages.single() shouldNotContain digits
    }

    @Test
    fun `S014 RF-3 un punto que no se guarda por nombre repetido no se anota como guardado`() {
        runBlocking { points.create(measured("12")) }
        log.entries.clear()

        runBlocking { points.create(measured("12").copy(id = "otro")) } shouldBe PointSaveResult.DuplicateName

        log.entries shouldBe emptyList()
    }
}
