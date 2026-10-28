package es.topophone.data.point

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.core.model.point.AxisHeightRef
import es.topophone.core.model.point.AxisRef
import es.topophone.core.model.point.GeodeticPosition
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

/** Un punto replanteado por PK y una medida de "Analizar" se guardan y se leen sin redondear (S054/RF-10, RF-13, RNF-T6). */
@RunWith(AndroidJUnit4::class)
class AxisStakePointTest {

    private val db = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext<Context>(),
        TopoPhoneDatabase::class.java,
    ).build()
    private val projects = RoomProjectRepository(db)
    private val points = RoomPointRepository(db, clock = { 50_000 })

    @After
    fun close() = db.close()

    private val project = runBlocking {
        projects.create(ProjectDefaults().toDraft().copy(name = "Obra")).shouldBeInstanceOf<SaveResult.Saved>().id
    }

    private val measuredCoordinates = PointCoordinates(
        GeodeticPosition(40.416801234567891, -3.703712345678901, 699.423),
        440_290.434567891,
        4_474_257.414123456,
        648.340987654,
    )
    private val designCoordinates = PointCoordinates(GeodeticPosition(40.4168, -3.7037, 699.428), 440_290.4, 4_474_257.4, 648.346)

    private fun measurement() = Measurement(
        antenna = GeodeticPosition(40.416801234567891, -3.703712345678901, 701.357),
        poleHeightM = 1.8,
        quality = PointQuality("FIX", 24, 0.6, 1.0, 0.009, 0.014, MeasurementMode.INSTANT, 1, 0, fromReplay = false),
        rawNmea = "\$GNGGA,1*00",
        outOfTolerance = emptySet(),
        measuredFromMs = 1_000,
        measuredToMs = 1_200,
        coordinates = measuredCoordinates,
    )

    private val ref = StakeoutRef(
        designId = "axis:Eje A:150000",
        designName = "Eje A",
        design = designCoordinates,
        dEastM = 0.034567891,
        dNorthM = 0.014123456,
        dHorizontalM = 0.037344,
        dHeightM = -0.005012345,
        designElevationM = 648.3460000001,
        toleranceHM = 0.025,
        toleranceVM = 0.03,
        withinTolerance = false,
        source = StakeSource.Alignment("Eje A"),
        chainageM = 150.0000000001,
        lateralOffsetM = 3.5,
        dLateralM = 0.012345678,
        dChainageM = -0.034567891,
        thicknessM = 0.1,
    )

    @Test
    fun `S054 RF-10 el replanteo por PK vuelve con su eje PK desplazamiento espesor y diferencias`() {
        runBlocking { points.create(Point.measured("a1", project, "Eje A_1_stkd", "", measurement(), 1_000, ref)) } shouldBe
            PointSaveResult.Saved

        val read = runBlocking { points.point("a1").first() }.shouldNotBeNull()

        read.origin shouldBe PointOrigin.STAKED
        read.staked shouldBe ref
        read.staked.shouldNotBeNull().source shouldBe StakeSource.Alignment("Eje A")
        read.staked.shouldNotBeNull().chainageM.shouldNotBeNull().toRawBits() shouldBe 150.0000000001.toRawBits()
        read.staked.shouldNotBeNull().dChainageM shouldBe -0.034567891
        read.staked.shouldNotBeNull().mode.shouldBeNull()
        read.measurement.shouldNotBeNull().rawNmea shouldBe "\$GNGGA,1*00"
    }

    @Test
    fun `S054 RF-10 el replanteo por PK no cuenta como replanteo de un punto del proyecto`() {
        runBlocking { points.create(Point.measured("a1", project, "Eje A_1_stkd", "", measurement(), 1_000, ref)) }

        val summaries = runBlocking { points.summaries(project).first() }

        summaries.single().stakeCount shouldBe 0
    }

    @Test
    fun `S054 RF-13 una medida desde Analizar guarda el eje el PK el desplazamiento y la diferencia de cota`() {
        val axis = AxisRef("Eje A", 150.0000000001, 3.5, dHeightM = -0.123456789, heightRef = AxisHeightRef.SURFACE)

        runBlocking { points.create(Point.measured("m1", project, "7", "", measurement(), 1_000, onAxis = axis)) } shouldBe
            PointSaveResult.Saved

        val read = runBlocking { points.point("m1").first() }.shouldNotBeNull()

        read.origin shouldBe PointOrigin.MEASURED
        read.onAxis shouldBe axis
        read.onAxis.shouldNotBeNull().dHeightM shouldBe -0.123456789
        read.staked.shouldBeNull()
    }

    @Test
    fun `S054 RF-13 una medida sin referencia de cota guarda el eje con la diferencia vacia`() {
        val axis = AxisRef("Eje A", 150.0, -3.5)

        runBlocking { points.create(Point.measured("m1", project, "7", "", measurement(), 1_000, onAxis = axis)) }

        val read = runBlocking { points.point("m1").first() }.shouldNotBeNull()

        read.onAxis shouldBe axis
        read.onAxis.shouldNotBeNull().dHeightM.shouldBeNull()
        read.onAxis.shouldNotBeNull().heightRef.shouldBeNull()
    }

    @Test
    fun `S054 RF-10 la consulta del informe y de la exportacion trae el eje, el espesor y la diferencia de PK`() {
        runBlocking { points.create(Point.measured("a1", project, "Eje A_1_stkd", "", measurement(), 1_000, ref)) }

        val exported = runBlocking { points.pointsForExport(project) }.single().staked.shouldNotBeNull()

        exported.source shouldBe StakeSource.Alignment("Eje A")
        exported.thicknessM shouldBe 0.1
        exported.dChainageM shouldBe -0.034567891
        exported.chainageM.shouldNotBeNull().toRawBits() shouldBe 150.0000000001.toRawBits()
    }
}
