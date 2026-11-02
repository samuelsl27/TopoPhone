package es.topophone.core.survey

import es.topophone.core.model.alignment.AlignmentElement
import es.topophone.core.model.alignment.VerticalProfile
import es.topophone.core.model.alignment.VerticalVertex
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot

/** Un punto medido de una toma de eje (S052/RF-5): su Este y Norte, su cota si la tiene y el radio de la curva que se pide en él (0: sin curva). */
data class FieldVertex(val easting: Double, val northing: Double, val heightM: Double? = null, val radiusM: Double = 0.0)

/** Por qué no se puede crear un eje con los puntos de una toma; el vértice se cuenta desde 1 (S052/RF-6). */
sealed interface FieldAxisIssue {
    data object TooFewPoints : FieldAxisIssue

    /** El punto [vertex] está a menos de 1 cm del anterior. */
    data class CoincidentPoints(val vertex: Int) : FieldAxisIssue

    data class InvalidRadius(val vertex: Int) : FieldAxisIssue

    /** El radio no cabe entre el vértice [vertex] y su vecino: las tangentes se solapan. */
    data class RadiusDoesNotFit(val vertex: Int) : FieldAxisIssue
}

sealed interface FieldAxisResult {
    /** [profile] es la rasante con las cotas de los puntos, solo si se pidió y todos tienen cota (RF-7). */
    data class Built(val elements: List<AlignmentElement>, val profile: VerticalProfile?) : FieldAxisResult

    data class Failed(val issue: FieldAxisIssue) : FieldAxisResult
}

/** La longitud en planta de la poligonal que une los puntos en orden (S052/RF-3). */
object PolylineLength {
    fun of(vertices: List<FieldVertex>): Double =
        vertices.zipWithNext().sumOf { (a, b) -> hypot(b.easting - a.easting, b.northing - a.northing) }
}

/**
 * Del eje tomado a los tramos (S052/RF-5 a RF-7): rectas por los puntos en el orden de medida y un arco en cada vértice interior con
 * radio. Es el constructor por vértices de S049/RF-8 sin clotoides ([PiAlignmentBuilder]); a él se le añaden el rechazo de puntos a menos
 * de 1 cm y la rasante opcional, cuyos vértices llevan el PK del pie de la perpendicular desde cada punto sobre el eje: el punto medio
 * del arco en un vértice con curva y el final de la recta anterior en el resto.
 */
object FieldAxisBuilder {
    /** Dos puntos consecutivos más cerca que esto son el mismo (RF-6). */
    const val MIN_SPACING_M = 0.01

    private const val NO_DEFLECTION = 1e-9

    fun build(vertices: List<FieldVertex>, startChainage: Double = 0.0, withGrade: Boolean = false): FieldAxisResult {
        if (vertices.size < 2) return FieldAxisResult.Failed(FieldAxisIssue.TooFewPoints)
        for (i in 1 until vertices.size) {
            val gap = hypot(vertices[i].easting - vertices[i - 1].easting, vertices[i].northing - vertices[i - 1].northing)
            if (gap < MIN_SPACING_M) return FieldAxisResult.Failed(FieldAxisIssue.CoincidentPoints(i + 1))
        }
        val pi = vertices.map { PiVertex(it.easting, it.northing, it.radiusM) }
        val elements = when (val result = PiAlignmentBuilder.build(pi, startChainage)) {
            is PiResult.Built -> result.elements
            is PiResult.Failed -> return FieldAxisResult.Failed(
                when (val issue = result.issue) {
                    PiIssue.TooFewVertices -> FieldAxisIssue.TooFewPoints
                    is PiIssue.CoincidentVertices -> FieldAxisIssue.CoincidentPoints(issue.vertex)
                    is PiIssue.InvalidParameter -> FieldAxisIssue.InvalidRadius(issue.vertex)
                    is PiIssue.SpiralTooLong -> FieldAxisIssue.InvalidRadius(issue.vertex)
                    is PiIssue.TangentsOverlap -> FieldAxisIssue.RadiusDoesNotFit(issue.vertex)
                },
            )
        }
        val profile = if (withGrade && vertices.all { it.heightM != null }) grade(vertices, elements, startChainage) else null
        return FieldAxisResult.Built(elements, profile)
    }

    private fun grade(vertices: List<FieldVertex>, elements: List<AlignmentElement>, startChainage: Double): VerticalProfile? {
        val azimuths = vertices.zipWithNext { a, b -> atan2(b.easting - a.easting, b.northing - a.northing) }
        val out = ArrayList<VerticalVertex>()
        out += VerticalVertex(startChainage, vertices.first().heightM!!)
        var next = 0 // el siguiente tramo por recorrer
        var chainage = startChainage
        for (i in 1 until vertices.size) {
            val bends = i < vertices.size - 1 && abs(deflection(azimuths[i - 1], azimuths[i])) >= NO_DEFLECTION
            val curved = bends && vertices[i].radiusM > 0.0
            // La recta que llega al vértice, si la hay (dos curvas pueden ir seguidas sin recta entre ellas).
            if (next < elements.size && elements[next].startCurvature == 0.0) {
                chainage = elements[next].endChainage
                next++
            }
            if (curved) {
                val arc = elements[next]
                out += VerticalVertex(arc.startChainage + arc.length / 2, vertices[i].heightM!!)
                chainage = arc.endChainage
                next++
            } else {
                out += VerticalVertex(chainage, vertices[i].heightM!!)
            }
        }
        return if (out.zipWithNext().all { (a, b) -> b.chainage > a.chainage }) VerticalProfile(out) else null
    }

    private fun deflection(from: Double, to: Double): Double {
        var a = (from - to) % (2 * PI)
        if (a > PI) a -= 2 * PI
        if (a <= -PI) a += 2 * PI
        return a
    }
}
