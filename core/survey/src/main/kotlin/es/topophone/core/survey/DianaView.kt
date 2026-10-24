package es.topophone.core.survey

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

/** Un punto de la diana en el cuadrado de lado 2: [x] a la derecha e [y] hacia arriba, con el centro en 0,0 (S018/RF-15). */
data class DianaPoint(val x: Double, val y: Double)

/**
 * Lo que dibuja la diana y la flecha del replanteo, puro y sin pantalla (S018/RF-7, RF-15, RF-17): dónde cae la punta, qué
 * parte del radio es el círculo de la tolerancia y hacia dónde apunta la flecha.
 */
object DianaView {
    /**
     * La punta a [eastM] y [northM] del punto de diseño, en un círculo unidad cuyo radio es [radiusM]. Con el rumbo del
     * usuario [headingRad] arriba, lo que está delante sale arriba; sin él, el norte arriba (S018/RF-17). Fuera del radio se
     * queda en el borde, en su misma dirección.
     */
    fun position(eastM: Double, northM: Double, radiusM: Double, headingRad: Double?): DianaPoint {
        val theta = headingRad ?: 0.0
        val x = eastM * cos(theta) - northM * sin(theta)
        val y = eastM * sin(theta) + northM * cos(theta)
        val norm = hypot(x, y) / radiusM
        val scale = if (norm > 1.0) 1.0 / norm else 1.0
        return DianaPoint(x / radiusM * scale, y / radiusM * scale)
    }

    /** El radio del círculo de la tolerancia como fracción del de la diana, sin pasar del borde (S018/RF-15). */
    fun toleranceFraction(toleranceM: Double, radiusM: Double): Double = (toleranceM / radiusM).coerceIn(0.0, 1.0)

    /** El ángulo de la flecha respecto a "delante", en sentido horario, en `(-π, π]` (S018/RF-7). */
    fun arrowAngleRad(aheadM: Double, rightM: Double): Double = atan2(rightM, aheadM)

    /** Lo mismo en grados enteros, con signo: positivo a la derecha y negativo a la izquierda; 180 es justo detrás. */
    fun arrowDegrees(aheadM: Double, rightM: Double): Int = (arrowAngleRad(aheadM, rightM) * 180.0 / PI).roundToInt()
}
