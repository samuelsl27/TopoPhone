package es.topophone.core.survey

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Lo que se ve de un promediado en curso (S017/RF-2 a RF-4): las épocas usadas, el tiempo promediado, la media y la
 * desviación típica y la estabilidad, en el SC del proyecto y en metros. Las que no se pueden calcular aún son null.
 *
 * @property used épocas usadas.
 * @property timeS tiempo promediado: épocas usadas por el periodo del receptor.
 * @property deviationHorizontalM raíz de la suma de los cuadrados de las desviaciones típicas muestrales del Este y el
 *   Norte; null con menos de 2 épocas.
 * @property deviationVerticalM desviación típica muestral de la altura; null con menos de 2 épocas.
 * @property stabilityHorizontalM distancia en planta entre la media de ahora y la de hace 60 s de tiempo promediado;
 *   null hasta pasado el primer minuto.
 * @property stabilityVerticalM lo mismo en altura, en valor absoluto.
 */
data class AverageStatsSnapshot(
    val used: Int,
    val timeS: Double,
    val meanEast: Double,
    val meanNorth: Double,
    val meanHeight: Double,
    val deviationHorizontalM: Double?,
    val deviationVerticalM: Double?,
    val stabilityHorizontalM: Double?,
    val stabilityVerticalM: Double?,
)

/**
 * Las épocas del gráfico de dispersión, como diferencia con la media actual en metros (S017/RF-5).
 *
 * @property scaleM el radio que abarca el gráfico: 1, 2 o 5 por una potencia de 10, de 1 cm como mínimo.
 */
class Dispersion(val eastM: FloatArray, val northM: FloatArray, val scaleM: Double) {
    val count: Int get() = eastM.size
}

/**
 * Estadísticas de un promediado en una sola pasada (S017/RF-3 a RF-5; plan A2). Cada época usada llega con su posición
 * en el SC del proyecto y cuesta O(1): Welford por eje, que con 36.000 épocas no pierde precisión, y las medias de los
 * últimos 60 s de tiempo promediado para la estabilidad. Se guardan también el Este y el Norte de todas las épocas, para
 * el gráfico.
 *
 * Sin Android y sin hilos: lo usa quien mide.
 *
 * @param epochPeriodS el periodo del receptor en segundos; el tiempo promediado es el de las épocas usadas por él.
 */
class AverageStats(private val epochPeriodS: Double) {
    private var n = 0
    private val mean = DoubleArray(AXES)
    private val sumSquares = DoubleArray(AXES)

    /** Épocas de un minuto: la media de hace 60 s es la de `n − window` épocas. */
    private val window = max(1, (SECONDS_PER_MINUTE / epochPeriodS).roundToInt())

    /** La media tras cada una de las últimas `window + 1` épocas, por eje, en un buffer circular. */
    private val means = Array(AXES) { DoubleArray(window + 1) }

    private var east = DoubleArray(INITIAL_CAPACITY)
    private var north = DoubleArray(INITIAL_CAPACITY)

    /** Una época usada: su posición en el SC del proyecto en metros. */
    fun add(east: Double, north: Double, height: Double) {
        if (n == this.east.size) {
            this.east = this.east.copyOf(n * 2)
            this.north = this.north.copyOf(n * 2)
        }
        this.east[n] = east
        this.north[n] = north
        n++
        update(0, east)
        update(1, north)
        update(2, height)
        val slot = n % (window + 1)
        for (axis in 0 until AXES) means[axis][slot] = mean[axis]
    }

    private fun update(axis: Int, value: Double) {
        val delta = value - mean[axis]
        mean[axis] += delta / n
        sumSquares[axis] += delta * (value - mean[axis])
    }

    /** Lo que hay ahora, o null sin ninguna época. */
    fun snapshot(): AverageStatsSnapshot? {
        if (n == 0) return null
        val deviations = n >= 2
        val before = (n - window) % (window + 1)
        val stable = n > window
        return AverageStatsSnapshot(
            used = n,
            timeS = n * epochPeriodS,
            meanEast = mean[0],
            meanNorth = mean[1],
            meanHeight = mean[2],
            deviationHorizontalM = if (deviations) sqrt((sumSquares[0] + sumSquares[1]) / (n - 1)) else null,
            deviationVerticalM = if (deviations) sqrt(sumSquares[2] / (n - 1)) else null,
            stabilityHorizontalM = if (stable) hypot(mean[0] - means[0][before], mean[1] - means[1][before]) else null,
            stabilityVerticalM = if (stable) abs(mean[2] - means[2][before]) else null,
        )
    }

    /** Hasta [maxPoints] épocas repartidas por igual, de la primera en adelante, respecto a la media de ahora. */
    fun dispersion(maxPoints: Int = MAX_PLOT_POINTS): Dispersion {
        val step = max(1, (n + maxPoints - 1) / maxPoints)
        val count = (n + step - 1) / step
        val eastM = FloatArray(count)
        val northM = FloatArray(count)
        var radius = 0.0
        for (i in 0 until count) {
            val de = east[i * step] - mean[0]
            val dn = north[i * step] - mean[1]
            eastM[i] = de.toFloat()
            northM[i] = dn.toFloat()
            radius = max(radius, hypot(de, dn))
        }
        return Dispersion(eastM, northM, niceScale(max(radius, MIN_SCALE_M)))
    }

    private companion object {
        const val AXES = 3
        const val SECONDS_PER_MINUTE = 60.0
        const val INITIAL_CAPACITY = 1024
        const val MAX_PLOT_POINTS = 2000
        const val MIN_SCALE_M = 0.01
        const val SCALE_EPSILON = 1e-9

        /** El menor 1, 2, 5 o 10 por una potencia de 10 que no queda por debajo de [radiusM]. */
        fun niceScale(radiusM: Double): Double {
            val base = 10.0.pow(floor(log10(radiusM)))
            val ratio = radiusM / base
            val factor = listOf(1.0, 2.0, 5.0, 10.0).first { ratio <= it + SCALE_EPSILON }
            return base * factor
        }
    }
}
