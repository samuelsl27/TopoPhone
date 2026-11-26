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
import es.topophone.core.model.point.PointCoordinates
import es.topophone.core.model.point.StakeMode
import es.topophone.core.model.point.StakeSource
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
import es.topophone.core.survey.ProjectPositionCalculator
import es.topophone.receiver.platform.Alerts
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.Test

/** La guía del replanteo de la superficie solo en cota: sin planta, sin zonas ni avisos de llegada y con el nivel de siempre (S062/RF-7 a RF-9, RF-13, RF-15). */
class SurfaceOnlyControllerTest {

    private class Recorder : Alerts {
        val events = mutableListOf<String>()
        val levelEvents = mutableListOf<String>()

        override fun connectionLost() = Unit

        override fun correctionsLost() = Unit

        override fun pointSaved() = Unit

        override fun solutionLost() {
            events += "solutionLost"
        }

        override fun tiltLost() = Unit

        override fun stakeoutApproach() {
            events += "approach"
        }

        override fun stakeoutArrived() {
            events += "arrived"
        }

        override fun stakeoutLevelArrived() {
            levelEvents += "levelArrived"
        }

        override fun stakeoutLevelLeft() {
            levelEvents += "levelLeft"
        }

        override fun stakeoutLeft() {
            events += "left"
        }
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
    private val alerts = Recorder()
    private var clock = 0L
    private val controller = StakeoutController(alerts, publishIntervalMs = 0, now = { clock })

    private val origin = GeodeticPoint(40.4168, -3.0, 699.423)
    private val poleHeight = 1.8
    private val antennaHeight = 1.8 + 0.134
    private val ground = GeodeticPosition(origin.latitudeDeg, origin.longitudeDeg, origin.ellipsoidalHeightM)
    private val originPosition = calculator.fromGround(ground, project.crs, project.heightSystem)
    private val e0 = originPosition.easting!!
    private val n0 = originPosition.northing!!

    private val target = StakeTarget("pt-1", "12", "", PointCoordinates(ground, e0, n0, 648.0))

    private val surfaceModel = Surface(
        "s1", "p1", "terreno", "obra.xml", 1L, CadSourceCrs.Project, LinearUnit.METRE,
        SurfaceExtent(e0 - 500, e0 + 500, n0 - 500, n0 + 500), 90.0, 120.0, 10, 8, 0,
    )

    private var heights: (Double, Double) -> SurfaceHeight = { _, _ -> SurfaceHeight.Value(100.0) }
    private val source = SurfaceHeightSource { e, n -> heights(e, n) }
    private fun guide(offset: Double = 0.0) = SurfaceGuide(surfaceModel, source, offset)

    private fun context(surface: SurfaceGuide? = guide(), admitted: AdmittedSolutions = AdmittedSolutions.FIX_ONLY) = StakeContext(
        StakeoutSettings(mode = StakeMode.BY_DISTANCE),
        StakeTarget("surface:s1", "terreno", "", PointCoordinates(ground, e0, n0, null), StakeSource.Surface("terreno")),
        admitted,
        null,
        poleHeight,
        project,
        calculator,
        surface = surface,
        elevationSource = ElevationSource.SURFACE,
        surfaceOnly = true,
    )

    private fun epoch(east: Double, north: Double, index: Int = 0, up: Double = 0.0, status: SolutionStatus = SolutionStatus.FIX): Epoch {
        val tip = LocalTangentPlane.offset(origin, east, north, up)
        val fix = GgaFix(
            "t$index",
            tip.latitudeDeg,
            tip.longitudeDeg,
            status,
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

    /** La cota de la punta a ([east], [north]), [up] por encima del origen, como la ve el controlador. */
    private fun tipHeight(east: Double, north: Double, up: Double = 0.0): Double {
        val tip = LocalTangentPlane.offset(origin, east, north, up)
        return calculator.fromGround(
            GeodeticPosition(tip.latitudeDeg, tip.longitudeDeg, tip.ellipsoidalHeightM),
            project.crs,
            project.heightSystem,
        ).height!!
    }

    private fun on(): GuidanceUi = controller.state.value.shouldBeInstanceOf<GuidanceState.On>().ui

    @Test
    fun `S062 RF-7 sin objetivo en planta la distancia es cero y la placa lo sabe`() {
        controller.onEpoch(epoch(12.0, -7.0), context())

        val ui = on()
        ui.surfaceOnly shouldBe true
        ui.guidance.distanceM shouldBe (0.0 plusOrMinus 1e-9)
    }

    @Test
    fun `S062 RF-7 al caminar no hay zonas ni avisos de llegada en planta`() {
        repeat(20) { controller.onEpoch(epoch(it * 3.0, 0.0, index = it), context()) }

        alerts.events.filter { it in setOf("approach", "arrived", "left") } shouldBe emptyList()
    }

    @Test
    fun `S062 RF-8 RF-9 la cota de diseno es la de la superficie con el desplazamiento y el nivel avisa al llegar`() {
        val up = 0.0
        val tip = tipHeight(5.0, 5.0, up)
        heights = { _, _ -> SurfaceHeight.Value(tip - 0.25) }

        controller.onEpoch(epoch(5.0, 5.0, index = 0, up = up), context(surface = guide(offset = 0.25)))

        val ui = on()
        ui.surface.shouldNotBeNull().designM.shouldNotBeNull() shouldBe (tip plusOrMinus 1e-6)
        ui.level.shouldNotBeNull().dzM shouldBe (0.0 plusOrMinus 1e-6)
        alerts.levelEvents shouldBe listOf("levelArrived")
    }

    @Test
    fun `S062 RF-9 desmonte con la punta por encima y terraplen por debajo`() {
        val tip = tipHeight(0.0, 0.0)
        heights = { _, _ -> SurfaceHeight.Value(tip - 0.5) }
        controller.onEpoch(epoch(0.0, 0.0, index = 0), context())
        (on().level.shouldNotBeNull().dzM > 0) shouldBe true // la punta está por encima: hay que excavar

        heights = { _, _ -> SurfaceHeight.Value(tip + 0.5) }
        controller.onEpoch(epoch(0.0, 0.0, index = 1), context())
        (on().level.shouldNotBeNull().dzM < 0) shouldBe true // por debajo: hay que rellenar
    }

    @Test
    fun `S062 RF-13 fuera de la superficie no hay cota ni nivel`() {
        heights = { _, _ -> SurfaceHeight.Outside }

        controller.onEpoch(epoch(0.0, 0.0), context())

        val ui = on()
        ui.level.shouldBeNull()
        ui.levelUnavailable shouldBe LevelUnavailable.OUTSIDE_SURFACE
        ui.surface.shouldNotBeNull().designM.shouldBeNull()
    }

    @Test
    fun `S062 RF-15 con una solucion no admitida la guia se apaga como en cualquier replanteo`() {
        controller.onEpoch(epoch(0.0, 0.0, status = SolutionStatus.FLOAT), context())

        controller.state.value shouldBe GuidanceState.Off(GuidanceOff.SOLUTION_NOT_ADMITTED)
    }
}
