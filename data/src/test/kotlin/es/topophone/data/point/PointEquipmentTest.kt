package es.topophone.data.point

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.core.model.guide.EquipmentProfile
import es.topophone.core.model.point.GeodeticPosition
import es.topophone.core.model.point.Measurement
import es.topophone.core.model.point.MeasurementMode
import es.topophone.core.model.point.Point
import es.topophone.core.model.point.PointCoordinates
import es.topophone.core.model.point.PointQuality
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

/** El equipo del receptor se guarda con cada punto medido y se lee de vuelta, también al exportar (S043/RF-19, RF-20). */
@RunWith(AndroidJUnit4::class)
class PointEquipmentTest {

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

    private fun point(name: String, equipment: EquipmentProfile?) = Point.measured(
        id = "id-$name",
        projectId = project,
        name = name,
        description = "",
        measurement = Measurement(
            antenna = GeodeticPosition(40.41668, -3.70371, 701.357),
            poleHeightM = 1.8,
            quality = PointQuality("FIX", 24, 0.6, 1.0, 0.009, 0.014, MeasurementMode.INSTANT, 1, 0, fromReplay = false),
            rawNmea = "",
            outOfTolerance = emptySet(),
            measuredFromMs = 1_000,
            measuredToMs = 1_000,
            coordinates = PointCoordinates(GeodeticPosition(40.41668, -3.70371, 699.423), 440_297.988, 4_474_244.001, 648.311),
            equipment = equipment,
        ),
        createdAtMs = 1_000,
    )

    private fun stored(name: String) = runBlocking { points.points(project).first() }.first { it.name == name }

    @Test
    fun `S043 RF-19 el equipo se guarda con el punto y se lee de vuelta`() {
        val equipment = EquipmentProfile("Reach RS3", "RS3-0042", "2026.2 estable")

        runBlocking { points.create(point("P1", equipment)) } shouldBe PointSaveResult.Saved

        stored("P1").measurement.shouldNotBeNull().equipment shouldBe equipment
    }

    @Test
    fun `S043 RF-19 un punto sin equipo lo lee como null`() {
        runBlocking { points.create(point("P2", null)) } shouldBe PointSaveResult.Saved

        stored("P2").measurement.shouldNotBeNull().equipment.shouldBeNull()
    }

    @Test
    fun `S043 RF-19 un campo vacio sigue siendo un campo vacio y los otros se conservan`() {
        runBlocking { points.create(point("P3", EquipmentProfile("Reach RS3", "", ""))) } shouldBe PointSaveResult.Saved

        stored("P3").measurement.shouldNotBeNull().equipment shouldBe EquipmentProfile("Reach RS3", "", "")
    }

    @Test
    fun `S043 RF-20 el equipo tambien sale al leer los puntos para exportar`() {
        runBlocking { points.create(point("P4", EquipmentProfile("Reach RS3", "RS3-0042", "2026.2"))) }

        val exported = runBlocking { points.pointsForExport(project) }.first { it.name == "P4" }

        exported.measurement.shouldNotBeNull().equipment shouldBe EquipmentProfile("Reach RS3", "RS3-0042", "2026.2")
    }
}
