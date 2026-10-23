package es.topophone.core.survey

import es.topophone.core.gnss.nmea.Epoch
import es.topophone.core.gnss.nmea.GgaFix
import es.topophone.core.gnss.nmea.SolutionStatus
import es.topophone.core.gnss.nmea.TiltAccuracyCoefficients
import es.topophone.core.gnss.nmea.TiltData
import es.topophone.core.gnss.nmea.TiltNotification
import es.topophone.core.gnss.nmea.TiltState
import es.topophone.core.model.point.GeodeticPosition
import es.topophone.core.model.point.TiltMode
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.math.sin
import org.junit.jupiter.api.Test

/** La prueba del asistente del modo de inclinación con épocas sintéticas a 5 Hz (S016/RF-5 a RF-9; plan A7). */
class TiltModeWizardTest {

    private val antennaHeight = 1.8 + 0.134
    private val tip = GeodeticPosition(40.4168, -3.7038, 699.423)
    private val separation = 51.234
    private var index = 0

    /** Una época a 5 Hz con el jalón inclinado [tiltDeg] hacia [directionDeg] sobre la punta fija. */
    private fun epoch(
        tiltDeg: Double,
        directionDeg: Double = 90.0,
        compensates: Boolean = false,
        status: SolutionStatus = SolutionStatus.FIX,
        notification: TiltNotification = TiltNotification.NONE,
        antennaHeightM: Double = antennaHeight,
        ggaOffsetFactor: Double = 1.0,
    ): Epoch {
        val tilt = TiltData(
            utcMillisOfDay = 36_930_000 + index * 200,
            state = TiltState.COMPENSATING,
            notification = notification,
            headingRad = 0.0,
            tiltDirectionRad = Math.toRadians(directionDeg),
            tiltRad = Math.toRadians(tiltDeg),
            accuracyCoefficients = TiltAccuracyCoefficients(1.0, 1.0, 1.0),
        )
        // Sin compensar, la GGA es la antena; compensando, la punta (ggaOffsetFactor imita otros movimientos).
        val antenna = PoleTip.alongPole(tip, TiltEpoch.poleVector(tilt)!!, antennaHeightM * ggaOffsetFactor)
        val gga = if (compensates) tip else antenna
        val e = Epoch(
            utcMillisOfDay = 36_930_000 + index * 200,
            date = null,
            gga = GgaFix(
                "t", gga.latitudeDeg, gga.longitudeDeg, status, 24, 0.6, gga.ellipsoidalHeightM!! - separation, separation, 1.0, "",
            ),
            gst = null,
            rmc = null,
            vtg = null,
            zda = null,
            basePosition = null,
            tilt = tilt,
            gsa = emptyMap(),
            gsv = emptyMap(),
            completedAtMs = 0,
        )
        index++
        return e
    }

    private fun nowMs() = 1_000_000L + index * 200L

    private fun TiltModeWizard.feed(vararg epochs: Epoch): WizardState = epochs.map { onEpoch(it, nowMs()) }.last()

    /** Un segundo de épocas iguales a 5 Hz. */
    private fun second(tiltDeg: Double, directionDeg: Double = 90.0, compensates: Boolean = false, factor: Double = 1.0) =
        Array(5) { epoch(tiltDeg, directionDeg, compensates, ggaOffsetFactor = factor) }

    private fun started(): TiltModeWizard {
        val wizard = TiltModeWizard(antennaHeight)
        wizard.feed(epoch(1.0))
        wizard.start(nowMs()) shouldBe true
        return wizard
    }

    @Test
    fun `S016 RF-5 antes de empezar dice que falta y no deja empezar`() {
        val wizard = TiltModeWizard(antennaHeight)

        wizard.feed(epoch(1.0, status = SolutionStatus.FLOAT, notification = TiltNotification.FAST_MOTION)) shouldBe
            WizardState.Ready(setOf(WizardMissing.FIX, WizardMissing.TILT))
        wizard.start(nowMs()) shouldBe false

        wizard.feed(epoch(1.0)) shouldBe WizardState.Ready(emptySet())
        wizard.start(nowMs()) shouldBe true
        wizard.state shouldBe WizardState.Reference(0.0)
    }

    @Test
    fun `S016 RF-8 si la GGA sigue a la antena propone Calculada por la app`() {
        val wizard = started()

        wizard.feed(*second(1.0)).shouldBeInstanceOf<WizardState.Tilt>()
        wizard.feed(epoch(10.0)).shouldBeInstanceOf<WizardState.Tilt>()
        val decided = wizard.feed(*second(20.0))

        val proposed = decided.shouldBeInstanceOf<WizardState.Proposed>()
        proposed.mode shouldBe TiltMode.APP
        // Esperado: H·(sen 20° − sen 1°) hacia el este.
        proposed.expectedM shouldBe (antennaHeight * (sin(Math.toRadians(20.0)) - sin(Math.toRadians(1.0))) plusOrMinus 1e-6)
        proposed.movedM shouldBe (proposed.expectedM plusOrMinus 1e-3)
    }

    @Test
    fun `S016 RF-7 si la GGA no se mueve propone Compensada por el receptor`() {
        val wizard = started()

        wizard.feed(*second(1.0, compensates = true))
        val proposed = wizard.feed(*second(25.0, directionDeg = 200.0, compensates = true)).shouldBeInstanceOf<WizardState.Proposed>()

        proposed.mode shouldBe TiltMode.RECEIVER
        proposed.movedM shouldBe (0.0 plusOrMinus 1e-6)
    }

    @Test
    fun `S016 RF-9 si el movimiento no encaja con ningun modo no es concluyente`() {
        val wizard = started()

        // La GGA se mueve la mitad de lo esperado: ni quieta ni con la antena.
        wizard.feed(*second(1.0, factor = 0.5))
        wizard.feed(*second(25.0, factor = 0.5)) shouldBe WizardState.Inconclusive(WizardInconclusive.MISMATCH)
    }

    @Test
    fun `S016 RF-9 perder el FIX o un aviso durante la prueba no es concluyente`() {
        val lostFix = started()
        lostFix.feed(*second(1.0))
        lostFix.feed(epoch(20.0, status = SolutionStatus.FLOAT)) shouldBe WizardState.Inconclusive(WizardInconclusive.LOST)

        val warned = started()
        warned.feed(epoch(1.0, notification = TiltNotification.FAST_MOTION)) shouldBe WizardState.Inconclusive(WizardInconclusive.LOST)
    }

    @Test
    fun `S016 RF-9 a los 60 s sin decidir no es concluyente`() {
        val wizard = started()
        val start = nowMs()
        wizard.feed(*second(1.0))

        wizard.onTick(start + 59_999) shouldBe WizardState.Tilt(Math.toRadians(1.0), 0.0, tiltMore = false)
        wizard.onTick(start + 60_000) shouldBe WizardState.Inconclusive(WizardInconclusive.TIMEOUT)
    }

    @Test
    fun `S016 RF-6 con el jalon de 0 m pide inclinar mas`() {
        val wizard = TiltModeWizard(antennaHeightM = 0.134)
        wizard.feed(epoch(1.0, antennaHeightM = 0.134))
        wizard.start(nowMs())
        wizard.feed(*Array(5) { epoch(1.0, antennaHeightM = 0.134) })

        // A 40° la antena se mueve 8 cm: no basta.
        val state = wizard.feed(epoch(40.0, antennaHeightM = 0.134)).shouldBeInstanceOf<WizardState.Tilt>()

        state.tiltMore shouldBe true
        state.expectedM shouldBe (0.134 * (sin(Math.toRadians(40.0)) - sin(Math.toRadians(1.0))) plusOrMinus 1e-9)
    }

    @Test
    fun `S016 RF-6 inclinar el jalon durante la referencia vuelve a empezarla`() {
        val wizard = started()

        wizard.feed(epoch(1.0), epoch(1.0)).shouldBeInstanceOf<WizardState.Reference>().progressS shouldBe (0.4 plusOrMinus 1e-9)
        wizard.feed(epoch(7.0)) shouldBe WizardState.Reference(0.0)
        wizard.feed(*Array(4) { epoch(1.0) }).shouldBeInstanceOf<WizardState.Reference>().progressS shouldBe (0.8 plusOrMinus 1e-9)
        wizard.feed(epoch(1.0)).shouldBeInstanceOf<WizardState.Tilt>()
    }

    @Test
    fun `S016 RF-7 la decision sale en la epoca que completa el segundo`() {
        val wizard = started()
        wizard.feed(*second(1.0))

        val states = Array(5) { epoch(30.0) }.map { wizard.onEpoch(it, nowMs()) }

        states.dropLast(1).forEach { it.shouldBeInstanceOf<WizardState.Hold>() }
        states.last().shouldBeInstanceOf<WizardState.Proposed>()
    }

    @Test
    fun `S016 RF-9 repetir vuelve a antes de empezar`() {
        val wizard = started()
        wizard.feed(*second(1.0, factor = 0.5))
        wizard.feed(*second(25.0, factor = 0.5)).shouldBeInstanceOf<WizardState.Inconclusive>()

        wizard.reset()

        wizard.state shouldBe WizardState.Ready(emptySet())
        wizard.start(nowMs()) shouldBe true
    }
}
