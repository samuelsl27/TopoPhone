package es.topophone.app.ui.stakeout

import es.topophone.app.log.RecordingLog
import es.topophone.core.geodesy.GeodeticPoint
import es.topophone.core.geodesy.LocalTangentPlane
import es.topophone.core.gnss.nmea.Epoch
import es.topophone.core.gnss.nmea.GgaFix
import es.topophone.core.gnss.nmea.GstAccuracy
import es.topophone.core.gnss.nmea.SolutionStatus
import es.topophone.core.gnss.nmea.TiltAccuracyCoefficients
import es.topophone.core.gnss.nmea.TiltData
import es.topophone.core.gnss.nmea.TiltNotification
import es.topophone.core.gnss.nmea.TiltState
import es.topophone.core.model.log.LogCategory
import es.topophone.core.model.log.LogLevel
import es.topophone.core.model.point.GeodeticPosition
import es.topophone.core.model.point.HeadingChoice
import es.topophone.core.model.point.PointCoordinates
import es.topophone.core.model.point.StakeMode
import es.topophone.core.model.point.StakeoutSettings
import es.topophone.core.model.point.TiltMode
import es.topophone.core.model.project.AngularUnit
import es.topophone.core.model.project.CorrectionsDatum
import es.topophone.core.model.project.LinearUnit
import es.topophone.core.model.project.Project
import es.topophone.core.model.project.ProjectCrs
import es.topophone.core.survey.HeadingResult
import es.topophone.core.survey.HeadingUnavailable
import es.topophone.core.survey.ProjectPositionCalculator
import es.topophone.core.survey.StakeZone
import es.topophone.receiver.platform.Alerts
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.Test

/**
 * La guía del replanteo época a época: la geometría, el rumbo, las zonas con sus avisos y las condiciones que la apagan
 * (S018/RF-5 a RF-13, RF-19 a RF-25, RF-35).
 */
class StakeoutControllerTest {

    private class Recorder : Alerts {
        val events = mutableListOf<String>()

        override fun connectionLost() {
            events += "connectionLost"
        }

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

    // En el meridiano central del huso 30 la convergencia es 0 y el Norte de cuadrícula es el geográfico: los desplazamientos
    // en el plano local valen como diferencias de cuadrícula (con el factor de escala de 0,9996).
    private val origin = GeodeticPoint(40.4168, -3.0, 699.423)
    private val poleHeight = 1.8
    private val antennaHeight = 1.8 + 0.134

    /** El punto de diseño, en el suelo del origen. */
    private val designCoordinates: PointCoordinates = run {
        val ground = GeodeticPosition(origin.latitudeDeg, origin.longitudeDeg, origin.ellipsoidalHeightM)
        val position = calculator.fromGround(ground, project.crs, project.heightSystem)
        PointCoordinates(ground, position.easting, position.northing, position.height)
    }
    private val target = StakeTarget("design-1", "12", "esquina", designCoordinates)

    private fun context(settings: StakeoutSettings = StakeoutSettings(), fixOnly: Boolean = true, tiltMode: TiltMode? = null) =
        StakeContext(settings, target, fixOnly, tiltMode, poleHeight, project, calculator)

    private fun tilt(state: TiltState = TiltState.COMPENSATING, headingDeg: Double? = 0.0) = TiltData(
        utcMillisOfDay = 0,
        state = state,
        notification = TiltNotification.NONE,
        headingRad = headingDeg?.let(Math::toRadians),
        tiltDirectionRad = 0.0,
        tiltRad = 0.0,
        accuracyCoefficients = TiltAccuracyCoefficients(1.0, 1.0, 1.0),
    )

    /** Una época con la punta a [east] y [north] metros del punto de diseño, a la hora [index] × 0,2 s. */
    private fun epoch(
        east: Double,
        north: Double,
        index: Int = 0,
        status: SolutionStatus = SolutionStatus.FIX,
        tilt: TiltData? = null,
        position: Boolean = true,
        up: Double = 0.0,
    ): Epoch {
        val tip = LocalTangentPlane.offset(origin, east, north, up)
        val fix = GgaFix(
            "t$index",
            if (position) tip.latitudeDeg else Double.NaN,
            if (position) tip.longitudeDeg else Double.NaN,
            if (position) status else SolutionStatus.NONE,
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
            tilt,
            emptyMap(),
            emptyMap(),
            0,
        )
    }

    private fun on(): GuidanceState.On = controller.state.value.shouldBeInstanceOf<GuidanceState.On>()

    private fun off(): GuidanceOff = controller.state.value.shouldBeInstanceOf<GuidanceState.Off>().reason

    @Test
    fun `S018 RF-35 antes de la primera posicion la guia esta apagada por falta de posicion y sin sonido`() {
        off() shouldBe GuidanceOff.NO_POSITION

        controller.onEpoch(epoch(0.0, 0.0, position = false), context())

        off() shouldBe GuidanceOff.NO_POSITION
        alerts.events shouldBe emptyList()
    }

    @Test
    fun `S018 RF-6 por distancia da la distancia horizontal y las diferencias de Norte y Este`() {
        // La punta 3 m al oeste y 4 m al sur del diseño: hay que ir 3 m al este y 4 m al norte.
        controller.onEpoch(epoch(east = -3.0, north = -4.0), context(StakeoutSettings(mode = StakeMode.BY_DISTANCE)))

        val g = on().ui.guidance
        g.distanceM shouldBe (5.0 plusOrMinus 0.005)
        g.dEastM shouldBe (3.0 plusOrMinus 0.005)
        g.dNorthM shouldBe (4.0 plusOrMinus 0.005)
        on().ui.zone shouldBe StakeZone.FAR
    }

    @Test
    fun `S018 RF-7 por direccion con el rumbo del IMU da adelante y derecha`() {
        val settings = StakeoutSettings(StakeMode.BY_DIRECTION, HeadingChoice.IMU)
        // El usuario mira al norte, 2 m al sur y 1,5 m al oeste del diseño: hay que avanzar 2 m y ir 1,5 m a la derecha.
        controller.onEpoch(epoch(-1.5, -2.0, tilt = tilt(headingDeg = 0.0)), context(settings))

        val ui = on().ui
        ui.heading.shouldBeInstanceOf<HeadingResult.Available>().rad shouldBe (0.0 plusOrMinus 0.01)
        ui.guidance.aheadM.shouldNotBeNull() shouldBe (2.0 plusOrMinus 0.005)
        ui.guidance.rightM.shouldNotBeNull() shouldBe (1.5 plusOrMinus 0.005)
    }

    @Test
    fun `S018 RF-9 con el rumbo de marcha la flecha sale del avance de la punta`() {
        val settings = StakeoutSettings(StakeMode.BY_DIRECTION, HeadingChoice.WALKING)
        // Camina hacia el norte a 1 m/s, a 10 m al sur del diseño al empezar.
        for (i in 0..15) controller.onEpoch(epoch(0.0, -10.0 + i * 0.2, index = i), context(settings))

        val ui = on().ui
        ui.heading.shouldBeInstanceOf<HeadingResult.Available>().rad shouldBe (0.0 plusOrMinus 0.01)
        ui.guidance.aheadM.shouldNotBeNull() shouldBe (ui.guidance.distanceM plusOrMinus 0.01)
        ui.guidance.rightM.shouldNotBeNull() shouldBe (0.0 plusOrMinus 0.01)
    }

    @Test
    fun `S018 RF-11 sin rumbo no hay adelante ni derecha pero si distancia y diferencias`() {
        controller.onEpoch(epoch(-3.0, -4.0), context(StakeoutSettings(StakeMode.BY_DIRECTION, HeadingChoice.WALKING)))

        val ui = on().ui
        ui.heading shouldBe HeadingResult.Unavailable(HeadingUnavailable.NOT_MOVING)
        ui.guidance.aheadM.shouldBeNull()
        ui.guidance.rightM.shouldBeNull()
        ui.guidance.distanceM shouldBe (5.0 plusOrMinus 0.005)
    }

    @Test
    fun `S018 RF-11 el IMU sin ETC dice por que no hay rumbo`() {
        controller.onEpoch(epoch(-3.0, -4.0), context(StakeoutSettings(heading = HeadingChoice.IMU)))

        on().ui.heading shouldBe HeadingResult.Unavailable(HeadingUnavailable.NO_IMU)
    }

    @Test
    fun `S018 RF-12 la diferencia de cota es la de la punta menos la del diseno`() {
        controller.onEpoch(epoch(0.5, 0.0, up = 0.25), context())

        on().ui.guidance.dHeightM.shouldNotBeNull() shouldBe (0.25 plusOrMinus 0.01)
    }

    @Test
    fun `S018 RF-13 muestra la solucion y la precision horizontal de la GST`() {
        controller.onEpoch(epoch(-1.0, 0.0), context())

        on().ui.solution shouldBe SolutionStatus.FIX
        on().ui.horizontalAccuracyM.shouldNotBeNull() shouldBe (0.01 plusOrMinus 1e-9)
    }

    @Test
    fun `S018 RF-15 la diana lleva la punta respecto al diseno y su escala`() {
        controller.onEpoch(epoch(-0.1, 0.05), context())

        val ui = on().ui
        ui.tipEastM shouldBe (-0.1 plusOrMinus 0.001)
        ui.tipNorthM shouldBe (0.05 plusOrMinus 0.001)
        ui.dianaRadiusM shouldBe 0.2
        ui.zone shouldBe StakeZone.AID
    }

    @Test
    fun `S018 RF-20 y RF-21 los avisos al acercarse, llegar e irse, cada uno una vez`() {
        listOf(0.9, 0.6, 0.38, 0.30, 0.1, 0.03, 0.02, 0.015, 0.02, 0.04, 0.05, 0.3).forEachIndexed { i, north ->
            controller.onEpoch(epoch(0.0, north, index = i), context())
        }

        alerts.events shouldBe listOf("approach", "arrived", "left")
        on().ui.zone shouldBe StakeZone.AID
    }

    @Test
    fun `S018 RF-22 sin FIX con Solo FIX la guia se apaga, avisa una vez y vuelve al recuperar el FIX`() {
        controller.onEpoch(epoch(-1.0, 0.0, 0), context())
        on()
        controller.onEpoch(epoch(-1.0, 0.0, 1, status = SolutionStatus.FLOAT), context())
        controller.onEpoch(epoch(-1.0, 0.0, 2, status = SolutionStatus.FLOAT), context())

        off() shouldBe GuidanceOff.NO_FIX
        alerts.events shouldBe listOf("solutionLost")

        controller.onEpoch(epoch(-1.0, 0.0, 3), context())

        on().ui.solution shouldBe SolutionStatus.FIX
        alerts.events shouldBe listOf("solutionLost")
    }

    @Test
    fun `S018 RF-23 sin Solo FIX la guia sigue con cualquier solucion y la muestra`() {
        controller.onEpoch(epoch(-1.0, 0.0, status = SolutionStatus.FLOAT), context(fixOnly = false))

        on().ui.solution shouldBe SolutionStatus.FLOAT
        alerts.events shouldBe emptyList()
    }

    @Test
    fun `S018 RF-24 con la inclinacion en uso y sin estado 30 la guia se apaga`() {
        controller.onEpoch(epoch(-1.0, 0.0, 0, tilt = tilt()), context(tiltMode = TiltMode.APP))
        on()

        controller.onEpoch(epoch(-1.0, 0.0, 1, tilt = tilt(TiltState.ALIGNMENT)), context(tiltMode = TiltMode.APP))
        off() shouldBe GuidanceOff.TILT_NOT_READY
        controller.onEpoch(epoch(-1.0, 0.0, 2, tilt = null), context(tiltMode = TiltMode.APP))
        off() shouldBe GuidanceOff.TILT_NOT_READY

        controller.onEpoch(epoch(-1.0, 0.0, 3, tilt = tilt()), context(tiltMode = TiltMode.APP))
        on()
    }

    @Test
    fun `S018 RF-24 con el jalon vertical la inclinacion no apaga nada`() {
        controller.onEpoch(epoch(-1.0, 0.0, tilt = tilt(TiltState.ALIGNMENT)), context(tiltMode = TiltMode.VERTICAL))

        on()
    }

    @Test
    fun `S018 RF-25 un corte apaga la guia, avisa una vez y vuelve con la primera epoca`() {
        controller.onEpoch(epoch(-1.0, 0.0, 0), context())
        on()

        controller.onSilence()
        controller.onSilence()

        off() shouldBe GuidanceOff.NO_DATA
        alerts.events shouldBe listOf("solutionLost")

        controller.onEpoch(epoch(-1.0, 0.0, 1), context())

        on()
        alerts.events shouldBe listOf("solutionLost")
    }

    @Test
    fun `S018 RF-25 con el corte sigue apagada y sin posicion no se vuelve a avisar`() {
        controller.onEpoch(epoch(-1.0, 0.0, 0), context())
        controller.onSilence()
        controller.onEpoch(epoch(0.0, 0.0, 1, position = false), context())

        off() shouldBe GuidanceOff.NO_POSITION
        alerts.events shouldBe listOf("solutionLost")
    }

    @Test
    fun `S018 RF-19 al volver de un apagado ya dentro de la tolerancia avisa de que ha llegado`() {
        controller.onEpoch(epoch(0.0, 0.01, 0), context())
        alerts.events shouldBe listOf("arrived")
        controller.onSilence()
        alerts.events.clear()

        controller.onEpoch(epoch(0.0, 0.01, 1), context())

        alerts.events shouldBe listOf("arrived")
        on().ui.zone shouldBe StakeZone.INSIDE
    }

    @Test
    fun `S018 RF-18 la tolerancia elegida se aplica en cada epoca`() {
        controller.onEpoch(epoch(0.0, 0.05, 0), context(StakeoutSettings(toleranceM = 0.025)))
        on().ui.zone shouldBe StakeZone.AID

        controller.onEpoch(epoch(0.0, 0.05, 1), context(StakeoutSettings(toleranceM = 0.06)))

        on().ui.zone shouldBe StakeZone.INSIDE
    }

    @Test
    fun `S018 RNF-T13 la guia se publica a 5 Hz como mucho y un cambio de zona se publica enseguida`() {
        val throttled = StakeoutController(alerts, publishIntervalMs = 200, now = { clock })

        clock = 1_000
        throttled.onEpoch(epoch(0.0, 0.9, 0), context())
        val first = throttled.state.value
        clock = 1_050
        throttled.onEpoch(epoch(0.0, 0.8, 1), context())
        throttled.state.value shouldBe first // dentro de los 200 ms: no se publica
        clock = 1_060
        throttled.onEpoch(epoch(0.0, 0.1, 2), context()) // entra en la zona de ayuda: se publica ya
        (throttled.state.value as GuidanceState.On).ui.zone shouldBe StakeZone.AID
        clock = 1_300
        throttled.onEpoch(epoch(0.0, 0.09, 3), context())
        (throttled.state.value as GuidanceState.On).ui.guidance.distanceM shouldBe (0.09 plusOrMinus 0.001)
    }

    @Test
    fun `S018 RF-5 al cambiar de punto de diseno la guia empieza de nuevo y no arrastra el rumbo de marcha`() {
        for (i in 0..15) controller.onEpoch(epoch(0.0, -10.0 + i * 0.2, index = i), context())
        on().ui.heading.shouldBeInstanceOf<HeadingResult.Available>()

        controller.reset()

        off() shouldBe GuidanceOff.NO_POSITION
        controller.onEpoch(epoch(0.0, 0.5, 16), context())
        on().ui.heading shouldBe HeadingResult.Unavailable(HeadingUnavailable.NOT_MOVING)
    }

    private val log = RecordingLog()
    private val logged = StakeoutController(alerts, log, publishIntervalMs = 0, now = { clock })

    @Test
    fun `S018 RF-20 y RF-21 el log anota cada cambio de zona una sola vez, sin coordenadas`() {
        listOf(0.9, 0.38, 0.30, 0.1, 0.02, 0.015, 0.05).forEachIndexed { i, north ->
            logged.onEpoch(epoch(0.0, north, index = i), context())
        }

        log.messages shouldBe listOf(
            "Replanteo de 12: entra en la zona de ayuda",
            "Replanteo de 12: dentro de la tolerancia",
            "Replanteo de 12: fuera de la tolerancia",
        )
        log.entries.all { it.second == LogCategory.POINTS } shouldBe true
        // Ni coordenadas ni distancias: solo el nombre del punto (S014/RF-7).
        log.messages.none { Regex("""\d+[.,]\d+""").containsMatchIn(it) } shouldBe true
    }

    @Test
    fun `S018 RF-22, RF-24 y RF-25 el log anota cuando la guia se apaga y por que, una sola vez`() {
        logged.onEpoch(epoch(-1.0, 0.0, 0), context())
        logged.onEpoch(epoch(-1.0, 0.0, 1, status = SolutionStatus.FLOAT), context())
        logged.onEpoch(epoch(-1.0, 0.0, 2, status = SolutionStatus.FLOAT), context())
        logged.onEpoch(epoch(-1.0, 0.0, 3), context())
        logged.onEpoch(epoch(-1.0, 0.0, 4, tilt = tilt(TiltState.ALIGNMENT)), context(tiltMode = TiltMode.APP))
        logged.onEpoch(epoch(-1.0, 0.0, 5, tilt = tilt()), context(tiltMode = TiltMode.APP))
        logged.onSilence(context())
        logged.onSilence(context())

        log.messages shouldBe listOf(
            "Replanteo de 12: guía apagada, sin FIX",
            "Replanteo de 12: guía apagada, la inclinación no está en estado 30",
            "Replanteo de 12: guía apagada, sin datos del receptor",
        )
        log.entries.map { it.first } shouldBe List(3) { LogLevel.WARNING }
    }

    @Test
    fun `S018 RF-35 abrir el replanteo sin posicion no deja nada en el log`() {
        logged.onEpoch(epoch(0.0, 0.0, position = false), context())

        log.messages shouldBe emptyList()
    }

    @Test
    fun `S018 RF-21 despues de apagarse y volver, la zona de ayuda avisa de nuevo`() {
        controller.onEpoch(epoch(0.0, 0.3, 0), context())
        alerts.events shouldBe listOf("approach")
        controller.onSilence()
        alerts.events.clear()

        controller.onEpoch(epoch(0.0, 0.3, 1), context())

        alerts.events shouldBe listOf("approach")
    }
}
