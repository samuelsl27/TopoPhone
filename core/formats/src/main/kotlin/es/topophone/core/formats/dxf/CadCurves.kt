package es.topophone.core.formats.dxf

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Una curva como polilínea en el plano: sus vértices, de la forma `x[i]`, `y[i]`. [original] marca los que están escritos en el DXF
 * y no son de la aproximación (S040/RF-34); null, todos.
 */
class Curve2(val x: DoubleArray, val y: DoubleArray, val original: BooleanArray? = null)

/** Una curva como polilínea en el espacio; [z] es null si ninguno de sus puntos de control traía Z. */
class Curve3(val x: DoubleArray, val y: DoubleArray, val z: DoubleArray?, val original: BooleanArray? = null)

/**
 * Arcos, círculos, bulges y splines como polilíneas que no se apartan de la curva más de la tolerancia (S040/RF-15). La tolerancia
 * va en las unidades del dibujo: 1 cm es `TOLERANCE_M / unidad en metros`.
 */
object CadCurves {
    /** La distancia máxima entre la curva y su polilínea: 1 cm sobre el terreno (RF-15). */
    const val TOLERANCE_M = 0.01

    private const val MIN_CIRCLE_SEGMENTS = 4
    private const val MAX_SPLINE_DEPTH = 10
    private const val TWO_PI = 2 * PI

    /** El ángulo máximo de un segmento para que su flecha sea ≤ [tolerance] en un radio [radius]. */
    private fun maxStep(radius: Double, tolerance: Double): Double = 2 * acos(1 - tolerance / radius)

    /** Un `ARC` de [startDegrees] a [endDegrees] en sentido antihorario, con sus dos extremos. Ángulos iguales, un círculo entero. */
    fun arc(cx: Double, cy: Double, radius: Double, startDegrees: Double, endDegrees: Double, tolerance: Double): Curve2 {
        val start = Math.toRadians(startDegrees)
        var sweep = Math.toRadians(endDegrees) - start
        sweep %= TWO_PI
        if (sweep <= 0.0) sweep += TWO_PI
        return sweepPoints(cx, cy, radius, start, sweep, tolerance, minSegments = 1).endsOnly()
    }

    /** Un `CIRCLE` como anillo cerrado (el último vértice repite el primero); un radio ≤ [tolerance] es un punto en su centro. */
    fun circle(cx: Double, cy: Double, radius: Double, tolerance: Double): Curve2 {
        if (radius <= tolerance) return Curve2(doubleArrayOf(cx), doubleArrayOf(cy))
        return sweepPoints(cx, cy, radius, 0.0, TWO_PI, tolerance, MIN_CIRCLE_SEGMENTS).startOnly()
    }

    // S040/RF-34: de un arco son originales sus dos extremos; de un círculo, su punto de partida (el cierre repite ese vértice).
    private fun Curve2.endsOnly() = Curve2(x, y, BooleanArray(x.size) { it == 0 || it == x.size - 1 })

    private fun Curve2.startOnly() = Curve2(x, y, BooleanArray(x.size) { it == 0 })

    private fun sweepPoints(cx: Double, cy: Double, r: Double, start: Double, sweep: Double, tolerance: Double, minSegments: Int): Curve2 {
        val n = if (r <= tolerance) 1 else maxOf(minSegments, ceil(abs(sweep) / maxStep(r, tolerance)).toInt())
        val x = DoubleArray(n + 1)
        val y = DoubleArray(n + 1)
        for (i in 0..n) {
            val a = start + sweep * i / n
            x[i] = cx + r * cos(a)
            y[i] = cy + r * sin(a)
        }
        if (abs(sweep - TWO_PI) < 1e-12) { // un círculo entero se cierra exactamente
            x[n] = x[0]
            y[n] = y[0]
        }
        return Curve2(x, y)
    }

    /**
     * Los vértices de en medio de un tramo curvo entre (x1, y1) y (x2, y2) con [bulge] = tan(θ/4): positivo, arco antihorario.
     * Los extremos no se incluyen: el tramo conserva sus vértices originales. Un bulge de 0 o un tramo sin longitud no añaden nada.
     */
    fun bulge(x1: Double, y1: Double, x2: Double, y2: Double, bulge: Double, tolerance: Double): Curve2 {
        val chord = hypot(x2 - x1, y2 - y1)
        if (bulge == 0.0 || chord == 0.0) return Curve2(DoubleArray(0), DoubleArray(0))
        val theta = 4 * atan(bulge)
        val radius = chord / (2 * sin(abs(theta) / 2))
        // Distancia con signo del punto medio de la cuerda al centro, hacia la izquierda de la cuerda.
        val offset = chord / 2 * (1 - bulge * bulge) / (2 * bulge)
        val cx = (x1 + x2) / 2 + (-(y2 - y1) / chord) * offset
        val cy = (y1 + y2) / 2 + ((x2 - x1) / chord) * offset
        val start = atan2(y1 - cy, x1 - cx)
        val n = maxOf(1, if (radius <= tolerance) 1 else ceil(abs(theta) / maxStep(radius, tolerance)).toInt())
        val x = DoubleArray(n - 1)
        val y = DoubleArray(n - 1)
        for (i in 1 until n) {
            val a = start + theta * i / n
            x[i - 1] = cx + radius * cos(a)
            y[i - 1] = cy + radius * sin(a)
        }
        return Curve2(x, y)
    }

    /**
     * Una `SPLINE` como polilínea a menos de [tolerance] de la curva (de Boor, con pesos si es racional). Cada tramo entre nudos se
     * parte por la mitad hasta que sus puntos a 1/4, 1/2 y 3/4 quedan a menos de la mitad de la tolerancia de la cuerda, con un
     * máximo de 10 niveles. Sin puntos de control, la polilínea por los puntos de ajuste.
     */
    fun spline(spline: DxfSpline, tolerance: Double): Curve3 {
        if (spline.controlPoints.isEmpty()) {
            val fit = spline.fitPoints
            val z = if (fit.any { it.z != null }) DoubleArray(fit.size) { fit[it].z ?: 0.0 } else null
            return Curve3(DoubleArray(fit.size) { fit[it].x }, DoubleArray(fit.size) { fit[it].y }, z, endsOnly(fit.size))
        }
        val evaluator = SplineEvaluator(spline)
        val points = ArrayList<DoubleArray>()
        val breaks = spline.knots.subList(spline.degree, spline.controlPoints.size + 1).distinct()
        points += evaluator.at(breaks.first())
        for (i in 0 until breaks.size - 1) {
            subdivide(
                evaluator,
                breaks[i],
                breaks[i + 1],
                points.last(),
                evaluator.at(breaks[i + 1]),
                tolerance,
                0,
                points,
            )
        }
        val hasZ = spline.controlPoints.any { it.z != null }
        return Curve3(
            DoubleArray(points.size) { points[it][0] },
            DoubleArray(points.size) { points[it][1] },
            if (hasZ) DoubleArray(points.size) { points[it][2] } else null,
            endsOnly(points.size),
        )
    }

    private fun endsOnly(n: Int) = BooleanArray(n) { it == 0 || it == n - 1 }

    private fun subdivide(
        e: SplineEvaluator,
        ta: Double,
        tb: Double,
        pa: DoubleArray,
        pb: DoubleArray,
        tol: Double,
        depth: Int,
        out: MutableList<DoubleArray>,
    ) {
        if (depth < MAX_SPLINE_DEPTH) {
            val worst = listOf(0.25, 0.5, 0.75).maxOf { f -> distanceToSegment(e.at(ta + (tb - ta) * f), pa, pb) }
            if (worst > tol / 2) {
                val tm = (ta + tb) / 2
                val pm = e.at(tm)
                subdivide(e, ta, tm, pa, pm, tol, depth + 1, out)
                subdivide(e, tm, tb, pm, pb, tol, depth + 1, out)
                return
            }
        }
        out += pb
    }

    private fun distanceToSegment(p: DoubleArray, a: DoubleArray, b: DoubleArray): Double {
        val d = DoubleArray(3) { b[it] - a[it] }
        val len2 = d[0] * d[0] + d[1] * d[1] + d[2] * d[2]
        val t = if (len2 == 0.0) 0.0 else (((p[0] - a[0]) * d[0] + (p[1] - a[1]) * d[1] + (p[2] - a[2]) * d[2]) / len2).coerceIn(0.0, 1.0)
        val dx = p[0] - (a[0] + t * d[0])
        val dy = p[1] - (a[1] + t * d[1])
        val dz = p[2] - (a[2] + t * d[2])
        return kotlin.math.sqrt(dx * dx + dy * dy + dz * dz)
    }

    /** El algoritmo de de Boor sobre coordenadas homogéneas (x·w, y·w, z·w, w). */
    private class SplineEvaluator(spline: DxfSpline) {
        private val degree = spline.degree
        private val knots = spline.knots.toDoubleArray()
        private val n = spline.controlPoints.size
        private val homogeneous = Array(n) {
            val w = spline.weights?.get(it) ?: 1.0
            val v = spline.controlPoints[it]
            doubleArrayOf(v.x * w, v.y * w, (v.z ?: 0.0) * w, w)
        }

        fun at(t: Double): DoubleArray {
            var k = degree
            while (k < n - 1 && knots[k + 1] <= t) k++
            val d = Array(degree + 1) { homogeneous[it + k - degree].copyOf() }
            for (r in 1..degree) {
                for (j in degree downTo r) {
                    val denominator = knots[j + 1 + k - r] - knots[j + k - degree]
                    val alpha = if (denominator == 0.0) 0.0 else (t - knots[j + k - degree]) / denominator
                    for (c in 0..3) d[j][c] = (1 - alpha) * d[j - 1][c] + alpha * d[j][c]
                }
            }
            val h = d[degree]
            return doubleArrayOf(h[0] / h[3], h[1] / h[3], h[2] / h[3])
        }
    }
}
