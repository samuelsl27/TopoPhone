package es.topophone.app.ui.stakeout

import es.topophone.core.geodesy.GeodeticPoint
import es.topophone.core.geodesy.LocalTangentPlane
import es.topophone.core.gnss.nmea.Epoch
import es.topophone.core.gnss.nmea.GgaFix
import es.topophone.core.gnss.nmea.GstAccuracy
import es.topophone.core.gnss.nmea.SolutionStatus
import es.topophone.core.model.cad.CadSourceCrs
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
import es.topophone.core.survey.LevelState
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
 * La guía contra una superficie: la cota de diseño de la superficie con desplazamiento en la punta, el nivel vertical que no se
 * reinicia con cada época, «Fuera de la superficie», la carga de teselas y el apagado (S042/RF-23 a RF-26).
 */
class StakeoutControllerSurfaceTest {

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

        override fun stakeoutApproach() = Unit

        override fun stakeoutArrived() = Unit

        override fun stakeoutLevelArrived() {
            levelEvents += "levelArrived"
        }

        override fun stakeoutLevelLeft() {
            levelEvents += "levelLeft"
        }

        override fun stakeoutLeft() = Unit
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

    private fun context(
        elevationSource: ElevationSource = ElevationSource.SURFACE,
        surface: SurfaceGuide? = guide(),
        designElevationM: Double? = null,
        line: LineGuide? = null,
    ) = StakeContext(
        StakeoutSettings(mode = StakeMode.BY_DISTANCE),
        target,
        true,
        null,
        poleHeight,
        project,
        calculator,
        designElevationM,
        line,
        surface,
        elevationSource,
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
    fun `S042 RF-24 la cota de diseno es la de la superficie con el desplazamiento y la diferencia sale contra ella`() {
        val up = 0.4
        controller.onEpoch(epoch(0.0, 0.0, up = up), context(surface = guide(offset = 0.3)))

        val ui = on()
        val tip = tipHeight(0.0, 0.0, up)
        ui.surface.shouldNotBeNull().height shouldBe SurfaceHeight.Value(100.0)
        ui.surface.shouldNotBeNull().offsetM shouldBe 0.3
        ui.surface.shouldNotBeNull().designM.shouldNotBeNull() shouldBe (100.3 plusOrMinus 1e-9)
        ui.guidance.dHeightM.shouldNotBeNull() shouldBe (tip - 100.3 plusOrMinus 1e-6)
        ui.level.shouldNotBeNull().dzM shouldBe (tip - 100.3 plusOrMinus 1e-6)
        ui.levelUnavailable.shouldBeNull()
    }

    @Test
    fun `S042 RF-24 la cota de la superficie se toma en la posicion de la punta y cambia al caminar`() {
        val (ex, _) = e0 to n0
        heights = { e, _ -> SurfaceHeight.Value(100.0 + 0.1 * (e - ex)) }

        controller.onEpoch(epoch(0.0, 0.0, index = 0), context())
        val atOrigin = on().surface.shouldNotBeNull().designM.shouldNotBeNull()
        controller.onEpoch(epoch(20.0, 0.0, index = 1), context())
        val twentyEast = on().surface.shouldNotBeNull().designM.shouldNotBeNull()

        atOrigin shouldBe (100.0 plusOrMinus 0.01)
        (twentyEast - atOrigin) shouldBe (2.0 plusOrMinus 0.05) // 20 m de plano local son unos 20 m de cuadrícula a 0,1 de pendiente
    }

    @Test
    fun `S042 RF-23 fuera de la superficie no hay nivel y la guia horizontal sigue encendida`() {
        heights = { _, _ -> SurfaceHeight.Outside }

        controller.onEpoch(epoch(3.0, 4.0), context())

        val ui = on()
        ui.surface.shouldNotBeNull().height shouldBe SurfaceHeight.Outside
        ui.surface.shouldNotBeNull().designM.shouldBeNull()
        ui.level.shouldBeNull()
        ui.levelUnavailable shouldBe LevelUnavailable.OUTSIDE_SURFACE
        ui.guidance.distanceM shouldBe (5.0 plusOrMinus 0.01)
        ui.guidance.dHeightM.shouldBeNull()
    }

    @Test
    fun `S042 RF-23 al volver a la superficie el nivel vuelve a empezar de cero`() {
        controller.onEpoch(epoch(0.0, 0.0, index = 0, up = 0.0), context())
        val before = on().level.shouldNotBeNull()
        heights = { _, _ -> SurfaceHeight.Outside }
        controller.onEpoch(epoch(0.0, 0.0, index = 1), context())
        on().level.shouldBeNull()

        heights = { _, _ -> SurfaceHeight.Value(100.0) }
        controller.onEpoch(epoch(0.0, 0.0, index = 2), context())

        on().level.shouldNotBeNull().dzM shouldBe (before.dzM plusOrMinus 1e-6)
    }

    @Test
    fun `S042 RF-26 el nivel no se reinicia con cada epoch aunque la cota de la superficie cambie`() {
        val flat = tipHeight(0.0, 0.0, 0.0)
        // La cota de la superficie baila unos milímetros alrededor de la de la punta: siempre en cota.
        var wobble = 0
        heights = { _, _ -> SurfaceHeight.Value(flat + if (wobble++ % 2 == 0) 0.002 else -0.002) }

        repeat(10) { controller.onEpoch(epoch(0.0, 0.0, index = it), context()) }

        alerts.levelEvents shouldBe listOf("levelArrived")
        on().level.shouldNotBeNull().state shouldBe LevelState.ON_GRADE
    }

    @Test
    fun `S042 RF-26 cambiar de desplazamiento si reinicia el nivel`() {
        val flat = tipHeight(0.0, 0.0, 0.0)
        heights = { _, _ -> SurfaceHeight.Value(flat) }
        repeat(3) { controller.onEpoch(epoch(0.0, 0.0, index = it), context()) }
        on().level.shouldNotBeNull().state shouldBe LevelState.ON_GRADE

        controller.onEpoch(epoch(0.0, 0.0, index = 3), context(surface = guide(offset = 0.5)))

        on().level.shouldNotBeNull().dzM shouldBe (-0.5 plusOrMinus 1e-6)
        on().level.shouldNotBeNull().state shouldBe LevelState.FILL
    }

    @Test
    fun `S042 RF-22 mientras la superficie carga se mantiene el ultimo nivel un instante y luego se dice que se calcula`() {
        controller.onEpoch(epoch(0.0, 0.0, index = 0, up = 0.2), context())
        val held = on().level.shouldNotBeNull()
        heights = { _, _ -> SurfaceHeight.Loading }

        clock = 500
        controller.onEpoch(epoch(0.0, 0.0, index = 1, up = 0.2), context())
        on().level shouldBe held
        on().levelUnavailable.shouldBeNull()
        on().surface.shouldNotBeNull().height shouldBe SurfaceHeight.Loading

        clock = 2_500
        controller.onEpoch(epoch(0.0, 0.0, index = 2, up = 0.2), context())
        on().level.shouldBeNull()
        on().levelUnavailable shouldBe LevelUnavailable.SURFACE_LOADING
    }

    @Test
    fun `S042 RF-25 con la cota del punto como origen la superficie no se usa`() {
        controller.onEpoch(epoch(0.0, 0.0), context(elevationSource = ElevationSource.POINT, designElevationM = 650.0))

        val ui = on()
        ui.surface.shouldBeNull()
        ui.level.shouldNotBeNull().dzM shouldBe (tipHeight(0.0, 0.0) - 650.0 plusOrMinus 1e-6)
    }

    @Test
    fun `S042 RF-24 con la superficie como origen pero sin superficie activa se usa la cota del punto`() {
        controller.onEpoch(epoch(0.0, 0.0), context(surface = null))

        val ui = on()
        ui.surface.shouldBeNull()
        ui.level.shouldNotBeNull().dzM shouldBe (tipHeight(0.0, 0.0) - 648.0 plusOrMinus 1e-6)
    }

    @Test
    fun `S042 RF-24 en una linea sin cota la cota de diseno sale de la superficie en la punta`() {
        val line = StakeLine.create(doubleArrayOf(e0 - 100.0, e0 + 100.0), doubleArrayOf(n0, n0), null)!!
        val guide = LineGuide(line, LineLocator(line), LineStakeoutMode.ALONG, 0.0, LineRange.whole(line))

        controller.onEpoch(epoch(20.0, 0.0, up = 0.1), context(line = guide))

        val ui = on()
        ui.surface.shouldNotBeNull().designM shouldBe (100.0 plusOrMinus 1e-9)
        ui.level.shouldNotBeNull().dzM shouldBe (tipHeight(20.0, 0.0, 0.1) - 100.0 plusOrMinus 1e-6)
    }

    @Test
    fun `S042 RF-26 sin FIX la guia se apaga como siempre y el nivel con ella`() {
        controller.onEpoch(epoch(0.0, 0.0, index = 0), context())
        on().level.shouldNotBeNull()

        controller.onEpoch(epoch(0.0, 0.0, index = 1, status = SolutionStatus.FLOAT), context())

        controller.state.value.shouldBeInstanceOf<GuidanceState.Off>().reason shouldBe GuidanceOff.NO_FIX
        alerts.events shouldBe listOf("solutionLost")
    }
}
