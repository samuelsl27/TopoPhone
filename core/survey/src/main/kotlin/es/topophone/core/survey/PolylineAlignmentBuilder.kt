package es.topophone.core.survey

import es.topophone.core.model.alignment.AlignmentElement
import es.topophone.core.model.alignment.AlignmentIssue
import es.topophone.core.model.alignment.VerticalVertex
import es.topophone.core.model.cad.CadEntity
import es.topophone.core.model.cad.CadEntityKind
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot

sealed interface PolylineResult {
    /** [profile] son los vértices de la rasante con las cotas de la polilínea, sin acuerdos; null si no traía cotas (RF-7). */
    class Built(val elements: List<AlignmentElement>, val profile: List<VerticalVertex>?) : PolylineResult

    class Failed(val issue: AlignmentIssue) : PolylineResult
}

/**
 * De una polilínea de un dibujo a un eje (S049/RF-6, RF-7, plan A5, decisión D1). Entre dos vértices originales (`isOriginal`) sin vértices
 * de por medio hay una recta. Si hay vértices de la aproximación de una curva, S040 los dejó exactamente sobre el arco: el círculo
 * por el inicio, uno de los de en medio y el final da el arco, y se comprueba que todos los de en medio caen a menos de 1 cm de él. Si no
 * (una spline), el eje no se crea y se dice en qué vértice original empieza esa curva.
 */
object PolylineAlignmentBuilder {
    /** Lo que un vértice intermedio puede apartarse del círculo para considerar que el trozo es un arco: lo que S040 aproxima, 1 cm. */
    const val ARC_FIT_TOLERANCE_M = 0.01

    /** Por debajo de esto la flecha de un trozo curvo es indistinguible de una recta. */
    private const val FLAT_M = 1e-4

    fun build(entity: CadEntity, nearEasting: Double, nearNorthing: Double, startChainage: Double = 0.0): PolylineResult {
        if (entity.kind != CadEntityKind.POLYLINE && entity.kind != CadEntityKind.LINE) {
            return PolylineResult.Failed(AlignmentIssue.NotAPolyline)
        }
        val n = entity.vertexCount
        val first = hypot(entity.easting[0] - nearEasting, entity.northing[0] - nearNorthing)
        val last = hypot(entity.easting[n - 1] - nearEasting, entity.northing[n - 1] - nearNorthing)
        val order = if (last < first) (n - 1 downTo 0).toList() else (0 until n).toList()
        val e = DoubleArray(n) { entity.easting[order[it]] }
        val nn = DoubleArray(n) { entity.northing[order[it]] }
        val z = entity.height?.let { h -> DoubleArray(n) { h[order[it]] } }
        val original = BooleanArray(n) { entity.isOriginal(order[it]) }
        original[0] = true
        original[n - 1] = true

        val elements = ArrayList<AlignmentElement>()
        val profile = if (z != null) ArrayList<VerticalVertex>() else null
        var chainage = startChainage
        var from = 0
        var originalNumber = 0
        profile?.add(VerticalVertex(chainage, z!![0]))
        while (from < n - 1) {
            var to = from + 1
            while (!original[to]) to++
            originalNumber++
            val element = if (to == from + 1) {
                line(e, nn, from, to, chainage)
            } else {
                arc(e, nn, from, to, chainage) ?: return PolylineResult.Failed(AlignmentIssue.NonCircularCurve(originalNumber))
            }
            if (element != null) {
                elements += element
                chainage += element.length
            }
            profile?.add(VerticalVertex(chainage, z!![to]))
            from = to
        }
        if (elements.isEmpty()) return PolylineResult.Failed(AlignmentIssue.NotAPolyline)
        return PolylineResult.Built(elements, profile?.let { dropRepeats(it) })
    }

    /** Un vértice repetido (un tramo sin longitud) no añade un vértice de rasante en el mismo PK. */
    private fun dropRepeats(vertices: List<VerticalVertex>): List<VerticalVertex> {
        val out = ArrayList<VerticalVertex>()
        for (v in vertices) if (out.isEmpty() || v.chainage > out.last().chainage) out += v
        return out
    }

    private fun line(e: DoubleArray, n: DoubleArray, from: Int, to: Int, chainage: Double): AlignmentElement? {
        val dE = e[to] - e[from]
        val dN = n[to] - n[from]
        val length = hypot(dE, dN)
        if (length == 0.0) return null
        return AlignmentElement.line(chainage, length, e[from], n[from], positive(atan2(dE, dN)))
    }

    /** El arco que pasa por los vértices de [from] a [to]; null si no son de un arco, o una recta si la flecha es despreciable. */
    private fun arc(e: DoubleArray, n: DoubleArray, from: Int, to: Int, chainage: Double): AlignmentElement? {
        val mid = (from + to) / 2
        val chord = hypot(e[to] - e[from], n[to] - n[from])
        if (chord == 0.0) return null
        // Distancia de los intermedios a la cuerda: sin flecha apreciable es una recta.
        val sagitta = (from + 1 until to).maxOf {
            abs((e[to] - e[from]) * (n[from] - n[it]) - (e[from] - e[it]) * (n[to] - n[from])) / chord
        }
        if (sagitta < FLAT_M) return line(e, n, from, to, chainage)
        val center = circumcenter(e[from], n[from], e[mid], n[mid], e[to], n[to]) ?: return null
        val radius = hypot(e[from] - center.first, n[from] - center.second)
        for (i in from + 1 until to) {
            if (abs(hypot(e[i] - center.first, n[i] - center.second) - radius) > ARC_FIT_TOLERANCE_M) return null
        }
        if (abs(hypot(e[to] - center.first, n[to] - center.second) - radius) > ARC_FIT_TOLERANCE_M) return null
        val counterClockwise = (e[mid] - e[from]) * (n[to] - n[mid]) - (n[mid] - n[from]) * (e[to] - e[mid]) > 0
        val startAngle = atan2(n[from] - center.second, e[from] - center.first)
        val endAngle = atan2(n[to] - center.second, e[to] - center.first)
        var sweep = if (counterClockwise) endAngle - startAngle else startAngle - endAngle
        if (sweep <= 0) sweep += 2 * PI
        val vx = e[from] - center.first
        val vy = n[from] - center.second
        val tangentE = if (counterClockwise) -vy else vy
        val tangentN = if (counterClockwise) vx else -vx
        val curvature = (if (counterClockwise) 1.0 else -1.0) / radius
        return AlignmentElement.arc(chainage, radius * sweep, e[from], n[from], positive(atan2(tangentE, tangentN)), curvature)
    }

    /** El azimut en [0, 2π). */
    private fun positive(azimuth: Double): Double = if (azimuth < 0.0) azimuth + 2 * PI else azimuth

    private fun circumcenter(ax: Double, ay: Double, bx: Double, by: Double, cx: Double, cy: Double): Pair<Double, Double>? {
        // Respecto a (ax, ay) para no perder cifras con coordenadas UTM de millones de metros.
        val bX = bx - ax
        val bY = by - ay
        val cX = cx - ax
        val cY = cy - ay
        val dd = 2 * (bX * cY - bY * cX)
        if (abs(dd) < 1e-12) return null
        val b2 = bX * bX + bY * bY
        val c2 = cX * cX + cY * cY
        return ax + (cY * b2 - bY * c2) / dd to ay + (bX * c2 - cX * b2) / dd
    }
}
