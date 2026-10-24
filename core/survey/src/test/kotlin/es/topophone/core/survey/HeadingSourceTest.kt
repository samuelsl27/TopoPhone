package es.topophone.core.survey

import es.topophone.core.gnss.nmea.TiltAccuracyCoefficients
import es.topophone.core.gnss.nmea.TiltData
import es.topophone.core.gnss.nmea.TiltNotification
import es.topophone.core.gnss.nmea.TiltState
import es.topophone.core.model.point.HeadingChoice
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.math.PI
import org.junit.jupiter.api.Test

/** El rumbo del usuario en el replanteo: de marcha o del IMU, y por qué falta (S018/RF-8 a RF-11). */
class HeadingSourceTest {

    private val source = HeadingSource()

    /** La punta va a [speed] m/s con el rumbo [bearingDeg] durante [seconds] s, con una época cada 0,2 s desde [from] ms. */
    private fun walk(
        bearingDeg: Double,
        speed: Double,
        seconds: Double,
        from: Long = 0L,
        startE: Double = 0.0,
        startN: Double = 0.0,
    ): Long {
        val b = Math.toRadians(bearingDeg)
        var t = from
        val steps = (seconds / 0.2).toInt()
        for (i in 0..steps) {
            val travelled = speed * i * 0.2
            source.onTip(t, startE + travelled * Math.sin(b), startN + travelled * Math.cos(b))
            t += 200
        }
        return t - 200
    }

    private fun available(result: HeadingResult) = result.shouldBeInstanceOf<HeadingResult.Available>()

    @Test
    fun `S018 RF-9 el rumbo de marcha es el del avance de la punta`() {
        val end = walk(bearingDeg = 0.0, speed = 1.0, seconds = 3.0)

        val north = available(source.walking(end))

        north.rad shouldBe (0.0 plusOrMinus 1e-9)
        north.ageS shouldBe 0.0
        source.reset()
        val endEast = walk(bearingDeg = 90.0, speed = 1.0, seconds = 3.0)
        available(source.walking(endEast)).rad shouldBe (PI / 2 plusOrMinus 1e-9)
        source.reset()
        val endWest = walk(bearingDeg = 250.0, speed = 0.8, seconds = 3.0)
        available(source.walking(endWest)).rad shouldBe (Math.toRadians(250.0) plusOrMinus 1e-9)
    }

    @Test
    fun `S018 RF-9 con 0,3 m por segundo o mas hay rumbo y con menos no`() {
        val slow = walk(bearingDeg = 45.0, speed = 0.25, seconds = 4.0)
        source.walking(slow) shouldBe HeadingResult.Unavailable(HeadingUnavailable.NOT_MOVING)
        source.reset()

        val limit = walk(bearingDeg = 45.0, speed = 0.3, seconds = 4.0)
        available(source.walking(limit)).rad shouldBe (PI / 4 plusOrMinus 1e-9)
    }

    @Test
    fun `S018 RF-9 parado con el ruido del GNSS no hay rumbo de marcha`() {
        var t = 0L
        repeat(40) { i ->
            source.onTip(t, 0.01 * (if (i % 2 == 0) 1 else -1), 0.01 * (if (i % 3 == 0) 1 else -1))
            t += 200
        }

        source.walking(t) shouldBe HeadingResult.Unavailable(HeadingUnavailable.NOT_MOVING)
    }

    @Test
    fun `S018 RF-9 al pararse conserva el ultimo rumbo 10 s y despues dice que no esta disponible`() {
        val stoppedAt = walk(bearingDeg = 120.0, speed = 1.0, seconds = 3.0)
        val east = 1.0 * 3.0 * Math.sin(Math.toRadians(120.0))
        val north = 1.0 * 3.0 * Math.cos(Math.toRadians(120.0))
        // Parado: la punta ya no se mueve.
        var t = stoppedAt
        repeat(60) {
            t += 200
            source.onTip(t, east, north)
        }

        val last = available(source.walking(stoppedAt + 5_000))
        last.rad shouldBe (Math.toRadians(120.0) plusOrMinus 1e-9)
        // La ventana de 2 s sigue viendo el avance hasta ~1,4 s después de pararse: la edad cuenta desde el último momento a 0,3 m/s o más.
        last.ageS shouldBe (3.6 plusOrMinus 0.6)
        available(source.walking(stoppedAt + 9_000)).ageS shouldBe (7.6 plusOrMinus 0.6)
        source.walking(stoppedAt + 13_000) shouldBe HeadingResult.Unavailable(HeadingUnavailable.NOT_MOVING)
    }

    @Test
    fun `S018 RF-9 si el reloj de las epocas vuelve atras empieza de nuevo`() {
        walk(bearingDeg = 0.0, speed = 1.0, seconds = 3.0, from = 100_000)

        source.onTip(10_000, 0.0, 0.0) // un bucle de la reproducción

        source.walking(10_000) shouldBe HeadingResult.Unavailable(HeadingUnavailable.NOT_MOVING)
    }

    private fun tilt(state: TiltState, headingDeg: Double?) = TiltData(
        utcMillisOfDay = 0,
        state = state,
        notification = TiltNotification.NONE,
        headingRad = headingDeg?.let(Math::toRadians),
        tiltDirectionRad = 0.0,
        tiltRad = 0.0,
        accuracyCoefficients = TiltAccuracyCoefficients(1.0, 1.0, 1.0),
    )

    @Test
    fun `S018 RF-10 el rumbo del IMU es el del ETC en estado 30, en el norte de cuadricula`() {
        available(source.imu(tilt(TiltState.COMPENSATING, 90.0), convergenceRad = 0.0)).rad shouldBe (PI / 2 plusOrMinus 1e-12)

        // Con la convergencia de +1° el norte de cuadrícula está 1° más a la derecha que el geográfico: el rumbo de cuadrícula es 1° menor.
        available(source.imu(tilt(TiltState.COMPENSATING, 90.0), convergenceRad = Math.toRadians(1.0))).rad shouldBe
            (Math.toRadians(89.0) plusOrMinus 1e-12)
        // Sin convergencia (SC geográfico) se queda tal cual.
        available(source.imu(tilt(TiltState.COMPENSATING, 90.0), convergenceRad = null)).rad shouldBe (PI / 2 plusOrMinus 1e-12)
    }

    @Test
    fun `S018 RF-10 el rumbo de cuadricula se queda entre 0 y 360 grados`() {
        available(source.imu(tilt(TiltState.COMPENSATING, 0.5), convergenceRad = Math.toRadians(1.0))).rad shouldBe
            (Math.toRadians(359.5) plusOrMinus 1e-12)
        available(source.imu(tilt(TiltState.COMPENSATING, 359.5), convergenceRad = Math.toRadians(-1.0))).rad shouldBe
            (Math.toRadians(0.5) plusOrMinus 1e-12)
    }

    @Test
    fun `S018 RF-11 sin estado 30, sin rumbo o sin ETC el IMU no esta disponible y se sabe por que`() {
        source.imu(tilt(TiltState.ALIGNMENT, 90.0), 0.0) shouldBe HeadingResult.Unavailable(HeadingUnavailable.IMU_NOT_READY)
        source.imu(tilt(TiltState.SETUP, 90.0), 0.0) shouldBe HeadingResult.Unavailable(HeadingUnavailable.IMU_NOT_READY)
        source.imu(tilt(TiltState.COMPENSATING, null), 0.0) shouldBe HeadingResult.Unavailable(HeadingUnavailable.IMU_NOT_READY)
        source.imu(null, 0.0) shouldBe HeadingResult.Unavailable(HeadingUnavailable.NO_IMU)
    }

    @Test
    fun `S018 RF-8 el origen elegido decide cual de los dos se usa`() {
        val end = walk(bearingDeg = 0.0, speed = 1.0, seconds = 3.0)
        val imu = tilt(TiltState.COMPENSATING, 90.0)

        available(source.heading(HeadingChoice.WALKING, end, imu, 0.0)).rad shouldBe (0.0 plusOrMinus 1e-9)
        available(source.heading(HeadingChoice.IMU, end, imu, 0.0)).rad shouldBe (PI / 2 plusOrMinus 1e-12)
        source.heading(HeadingChoice.IMU, end, null, 0.0) shouldBe HeadingResult.Unavailable(HeadingUnavailable.NO_IMU)
    }
}
