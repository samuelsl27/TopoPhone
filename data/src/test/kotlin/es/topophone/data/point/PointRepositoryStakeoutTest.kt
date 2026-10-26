package es.topophone.data.point

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.core.model.cad.CadDrawing
import es.topophone.core.model.cad.CadEntityKind
import es.topophone.core.model.cad.CadSourceCrs
import es.topophone.core.model.point.ElevationSource
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
import es.topophone.core.model.project.LinearUnit
import es.topophone.core.model.project.ProjectDefaults
import es.topophone.data.cad.RoomCadRepository
import es.topophone.data.db.TopoPhoneDatabase
import es.topophone.data.project.RoomProjectRepository
import es.topophone.data.project.SaveResult
import es.topophone.data.surface.RoomSurfaceRepository
import es.topophone.data.surface.SurfaceDraft
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
        designElevationM = designCoordinates.height, // en S018 la cota usada era la altura del punto de diseño
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

    private val checked = ref.copy(
        designElevationM = 648.2914123456,
        toleranceHM = 0.025,
        toleranceVM = 0.03,
        withinTolerance = false,
    )

    @Test
    fun `S019 RF-13 y RF-14 la cota usada, las tolerancias y si cumple se guardan y se leen sin redondear`() {
        runBlocking { points.create(staked("12_stkd", checked)) }

        val read = runBlocking { points.point("id-12_stkd").first() }.shouldNotBeNull()

        read.staked shouldBe checked
        read.staked.shouldNotBeNull().designElevationM shouldBe 648.2914123456
        read.staked.shouldNotBeNull().withinTolerance shouldBe false
    }

    @Test
    fun `S019 RF-14 dentro de la tolerancia verdadero y falso se distinguen del sin evaluar`() {
        runBlocking {
            points.create(staked("1_stkd", checked.copy(withinTolerance = true)))
            points.create(staked("2_stkd", checked.copy(withinTolerance = false)))
            points.create(staked("3_stkd", ref))
        }

        fun within(name: String) = runBlocking {
            points.point("id-$name").first()
        }.shouldNotBeNull().staked.shouldNotBeNull().withinTolerance

        within("1_stkd") shouldBe true
        within("2_stkd") shouldBe false
        within("3_stkd").shouldBeNull()
        runBlocking { points.point("id-3_stkd").first() }.shouldNotBeNull().staked.shouldNotBeNull().notEvaluated shouldBe true
    }

    @Test
    fun `S019 RF-13 un replanteo sin cota se guarda con la cota usada y la diferencia de cota vacias`() {
        val noElevation = checked.copy(designElevationM = null, dHeightM = null)
        runBlocking { points.create(staked("1_stkd", noElevation)) }

        val read = runBlocking { points.point("id-1_stkd").first() }.shouldNotBeNull()

        read.staked shouldBe noElevation
        read.staked.shouldNotBeNull().hasElevation shouldBe false
        read.staked.shouldNotBeNull().designElevationM.shouldBeNull()
    }

    @Test
    fun `S019 RF-13 una cota escrita distinta de la del punto se guarda aparte del diseno por valor`() {
        val written = checked.copy(designElevationM = 646.9)
        runBlocking { points.create(staked("1_stkd", written)) }

        val read = runBlocking { points.point("id-1_stkd").first() }.shouldNotBeNull().staked.shouldNotBeNull()

        read.designElevationM shouldBe 646.9
        read.design.height shouldBe designCoordinates.height
    }

    @Test
    fun `S019 RF-14 la exportacion y la papelera conservan lo guardado del replanteo`() {
        runBlocking {
            points.create(staked("12_stkd", checked))
            points.moveToTrash(listOf("id-12_stkd"))
            points.restore("id-12_stkd")
        }

        runBlocking { points.pointsForExport(project) }.single().staked shouldBe checked
        runBlocking { points.point("id-12_stkd").first() }.shouldNotBeNull().staked shouldBe checked
    }

    @Test
    fun `S019 RF-19 los puntos replanteados de la papelera no salen en la exportacion del informe`() {
        runBlocking {
            points.create(staked("12_stkd", checked))
            points.create(staked("13_stkd", checked.copy(designId = "design-13")))
            points.moveToTrash(listOf("id-12_stkd"))
        }

        runBlocking { points.pointsForExport(project) }.map { it.name } shouldBe listOf("13_stkd")
    }

    @Test
    fun `S018 RF-28 el diseno sin altura ni cota se guarda y se lee tal cual`() {
        val geographic = ref.copy(
            design = PointCoordinates(GeodeticPosition(41.3874, 2.1686, null), null, null, null),
            dHeightM = null,
            designElevationM = null,
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

    private fun stakedOf(designId: String, name: String) = staked(name, ref.copy(designId = designId))

    private fun stakeCounts(): Map<String, Int> = runBlocking { points.summaries(project).first() }.associate { it.name to it.stakeCount }

    @Test
    fun `S018 RF-33 el resumen de un punto de diseno dice cuantos replanteos tiene`() {
        runBlocking {
            points.create(Point.measured("design-12", project, "12", "", measurement(), 500))
            points.create(Point.measured("design-13", project, "13", "", measurement(), 600))
            points.create(stakedOf("design-12", "12_stkd"))
            points.create(stakedOf("design-12", "12_stkd2"))
            points.create(stakedOf("design-13", "13_stkd"))
        }

        val counts = stakeCounts()

        counts["12"] shouldBe 2
        counts["13"] shouldBe 1
        counts["12_stkd"] shouldBe 0
    }

    @Test
    fun `S018 RF-33 un punto sin replanteos tiene cero y uno replanteado a la papelera ya no cuenta`() {
        runBlocking {
            points.create(Point.measured("design-12", project, "12", "", measurement(), 500))
            points.create(Point.measured("other", project, "7", "", measurement(), 600))
            points.create(stakedOf("design-12", "12_stkd"))
            points.create(stakedOf("design-12", "12_stkd2"))
        }
        stakeCounts()["7"] shouldBe 0
        stakeCounts()["12"] shouldBe 2

        runBlocking { points.moveToTrash(listOf("id-12_stkd2")) }

        stakeCounts()["12"] shouldBe 1
    }

    @Test
    fun `S018 RF-33 los replanteos de un punto de otro proyecto no cuentan`() {
        val other = runBlocking {
            projects.create(ProjectDefaults().toDraft().copy(name = "Otra obra")).shouldBeInstanceOf<SaveResult.Saved>().id
        }
        runBlocking {
            points.create(Point.measured("design-12", project, "12", "", measurement(), 500))
            points.create(
                Point.measured("id-x", other, "12_stkd", "", measurement(), 1_000, ref.copy(designId = "design-12")),
            )
        }

        stakeCounts()["12"] shouldBe 0
    }

    @Test
    fun `S018 RF-33 la exportacion lleva los datos del replanteo`() {
        runBlocking { points.create(staked("12_stkd", ref)) }

        val exported = runBlocking { points.pointsForExport(project) }.single()

        exported.origin shouldBe PointOrigin.STAKED
        exported.staked shouldBe ref
    }

    private val cadRef = ref.copy(
        designId = "cad:l1:7:3",
        designName = "Eje",
        source = StakeSource.CadElement("calle.dxf", "Eje", CadEntityKind.POLYLINE),
        mode = LineStakeoutMode.ALONG,
        chainageM = 45.123456789,
        lateralOffsetM = -1.5,
        dLateralM = 0.012345678,
    )

    @Test
    fun `S041 RF-28 un replanteo de un elemento de dibujo guarda y lee origen modo PK desplazamiento y error lateral`() {
        runBlocking { points.create(staked("Eje_1_stkd", cadRef)) } shouldBe PointSaveResult.Saved

        val read = runBlocking { points.point("id-Eje_1_stkd").first() }.shouldNotBeNull()

        read.staked shouldBe cadRef
        read.staked.shouldNotBeNull().fromDrawing shouldBe true
        read.staked.shouldNotBeNull().chainageM shouldBe 45.123456789
        read.staked.shouldNotBeNull().dLateralM shouldBe 0.012345678
        read.origin shouldBe PointOrigin.STAKED
        read.measurement.shouldNotBeNull().coordinates shouldBe measuredCoordinates
    }

    @Test
    fun `S041 RF-28 un replanteo de un punto del proyecto sigue sin origen de dibujo ni datos de linea`() {
        runBlocking { points.create(staked("12_stkd", ref)) }

        val read = runBlocking { points.point("id-12_stkd").first() }.shouldNotBeNull().staked.shouldNotBeNull()

        read.source shouldBe StakeSource.ProjectPoint
        read.mode.shouldBeNull()
        read.chainageM.shouldBeNull()
        read.lateralOffsetM.shouldBeNull()
        read.dLateralM.shouldBeNull()
    }

    @Test
    fun `S041 RF-28 un replanteo de un punto fijo de un dibujo no tiene PK ni desplazamiento si el modo no los da`() {
        val fixed = cadRef.copy(mode = LineStakeoutMode.BY_SEGMENTS, lateralOffsetM = null, dLateralM = null)
        runBlocking { points.create(staked("Eje_1_stkd", fixed)) }

        val read = runBlocking { points.point("id-Eje_1_stkd").first() }.shouldNotBeNull().staked.shouldNotBeNull()

        read shouldBe fixed
        read.lateralOffsetM.shouldBeNull()
    }

    @Test
    fun `S041 RF-27 y RF-32 el punto y su replanteo se guardan juntos o no se guarda nada`() {
        runBlocking { points.create(staked("Eje_1_stkd", cadRef)) }
        val duplicate = runBlocking { points.create(staked("Eje_1_stkd", cadRef.copy(designName = "otro"))) }

        duplicate shouldBe PointSaveResult.DuplicateName
        runBlocking { points.point("id-Eje_1_stkd").first() }.shouldNotBeNull().staked.shouldNotBeNull().designName shouldBe "Eje"
    }

    @Test
    fun `S041 RF-32 al borrar el dibujo el punto replanteado conserva el origen y lo guardado`() {
        val cad = RoomCadRepository(db)
        runBlocking {
            cad.begin(CadDrawing("d1", project, "calle.dxf", 1L, CadSourceCrs.Project, LinearUnit.METRE, true, true))
            points.create(staked("Eje_1_stkd", cadRef))

            cad.deleteDrawing("d1")
        }

        val read = runBlocking { points.point("id-Eje_1_stkd").first() }.shouldNotBeNull()

        read.staked shouldBe cadRef
        read.staked.shouldNotBeNull().source shouldBe StakeSource.CadElement("calle.dxf", "Eje", CadEntityKind.POLYLINE)
    }

    @Test
    fun `S041 RF-28 la exportacion de los puntos lee tambien las columnas del replanteo de un dibujo`() {
        runBlocking { points.create(staked("Eje_1_stkd", cadRef)) }

        val exported = runBlocking { db.points().forExport(project) }.single()

        exported.stakeCadDrawing shouldBe "calle.dxf"
        exported.stakeChainage shouldBe 45.123456789
    }

    private val surfaceRef = ref.copy(
        designElevationM = 601.5512345678,
        dHeightM = -0.0112345678,
        toleranceHM = 0.025,
        toleranceVM = 0.03,
        withinTolerance = true,
        elevationSource = ElevationSource.SURFACE,
        surfaceName = "terreno",
        surfaceImportedAtMs = 1_792_080_000_000L,
        surfaceElevationM = 601.2512345678,
        verticalOffsetM = 0.3,
    )

    @Test
    fun `S042 RF-28 el origen de la cota, la superficie, su cota y el desplazamiento se guardan y se leen sin redondear`() {
        runBlocking { points.create(staked("12_stkd", surfaceRef)) }

        val read = runBlocking { points.point("id-12_stkd").first() }.shouldNotBeNull().staked.shouldNotBeNull()

        read shouldBe surfaceRef
        read.usedSurface shouldBe true
        read.surfaceElevationM shouldBe 601.2512345678
        read.verticalOffsetM shouldBe 0.3
    }

    @Test
    fun `S042 RF-29 fuera de la superficie se guarda el nombre sin ninguna cota`() {
        val outside = ref.copy(
            designElevationM = null,
            dHeightM = null,
            surfaceName = "terreno",
            surfaceImportedAtMs = 1_792_080_000_000L,
            verticalOffsetM = 0.3,
        )
        runBlocking { points.create(staked("12_stkd", outside)) }

        val read = runBlocking { points.point("id-12_stkd").first() }.shouldNotBeNull().staked.shouldNotBeNull()

        read.outsideSurface shouldBe true
        read.elevationSource.shouldBeNull()
        read.surfaceElevationM.shouldBeNull()
        read.dHeightM.shouldBeNull()
        read.designElevationM.shouldBeNull()
    }

    @Test
    fun `S042 RF-32 al borrar la superficie el punto replanteado conserva lo guardado`() {
        val surfaces = RoomSurfaceRepository(db)
        runBlocking {
            surfaces.begin(SurfaceDraft("s1", project, "terreno", "obra.xml", 1L, CadSourceCrs.Project, LinearUnit.METRE))
            points.create(staked("12_stkd", surfaceRef))

            surfaces.delete("s1")
        }

        val read = runBlocking { points.point("id-12_stkd").first() }.shouldNotBeNull()

        read.staked shouldBe surfaceRef
        read.staked.shouldNotBeNull().surfaceName shouldBe "terreno"
    }

    @Test
    fun `S042 RF-28 un replanteo sin superficie no guarda nada de ella y los de antes se leen igual`() {
        runBlocking { points.create(staked("12_stkd", ref)) }

        val read = runBlocking { points.point("id-12_stkd").first() }.shouldNotBeNull().staked.shouldNotBeNull()

        read.elevationSource.shouldBeNull()
        read.surfaceName.shouldBeNull()
        read.verticalOffsetM.shouldBeNull()
        read.usedSurface shouldBe false
        read.outsideSurface shouldBe false
    }
}
