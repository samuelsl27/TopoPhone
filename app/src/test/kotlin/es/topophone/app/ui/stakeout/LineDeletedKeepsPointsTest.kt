package es.topophone.app.ui.stakeout

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.core.model.codes.CodeGeometry
import es.topophone.core.model.point.GeodeticPosition
import es.topophone.core.model.point.LineStakeoutMode
import es.topophone.core.model.point.Measurement
import es.topophone.core.model.point.MeasurementMode
import es.topophone.core.model.point.Point
import es.topophone.core.model.point.PointCoordinates
import es.topophone.core.model.point.PointOrigin
import es.topophone.core.model.point.PointQuality
import es.topophone.core.model.point.StakeSource
import es.topophone.core.model.point.StakeoutRef
import es.topophone.core.model.project.ProjectDefaults
import es.topophone.data.db.TopoPhoneDatabase
import es.topophone.data.line.LineSaveResult
import es.topophone.data.line.NewLine
import es.topophone.data.line.RoomLineRepository
import es.topophone.data.point.RoomPointRepository
import es.topophone.data.project.RoomProjectRepository
import es.topophone.data.project.SaveResult
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith

/** Al borrar la línea se conservan los puntos replanteados con lo que guardaron de ella (S027/RF-16). */
@RunWith(AndroidJUnit4::class)
class LineDeletedKeepsPointsTest {

    private val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), TopoPhoneDatabase::class.java)
        .allowMainThreadQueries()
        .build()
    private val projects = RoomProjectRepository(db)
    private val points = RoomPointRepository(db)
    private val lines = RoomLineRepository(db, points)

    @After
    fun close() = db.close()

    private val projectId: String = runBlocking {
        (projects.create(ProjectDefaults().toDraft().copy(name = "Calle Mayor 12")) as SaveResult.Saved).id
    }

    private val coordinates = PointCoordinates(GeodeticPosition(40.4168, -3.7038, 699.42), 440_000.1234, 4_470_000.5, 700.0)

    private fun vertex(name: String, east: Double) = Point(
        id = "id-$name",
        projectId = projectId,
        name = name,
        description = "",
        origin = PointOrigin.MANUAL,
        coordinates = PointCoordinates(GeodeticPosition(40.4, -3.7, null), east, 4_474_000.0, null),
        measurement = null,
        typed = null,
        edited = false,
        hidden = false,
        copyOf = null,
        createdAtMs = 3_000,
        deletedAtMs = null,
    )

    private fun staked(lineId: String): Point = Point.measured(
        id = "staked-1",
        projectId = projectId,
        name = "BORD_1_1_stkd",
        description = "",
        measurement = Measurement(
            antenna = GeodeticPosition(40.4168, -3.7038, 701.0),
            poleHeightM = 1.8,
            quality = PointQuality("FIX", 24, 0.6, 1.0, 0.009, 0.014, MeasurementMode.INSTANT, 1, 0, fromReplay = false),
            rawNmea = "",
            outOfTolerance = emptySet(),
            measuredFromMs = 1_000,
            measuredToMs = 1_200,
            coordinates = coordinates,
        ),
        createdAtMs = 1_000,
        staked = StakeoutRef(
            designId = "line:$lineId",
            designName = "BORD_1",
            design = coordinates,
            dEastM = 0.01,
            dNorthM = 0.02,
            dHorizontalM = 0.0224,
            dHeightM = 0.0,
            designElevationM = 700.0,
            toleranceHM = 0.025,
            toleranceVM = 0.03,
            withinTolerance = true,
            source = StakeSource.ProjectLine(lineId, "BORD_1"),
            mode = LineStakeoutMode.ALONG,
            chainageM = 12.5,
            lateralOffsetM = 1.0,
            dLateralM = 0.011,
        ),
    )

    private fun setUpLine(): String {
        runBlocking { listOf(vertex("A", 440_000.0), vertex("B", 440_030.0)).forEach { points.create(it) } }
        val id = (
            runBlocking {
                lines.createFromPoints(NewLine(projectId, "BORD_1", null, CodeGeometry.LINE, false, listOf("id-A", "id-B")))
            } as LineSaveResult.Saved
            ).id
        runBlocking { points.create(staked(id)) }
        return id
    }

    private fun stakedPoint(): Point = runBlocking { points.point("staked-1").first() }.shouldNotBeNull()

    @Test
    fun `S027 RF-16 con la linea en la papelera el punto replanteado conserva su linea`() {
        val lineId = setUpLine()

        runBlocking { lines.moveToTrash(lineId) }

        stakedPoint().staked.shouldNotBeNull().source shouldBe StakeSource.ProjectLine(lineId, "BORD_1")
        stakedPoint().staked.shouldNotBeNull().chainageM shouldBe 12.5
    }

    @Test
    fun `S027 RF-16 con la linea borrada del todo el punto sigue con el modo, el PK, el desplazamiento y las diferencias`() {
        val lineId = setUpLine()
        runBlocking { lines.moveToTrash(lineId) }

        runBlocking { lines.deletePermanently(lineId) }

        val ref = stakedPoint().staked.shouldNotBeNull()
        ref.source shouldBe StakeSource.ProjectLine(lineId, "BORD_1")
        ref.mode shouldBe LineStakeoutMode.ALONG
        ref.lateralOffsetM shouldBe 1.0
        ref.dLateralM shouldBe 0.011
        ref.dEastM shouldBe 0.01
        ref.design shouldBe coordinates
        runBlocking { lines.line(lineId).first() } shouldBe null
    }

    @Test
    fun `S027 RF-16 borrar la linea tampoco borra los puntos que eran sus vertices`() {
        val lineId = setUpLine()
        runBlocking { lines.moveToTrash(lineId) }
        runBlocking { lines.deletePermanently(lineId) }

        runBlocking { points.points(projectId).first() }.map { it.name }.sorted() shouldBe listOf("A", "B", "BORD_1_1_stkd")
    }
}
