package es.topophone.data.point

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.core.model.point.GearUsed
import es.topophone.core.model.point.GeodeticPosition
import es.topophone.core.model.point.Measurement
import es.topophone.core.model.point.MeasurementMode
import es.topophone.core.model.point.Point
import es.topophone.core.model.point.PointChange
import es.topophone.core.model.point.PointCoordinates
import es.topophone.core.model.point.PointQuality
import es.topophone.core.model.point.PoleKind
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

/** El jalón y la antena de cada punto se guardan por valor y sin redondear (S046/RF-24, RF-25, RF-29). */
@RunWith(AndroidJUnit4::class)
class PointRepositoryGearTest {

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

    private val gear = GearUsed("Jalón corto", PoleKind.SLANT, 1.9500000000001, "Reach RS4 y RS4 Pro", 0.095, 2.0440760000001)

    private fun point(name: String, gear: GearUsed?) = Point.measured(
        id = "id-$name",
        projectId = project,
        name = name,
        description = "",
        measurement = Measurement(
            antenna = GeodeticPosition(40.41668, -3.70371, 701.357),
            poleHeightM = 1.9490760000001,
            quality = PointQuality("FIX", 24, 0.6, 1.0, 0.009, 0.014, MeasurementMode.INSTANT, 1, 0, fromReplay = false),
            rawNmea = "",
            outOfTolerance = emptySet(),
            measuredFromMs = 1_000,
            measuredToMs = 1_000,
            coordinates = PointCoordinates(GeodeticPosition(40.41668, -3.70371, 699.423), 440_297.988, 4_474_244.001, 648.311),
            gear = gear,
        ),
        createdAtMs = 1_000,
    )

    private fun stored(name: String) = runBlocking { points.points(project).first() }.first { it.name == name }

    @Test
    fun `S046 RF-24 el jalon y la antena se guardan y se leen sin redondear`() {
        runBlocking { points.create(point("P1", gear)) } shouldBe PointSaveResult.Saved

        stored("P1").measurement.shouldNotBeNull().gear shouldBe gear
        stored("P1").measurement.shouldNotBeNull().poleHeightM shouldBe 1.9490760000001
    }

    @Test
    fun `S046 RF-29 un punto sin equipo se lee sin equipo`() {
        runBlocking { points.create(point("P2", null)) } shouldBe PointSaveResult.Saved

        stored("P2").measurement.shouldNotBeNull().gear.shouldBeNull()
    }

    @Test
    fun `S046 RF-25 corregir el jalon de un punto guarda el equipo nuevo y deja el de los demas`() {
        runBlocking {
            points.create(point("P3", gear))
            points.create(point("P4", gear))
        }
        val corrected = stored("P3").let { p ->
            p.copy(
                measurement = p.measurement!!.copy(
                    gear = gear.copy(poleKind = PoleKind.VERTICAL, poleEnteredM = 2.0, antennaHeightM = 2.095),
                ),
            )
        }

        runBlocking { points.update(corrected, PointChange.POLE) } shouldBe PointSaveResult.Saved

        stored("P3").measurement.shouldNotBeNull().gear!!.poleEnteredM shouldBe 2.0
        stored("P4").measurement.shouldNotBeNull().gear shouldBe gear
    }
}
