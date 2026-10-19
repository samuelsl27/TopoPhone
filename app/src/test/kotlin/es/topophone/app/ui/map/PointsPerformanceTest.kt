package es.topophone.app.ui.map

import android.content.Context
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.core.formats.csv.CsvExport
import es.topophone.core.formats.csv.CsvOptions
import es.topophone.core.model.point.GeodeticPosition
import es.topophone.core.model.point.Measurement
import es.topophone.core.model.point.MeasurementMode
import es.topophone.core.model.point.Point
import es.topophone.core.model.point.PointCoordinates
import es.topophone.core.model.point.PointQuality
import es.topophone.core.model.point.PointSearch
import es.topophone.core.model.project.ProjectDefaults
import es.topophone.data.db.TopoPhoneDatabase
import es.topophone.data.point.RoomPointRepository
import es.topophone.data.project.RoomProjectRepository
import es.topophone.data.project.SaveResult
import io.kotest.matchers.longs.shouldBeLessThan
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import java.io.BufferedWriter
import java.io.ByteArrayOutputStream
import java.io.OutputStreamWriter
import kotlin.time.measureTimedValue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Un proyecto con 10 000 puntos se lee y se prepara para el mapa en menos de 2 s (S010, RNF de la spec), y se exporta a CSV
 * en menos de 3 s (S012, RNF de la spec). Robolectric sirve de cota; la medida en el móvil de referencia es de la demo.
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

    private fun tenThousandPoints(): String {
        val projectId = runBlocking { (projects.create(ProjectDefaults().toDraft().copy(name = "Parcelaria")) as SaveResult.Saved).id }
        runBlocking {
            db.withTransaction {
                repeat(POINTS) { i ->
                    val lat = 40.4 + (i % 100) * 1e-4
                    val lon = -3.7 + (i / 100) * 1e-4
                    points.create(
                        Point.measured(
                            id = "id-$i",
                            projectId = projectId,
                            name = "P$i",
                            description = "",
                            measurement = Measurement(
                                antenna = GeodeticPosition(lat, lon, 700.0),
                                poleHeightM = 1.8,
                                quality = PointQuality("FIX", 20, 0.6, 1.0, 0.01, 0.015, MeasurementMode.INSTANT, 1, 0, false),
                                rawNmea = "\$GNGGA,$i*00\n\$GNGST,$i*00",
                                outOfTolerance = emptySet(),
                                measuredFromMs = i.toLong(),
                                measuredToMs = i.toLong(),
                                coordinates = PointCoordinates(GeodeticPosition(lat, lon, 698.066), 440_000.0 + i, 4_474_000.0 + i, 650.0),
                            ),
                            createdAtMs = i.toLong(),
                        ),
                    )
                }
            }
        }
        return projectId
    }

    @Test
    fun `S010 RNF con 10 000 puntos el proyecto se lee y se prepara para el mapa en menos de 2 s`() {
        val projectId = tenThousandPoints()

        val (mapPoints, elapsed) = measureTimedValue {
            val list = runBlocking { points.summaries(projectId).first() }
            MapPoints.from(list).also { MapPoints.labelMinZoom(it) }
        }

        mapPoints.size shouldBe POINTS
        elapsed.inWholeMilliseconds shouldBeLessThan 2_000
    }

    @Test
    fun `S011 RNF con 10 000 puntos la lista se lee y la busqueda filtra en menos de 1 s`() {
        val projectId = tenThousandPoints()

        val (found, elapsed) = measureTimedValue {
            val summaries = runBlocking { points.summaries(projectId).first() }
            summaries.size shouldBe POINTS
            summaries.filter { PointSearch.matches(it, "p99") }
        }

        found.map { it.name }.toSet() shouldBe setOf("P99") + (990..999).map { "P$it" } + (9900..9999).map { "P$it" }
        elapsed.inWholeMilliseconds shouldBeLessThan 1_000
    }

    @Test
    fun `S012 RNF con 10 000 puntos los puntos se leen y el CSV se escribe en menos de 3 s`() {
        val projectId = tenThousandPoints()
        val project = runBlocking { projects.project(projectId) }.shouldNotBeNull()

        val (bytes, elapsed) = measureTimedValue {
            val read = runBlocking { points.pointsForExport(projectId) }
            val out = ByteArrayOutputStream()
            BufferedWriter(OutputStreamWriter(out, Charsets.UTF_8)).use { writer ->
                CsvExport.write(read.asSequence(), project, CsvOptions(), writer)
            }
            out.toByteArray()
        }

        bytes.toString(Charsets.UTF_8).split("\r\n").size shouldBe POINTS + 2 // cabecera, puntos y la línea vacía del CRLF final
        elapsed.inWholeMilliseconds shouldBeLessThan 3_000
    }

    private companion object {
        const val POINTS = 10_000
    }
}
