package es.topophone.core.survey

import es.topophone.core.gnss.nmea.TiltAccuracyCoefficients
import es.topophone.core.gnss.nmea.TiltData
import es.topophone.core.gnss.nmea.TiltNotification
import es.topophone.core.gnss.nmea.TiltState
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

/** Época de inclinación: estado 30, aviso, guía, vector del jalón y precisión de la inclinación (S016, plan A2). */
class TiltEpochTest {

    /** El ejemplo de ETC_rev_3: compensando, sin aviso, coeficientes N 1,013, E 0,993 y U 1,021. */
    private val compensating = TiltData(
        utcMillisOfDay = 70_708_800,
        state = TiltState.COMPENSATING,
        notification = TiltNotification.NONE,
        headingRad = Math.toRadians(172.543),
        tiltDirectionRad = Math.toRadians(5.621),
        tiltRad = Math.toRadians(2.344),
        accuracyCoefficients = TiltAccuracyCoefficients(north = 1.013, east = 0.993, up = 1.021),
    )

    @Test
    fun `S016 RF-18 en estado 30 con la inclinacion, su direccion y los coeficientes`() {
        TiltEpoch.isCompensating(compensating) shouldBe true
        TiltEpoch.isClean(compensating) shouldBe true
        // El rumbo no hace falta para la punta.
        TiltEpoch.isCompensating(compensating.copy(headingRad = null)) shouldBe true
    }

    @Test
    fun `S016 RF-18 en estado 30 sin algun campo cuenta como fuera del estado 30`() {
        TiltEpoch.isCompensating(compensating.copy(tiltRad = null)) shouldBe false
        TiltEpoch.isCompensating(compensating.copy(tiltDirectionRad = null)) shouldBe false
        TiltEpoch.isCompensating(compensating.copy(accuracyCoefficients = null)) shouldBe false
        TiltEpoch.isCompensating(compensating.copy(state = TiltState.ALIGNMENT)) shouldBe false
        TiltEpoch.isCompensating(null) shouldBe false
    }

    @Test
    fun `S016 RF-19 con un aviso esta en estado 30 pero no limpia`() {
        val fast = compensating.copy(notification = TiltNotification.FAST_MOTION)

        TiltEpoch.isCompensating(fast) shouldBe true
        TiltEpoch.isClean(fast) shouldBe false
    }

    @ParameterizedTest
    @CsvSource(
        "OFF, OFF, NO_TILT",
        "FATAL, NONE, FATAL",
        "SETUP, NONE, SETUP",
        "ALIGNMENT, NONE, ALIGNMENT",
        "ALIGNMENT, BAD_GNSS, ALIGNMENT",
        "COMPENSATING, FAST_MOTION, FAST_MOTION",
        "COMPENSATING, BAD_GNSS, BAD_GNSS",
        "COMPENSATING, FILTER_FAULT, FILTER_FAULT",
        "COMPENSATING, OFF, SETUP",
    )
    fun `S016 RF-12 la guia sale del estado o del aviso`(state: TiltState, notification: TiltNotification, guide: TiltGuide) {
        TiltEpoch.guide(compensating.copy(state = state, notification = notification)) shouldBe guide
    }

    @Test
    fun `S016 RF-12 sin ETC la guia pide encenderla y limpia no hay guia`() {
        TiltEpoch.guide(null) shouldBe TiltGuide.NO_TILT
        TiltEpoch.guide(compensating).shouldBeNull()
        // Estado 30 con un campo vacío: esperar (plan, decisiones técnicas).
        TiltEpoch.guide(compensating.copy(accuracyCoefficients = null)) shouldBe TiltGuide.SETUP
    }

    @Test
    fun `S016 RF-13 el vector del jalon va de la punta a la antena`() {
        val tilt = compensating.copy(tiltRad = Math.toRadians(15.25), tiltDirectionRad = Math.toRadians(210.5))

        val pole = TiltEpoch.poleVector(tilt).shouldNotBeNull()

        val theta = Math.toRadians(15.25)
        val alpha = Math.toRadians(210.5)
        pole.east shouldBe (sin(theta) * sin(alpha) plusOrMinus 1e-15)
        pole.north shouldBe (sin(theta) * cos(alpha) plusOrMinus 1e-15)
        pole.up shouldBe (cos(theta) plusOrMinus 1e-15)
        sqrt(pole.east * pole.east + pole.north * pole.north + pole.up * pole.up) shouldBe (1.0 plusOrMinus 1e-15)
        TiltEpoch.poleVector(tilt.copy(state = TiltState.ALIGNMENT)).shouldBeNull()
    }

    @Test
    fun `S016 RF-21 la precision es el coeficiente por la altura de antena, en mm`() {
        val accuracy = TiltEpoch.accuracy(compensating, antennaHeightM = 1.934).shouldNotBeNull()

        // σe = 0,993·1,934 = 1,920 mm, σn = 1,013·1,934 = 1,959 mm y σu = 1,021·1,934 = 1,975 mm.
        accuracy.horizontalM shouldBe (sqrt(0.993 * 0.993 + 1.013 * 1.013) * 1.934 / 1000 plusOrMinus 1e-12)
        accuracy.horizontalM shouldBe (0.002743 plusOrMinus 5e-7)
        accuracy.verticalM shouldBe (0.001975 plusOrMinus 5e-7)
        TiltEpoch.accuracy(compensating.copy(accuracyCoefficients = null), 1.934).shouldBeNull()
    }
}
