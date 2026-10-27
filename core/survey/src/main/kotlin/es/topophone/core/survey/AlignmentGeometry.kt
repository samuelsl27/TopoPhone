package es.topophone.core.survey

import es.topophone.core.model.alignment.Alignment
import es.topophone.core.model.alignment.AlignmentElement
import es.topophone.core.model.alignment.ElementKind
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.sin

/** La posición, el azimut (horario desde el Norte, en radianes) y la curvatura (positiva a la izquierda) del eje en un punto. */
data class ElementState(val easting: Double, val northing: Double, val azimuth: Double, val curvature: Double)

/**
 * La geometría de un tramo del eje (S049/RF-4, RF-20, plan A1). La curvatura varía linealmente de [AlignmentElement.startCurvature]
 * a [AlignmentElement.endCurvature], así que el azimut es `az(s) = az₀ − (k₀·s + c·s²/2)` con `c = (k₁ − k₀) / L` para los tres
 * tipos. Recta y arco tienen forma cerrada; la clotoide se integra por cuadratura de Gauss-Legendre de 16 puntos en paneles de
 * [PANEL_M] metros como máximo, con un error de orden 1e-12 m en las longitudes reales.
 */
object ElementGeometry {
    /** La anchura máxima de un panel de integración: en 5 m una clotoide de radio mínimo 10 m gira menos de 0,5 rad. */
    private const val PANEL_M = 5.0
    private const val NODES = 16

    private val nodes = DoubleArray(NODES)
    private val weights = DoubleArray(NODES)

    init {
        // Raíces del polinomio de Legendre de grado 16 por Newton con la recurrencia de tres términos.
        for (i in 0 until NODES / 2) {
            var x = cos(PI * (i + 0.75) / (NODES + 0.5))
            var derivative: Double
            do {
                var p0 = 1.0
                var p1 = x
                for (n in 2..NODES) {
                    val p2 = ((2 * n - 1) * x * p1 - (n - 1) * p0) / n
                    p0 = p1
                    p1 = p2
                }
                derivative = NODES * (x * p1 - p0) / (x * x - 1)
                val step = p1 / derivative
                x -= step
            } while (abs(step) > 1e-16)
            nodes[i] = -x
            nodes[NODES - 1 - i] = x
            weights[i] = 2.0 / ((1 - x * x) * derivative * derivative)
            weights[NODES - 1 - i] = weights[i]
        }
    }

    /** El estado del eje a [s] metros del inicio de [e] (de 0 a su longitud). */
    fun stateAt(e: AlignmentElement, s: Double): ElementState {
        val k0 = e.startCurvature
        val c = (e.endCurvature - e.startCurvature) / e.length
        val h0 = PI / 2 - e.startAzimuth
        val x: Double
        val y: Double
        when (e.kind) {
            ElementKind.LINE -> {
                x = s
                y = 0.0
            }
            ElementKind.ARC -> {
                val half = sin(k0 * s / 2)
                x = sin(k0 * s) / k0
                y = 2 * half * half / k0
            }
            ElementKind.CLOTHOID -> {
                val local = integrate(k0, c, s)
                x = local.first
                y = local.second
            }
        }
        val cosH = cos(h0)
        val sinH = sin(h0)
        return ElementState(
            easting = e.startEasting + x * cosH - y * sinH,
            northing = e.startNorthing + x * sinH + y * cosH,
            azimuth = e.startAzimuth - (k0 * s + c * s * s / 2),
            curvature = k0 + c * s,
        )
    }

    /** `∫₀ˢ (cos θ(t), sen θ(t)) dt` con `θ(t) = k₀·t + c·t²/2`, en el sistema del tramo (rumbo inicial sobre +x). */
    private fun integrate(k0: Double, c: Double, s: Double): Pair<Double, Double> {
        if (s == 0.0) return 0.0 to 0.0
        val panels = ceil(s / PANEL_M).toInt().coerceAtLeast(1)
        val width = s / panels
        var x = 0.0
        var y = 0.0
        for (p in 0 until panels) {
            val mid = (p + 0.5) * width
            val half = width / 2
            for (i in 0 until NODES) {
                val t = mid + half * nodes[i]
                val theta = k0 * t + c * t * t / 2
                x += weights[i] * cos(theta)
                y += weights[i] * sin(theta)
            }
        }
        return x * width / 2 to y * width / 2
    }
}

/** Un punto del eje o a su lado (S049/RF-20): el que se calcula con un PK y un desplazamiento. */
data class AxisPoint(
    val chainage: Double,
    val offset: Double,
    val easting: Double,
    val northing: Double,
    val axisEasting: Double,
    val axisNorthing: Double,
    val azimuth: Double,
    val curvature: Double,
) {
    /** El radio del eje en ese PK; infinito en una recta. */
    val radius: Double get() = if (curvature == 0.0) Double.POSITIVE_INFINITY else 1.0 / abs(curvature)
}

/**
 * Los puntos de un eje en planta por su PK y su desplazamiento (S049/RF-4, RF-20, constitución nº 2). El desplazamiento negativo es
 * a la izquierda y el positivo a la derecha, en el sentido de los PK crecientes.
 */
class AlignmentGeometry(private val elements: List<AlignmentElement>) {
    constructor(alignment: Alignment) : this(alignment.elements)

    init {
        require(elements.isNotEmpty()) { "Un eje tiene al menos un tramo" }
    }

    val startChainage: Double = elements.first().startChainage
    val endChainage: Double = elements.last().endChainage

    /** El PK está dentro del eje, con una holgura de una micra por el redondeo. */
    fun contains(chainage: Double): Boolean = chainage >= startChainage - TOLERANCE && chainage <= endChainage + TOLERANCE

    /** El punto del eje en [chainage] y a [offset] metros de él; null si el PK está fuera del eje (RF-21). */
    fun pointAt(chainage: Double, offset: Double = 0.0): AxisPoint? {
        if (!contains(chainage)) return null
        val index = elementIndex(chainage)
        val element = elements[index]
        val s = (chainage - element.startChainage).coerceIn(0.0, element.length)
        val state = ElementGeometry.stateAt(element, s)
        return AxisPoint(
            chainage = chainage,
            offset = offset,
            easting = state.easting + offset * cos(state.azimuth),
            northing = state.northing - offset * sin(state.azimuth),
            axisEasting = state.easting,
            axisNorthing = state.northing,
            azimuth = state.azimuth,
            curvature = state.curvature,
        )
    }

    /** La posición del tramo que contiene [chainage]: el último que empieza en o antes de él. */
    internal fun elementIndex(chainage: Double): Int {
        var low = 0
        var high = elements.lastIndex
        while (low < high) {
            val mid = (low + high + 1) / 2
            if (elements[mid].startChainage <= chainage) low = mid else high = mid - 1
        }
        return low
    }

    private companion object {
        const val TOLERANCE = 1e-6
    }
}
