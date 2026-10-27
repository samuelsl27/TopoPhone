package es.topophone.core.survey

import es.topophone.core.model.alignment.AlignmentElement
import es.topophone.core.model.alignment.ElementKind
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.sqrt

/** Puntos de un eje en planta: el PK y las coordenadas de cada uno, en el orden del eje. */
class AxisSamples(val chainage: DoubleArray, val easting: DoubleArray, val northing: DoubleArray) {
    val size: Int get() = chainage.size
}

/**
 * Los puntos de un eje en planta para dibujarlo, calcular su extensión o proyectarlo a otro sistema (S049/RF-11, RF-17, plan A6 y A8).
 * Las rectas se muestrean cada [maxStepM] como mucho, y las curvas con una cuerda cuya flecha no pasa de [sagittaM]: `cuerda = √(8·R·f)`
 * con el radio mínimo del tramo. Cada tramo aporta su inicio y su final, y el final de uno no se repite como inicio del siguiente.
 */
object AlignmentSampler {
    /** Menor paso entre muestras: por debajo de 5 cm el radio pequeño de una clotoide no ganaría nada. */
    private const val MIN_STEP_M = 0.05

    fun sample(elements: List<AlignmentElement>, sagittaM: Double, maxStepM: Double): AxisSamples {
        val chainage = ArrayList<Double>()
        val easting = ArrayList<Double>()
        val northing = ArrayList<Double>()
        for ((index, e) in elements.withIndex()) {
            val step = stepOf(e, sagittaM, maxStepM)
            val n = max(1, ceil(e.length / step).toInt())
            for (i in (if (index == 0) 0 else 1)..n) {
                val s = if (i == n) e.length else e.length * i / n
                val state = ElementGeometry.stateAt(e, s)
                chainage += e.startChainage + s
                easting += state.easting
                northing += state.northing
            }
        }
        return AxisSamples(chainage.toDoubleArray(), easting.toDoubleArray(), northing.toDoubleArray())
    }

    private fun stepOf(e: AlignmentElement, sagittaM: Double, maxStepM: Double): Double {
        if (e.kind == ElementKind.LINE) return maxStepM
        val curvature = max(abs(e.startCurvature), abs(e.endCurvature))
        val chord = sqrt(8.0 * sagittaM / curvature)
        return chord.coerceIn(MIN_STEP_M, maxStepM)
    }
}
