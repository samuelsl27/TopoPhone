package es.topophone.core.survey

import es.topophone.core.gnss.nmea.GgaFix
import es.topophone.core.gnss.nmea.SolutionStatus
import es.topophone.core.gnss.nmea.TiltAccuracyCoefficients
import es.topophone.core.gnss.nmea.TiltData
import es.topophone.core.gnss.nmea.TiltNotification
import es.topophone.core.gnss.nmea.TiltState
import es.topophone.core.model.point.GeodeticPosition
import es.topophone.core.model.point.TiltMode
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** La punta que se muestra en directo según el modo y el ETC (S016/RF-14, RF-16, RF-17, RF-21; plan A4). */
class LiveTipTest {

    private val fix = GgaFix("101530.20", 40.4168, -3.7038, SolutionStatus.FIX, 24, 0.6, 650.123, 51.234, 1.0, "\$GNGGA,...")
    private val antenna = GeodeticPosition(40.4168, -3.7038, 650.123 + 51.234)
    private val antennaHeight = 1.8 + 0.134

    private val compensating = TiltData(
        utcMillisOfDay = 36_930_200,
        state = TiltState.COMPENSATING,
        notification = TiltNotification.NONE,
        headingRad = Math.toRadians(123.4),
        tiltDirectionRad = Math.toRadians(210.5),
        tiltRad = Math.toRadians(15.25),
        accuracyCoefficients = TiltAccuracyCoefficients(north = 1.10, east = 1.20, up = 0.30),
    )
    private val alignment = compensating.copy(
        state = TiltState.ALIGNMENT,
        tiltRad = null,
        tiltDirectionRad = null,
        accuracyCoefficients = null,
    )

    private fun verticalTip() = GeodeticPosition(antenna.latitudeDeg, antenna.longitudeDeg, antenna.ellipsoidalHeightM!! - antennaHeight)

    private fun shouldBeAt(actual: GeodeticPosition, expected: GeodeticPosition) {
        actual.latitudeDeg shouldBe (expected.latitudeDeg plusOrMinus 1e-11)
        actual.longitudeDeg shouldBe (expected.longitudeDeg plusOrMinus 1e-11)
        actual.ellipsoidalHeightM.shouldNotBeNull() shouldBe (expected.ellipsoidalHeightM!! plusOrMinus 1e-6)
    }

    @Test
    fun `S016 RF-16 en APP con estado 30 se muestra la punta calculada`() {
        val live = LiveTip.of(fix, compensating, TiltMode.APP, poleHeightM = 1.8).shouldNotBeNull()

        shouldBeAt(live.ground, PoleTip.fromAntenna(antenna, TiltEpoch.poleVector(compensating)!!, antennaHeight))
        live.uncompensated shouldBe false
    }

    @Test
    fun `S016 RF-16 un aviso del ETC no impide mostrar la punta`() {
        val live = LiveTip.of(fix, compensating.copy(notification = TiltNotification.FAST_MOTION), TiltMode.APP, 1.8).shouldNotBeNull()

        live.uncompensated shouldBe false
    }

    @Test
    fun `S016 RF-21 en APP se suma la precision de la inclinacion`() {
        val live = LiveTip.of(fix, compensating, TiltMode.APP, poleHeightM = 1.8).shouldNotBeNull()

        val accuracy = TiltEpoch.accuracy(compensating, antennaHeight)!!
        live.extraHorizontalM shouldBe accuracy.horizontalM
        live.extraVerticalM shouldBe accuracy.verticalM
    }

    @Test
    fun `S016 RF-17 en APP fuera del estado 30 se muestra el jalon vertical sin compensar`() {
        val live = LiveTip.of(fix, alignment, TiltMode.APP, poleHeightM = 1.8).shouldNotBeNull()

        shouldBeAt(live.ground, verticalTip())
        live.uncompensated shouldBe true
        live.extraHorizontalM shouldBe 0.0
        LiveTip.of(fix, null, TiltMode.APP, 1.8).shouldNotBeNull().uncompensated shouldBe true
    }

    @Test
    fun `S016 RF-14 en RECEIVER la GGA ya es la punta y no se resta el jalon`() {
        val live = LiveTip.of(fix, compensating, TiltMode.RECEIVER, poleHeightM = 1.8).shouldNotBeNull()

        shouldBeAt(live.ground, antenna)
        live.uncompensated shouldBe false
        live.extraHorizontalM shouldBe 0.0
        LiveTip.of(fix, alignment, TiltMode.RECEIVER, 1.8).shouldNotBeNull().uncompensated shouldBe true
    }

    @Test
    fun `S016 RF-16 en jalon vertical o sin modo, el jalon vertical sin aviso`() {
        listOf(TiltMode.VERTICAL, null).forEach { mode ->
            val live = LiveTip.of(fix, compensating, mode, poleHeightM = 1.8).shouldNotBeNull()

            shouldBeAt(live.ground, verticalTip())
            live.uncompensated shouldBe false
        }
    }

    @Test
    fun `S016 RF-27 sin altitud la punta sale sin altura y sin posicion no hay punta`() {
        LiveTip.of(fix.copy(altitudeM = null), compensating, TiltMode.APP, 1.8).shouldNotBeNull().ground.ellipsoidalHeightM.shouldBeNull()
        LiveTip.of(fix.copy(status = SolutionStatus.NONE, latitudeDeg = 0.0, longitudeDeg = 0.0), compensating, TiltMode.APP, 1.8)
            .shouldBeNull()
    }
}
