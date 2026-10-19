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
import es.topophone.core.model.project.ProjectDefaults
import es.topophone.data.db.TopoPhoneDatabase
import es.topophone.data.project.RoomProjectRepository
import es.topophone.data.project.SaveResult
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith

/** Puntos que se exportan: los activos, ocultos incluidos, en orden de creación y sin las frases NMEA (S012/RF-1, RF-3). */
@RunWith(AndroidJUnit4::class)
class PointRepositoryExportTest {

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

    private fun point(name: String, createdAtMs: Long) = Point.measured(
        id = "id-$name",
        projectId = project,
        name = name,
        description = "",
        measurement = Measurement(
            antenna = GeodeticPosition(40.41668, -3.70371, 701.357),
            poleHeightM = 1.8,
            quality = PointQuality("FIX", 24, 0.6, 1.0, 0.009, 0.014, MeasurementMode.AVERAGED, 20, 6, fromReplay = false),
            rawNmea = "\$GNGGA,1*00\n\$GNGST,1*00",
            outOfTolerance = emptySet(),
            measuredFromMs = 1_000,
            measuredToMs = 21_000,
            coordinates = PointCoordinates(GeodeticPosition(40.41668, -3.70371, 699.423), 440_297.988, 4_474_244.001, 648.311),
        ),
        createdAtMs = createdAtMs,
    )

    @Test
    fun `S012 RF-1 y RF-3 se exportan los activos, ocultos incluidos, en orden de creacion y sin la papelera`() {
        runBlocking {
            points.create(point("2", 2_000))
            points.create(point("1", 1_000))
            points.create(point("3", 3_000))
            points.create(point("borrado", 4_000))
            points.setHidden(listOf("id-3"), hidden = true)
            points.moveToTrash(listOf("id-borrado"))
        }

        val exported = runBlocking { points.pointsForExport(project) }

        exported.map { it.name } shouldBe listOf("1", "2", "3")
        exported.last().hidden shouldBe true
    }

    @Test
    fun `S012 RF-6 el punto exportado lleva todo salvo las frases NMEA, que el CSV no usa`() {
        val saved = point("1", 1_000)
        runBlocking { points.create(saved) }

        val exported = runBlocking { points.pointsForExport(project) }.single()

        exported shouldBe saved.copy(measurement = saved.measurement!!.copy(rawNmea = ""))
    }
}
