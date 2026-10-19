package es.topophone.data.log

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.core.model.log.LogCategory
import es.topophone.core.model.point.GeodeticPosition
import es.topophone.core.model.point.Measurement
import es.topophone.core.model.point.MeasurementMode
import es.topophone.core.model.point.Point
import es.topophone.core.model.point.PointCoordinates
import es.topophone.core.model.point.PointQuality
import es.topophone.core.model.project.ProjectDefaults
import es.topophone.data.db.TopoPhoneDatabase
import es.topophone.data.point.PointSaveResult
import es.topophone.data.point.RoomPointRepository
import es.topophone.data.project.RoomProjectRepository
import es.topophone.data.project.SaveResult
import io.kotest.matchers.longs.shouldBeLessThan
import io.kotest.matchers.shouldBe
import kotlin.time.measureTime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

/**
 * 10 000 anotaciones seguidas no retrasan el guardado de un punto más de 50 ms (S014, RNF-T13). Robolectric sirve de
 * cota; la medida en el móvil es de la demo.
 */
@RunWith(AndroidJUnit4::class)
class LogPerformanceTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val db = TopoPhoneDatabase.build(context, "logperf-${System.nanoTime()}.db")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var log: FileAppLog
    private lateinit var points: RoomPointRepository
    private lateinit var projectId: String
    private var next = 0

    @Before
    fun open() {
        log = FileAppLog(folder.newFolder("log"), flowOf(10), scope)
        points = RoomPointRepository(db, log = log)
        projectId = runBlocking {
            (RoomProjectRepository(db).create(ProjectDefaults().toDraft().copy(name = "Parcelaria")) as SaveResult.Saved).id
        }
    }

    @After
    fun close() {
        scope.cancel()
        db.close()
        context.deleteDatabase(db.openHelper.databaseName)
    }

    private fun save(): Long = measureTime {
        val i = next++
        val point = Point.measured(
            id = "id-$i",
            projectId = projectId,
            name = "P$i",
            description = "",
            measurement = Measurement(
                antenna = GeodeticPosition(40.4 + i * 1e-6, -3.7, 700.0),
                poleHeightM = 1.8,
                quality = PointQuality("FIX", 24, 0.6, 1.0, 0.009, 0.014, MeasurementMode.INSTANT, 1, 0, fromReplay = false),
                rawNmea = "",
                outOfTolerance = emptySet(),
                measuredFromMs = 0,
                measuredToMs = 0,
                coordinates = PointCoordinates(GeodeticPosition(40.4 + i * 1e-6, -3.7, 698.2), 440_313.12, 4_474_258.87, 648.31),
            ),
            createdAtMs = 0,
        )
        runBlocking { points.create(point) } shouldBe PointSaveResult.Saved
    }.inWholeMilliseconds

    private fun List<Long>.median() = sorted()[size / 2]

    @Test
    fun `S014 RNF 10000 anotaciones no retrasan un punto mas de 50 ms`() {
        repeat(10) { save() } // calentamiento
        val alone = List(10) { save() }.median()

        val flooded = List(5) {
            measureTime {
                repeat(10_000) { n -> log.info(LogCategory.RECEIVER, "Anotación de prueba $n con un texto de longitud normal") }
                save()
            }.inWholeMilliseconds
        }.median()

        (flooded - alone) shouldBeLessThan 50L
    }
}
