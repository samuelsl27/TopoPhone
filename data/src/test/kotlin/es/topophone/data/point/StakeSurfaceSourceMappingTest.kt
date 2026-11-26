package es.topophone.data.point

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.core.model.point.ElevationSource
import es.topophone.core.model.point.GeodeticPosition
import es.topophone.core.model.point.Measurement
import es.topophone.core.model.point.MeasurementMode
import es.topophone.core.model.point.Point
import es.topophone.core.model.point.PointCoordinates
import es.topophone.core.model.point.PointOrigin
import es.topophone.core.model.point.PointQuality
import es.topophone.core.model.point.StakeSource
import es.topophone.core.model.project.ProjectDefaults
import es.topophone.core.survey.StakeoutDiff
import es.topophone.data.db.TopoPhoneDatabase
import es.topophone.data.project.RoomProjectRepository
import es.topophone.data.project.SaveResult
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith

/** Un punto replanteado contra la superficie solo en cota vuelve de la base con su origen y sin redondear (S062/RF-17, RNF-T6). */
@RunWith(AndroidJUnit4::class)
class StakeSurfaceSourceMappingTest {

    private val db = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext<Context>(),
        TopoPhoneDatabase::class.java,
    ).build()
    private val points = RoomPointRepository(db, clock = { 50_000 })
    private val project = runBlocking {
        RoomProjectRepository(db).create(ProjectDefaults().toDraft().copy(name = "Obra")).shouldBeInstanceOf<SaveResult.Saved>().id
    }

    @After
    fun close() = db.close()

    private val measured = PointCoordinates(
        GeodeticPosition(40.416801234567891, -3.703712345678901, 663.388123),
        440_123.456789012,
        4_474_321.987654321,
        612.388123456,
    )

    private fun measurement() = Measurement(
        antenna = GeodeticPosition(40.416801234567891, -3.703712345678901, 665.388),
        poleHeightM = 2.0,
        quality = PointQuality("FIX", 24, 0.6, 1.0, 0.009, 0.014, MeasurementMode.INSTANT, 1, 0, fromReplay = false),
        rawNmea = "\$GNGGA,1*00",
        outOfTolerance = emptySet(),
        measuredFromMs = 1_000,
        measuredToMs = 1_200,
        coordinates = measured,
    )

    private val ref = StakeoutDiff.surfaceRef("s1", "explanacion", measured, designElevationM = 612.440000001, toleranceVM = 0.03).copy(
        elevationSource = ElevationSource.SURFACE,
        surfaceName = "explanacion",
        surfaceImportedAtMs = 1_790_000_000_000L,
        surfaceElevationM = 612.340000001,
        verticalOffsetM = 0.1,
    )

    @Test
    fun `S062 RF-17 el punto vuelve con el origen superficie y todo lo guardado sin redondear`() {
        runBlocking { points.create(Point.measured("s1", project, "SUP1", "", measurement(), 1_000, ref)) } shouldBe PointSaveResult.Saved

        val read = runBlocking { points.point("s1").first() }.shouldNotBeNull()

        read.origin shouldBe PointOrigin.STAKED
        read.staked shouldBe ref
        read.staked.shouldNotBeNull().source shouldBe StakeSource.Surface("explanacion")
        read.staked.shouldNotBeNull().fromSurfaceOnly shouldBe true
        read.staked.shouldNotBeNull().dHeightM.shouldNotBeNull().toRawBits() shouldBe ref.dHeightM!!.toRawBits()
    }

    @Test
    fun `S062 RF-17 un replanteo de un punto sigue sin el origen superficie`() {
        val pointRef = StakeoutDiff.ref("d1", "12", measured.copy(easting = 440_123.0), measured, toleranceHM = 0.025, toleranceVM = 0.03)
        runBlocking { points.create(Point.measured("p1", project, "12_stkd", "", measurement(), 1_000, pointRef)) }

        runBlocking { points.point("p1").first() }.shouldNotBeNull().staked.shouldNotBeNull().source shouldBe StakeSource.ProjectPoint
    }
}
