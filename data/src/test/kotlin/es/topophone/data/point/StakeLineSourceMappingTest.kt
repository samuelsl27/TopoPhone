package es.topophone.data.point

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.core.model.codes.AttributeType
import es.topophone.core.model.codes.CodeSymbol
import es.topophone.core.model.codes.PointAttribute
import es.topophone.core.model.codes.PointCode
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

/** Un punto replanteado de una línea del proyecto guarda de qué línea viene y se lee sin redondear (S027/RF-11, RF-12, RNF-T6). */
@RunWith(AndroidJUnit4::class)
class StakeLineSourceMappingTest {

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
        designId = "line:l1",
        designName = "BORD_1",
        design = designCoordinates,
        dEastM = 0.034567891,
        dNorthM = 0.014123456,
        dHorizontalM = 0.037344,
        dHeightM = -0.005012345,
        designElevationM = 648.3460000001,
        toleranceHM = 0.025,
        toleranceVM = 0.03,
        withinTolerance = false,
        source = StakeSource.ProjectLine("l1", "BORD_1"),
        mode = LineStakeoutMode.ALONG,
        chainageM = 45.123456789,
        lateralOffsetM = -1.5,
        dLateralM = 0.012345678,
    )

    @Test
    fun `S027 RF-12 el replanteo de una linea vuelve con la linea el modo el PK el desplazamiento y las diferencias`() {
        runBlocking { points.create(Point.measured("a1", project, "BORD_1_1_stkd", "", measurement(), 1_000, ref)) } shouldBe
            PointSaveResult.Saved

        val read = runBlocking { points.point("a1").first() }.shouldNotBeNull()

        read.origin shouldBe PointOrigin.STAKED
        read.staked shouldBe ref
        read.staked.shouldNotBeNull().source shouldBe StakeSource.ProjectLine("l1", "BORD_1")
        read.staked.shouldNotBeNull().chainageM.shouldNotBeNull().toRawBits() shouldBe 45.123456789.toRawBits()
        read.staked.shouldNotBeNull().lateralOffsetM shouldBe -1.5
        read.staked.shouldNotBeNull().dLateralM shouldBe 0.012345678
    }

    @Test
    fun `S027 RF-12 el punto replanteado guarda el codigo y los atributos de la linea por valor`() {
        val code =
            PointCode("BORD", "Bordillo", 0x1E88E5, CodeSymbol.CIRCLE, listOf(PointAttribute("Material", AttributeType.TEXT, "Granito")))
        val point = Point.measured("a1", project, "BORD_1_1_stkd", "", measurement(), 1_000, ref).copy(code = code)
        runBlocking { points.create(point) } shouldBe PointSaveResult.Saved

        val read = runBlocking { points.point("a1").first() }.shouldNotBeNull()

        read.code shouldBe code
    }

    @Test
    fun `S027 RF-16 borrar la linea no toca el punto replanteado ni lo que guarda de ella`() {
        runBlocking { points.create(Point.measured("a1", project, "BORD_1_1_stkd", "", measurement(), 1_000, ref)) }

        // La fuente no tiene clave foranea: no hay tabla de lineas que borrar con el punto.
        val read = runBlocking { points.point("a1").first() }.shouldNotBeNull()

        read.staked.shouldNotBeNull().source shouldBe StakeSource.ProjectLine("l1", "BORD_1")
    }

    @Test
    fun `S027 RF-12 el replanteo de una linea no cuenta como replanteo de un punto del proyecto`() {
        runBlocking { points.create(Point.measured("a1", project, "BORD_1_1_stkd", "", measurement(), 1_000, ref)) }

        runBlocking { points.summaries(project).first() }.single().stakeCount shouldBe 0
    }

    @Test
    fun `S027 RF-12 un replanteo de un dibujo o de un eje no se lee como de una linea`() {
        val cad = ref.copy(source = StakeSource.ProjectPoint)
        runBlocking { points.create(Point.measured("a1", project, "P1_stkd", "", measurement(), 1_000, cad)) }

        runBlocking { points.point("a1").first() }.shouldNotBeNull().staked.shouldNotBeNull().source shouldBe StakeSource.ProjectPoint
    }
}
