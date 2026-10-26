package es.topophone.app.ui.stakeout

import es.topophone.core.geodesy.GeodeticPoint
import es.topophone.core.geodesy.LocalTangentPlane
import es.topophone.core.gnss.nmea.Epoch
import es.topophone.core.gnss.nmea.GgaFix
import es.topophone.core.gnss.nmea.GstAccuracy
import es.topophone.core.gnss.nmea.SolutionStatus
import es.topophone.core.model.point.GeodeticPosition
import es.topophone.core.model.point.HeadingChoice
import es.topophone.core.model.point.LineStakeoutMode
import es.topophone.core.model.point.PointCoordinates
import es.topophone.core.model.point.StakeMode
import es.topophone.core.model.point.StakeoutSettings
import es.topophone.core.model.project.AngularUnit
import es.topophone.core.model.project.CorrectionsDatum
import es.topophone.core.model.project.LinearUnit
import es.topophone.core.model.project.Project
import es.topophone.core.model.project.ProjectCrs
import es.topophone.core.survey.HeadingResult
import es.topophone.core.survey.LineLocator
import es.topophone.core.survey.LineRange
import es.topophone.core.survey.ProjectPositionCalculator
import es.topophone.core.survey.RangePosition
import es.topophone.core.survey.StakeLine
import es.topophone.core.survey.StakeZone
import es.topophone.receiver.platform.Alerts
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.Test

/**
 * La guía de una línea: el objetivo que se mueve con la punta, la diana, las zonas con el error lateral, los avisos, la cota
 * interpolada y el apagado (S041/RF-6, RF-10, RF-12, RF-13, RF-25, RF-26).
 */
class StakeoutControllerLineTest {

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
    private val controller = StakeoutController(alerts, publishIntervalMs = 0, now = { 0L })

    // En el meridiano central del huso 30 el Norte de cuadrícula es el geográfico: un desplazamiento local vale (× gridScale) en cuadrícula.
    private val origin = GeodeticPoint(40.4168, -3.0, 699.423)
    private val poleHeight = 1.8
    private val antennaHeight = 1.8 + 0.134
    private val ground = GeodeticPosition(origin.latitudeDeg, origin.longitudeDeg, origin.ellipsoidalHeightM)
    private val originPosition = calculator.fromGround(ground, project.crs, project.heightSystem)
    private val e0 = originPosition.easting!!

    /** Lo que mide en cuadrícula un metro de plano local al este del origen (escala del SC y de la altura): se mide, no se supone. */
    private val gridScale: Double = run {
        val tip = LocalTangentPlane.offset(origin, 1_000.0, 0.0, 0.0)
        val tipGround = GeodeticPosition(tip.latitudeDeg, tip.longitudeDeg, tip.ellipsoidalHeightM)
        val grid = calculator.fromGround(tipGround, project.crs, project.heightSystem)
        (grid.easting!! - e0) / 1_000.0
    }
    private val n0 = originPosition.northing!!

    /** El Este y el Norte de cuadrícula de la punta a ([east], [north]) metros del origen en el plano local. */
    private fun grid(east: Double, north: Double): Pair<Double, Double> {
        val tip = LocalTangentPlane.offset(origin, east, north, 0.0)
        val position = calculator.fromGround(
            GeodeticPosition(tip.latitudeDeg, tip.longitudeDeg, tip.ellipsoidalHeightM),
            project.crs,
            project.heightSystem,
        )
        return position.easting!! to position.northing!!
    }

    /** Una línea hacia el este por el origen, de 100 m al oeste a 100 m al este, con o sin cota de 100 a 110 m. */
    private fun eastLine(z: Boolean = false) = StakeLine.create(
        doubleArrayOf(e0 - 100.0, e0 + 100.0),
        doubleArrayOf(n0, n0),
        if (z) doubleArrayOf(100.0, 110.0) else null,
    )!!

    private val target = StakeTarget("line-1", "Eje", "", PointCoordinates(ground, e0 - 100.0, n0, null))

    private fun guide(
        line: StakeLine = eastLine(),
        mode: LineStakeoutMode = LineStakeoutMode.ALONG,
        offset: Double = 0.0,
        range: LineRange = LineRange.whole(line),
    ) = LineGuide(line, LineLocator(line), mode, offset, range)

    private fun context(
        guide: LineGuide? = guide(),
        settings: StakeoutSettings = StakeoutSettings(mode = StakeMode.BY_DISTANCE),
        fixOnly: Boolean = true,
        designElevationM: Double? = null,
    ) = StakeContext(settings, target, fixOnly, null, poleHeight, project, calculator, designElevationM, guide)

    private fun epoch(east: Double, north: Double, index: Int = 0, status: SolutionStatus = SolutionStatus.FIX, up: Double = 0.0): Epoch {
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
            36_930_000 + index * 200,
            null,
            fix,
            GstAccuracy("t", 0.008, 0.006, 0.015),
            null,
            null,
            null,
            null,
            null,
            emptyMap(),
            emptyMap(),
            0,
        )
    }

    private fun on(): GuidanceUi = controller.state.value.shouldBeInstanceOf<GuidanceState.On>().ui

    private fun off(): GuidanceOff = controller.state.value.shouldBeInstanceOf<GuidanceState.Off>().reason

    @Test
    fun `S041 RF-10 el objetivo de la punta es su punto mas cercano de la linea y da el PK el lado y lo que falta`() {
        controller.onEpoch(epoch(east = 30.0, north = -0.3), context())

        val line = on().line.shouldNotBeNull()
        val pk = grid(30.0, -0.3).first - (e0 - 100.0) // desde el vértice del oeste
        line.chainageM shouldBe (pk plusOrMinus 0.001)
        line.remainingM shouldBe (200.0 - pk plusOrMinus 0.001)
        line.location.lateralM shouldBe (0.3 * gridScale plusOrMinus 0.002) // al sur de una línea hacia el este: a la derecha
        line.lateralErrorM.shouldNotBeNull() shouldBe (0.3 * gridScale plusOrMinus 0.002)
        line.position shouldBe RangePosition.WITHIN
    }

    @Test
    fun `S041 RF-10 la guia apunta al punto mas cercano y el objetivo se mueve al caminar sin que el controlador se reinicie`() {
        controller.onEpoch(epoch(east = 10.0, north = -2.0, index = 0), context())
        val first = on()
        controller.onEpoch(epoch(east = 40.0, north = -2.0, index = 1), context())
        val second = on()

        first.guidance.distanceM shouldBe (2.0 * gridScale plusOrMinus 0.01)
        first.guidance.dNorthM shouldBe (2.0 * gridScale plusOrMinus 0.01) // hay que ir al norte
        first.guidance.dEastM shouldBe (0.0 plusOrMinus 0.01)
        second.guidance.distanceM shouldBe (2.0 * gridScale plusOrMinus 0.01)
        (second.objective.shouldNotBeNull().easting - first.objective.shouldNotBeNull().easting) shouldBe
            (30.0 * gridScale plusOrMinus 0.01)
    }

    @Test
    fun `S041 RF-12 la diana lleva la punta respecto al objetivo y no respecto al primer vertice`() {
        controller.onEpoch(epoch(east = 55.0, north = -0.1), context())

        val ui = on()
        ui.tipEastM shouldBe (0.0 plusOrMinus 0.002)
        ui.tipNorthM shouldBe (-0.1 * gridScale plusOrMinus 0.002)
        ui.zone shouldBe StakeZone.AID
    }

    @Test
    fun `S041 RF-13 con el error lateral dentro de la tolerancia la diana esta dentro y avisa al entrar y al salir`() {
        listOf(0.9, 0.6, 0.38, 0.30, 0.1, 0.03, 0.02, 0.015, 0.02, 0.04, 0.05, 0.3).forEachIndexed { i, south ->
            controller.onEpoch(epoch(east = 20.0 + i, north = -south, index = i), context())
        }

        alerts.events shouldBe listOf("approach", "arrived", "left")
        on().zone shouldBe StakeZone.AID
        on().line.shouldNotBeNull().lateralErrorM.shouldNotBeNull() shouldBe (0.3 * gridScale plusOrMinus 0.002)
    }

    @Test
    fun `S041 RF-13 con el error lateral dentro de la tolerancia la zona es la de dentro`() {
        controller.onEpoch(epoch(east = 20.0, north = -0.01), context())

        on().zone shouldBe StakeZone.INSIDE
    }

    @Test
    fun `S041 RF-11 con desplazamiento la guia lleva a la paralela y el error lateral es la resta`() {
        // Un desplazamiento de 3 m a la derecha de una línea hacia el este es 3 m al sur.
        controller.onEpoch(epoch(east = 20.0, north = -1.0), context(guide(offset = 3.0)))

        val ui = on()
        ui.guidance.distanceM shouldBe (2.0 * gridScale plusOrMinus 0.01)
        ui.guidance.dNorthM shouldBe (-2.0 * gridScale plusOrMinus 0.01) // la paralela está más al sur
        ui.line.shouldNotBeNull().lateralErrorM.shouldNotBeNull() shouldBe (-2.0 * gridScale plusOrMinus 0.01)
    }

    @Test
    fun `S041 RF-11 estando sobre la paralela con desplazamiento a la izquierda llega y avisa`() {
        controller.onEpoch(epoch(east = 20.0, north = 2.0, index = 0), context(guide(offset = -2.0)))
        controller.onEpoch(epoch(east = 21.0, north = 2.0, index = 1), context(guide(offset = -2.0)))

        on().zone shouldBe StakeZone.INSIDE
        alerts.events.contains("arrived") shouldBe true
    }

    @Test
    fun `S041 RF-14 pasado el final de la linea el objetivo es el extremo y no hay error lateral`() {
        controller.onEpoch(epoch(east = 130.0, north = -3.0), context())

        val line = on().line.shouldNotBeNull()
        line.position shouldBe RangePosition.AFTER
        line.lateralErrorM.shouldBeNull()
        line.remainingM shouldBe 0.0
        val tip = grid(130.0, -3.0)
        on().guidance.distanceM shouldBe (Math.hypot(tip.first - (e0 + 100.0), tip.second - n0) plusOrMinus 0.001)
    }

    @Test
    fun `S041 RF-17 en el tramo A-B fuera del tramo el objetivo es el extremo del tramo`() {
        val line = eastLine()
        val range = LineRange(150.0, 170.0)

        controller.onEpoch(epoch(east = -40.0, north = -1.0), context(guide(line, LineStakeoutMode.SEGMENT_AB, range = range)))

        val objective = on().line.shouldNotBeNull()
        objective.position shouldBe RangePosition.BEFORE
        objective.chainageM shouldBe 150.0
        objective.alongM shouldBe 0.0
    }

    @Test
    fun `S041 RF-25 la llegada horizontal no depende de la cota con o sin cota`() {
        val flat = StakeoutController(alerts, publishIntervalMs = 0, now = { 0L })
        flat.onEpoch(epoch(east = 20.0, north = -0.01, up = 3.0), context(guide(eastLine(z = false))))
        controller.onEpoch(epoch(east = 20.0, north = -0.01, up = 3.0), context(guide(eastLine(z = true))))

        (flat.state.value as GuidanceState.On).ui.zone shouldBe StakeZone.INSIDE
        on().zone shouldBe StakeZone.INSIDE
        on().level.shouldNotBeNull() // la cota no impide la llegada: solo da nivel
    }

    @Test
    fun `S041 RF-23 la cota de diseno es la interpolada en el PK y la diferencia de cota sale contra ella`() {
        controller.onEpoch(epoch(east = -50.0, north = 0.0, index = 0, up = 0.0), context(guide(eastLine(z = true))))
        val first = on()
        controller.onEpoch(epoch(east = 50.0, north = 0.0, index = 1, up = 0.0), context(guide(eastLine(z = true))))
        val second = on()

        first.line.shouldNotBeNull().heightM.shouldNotBeNull() shouldBe (102.5 plusOrMinus 0.02)
        second.line.shouldNotBeNull().heightM.shouldNotBeNull() shouldBe (107.5 plusOrMinus 0.02)
        // la punta está a la misma altura: la diferencia de cota baja lo que sube la línea
        (first.guidance.dHeightM.shouldNotBeNull() - second.guidance.dHeightM.shouldNotBeNull()) shouldBe (5.0 plusOrMinus 0.05)
        second.level.shouldNotBeNull().dzM shouldBe second.guidance.dHeightM.shouldNotBeNull()
    }

    @Test
    fun `S041 RF-23 al avanzar por una linea con cota el nivel no se reinicia cada epoca`() {
        // Se mide cuánto le falta a la punta para estar en cota en el centro y se sube la línea esa diferencia.
        val probe = StakeoutController(alerts, publishIntervalMs = 0, now = { 0L })
        probe.onEpoch(epoch(east = 0.0, north = 0.0), context(guide(eastLine(z = true))))
        val shift = (probe.state.value as GuidanceState.On).ui.guidance.dHeightM.shouldNotBeNull()
        val line = StakeLine.create(
            doubleArrayOf(e0 - 100.0, e0 + 100.0),
            doubleArrayOf(n0, n0),
            doubleArrayOf(100.0 + shift, 110.0 + shift),
        )!!
        val g = guide(line)

        // La punta sube con la pendiente de la línea (0,05 m por metro de cuadrícula): se queda en cota y el aviso suena una vez.
        for (i in 0..10) controller.onEpoch(epoch(east = i * 1.0, north = 0.0, index = i, up = i * 0.05 * gridScale), context(g))

        on().level.shouldNotBeNull().dzM shouldBe (0.0 plusOrMinus 0.01)
        alerts.levelEvents.count { it == "levelArrived" } shouldBe 1
    }

    @Test
    fun `S041 RF-24 una cota escrita sustituye a la interpolada de la linea`() {
        controller.onEpoch(epoch(east = 0.0, north = 0.0, up = 0.0), context(guide(eastLine(z = true)), designElevationM = 650.0))

        on().line.shouldNotBeNull().heightM.shouldNotBeNull() shouldBe (105.0 plusOrMinus 0.02) // la de la línea sigue ahí
        on().level.shouldNotBeNull().dzM shouldBe (on().guidance.dHeightM.shouldNotBeNull() plusOrMinus 1e-9)
    }

    @Test
    fun `S041 RF-24 en una linea sin cota una cota escrita a mano da nivel desmonte y terraplen`() {
        controller.onEpoch(epoch(east = 20.0, north = 0.0, up = 0.0), context(guide(eastLine(z = false))))
        val tipHeight = 650.0 // la cota de diseño que se escribe; la de la punta es la que sale de la época
        controller.onEpoch(
            epoch(east = 20.0, north = 0.0, up = 0.0, index = 1),
            context(guide(eastLine(z = false)), designElevationM = tipHeight),
        )

        val level = on().level.shouldNotBeNull()
        on().levelUnavailable.shouldBeNull()
        level.dzM shouldBe (on().guidance.dHeightM.shouldNotBeNull() plusOrMinus 1e-9)
        on().line.shouldNotBeNull().heightM.shouldBeNull()
    }

    @Test
    fun `S041 RF-24 cambiar la cota escrita en una linea reinicia el nivel y vuelve a avisar de la entrada en cota`() {
        val g = guide(eastLine(z = false))
        val probe = StakeoutController(alerts, publishIntervalMs = 0, now = { 0L })
        probe.onEpoch(epoch(east = 20.0, north = 0.0), context(g, designElevationM = 0.0))
        val tipHeight = (probe.state.value as GuidanceState.On).ui.guidance.dHeightM.shouldNotBeNull() // punta menos 0

        controller.onEpoch(epoch(east = 20.0, north = 0.0, index = 0), context(g, designElevationM = tipHeight))
        controller.onEpoch(epoch(east = 21.0, north = 0.0, index = 1), context(g, designElevationM = tipHeight + 5.0))
        controller.onEpoch(epoch(east = 22.0, north = 0.0, index = 2), context(g, designElevationM = tipHeight))

        // al cambiar la cota el nivel empieza de cero: no avisa de la salida y vuelve a avisar de la entrada
        alerts.levelEvents shouldBe listOf("levelArrived", "levelArrived")
    }

    @Test
    fun `S041 RF-24 una cota escrita gana a la de una linea con cota y la interpolada sigue disponible en el objetivo`() {
        controller.onEpoch(epoch(east = 0.0, north = 0.0, up = 0.0), context(guide(eastLine(z = true)), designElevationM = 650.0))

        on().line.shouldNotBeNull().heightM.shouldNotBeNull() shouldBe (105.0 plusOrMinus 0.02)
        on().guidance.dHeightM.shouldNotBeNull() shouldBe (on().level.shouldNotBeNull().dzM plusOrMinus 1e-9)
    }

    @Test
    fun `S041 RF-24 una linea sin cota no tiene nivel y dice que falta la cota de diseno`() {
        controller.onEpoch(epoch(east = 20.0, north = 0.0), context(guide(eastLine(z = false))))

        on().level.shouldBeNull()
        on().levelUnavailable shouldBe LevelUnavailable.NO_DESIGN_ELEVATION
    }

    @Test
    fun `S041 RF-26 sin FIX la guia de la linea se apaga con aviso una vez y vuelve al recuperarlo`() {
        controller.onEpoch(epoch(east = 20.0, north = -1.0, index = 0), context())
        controller.onEpoch(epoch(east = 20.0, north = -1.0, index = 1, status = SolutionStatus.FLOAT), context())
        controller.onEpoch(epoch(east = 20.0, north = -1.0, index = 2, status = SolutionStatus.FLOAT), context())

        off() shouldBe GuidanceOff.NO_FIX
        alerts.events.count { it == "solutionLost" } shouldBe 1

        controller.onEpoch(epoch(east = 20.0, north = -1.0, index = 3), context())

        on().line.shouldNotBeNull()
    }

    @Test
    fun `S041 RF-26 un corte apaga la guia de la linea`() {
        controller.onEpoch(epoch(east = 20.0, north = -1.0), context())

        controller.onSilence(context())

        off() shouldBe GuidanceOff.NO_DATA
    }

    @Test
    fun `S041 RF-9 el rumbo de marcha al caminar por la linea sale del avance de la punta y no del movimiento del objetivo`() {
        val settings = StakeoutSettings(StakeMode.BY_DIRECTION, HeadingChoice.WALKING)
        for (i in 0..15) controller.onEpoch(epoch(east = -20.0 + i * 0.2, north = -1.5, index = i), context(settings = settings))

        val heading = on().heading.shouldBeInstanceOf<HeadingResult.Available>().rad
        heading shouldBe (Math.PI / 2 plusOrMinus 0.02) // hacia el este
    }

    @Test
    fun `S041 RF-6 sin objetivo que se mueva la guia usa el punto fijo de siempre`() {
        controller.onEpoch(epoch(east = -3.0, north = -4.0), context(guide = null))

        on().line.shouldBeNull()
        on().objective.shouldBeNull()
        val tip = grid(-3.0, -4.0)
        // el objetivo es el primer vértice
        on().guidance.distanceM shouldBe (Math.hypot(tip.first - (e0 - 100.0), tip.second - n0) plusOrMinus 0.001)
    }

    @Test
    fun `S041 RF-26 reset olvida el tramo anterior y la guia vuelve a empezar`() {
        controller.onEpoch(epoch(east = 20.0, north = -1.0), context())
        controller.reset()

        off() shouldBe GuidanceOff.NO_POSITION
        controller.onEpoch(epoch(east = -60.0, north = 1.0), context())
        on().line.shouldNotBeNull().chainageM shouldBe (grid(-60.0, 1.0).first - (e0 - 100.0) plusOrMinus 0.001)
    }
}
