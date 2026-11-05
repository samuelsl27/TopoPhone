package es.topophone.app.ui.stakeout

import es.topophone.core.geodesy.GeodeticPoint
import es.topophone.core.geodesy.LocalTangentPlane
import es.topophone.core.gnss.nmea.Epoch
import es.topophone.core.gnss.nmea.GgaFix
import es.topophone.core.gnss.nmea.GstAccuracy
import es.topophone.core.gnss.nmea.SolutionStatus
import es.topophone.core.model.cad.CadSourceCrs
import es.topophone.core.model.point.AdmittedSolutions
import es.topophone.core.model.point.ElevationSource
import es.topophone.core.model.point.GeodeticPosition
import es.topophone.core.model.point.LineStakeoutMode
import es.topophone.core.model.point.PointCoordinates
import es.topophone.core.model.point.StakeMode
import es.topophone.core.model.point.StakeoutSettings
import es.topophone.core.model.project.AngularUnit
import es.topophone.core.model.project.CorrectionsDatum
import es.topophone.core.model.project.LinearUnit
import es.topophone.core.model.project.Project
import es.topophone.core.model.project.ProjectCrs
import es.topophone.core.model.surface.Surface
import es.topophone.core.model.surface.SurfaceExtent
import es.topophone.core.model.surface.SurfaceGuide
import es.topophone.core.model.surface.SurfaceHeight
import es.topophone.core.model.surface.SurfaceHeightSource
import es.topophone.core.survey.LineLocator
import es.topophone.core.survey.LineRange
import es.topophone.core.survey.ProjectPositionCalculator
import es.topophone.core.survey.StakeLine
import es.topophone.receiver.platform.Alerts
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.Test

/**
 * La guía de una línea con alturas en solo algunos vértices: la cota de diseño es la del objetivo, y donde no hay se usa la superficie
 * activa si se pide (S027/RF-8, RF-9, RF-10).
 */
class StakeoutControllerPartialHeightTest {

    private object SilentAlerts : Alerts {
        override fun connectionLost() = Unit

        override fun correctionsLost() = Unit

        override fun pointSaved() = Unit

        override fun solutionLost() = Unit

        override fun tiltLost() = Unit
    }

    private val project = Project(
        id = "p1",
        name = "Calle Mayor 12",
        author = "",
        description = "",
        crs = ProjectCrs.ETRS89_UTM30,
        linearUnit = LinearUnit.METRE,
        angularUnit = AngularUnit.DECIMAL_DEGREES,
        correctionsDatum = CorrectionsDatum.ETRS89,
        folderId = null,
        createdAtMs = 1_000,
        modifiedAtMs = 1_000,
        openedAtMs = null,
        deletedAtMs = null,
    )
    private val calculator = ProjectPositionCalculator()
    private val controller = StakeoutController(SilentAlerts, publishIntervalMs = 0, now = { 0L })
    private val origin = GeodeticPoint(40.4168, -3.0, 699.423)
    private val poleHeight = 1.8
    private val antennaHeight = 1.8 + 0.134
    private val ground = GeodeticPosition(origin.latitudeDeg, origin.longitudeDeg, origin.ellipsoidalHeightM)
    private val originPosition = calculator.fromGround(ground, project.crs, project.heightSystem)
    private val e0 = originPosition.easting!!
    private val n0 = originPosition.northing!!

    /** Una línea hacia el este: sin altura en el vértice del oeste y con 100 m en el del medio y 110 m en el del este. */
    private val line = StakeLine.create(
        doubleArrayOf(e0 - 100.0, e0, e0 + 100.0),
        doubleArrayOf(n0, n0, n0),
        doubleArrayOf(Double.NaN, 100.0, 110.0),
    )!!

    private val target = StakeTarget("line-1", "L_1", "", PointCoordinates(ground, e0 - 100.0, n0, line.height(0)))

    private val surfaceModel = Surface(
        "s1", "p1", "terreno", "obra.xml", 1L, CadSourceCrs.Project, LinearUnit.METRE,
        SurfaceExtent(e0 - 500, e0 + 500, n0 - 500, n0 + 500), 90.0, 120.0, 10, 8, 0,
    )
    private val surface = SurfaceGuide(surfaceModel, SurfaceHeightSource { _, _ -> SurfaceHeight.Value(95.0) }, 0.0)

    private fun context(whereNoHeight: Boolean, designElevationM: Double? = null, source: ElevationSource = ElevationSource.POINT) =
        StakeContext(
            StakeoutSettings(mode = StakeMode.BY_DISTANCE),
            target,
            AdmittedSolutions.FIX_ONLY,
            null,
            poleHeight,
            project,
            calculator,
            designElevationM,
            LineGuide(line, LineLocator(line), LineStakeoutMode.TO_LINE, 0.0, LineRange.whole(line)),
            surface,
            source,
            surfaceWhereNoHeight = whereNoHeight,
        )

    private fun epoch(east: Double, north: Double, index: Int = 0): Epoch {
        val tip = LocalTangentPlane.offset(origin, east, north, 0.0)
        val fix = GgaFix(
            "t$index",
            tip.latitudeDeg,
            tip.longitudeDeg,
            SolutionStatus.FIX,
            24,
            0.6,
            tip.ellipsoidalHeightM + antennaHeight - 51.234,
            51.234,
            1.0,
            "",
        )
        return Epoch(
            36_930_000 + index * 200, null, fix,
            GstAccuracy(
                "t",
                0.008,
                0.006,
                0.015,
            ),
            null, null, null, null, null, emptyMap(), emptyMap(), 0,
        )
    }

    private fun on(): GuidanceUi = controller.state.value.shouldBeInstanceOf<GuidanceState.On>().ui

    @Test
    fun `S027 RF-8 donde hay cota de diseno se replantea contra ella aunque haya superficie`() {
        controller.onEpoch(epoch(east = 50.0, north = 0.0), context(whereNoHeight = true))

        val ui = on()
        ui.line.shouldNotBeNull().heightM.shouldNotBeNull() shouldBe (105.0 plusOrMinus 0.05)
        ui.surface.shouldBeNull()
    }

    @Test
    fun `S027 RF-10 donde no hay cota de diseno y hay superficie se replantea contra la superficie`() {
        controller.onEpoch(epoch(east = -50.0, north = 0.0), context(whereNoHeight = true))

        val ui = on()
        ui.line.shouldNotBeNull().heightM.shouldBeNull()
        ui.surface.shouldNotBeNull()
        ui.level.shouldNotBeNull()
    }

    @Test
    fun `S027 RF-9 sin superficie pedida donde no hay cota de diseno no hay nivel vertical y se dice por que`() {
        controller.onEpoch(epoch(east = -50.0, north = 0.0), context(whereNoHeight = false))

        val ui = on()
        ui.line.shouldNotBeNull().heightM.shouldBeNull()
        ui.surface.shouldBeNull()
        ui.level.shouldBeNull()
        ui.levelUnavailable shouldBe LevelUnavailable.NO_DESIGN_ELEVATION
    }

    @Test
    fun `S027 RF-9 la cota escrita a mano manda tambien donde no hay cota de diseno`() {
        controller.onEpoch(epoch(east = -50.0, north = 0.0), context(whereNoHeight = true, designElevationM = 101.0))

        val ui = on()
        ui.surface.shouldBeNull()
        ui.level.shouldNotBeNull()
    }

    @Test
    fun `S027 RF-9 el primer vertice sin altura no presta la suya al tramo, que no tiene cota`() {
        // El objetivo de una linea es el de su PK; el vertice de partida (sin altura) no le da una cota supuesta.
        controller.onEpoch(epoch(east = -80.0, north = 0.0), context(whereNoHeight = false))

        on().line.shouldNotBeNull().heightM.shouldBeNull()
        on().levelUnavailable shouldBe LevelUnavailable.NO_DESIGN_ELEVATION
    }
}
