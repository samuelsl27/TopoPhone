package es.topophone.data.point

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.core.model.point.AppliedShift
import es.topophone.core.model.point.BaseShiftVector
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

/** Un punto medido con desplazamiento de base guarda el vector, el punto conocido y la punta sin desplazar (S020/RF-8, RF-9, RF-14). */
@RunWith(AndroidJUnit4::class)
class BaseShiftPointSaveTest {

    private val db = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext<Context>(),
        TopoPhoneDatabase::class.java,
    ).build()
    private val projects = RoomProjectRepository(db)
    private val points = RoomPointRepository(db)

    @After
    fun close() = db.close()

    private val project = runBlocking {
        projects.create(ProjectDefaults().toDraft().copy(name = "Calle Mayor 12")).shouldBeInstanceOf<SaveResult.Saved>().id
    }

    private val applied = AppliedShift(
        shiftId = "s1",
        knownName = "V-12",
        vector = BaseShiftVector(-2.147_247_769_869_864, -1.965_396_083_076_484_5, 0.516_280_195_210_129),
        unshiftedGround = GeodeticPosition(40.416_784_692_131_955, -3.703_775_259_501_478, 649.389_000_573_553_1),
    )

    private fun point(id: String, shift: AppliedShift?, replay: Boolean = false) = Point.measured(
        id = id,
        projectId = project,
        name = id,
        description = "",
        measurement = Measurement(
            antenna = GeodeticPosition(40.416_801_234_567_89, -3.703_712_345_678_9, 701.357_123_456),
            poleHeightM = 1.8,
            quality = PointQuality("FIX", 24, 0.6, 1.0, 0.009, 0.014, MeasurementMode.INSTANT, 1, 0, fromReplay = replay),
            rawNmea = "",
            outOfTolerance = emptySet(),
            measuredFromMs = 1,
            measuredToMs = 2,
            coordinates = PointCoordinates(GeodeticPosition(40.4168, -3.7037, 699.4), 440_290.4, 4_474_257.4, 648.3),
            shift = shift,
        ),
        createdAtMs = 3,
    )

    @Test
    fun `S020 RF-8 el punto guarda y devuelve el vector sin redondear`() = runBlocking<Unit> {
        points.create(point("p1", applied))

        val read = points.points(project).first().single().measurement.shouldNotBeNull().shift.shouldNotBeNull()

        read shouldBe applied
        read.vector.dx.toRawBits() shouldBe (-2.147_247_769_869_864).toRawBits()
        read.unshiftedGround.ellipsoidalHeightM.shouldNotBeNull().toRawBits() shouldBe 649.389_000_573_553_1.toRawBits()
    }

    @Test
    fun `S020 RF-8 un punto sin desplazamiento no tiene nada guardado`() = runBlocking<Unit> {
        points.create(point("p2", null))

        points.points(project).first().single().measurement.shouldNotBeNull().shift.shouldBeNull()
    }

    @Test
    fun `S020 RF-8 el punto conserva su vector aunque cambie el activo`() = runBlocking<Unit> {
        points.create(point("p1", applied))
        points.create(point("p3", applied.copy(shiftId = "s2", vector = BaseShiftVector(5.0, 1.0, 0.0))))

        val byName = points.points(project).first().associateBy { it.name }

        byName.getValue("p1").measurement!!.shift!!.vector shouldBe applied.vector
        byName.getValue("p3").measurement!!.shift!!.vector shouldBe BaseShiftVector(5.0, 1.0, 0.0)
    }

    @Test
    fun `S020 RF-14 un punto de reproduccion con desplazamiento queda marcado`() = runBlocking<Unit> {
        points.create(point("p4", applied, replay = true))

        val measurement = points.points(project).first().single().measurement.shouldNotBeNull()

        measurement.quality.fromReplay shouldBe true
        measurement.shift shouldBe applied
    }

    @Test
    fun `S020 RF-8 una punta sin altura se guarda sin altura`() = runBlocking<Unit> {
        val noHeight = applied.copy(unshiftedGround = GeodeticPosition(40.4, -3.7, null))
        points.create(point("p5", noHeight))

        points.points(project).first().single().measurement.shouldNotBeNull().shift shouldBe noHeight
    }
}
