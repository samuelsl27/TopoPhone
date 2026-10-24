package es.topophone.core.survey

import es.topophone.core.gnss.nmea.TiltData
import es.topophone.core.gnss.nmea.TiltState
import es.topophone.core.model.point.HeadingChoice
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.hypot

/** Por qué falta el rumbo del usuario (S018/RF-11). */
enum class HeadingUnavailable {
    /** De marcha: la punta no avanza a la velocidad mínima ni la conserva de hace poco. */
    NOT_MOVING,

    /** Del IMU: el receptor no manda la frase ETC (otro receptor, o una reproducción sin ella). */
    NO_IMU,

    /** Del IMU: el ETC no está en estado 30 o no trae rumbo. */
    IMU_NOT_READY,
}

/** El rumbo del usuario, o por qué no lo hay (S018/RF-9, RF-10, RF-11). */
sealed interface HeadingResult {
    /**
     * [rad] desde el norte de cuadrícula en sentido horario, en `[0, 2π)`. [ageS] es 0 si es el de ahora y, con el rumbo
     * de marcha, los segundos que hace de la última vez que se iba a la velocidad mínima ("último rumbo").
     */
    data class Available(val rad: Double, val ageS: Double = 0.0) : HeadingResult

    data class Unavailable(val reason: HeadingUnavailable) : HeadingResult
}

/**
 * El rumbo del usuario en el replanteo (S018/A2, RF-8 a RF-11): del avance de la punta o del IMU del receptor. Es pura y
 * lleva la cuenta de la marcha; el tiempo es el de las épocas, en ms, para que una reproducción a ×5 dé lo mismo que a ×1.
 *
 * Marcha: con las posiciones de los últimos [windowMs], si la punta avanza a [minSpeedMps] o más, el rumbo es el de ese
 * avance; parado, se conserva el último [holdS] segundos. IMU: el rumbo del ETC en estado 30, que es respecto al norte
 * geográfico, y por eso se le resta la convergencia de cuadrícula (rumbo de cuadrícula = rumbo geográfico − convergencia).
 */
class HeadingSource(
    private val minSpeedMps: Double = MIN_SPEED_MPS,
    private val holdS: Double = HOLD_S,
    private val windowMs: Long = WINDOW_MS,
) {
    private class Sample(val timeMs: Long, val east: Double, val north: Double)

    private val samples = ArrayDeque<Sample>()
    private var lastRad: Double? = null
    private var lastValidMs = 0L

    /** Una posición de la punta, con la hora de su época y en metros de cuadrícula o del plano local. */
    fun onTip(timeMs: Long, east: Double, north: Double) {
        if (samples.isNotEmpty() && timeMs < samples.last().timeMs) reset() // el reloj de las épocas vuelve atrás: un bucle
        samples.addLast(Sample(timeMs, east, north))
        while (samples.size > 2 && timeMs - samples[1].timeMs >= windowMs) samples.removeFirst()
        val oldest = samples.first()
        val span = timeMs - oldest.timeMs
        if (span < windowMs / 2) return
        val dEast = east - oldest.east
        val dNorth = north - oldest.north
        val speed = hypot(dEast, dNorth) / (span / 1_000.0)
        if (speed >= minSpeedMps) {
            lastRad = atan2(dEast, dNorth).let { if (it < 0.0) it + 2.0 * PI else it }
            lastValidMs = timeMs
        }
    }

    /** El rumbo de marcha en el instante [timeMs]. */
    fun walking(timeMs: Long): HeadingResult {
        val rad = lastRad ?: return HeadingResult.Unavailable(HeadingUnavailable.NOT_MOVING)
        val ageS = (timeMs - lastValidMs) / 1_000.0
        return if (ageS <= holdS) HeadingResult.Available(rad, ageS) else HeadingResult.Unavailable(HeadingUnavailable.NOT_MOVING)
    }

    /** El rumbo del IMU de la época [tilt], con la [convergenceRad] del SC del proyecto (null en un SC geográfico). */
    fun imu(tilt: TiltData?, convergenceRad: Double?): HeadingResult {
        if (tilt == null) return HeadingResult.Unavailable(HeadingUnavailable.NO_IMU)
        val heading = tilt.headingRad
        if (tilt.state != TiltState.COMPENSATING || heading == null) return HeadingResult.Unavailable(HeadingUnavailable.IMU_NOT_READY)
        val grid = (heading - (convergenceRad ?: 0.0)).mod(2.0 * PI)
        return HeadingResult.Available(grid)
    }

    /** El rumbo del origen elegido (S018/RF-8). */
    fun heading(choice: HeadingChoice, timeMs: Long, tilt: TiltData?, convergenceRad: Double?): HeadingResult = when (choice) {
        HeadingChoice.WALKING -> walking(timeMs)
        HeadingChoice.IMU -> imu(tilt, convergenceRad)
    }

    fun reset() {
        samples.clear()
        lastRad = null
        lastValidMs = 0L
    }

    companion object {
        /** S018/RF-9: velocidad mínima para fiarse del avance, en m/s. */
        const val MIN_SPEED_MPS = 0.3

        /** S018/RF-9: segundos que se conserva el último rumbo de marcha al pararse. */
        const val HOLD_S = 10.0

        /** Las posiciones que cuentan para el avance, en ms. */
        const val WINDOW_MS = 2_000L
    }
}
