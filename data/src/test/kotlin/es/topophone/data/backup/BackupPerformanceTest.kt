package es.topophone.data.backup

import android.content.Context
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.core.formats.backup.BackupKind
import es.topophone.core.model.point.GeodeticPosition
import es.topophone.core.model.point.Measurement
import es.topophone.core.model.point.MeasurementMode
import es.topophone.core.model.point.Point
import es.topophone.core.model.point.PointCoordinates
import es.topophone.core.model.point.PointQuality
import es.topophone.core.model.project.ProjectDefaults
import es.topophone.data.db.TopoPhoneDatabase
import es.topophone.data.point.RoomPointRepository
import es.topophone.data.project.RoomProjectRepository
import es.topophone.data.project.SaveResult
import io.kotest.matchers.longs.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.io.ByteArrayOutputStream
import kotlin.time.measureTimedValue
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

/**
 * Con 10 000 puntos, la copia completa se escribe en menos de 5 s y se restaura en menos de 10 s (S013, RNF de la spec).
 * Robolectric sirve de cota; la medida en el móvil de referencia es de la demo.
 */
@RunWith(AndroidJUnit4::class)
class BackupPerformanceTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val name = "perf-${System.nanoTime()}.db"
    private lateinit var db: TopoPhoneDatabase
    private lateinit var settings: TestSettings
    private lateinit var repository: BackupRepository

    /** Unas frases NMEA como las de una medida instantánea (GGA, GST, GSA y RMC). */
    private val nmea = listOf(
        "\$GNGGA,101530.20,4025.0080000,N,00342.2228000,W,4,24,0.6,650.123,M,51.234,M,1.0,0001*5C",
        "\$GNGST,101530.20,0.012,0.010,0.007,45.0,0.008,0.006,0.015*4F",
        "\$GNGSA,A,3,02,05,07,09,13,14,15,17,19,20,24,30,1.2,0.6,1.0,1*3A",
        "\$GNRMC,101530.20,A,4025.0080000,N,00342.2228000,W,0.01,0.0,191026,,,R,V*1C",
    ).joinToString("\n")

    @Before
    fun open() {
        db = TopoPhoneDatabase.build(context, name)
        settings = TestSettings(folder.newFolder("settings"))
        repository =
            BackupRepository(
                context,
                db,
                settings.archive,
                "0.13.0",
                workDir = folder.newFolder("work"),
                undoDir = folder.newFolder("undo"),
            )
        val projects = RoomProjectRepository(db)
        val points = RoomPointRepository(db)
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
                            description = "Bordillo $i",
                            measurement = Measurement(
                                antenna = GeodeticPosition(lat, lon, 700.0),
                                poleHeightM = 1.8,
                                quality = PointQuality("FIX", 20, 0.6, 1.0, 0.01, 0.015, MeasurementMode.INSTANT, 1, 0, false),
                                rawNmea = nmea,
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
    }

    @After
    fun close() {
        settings.close()
        db.close()
        context.deleteDatabase(name)
    }

    @Test
    fun `S013 RNF 10 000 puntos se copian en menos de 5 s`() {
        val (bytes, elapsed) = measureTimedValue {
            ByteArrayOutputStream().also { runBlocking { repository.writeBackup(it) } shouldBe true }.toByteArray()
        }

        bytes.isNotEmpty() shouldBe true
        elapsed.inWholeMilliseconds shouldBeLessThan 5_000
    }

    @Test
    fun `S013 RNF 10 000 puntos se restauran en menos de 10 s`() {
        val bytes = ByteArrayOutputStream().also { runBlocking { repository.writeBackup(it) } }.toByteArray()

        val (result, elapsed) = measureTimedValue {
            runBlocking {
                val valid = repository.read({ bytes.inputStream() }, BackupKind.BACKUP).shouldBeInstanceOf<BackupRead.Valid>()
                repository.restore(valid)
            }
        }

        result shouldBe RestoreResult.Restored(projects = 1, points = POINTS)
        elapsed.inWholeMilliseconds shouldBeLessThan 10_000
    }

    private companion object {
        const val POINTS = 10_000
    }
}
