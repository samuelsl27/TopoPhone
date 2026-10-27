package es.topophone.core.survey

import es.topophone.core.model.alignment.AlignmentElement
import es.topophone.core.model.alignment.VerticalCurve
import es.topophone.core.model.alignment.VerticalVertex
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.tan

/** Un vértice del eje escrito a mano (S049/RF-8): su Este y Norte, el radio de su curva (0: sin curva) y la longitud de sus clotoides. */
data class PiVertex(val easting: Double, val northing: Double, val radius: Double = 0.0, val spiralLength: Double = 0.0)

/** Por qué no se puede construir un eje con unos vértices; [vertex] es el número del vértice, desde 1. */
sealed interface PiIssue {
    data object TooFewVertices : PiIssue

    data class CoincidentVertices(val vertex: Int) : PiIssue

    data class InvalidParameter(val vertex: Int) : PiIssue

    /** Las clotoides son tan largas que no queda ángulo para el arco. */
    data class SpiralTooLong(val vertex: Int) : PiIssue

    /** Las tangentes de la curva se solapan con las de un vértice vecino o salen del segmento. */
    data class TangentsOverlap(val vertex: Int) : PiIssue
}

sealed interface PiResult {
    data class Built(val elements: List<AlignmentElement>) : PiResult

    data class Failed(val issue: PiIssue) : PiResult
}

/**
 * Del polígono de vértices con radio y clotoides simétricas a los tramos del eje (S049/RF-8). En cada vértice con radio R y clotoides
 * de longitud Ls: tangente `T = (R + p)·tan(Δ/2) + k`, con el retranqueo `p` y la abscisa `k` de la clotoide, y entre las dos
 * clotoides un arco que gira `Δ − Ls/R`. Sin clotoides es un arco simple de tangente `R·tan(Δ/2)`. Los tramos se encadenan desde el
 * primer vértice, así que cada uno empieza donde acaba el anterior.
 */
object PiAlignmentBuilder {
    private const val NO_DEFLECTION = 1e-9

    fun build(vertices: List<PiVertex>, startChainage: Double = 0.0): PiResult {
        if (vertices.size < 2) return PiResult.Failed(PiIssue.TooFewVertices)
        val azimuths = ArrayList<Double>()
        val lengths = ArrayList<Double>()
        for (i in 0 until vertices.size - 1) {
            val dE = vertices[i + 1].easting - vertices[i].easting
            val dN = vertices[i + 1].northing - vertices[i].northing
            val d = hypot(dE, dN)
            if (d == 0.0) return PiResult.Failed(PiIssue.CoincidentVertices(i + 2))
            azimuths += atan2(dE, dN)
            lengths += d
        }
        val curves = arrayOfNulls<Curve>(vertices.size)
        val tangents = DoubleArray(vertices.size)
        for (i in 1 until vertices.size - 1) {
            val v = vertices[i]
            if (v.radius < 0.0 || v.spiralLength < 0.0 || !v.radius.isFinite() || !v.spiralLength.isFinite()) {
                return PiResult.Failed(PiIssue.InvalidParameter(i + 1))
            }
            if (v.radius == 0.0) continue
            val delta = normalize(azimuths[i - 1] - azimuths[i]) // positivo a la izquierda
            if (abs(delta) < NO_DEFLECTION) continue
            val angle = abs(delta)
            val thetaS = v.spiralLength / (2 * v.radius)
            if (angle - 2 * thetaS <= NO_DEFLECTION && v.spiralLength > 0.0) return PiResult.Failed(PiIssue.SpiralTooLong(i + 1))
            var shift = 0.0
            var abscissa = 0.0
            if (v.spiralLength > 0.0) {
                val spiral = ElementGeometry.stateAt(
                    AlignmentElement.clothoid(0.0, v.spiralLength, 0.0, 0.0, PI / 2, 0.0, 1.0 / v.radius),
                    v.spiralLength,
                )
                shift = spiral.northing - v.radius * (1 - cos(thetaS))
                abscissa = spiral.easting - v.radius * sin(thetaS)
            }
            tangents[i] = (v.radius + shift) * tan(angle / 2) + abscissa
            curves[i] = Curve(if (delta > 0) 1.0 else -1.0, angle, v.radius, v.spiralLength, thetaS)
        }
        for (i in 0 until vertices.size - 1) {
            if (tangents[i] + tangents[i + 1] > lengths[i] + 1e-9) {
                return PiResult.Failed(PiIssue.TangentsOverlap(if (tangents[i + 1] > 0.0 && tangents[i] == 0.0) i + 2 else i + 1))
            }
        }
        return PiResult.Built(chain(vertices, azimuths, lengths, tangents, curves, startChainage))
    }

    private class Curve(val sign: Double, val angle: Double, val radius: Double, val spiral: Double, val thetaS: Double)

    private fun chain(
        vertices: List<PiVertex>,
        azimuths: List<Double>,
        lengths: List<Double>,
        tangents: DoubleArray,
        curves: Array<Curve?>,
        startChainage: Double,
    ): List<AlignmentElement> {
        val out = ArrayList<AlignmentElement>()
        var east = vertices[0].easting
        var north = vertices[0].northing
        var chainage = startChainage

        fun advance(element: AlignmentElement) {
            out += element
            val end = ElementGeometry.stateAt(element, element.length)
            east = end.easting
            north = end.northing
            chainage += element.length
        }

        for (seg in 0 until vertices.size - 1) {
            val lineLength = lengths[seg] - tangents[seg] - tangents[seg + 1]
            if (lineLength > NO_DEFLECTION) advance(AlignmentElement.line(chainage, lineLength, east, north, azimuths[seg]))
            val curve = curves[seg + 1] ?: continue
            val k = curve.sign / curve.radius
            var azimuth = azimuths[seg]
            if (curve.spiral > 0.0) {
                val spiral = AlignmentElement.clothoid(chainage, curve.spiral, east, north, azimuth, 0.0, k)
                advance(spiral)
                azimuth -= curve.sign * curve.thetaS
            }
            val arcAngle = curve.angle - 2 * curve.thetaS
            advance(AlignmentElement.arc(chainage, curve.radius * arcAngle, east, north, azimuth, k))
            azimuth -= curve.sign * arcAngle
            if (curve.spiral > 0.0) advance(AlignmentElement.clothoid(chainage, curve.spiral, east, north, azimuth, k, 0.0))
        }
        return out
    }

    /** El ángulo en (−π, π]. */
    private fun normalize(angle: Double): Double {
        var a = angle % (2 * PI)
        if (a > PI) a -= 2 * PI
        if (a <= -PI) a += 2 * PI
        return a
    }
}

/** Un vértice de la rasante escrito a mano o leído de un CSV (S049/RF-8, RF-9): PK, cota y, si lo trae, el Kv del acuerdo. */
data class KvVertex(val chainage: Double, val elevation: Double, val kv: Double? = null)

/**
 * La rasante de unos vértices con su Kv (S049/RF-8, RF-9, decisión D4). Un acuerdo parabólico simétrico mide `Kv · |Δi|` metros, con Δi el
 * cambio de pendiente (m/m) en el vértice. Sin Kv, o con un Kv que no es positivo, el vértice no lleva acuerdo. Los vértices se ordenan por PK;
 * con PK repetidos o en un extremo el acuerdo se deja con su Kv como longitud para que `ProfileValidator` lo rechace con su motivo.
 */
object ProfileKvBuilder {
    fun build(vertices: List<KvVertex>): List<VerticalVertex> {
        val sorted = vertices.sortedBy { it.chainage }
        return sorted.mapIndexed { i, v ->
            val kv = v.kv
            val curve = if (kv == null || !(kv > 0.0)) {
                VerticalCurve.None
            } else if (i == 0 || i == sorted.lastIndex) {
                VerticalCurve.Parabola(kv)
            } else {
                val before = sorted[i - 1]
                val after = sorted[i + 1]
                val run1 = v.chainage - before.chainage
                val run2 = after.chainage - v.chainage
                if (run1 == 0.0 || run2 == 0.0) {
                    VerticalCurve.Parabola(kv)
                } else {
                    val change = abs((after.elevation - v.elevation) / run2 - (v.elevation - before.elevation) / run1)
                    if (change == 0.0) VerticalCurve.None else VerticalCurve.Parabola(kv * change)
                }
            }
            VerticalVertex(v.chainage, v.elevation, curve)
        }
    }
}
