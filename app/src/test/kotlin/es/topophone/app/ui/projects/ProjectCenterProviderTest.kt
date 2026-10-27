package es.topophone.app.ui.projects

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.app.ui.map.FakePointRepository
import es.topophone.core.model.map.MapViewport
import es.topophone.core.model.point.GeodeticPosition
import es.topophone.core.model.point.Measurement
import es.topophone.core.model.point.MeasurementMode
import es.topophone.core.model.point.Point
import es.topophone.core.model.point.PointCoordinates
import es.topophone.core.model.point.PointQuality
import es.topophone.core.model.project.CentralPoint
import es.topophone.core.model.project.ProjectDefaults
import es.topophone.data.db.TopoPhoneDatabase
import es.topophone.data.map.RoomMapViewportRepository
import es.topophone.data.project.RoomProjectRepository
import es.topophone.data.project.SaveResult
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith

/** Un único cálculo del centro del proyecto (S006/RF-35, RF-37). */
@RunWith(AndroidJUnit4::class)
class ProjectCenterProviderTest {

    private val db = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext<Context>(),
        TopoPhoneDatabase::class.java,
    ).build()
    private val projects = RoomProjectRepository(db)
    private val viewports = RoomMapViewportRepository(db)
    private val points = FakePointRepository()
    private val provider = ProjectCenterProvider(points, viewports)
    private val projectId = runBlocking {
        projects.create(ProjectDefaults().toDraft().copy(name = "Proyecto")).shouldBeInstanceOf<SaveResult.Saved>().id
    }
    private val central = CentralPoint(39.46991234567891, -0.37631234567891)

    @After
    fun close() = db.close()

    private fun center() = runBlocking { provider.center(projects.project(projectId)!!) }

    private fun point(lat: Double, lon: Double) = Point.measured(
        id = "p-$lat",
        projectId = projectId,
        name = "P$lat",
        description = "",
        measurement = Measurement(
            antenna = GeodeticPosition(lat, lon, 700.0),
            poleHeightM = 1.8,
            quality = PointQuality("FIX", 20, 0.6, 1.0, 0.01, 0.015, MeasurementMode.INSTANT, 1, 0, false),
            rawNmea = "",
            outOfTolerance = emptySet(),
            measuredFromMs = 0,
            measuredToMs = 0,
            coordinates = PointCoordinates(GeodeticPosition(lat, lon, 698.0), 440_297.0, 4_474_244.0, 650.0),
        ),
        createdAtMs = 0,
    )

    private fun setCentral(centralPoint: CentralPoint?) = runBlocking {
        projects.update(projectId, projects.project(projectId)!!.toDraft().copy(centralPoint = centralPoint))
    }

    @Test
    fun `S006 RF-37 un proyecto sin punto central ni puntos ni encuadre no tiene centro`() {
        center().shouldBeNull()
    }

    @Test
    fun `S006 RF-37 el punto central manda sobre los puntos y el encuadre`() {
        points.points.value = listOf(point(37.0, -5.0))
        runBlocking { viewports.save(projectId, MapViewport(41.0, -2.0, 15.0)) }
        setCentral(central)

        center() shouldBe central
    }

    @Test
    fun `S006 RF-35 al borrar el punto central el centro vuelve a ser el de los puntos y luego el del encuadre`() {
        points.points.value = listOf(point(40.0, -4.0), point(42.0, -2.0))
        runBlocking { viewports.save(projectId, MapViewport(35.0, -1.0, 15.0)) }
        setCentral(central)

        setCentral(null)
        center()!!.latitudeDeg shouldBe (41.0 plusOrMinus 1e-12)
        center()!!.longitudeDeg shouldBe (-3.0 plusOrMinus 1e-12)

        points.points.value = emptyList()
        center() shouldBe CentralPoint(35.0, -1.0)
    }
}
