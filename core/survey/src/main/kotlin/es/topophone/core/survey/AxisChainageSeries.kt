package es.topophone.core.survey

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToLong

/**
 * Los PK de un replanteo por eje (S054/RF-2 a RF-4, plan A2): el inicial, los de un intervalo, el final y los puntos singulares,
 * por orden de recorrido y sin repetidos (dos PK a menos de 1 mm son el mismo). Va del PK inicial al final, hacia atrás si el final es
 * menor. Los PK se calculan al pedirlos: un intervalo diminuto en un eje largo no construye una lista enorme.
 *
 * @param endM el PK final; sin él, el intervalo llega al final del eje ([axisEndM]).
 * @param intervalM el intervalo en metros; sin él solo se replantea el PK inicial, más los singulares si se piden (RF-2).
 * @param keyStations los PK singulares del eje si está marcada la casilla "Puntos singulares"; null si no (RF-4). Los que quedan
 *   fuera del recorrido no cuentan.
 */
class AxisChainageSeries(
    private val startM: Double,
    endM: Double?,
    private val intervalM: Double?,
    keyStations: List<Double>?,
    axisEndM: Double,
) {
    private val finalM: Double = endM ?: if (intervalM != null || keyStations != null) axisEndM else startM
    private val direction = if (finalM >= startM) 1.0 else -1.0
    private val span = abs(finalM - startM)

    /** Cuántos puntos del intervalo hay entre el inicial y el final, sin contarlos. */
    private val interior: Long = if (intervalM != null &&
        span - SAME_M > 0.0
    ) {
        max(0L, ceil((span - SAME_M) / intervalM).toLong() - 1L)
    } else {
        0L
    }
    private val hasFinal: Boolean = span >= SAME_M && (intervalM != null || (keyStations != null && endM != null))
    private val baseSize: Long = 1L + interior + (if (hasFinal) 1L else 0L)

    /** Los singulares del recorrido que no coinciden con uno del intervalo, con su distancia al inicio, por orden de recorrido. */
    private val keys: List<Pair<Double, Double>> = keyStations.orEmpty()
        .filter { it >= minOf(startM, finalM) - SAME_M && it <= maxOf(startM, finalM) + SAME_M }
        .map { abs(it - startM) to it }
        .sortedBy { it.first }
        .fold(ArrayList<Pair<Double, Double>>()) { out, key ->
            if (out.isEmpty() || key.first - out.last().first >= SAME_M) out += key
            out
        }
        .filter { !coincidesWithBase(it.first) }

    /** Cuántos PK tiene la serie. */
    val size: Int = (baseSize + keys.size).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()

    /** El PK del punto [index], de 0 a [size] - 1. */
    fun chainageOf(index: Int): Double {
        require(index in 0 until size)
        var before = 0
        for ((t, pk) in keys) {
            val position = rank(t) + before
            if (index == position.toInt()) return pk
            if (index < position) break
            before++
        }
        return baseChainage((index - before).toLong())
    }

    /** La posición del PK siguiente: no pasa del último (RF-3). */
    fun next(index: Int): Int = minOf(index + 1, size - 1)

    /** La posición del PK anterior: no pasa del primero (RF-3). */
    fun previous(index: Int): Int = maxOf(index - 1, 0)

    private fun baseChainage(j: Long): Double = when {
        j == 0L -> startM
        hasFinal && j == baseSize - 1 -> finalM
        else -> startM + direction * j * (intervalM ?: 0.0)
    }

    private fun baseDistance(j: Long): Double = when {
        j == 0L -> 0.0
        hasFinal && j == baseSize - 1 -> span
        else -> j * (intervalM ?: 0.0)
    }

    /** Cuántos puntos de la base quedan antes de la distancia [t] al inicio. */
    private fun rank(t: Double): Long {
        val step = intervalM ?: return 1L
        return minOf(interior + 1L, max(1L, ceil(t / step).toLong()))
    }

    private fun coincidesWithBase(t: Double): Boolean {
        if (t < SAME_M) return true
        if (hasFinal && abs(span - t) < SAME_M) return true
        val step = intervalM ?: return false
        val nearest = (t / step).roundToLong()
        return (nearest - 1..nearest + 1).any { it in 1..interior && abs(it * step - t) < SAME_M }
    }

    private companion object {
        const val SAME_M = KeyStations.SAME_M
    }
}
