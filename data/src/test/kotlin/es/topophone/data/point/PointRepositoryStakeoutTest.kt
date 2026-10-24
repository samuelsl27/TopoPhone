package es.topophone.data.point

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.core.model.point.GeodeticPosition
import es.topophone.core.model.point.Measurement
import es.topophone.core.model.point.MeasurementMode
import es.topophone.core.model.point.Point
import es.topophone.core.model.point.PointCoordinates
import es.topophone.core.model.point.PointOrigin
import es.topophone.core.model.point.PointQuality
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

/** Un punto replanteado se guarda y se lee con su diseño y sus diferencias, sin redondear (S018/RF-27, RF-28, RF-32). */
@RunWith(AndroidJUnit4::class)
class PointRepositoryStakeoutTest {

    private val db = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext<Context>(),
        TopoPhoneDatabase::class.java,
    ).build()
    private val projects = RoomProjectRepository(db)
    private val points = RoomPointRepository(db, clock = { 50_000 })

    @After
    fun close() = db.close()

    private val project = runBlocking {
        projects.create(ProjectDefaults().toDraft().copy(name = "Calle Mayor 12")).shouldBeInstanceOf<SaveResult.Saved>().id
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

    private fun staked(name: String, ref: StakeoutRef?) = Point.measured("id-$name", project, name, "esquina", measurement(), 1_000, ref)

    private val ref = StakeoutRef(
        designId = "design-12",
        designName = "12",
        design = designCoordinates,
        dEastM = 0.034567891,
        dNorthM = 0.014123456,
        dHorizontalM = 0.037344,
        dHeightM = -0.005012345,
    )

    @Test
    fun `S018 RF-27 y RF-28 un punto replanteado se guarda y se lee con su diseno y sus diferencias sin redondear`() {
        runBlocking { points.create(staked("12_stkd", ref)) } shouldBe PointSaveResult.Saved

        val read = runBlocking { points.point("id-12_stkd").first() }.shouldNotBeNull()

        read.origin shouldBe PointOrigin.STAKED
        read.staked shouldBe ref
        read.measurement.shouldNotBeNull().coordinates shouldBe measuredCoordinates
        read.measurement.shouldNotBeNull().rawNmea shouldBe "\$GNGGA,1*00"
        read.description shouldBe "esquina"
    }

    @Test
    fun `S018 RF-28 el diseno sin altura ni cota se guarda y se lee tal cual`() {
        val geographic = ref.copy(
            design = PointCoordinates(GeodeticPosition(41.3874, 2.1686, null), null, null, null),
            dHeightM = null,
        )
        runBlocking { points.create(staked("1_stkd", geographic)) }

        val read = runBlocking { points.point("id-1_stkd").first() }.shouldNotBeNull()

        read.staked shouldBe geographic
        read.staked.shouldNotBeNull().design.easting.shouldBeNull()
        read.staked.shouldNotBeNull().dHeightM.shouldBeNull()
    }

    @Test
    fun `S018 RF-27 un punto medido normal no tiene referencia de replanteo`() {
        runBlocking { points.create(staked("1", null)) }

        val read = runBlocking { points.point("id-1").first() }.shouldNotBeNull()

        read.origin shouldBe PointOrigin.MEASURED
        read.staked.shouldBeNull()
        read.measurement.shouldNotBeNull()
    }

    @Test
    fun `S018 RF-28 borrar o editar el punto de diseno no cambia lo guardado en el replanteo`() {
        val design = Point.measured("design-12", project, "12", "", measurement(), 500)
        runBlocking {
            points.create(design)
            points.create(staked("12_stkd", ref))
            points.moveToTrash(listOf("design-12"))
        }

        val read = runBlocking { points.point("id-12_stkd").first() }.shouldNotBeNull()

        read.staked shouldBe ref
        read.staked.shouldNotBeNull().design shouldBe designCoordinates
    }

    @Test
    fun `S018 RF-27 el punto replanteado entra en la lista y en el resumen con su origen`() {
        runBlocking { points.create(staked("12_stkd", ref)) }

        val summary = runBlocking { points.summaries(project).first() }.single()

        summary.origin shouldBe PointOrigin.STAKED
        summary.name shouldBe "12_stkd"
    }

    @Test
    fun `S018 RF-27 un punto replanteado se puede borrar a la papelera y restaurar con su replanteo`() {
        runBlocking {
            points.create(staked("12_stkd", ref))
            points.moveToTrash(listOf("id-12_stkd"))
            points.restore("id-12_stkd")
        }

        val read = runBlocking { points.point("id-12_stkd").first() }.shouldNotBeNull()

        read.origin shouldBe PointOrigin.STAKED
        read.staked shouldBe ref
    }
}
