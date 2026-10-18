package es.topophone.app.ui.map

import android.content.Context
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.core.model.point.CollectedPoint
import es.topophone.core.model.point.GeodeticPosition
import es.topophone.core.model.point.MeasurementMode
import es.topophone.core.model.point.PointQuality
import es.topophone.core.model.project.ProjectDefaults
import es.topophone.data.db.TopoPhoneDatabase
import es.topophone.data.point.RoomPointRepository
import es.topophone.data.project.RoomProjectRepository
import es.topophone.data.project.SaveResult
import io.kotest.matchers.longs.shouldBeLessThan
import io.kotest.matchers.shouldBe
import kotlin.time.measureTimedValue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Un proyecto con 10 000 puntos se lee y se prepara para el mapa en menos de 2 s (S010, RNF de la spec).
 * Robolectric sirve de cota; la medida en el móvil de referencia es de la demo.
 */
@RunWith(AndroidJUnit4::class)
class PointsPerformanceTest {

    private val db = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext<Context>(),
        TopoPhoneDatabase::class.java,
    ).build()
    private val projects = RoomProjectRepository(db)
    private val points = RoomPointRepository(db)

    @After
    fun close() = db.close()

    @Test
    fun `S010 RNF con 10 000 puntos el proyecto se lee y se prepara para el mapa en menos de 2 s`() {
        val projectId = runBlocking { (projects.create(ProjectDefaults().toDraft().copy(name = "Parcelaria")) as SaveResult.Saved).id }
        runBlocking {
            db.withTransaction {
                repeat(POINTS) { i ->
                    val lat = 40.4 + (i % 100) * 1e-4
                    val lon = -3.7 + (i / 100) * 1e-4
                    points.create(
                        CollectedPoint(
                            id = "id-$i", projectId = projectId, name = "P$i", description = "",
                            antenna = GeodeticPosition(lat, lon, 700.0), poleHeightM = 1.8, ground = GeodeticPosition(lat, lon, 698.066),
                            easting = 440_000.0 + i, northing = 4_474_000.0 + i, height = 650.0,
                            quality = PointQuality("FIX", 20, 0.6, 1.0, 0.01, 0.015, MeasurementMode.INSTANT, 1, 0, false),
                            rawNmea = "\$GNGGA,$i*00\n\$GNGST,$i*00", outOfTolerance = emptySet(),
                            measuredFromMs = i.toLong(), measuredToMs = i.toLong(), createdAtMs = i.toLong(),
                        ),
                    )
                }
            }
        }

        val (mapPoints, elapsed) = measureTimedValue {
            val list = runBlocking { points.points(projectId).first() }
            MapPoints.from(list).also { MapPoints.labelMinZoom(it) }
        }

        mapPoints.size shouldBe POINTS
        elapsed.inWholeMilliseconds shouldBeLessThan 2_000
    }

    private companion object {
        const val POINTS = 10_000
    }
}
