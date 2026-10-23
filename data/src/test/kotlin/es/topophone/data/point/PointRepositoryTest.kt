package es.topophone.data.point

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.cash.turbine.test
import es.topophone.core.model.point.BaseSource
import es.topophone.core.model.point.GeodeticPosition
import es.topophone.core.model.point.Measurement
import es.topophone.core.model.point.MeasurementMode
import es.topophone.core.model.point.Point
import es.topophone.core.model.point.PointBase
import es.topophone.core.model.point.PointCoordinates
import es.topophone.core.model.point.PointQuality
import es.topophone.core.model.point.PointTilt
import es.topophone.core.model.point.PoleVector
import es.topophone.core.model.point.PrecisionFailure
import es.topophone.core.model.point.TiltAngles
import es.topophone.core.model.point.TiltMode
import es.topophone.core.model.project.ProjectDefaults
import es.topophone.data.db.TopoPhoneDatabase
import es.topophone.data.project.ProjectRepository
import es.topophone.data.project.RoomProjectRepository
import es.topophone.data.project.SaveResult
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith

/** Puntos recolectados de cada proyecto (S010/RF-3, RF-16 a RF-18, RF-21). */
@RunWith(AndroidJUnit4::class)
class PointRepositoryTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val db = Room.inMemoryDatabaseBuilder(context, TopoPhoneDatabase::class.java).build()
    private var now = 1_000L
    private val projects = RoomProjectRepository(db, clock = { now })
    private val points = RoomPointRepository(db)

    @After
    fun close() = db.close()

    private fun newProject(name: String): String = runBlocking {
        projects.create(ProjectDefaults().toDraft().copy(name = name)).shouldBeInstanceOf<SaveResult.Saved>().id
    }

    private fun point(projectId: String, name: String, createdAtMs: Long = 2_000) = Point.measured(
        id = "id-$name-$projectId",
        projectId = projectId,
        name = name,
        description = "arqueta",
        measurement = Measurement(
            antenna = GeodeticPosition(40.416_801_234_567_89, -3.703_712_345_678_9, 701.357_123_456),
            poleHeightM = 1.8,
            quality = PointQuality("FLOAT", 18, 0.7, 2.0, 0.031, 0.052, MeasurementMode.AVERAGED, 5, 2, fromReplay = true),
            rawNmea = "\$GNGGA,1*00\n\$GNGST,1*00",
            outOfTolerance = setOf(PrecisionFailure.NOT_FIX, PrecisionFailure.VERTICAL),
            measuredFromMs = 1_500,
            measuredToMs = 1_900,
            coordinates = PointCoordinates(
                GeodeticPosition(40.416_801_234_567_89, -3.703_712_345_678_9, 699.423_123_456),
                440_290.434_567_891,
                4_474_257.414_123_456,
                648.340_987_654,
            ),
        ),
        createdAtMs = createdAtMs,
    )

    @Test
    fun `S015 RF-20 un punto medido guarda su base y su linea base sin redondear`() = runBlocking<Unit> {
        val project = newProject("Calle Mayor 12")
        val base = PointBase(
            GeodeticPosition(43.557_866_603_2, -5.913_070_411_3, 78.433_812_345),
            BaseSource.CORRECTIONS,
            stationId = 6,
            antennaHeightM = 0.0,
            baselineM = 382_123.456_789_012,
        )
        val saved = point(project, "P12").let { it.copy(measurement = it.measurement!!.copy(base = base)) }

        points.create(saved) shouldBe PointSaveResult.Saved

        points.points(project).first().single().measurement!!.base shouldBe base
        points.pointsForExport(project).single().measurement!!.base shouldBe base
    }

    @Test
    fun `S016 RF-22 un punto guarda su inclinacion tal cual`() = runBlocking<Unit> {
        val project = newProject("Calle Mayor 12")
        val app = PointTilt(
            TiltMode.APP,
            verified = true,
            angles = TiltAngles(
                tiltRad = 0.209_439_510_239_319_55,
                directionRad = 3.673_868_314_731_582_6,
                headingRad = 2.153_736_051_229_437,
                maxTiltRad = 0.218_166_156_499_291_55,
                horizontalAccuracyM = 0.002_743_123_456,
                verticalAccuracyM = 0.001_974_614_000_1,
                pole = PoleVector(-0.105_515_123_456_7, -0.179_123_456_789, 0.978_147_600_733_805_6),
            ),
        )
        val receiverAngles = app.angles!!.copy(headingRad = null, receiverPoleHeightM = 1.8)
        val receiver = PointTilt(TiltMode.RECEIVER, verified = false, angles = receiverAngles)
        val vertical = PointTilt(TiltMode.VERTICAL, verified = false, angles = null)
        listOf("P1" to app, "P2" to receiver, "P3" to vertical).forEachIndexed { i, (name, tilt) ->
            points.create(point(project, name, 2_000L + i).let { it.copy(measurement = it.measurement!!.copy(tilt = tilt)) })
        }

        points.points(project).first().map { it.measurement!!.tilt } shouldBe listOf(app, receiver, vertical)
        points.pointsForExport(project).map { it.measurement!!.tilt } shouldBe listOf(app, receiver, vertical)
    }

    @Test
    fun `S015 RF-20 sin base el punto se guarda sin base`() = runBlocking<Unit> {
        val project = newProject("Calle Mayor 12")
        val receiver = PointBase(GeodeticPosition(40.4166667, -3.7, 700.0), BaseSource.RECEIVER, null, null, 300.5)

        points.create(point(project, "P1"))
        points.create(point(project, "P2").let { it.copy(measurement = it.measurement!!.copy(base = receiver)) })

        points.points(project).first().map { it.measurement!!.base } shouldBe listOf(null, receiver)
    }

    @Test
    fun `S010 RF-16, RF-17 y RF-18 un punto se guarda y se lee tal cual, sin redondear, con su calidad y su observacion`() =
        runBlocking<Unit> {
            val project = newProject("Calle Mayor 12")
            val saved = point(project, "P12")

            points.create(saved) shouldBe PointSaveResult.Saved

            points.points(project).first() shouldBe listOf(saved)
        }

    @Test
    fun `S010 RF-3 no se guardan dos puntos con el mismo nombre en un proyecto, pero si en otro`() = runBlocking<Unit> {
        val calle = newProject("Calle Mayor 12")
        val muralla = newProject("Muralla")
        points.create(point(calle, "P12"))

        points.nameExists(calle, "P12") shouldBe true
        points.create(point(calle, "P12").copy(id = "otro")) shouldBe PointSaveResult.DuplicateName
        points.create(point(muralla, "P12")) shouldBe PointSaveResult.Saved
        points.points(calle).first().size shouldBe 1
    }

    @Test
    fun `S010 RF-2 y RF-21 el ultimo nombre y el numero de puntos siguen a lo guardado`() = runBlocking<Unit> {
        val project = newProject("Calle Mayor 12")
        points.lastName(project).shouldBeNull()

        points.count(project).test {
            awaitItem() shouldBe 0
            points.create(point(project, "P9", createdAtMs = 2_000))
            awaitItem() shouldBe 1
            points.create(point(project, "P10", createdAtMs = 3_000))
            awaitItem() shouldBe 2
        }

        points.lastName(project) shouldBe "P10"
    }

    @Test
    fun `S010 RF-16 los puntos se borran con su proyecto al vaciar la papelera`() = runBlocking<Unit> {
        val project = newProject("Papelera")
        points.create(point(project, "1"))

        projects.moveToTrash(project)
        now += (ProjectRepository.TRASH_DAYS + 1) * 24L * 60 * 60 * 1000
        projects.purgeTrash()

        points.points(project).first().shouldBeEmpty()
    }
}
