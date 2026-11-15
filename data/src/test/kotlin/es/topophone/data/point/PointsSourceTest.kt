package es.topophone.data.point

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.core.model.point.GeodeticPosition
import es.topophone.core.model.point.Measurement
import es.topophone.core.model.point.MeasurementMode
import es.topophone.core.model.point.Point
import es.topophone.core.model.point.PointCoordinates
import es.topophone.core.model.point.PointQuality
import es.topophone.core.model.point.PositionSource
import es.topophone.core.model.project.ProjectDefaults
import es.topophone.data.db.TopoPhoneDatabase
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

/** La fuente de la posición se guarda con cada punto y se lee de vuelta (S022/RF-11, RF-19). */
@RunWith(AndroidJUnit4::class)
class PointsSourceTest {

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

    private fun point(name: String, source: PositionSource?, fromReplay: Boolean) = Point.measured(
        id = "id-$name",
        projectId = project,
        name = name,
        description = "",
        measurement = Measurement(
            antenna = GeodeticPosition(40.41668, -3.70371, 701.357),
            poleHeightM = 0.0,
            quality = PointQuality(
                "SINGLE",
                9,
                null,
                null,
                2.5,
                null,
                MeasurementMode.INSTANT,
                1,
                0,
                fromReplay = fromReplay,
                source = source ?: PositionSource.RECEIVER,
            ),
            rawNmea = "",
            outOfTolerance = emptySet(),
            measuredFromMs = 1_000,
            measuredToMs = 1_000,
            coordinates = PointCoordinates(GeodeticPosition(40.41668, -3.70371, 701.357), 440_297.988, 4_474_244.001, 648.311),
        ),
        createdAtMs = 1_000,
    )

    private fun stored(name: String) = runBlocking { points.points(project).first() }.first { it.name == name }

    @Test
    fun `S022 RF-11 cada fuente se guarda y se lee de vuelta`() {
        for (source in PositionSource.entries) {
            runBlocking { points.create(point(source.name, source, source.simulated)) } shouldBe PointSaveResult.Saved

            val quality = stored(source.name).measurement.shouldNotBeNull().quality
            quality.source shouldBe source
            quality.fromReplay shouldBe source.simulated
        }
    }

    @Test
    fun `S022 RF-11 un punto del movil conserva su precision y sin HDOP ni edad de correcciones`() {
        runBlocking { points.create(point("Movil", PositionSource.PHONE, fromReplay = false)) } shouldBe PointSaveResult.Saved

        val quality = stored("Movil").measurement.shouldNotBeNull().quality
        quality.source shouldBe PositionSource.PHONE
        quality.horizontalM shouldBe 2.5
        quality.hdop shouldBe null
        quality.correctionAgeS shouldBe null
    }

    @Test
    fun `S022 RF-19 un punto de una reproduccion guardado como antes se lee como reproduccion`() {
        // Sin fuente explicita: el valor por defecto es el receptor, y una reproduccion marcada con fromReplay se guarda tal cual.
        runBlocking { points.create(point("Repro", PositionSource.REPLAY, fromReplay = true)) } shouldBe PointSaveResult.Saved

        stored("Repro").measurement.shouldNotBeNull().quality.source shouldBe PositionSource.REPLAY
    }
}
