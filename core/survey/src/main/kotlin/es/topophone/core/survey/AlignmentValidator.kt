package es.topophone.core.survey

import es.topophone.core.model.alignment.AlignmentElement
import es.topophone.core.model.alignment.AlignmentIssue
import es.topophone.core.model.alignment.ProfileIssue
import es.topophone.core.model.alignment.VerticalCurve
import es.topophone.core.model.alignment.VerticalVertex
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.tan

/** Comprueba que los tramos de un eje se encadenan (S049/RF-5, plan A2). */
object AlignmentValidator {
    /** La separación máxima entre el final de un tramo y el principio del siguiente: 1 cm (RF-5, suposición de la spec). */
    const val MAX_GAP_M = 0.01

    /**
     * Lo mismo, y además que el final de cada tramo calculado con su geometría quede a menos de 1 cm del que declara el fichero
     * (RF-5): [declaredEnds] son los finales (Este, Norte) en metros, uno por tramo, o null donde el fichero no lo trae.
     */
    fun validate(elements: List<AlignmentElement>, declaredEnds: List<Pair<Double, Double>?>): AlignmentIssue? {
        for (i in elements.indices) {
            val declared = declaredEnds.getOrNull(i) ?: continue
            val end = ElementGeometry.stateAt(elements[i], elements[i].length)
            val difference = hypot(end.easting - declared.first, end.northing - declared.second)
            if (difference > MAX_GAP_M) return AlignmentIssue.InconsistentElement(element = i + 1, differenceM = difference)
            if (i < elements.size - 1) {
                val next = elements[i + 1]
                val gap = hypot(end.easting - next.startEasting, end.northing - next.startNorthing)
                if (gap > MAX_GAP_M) return AlignmentIssue.Gap(element = i + 2, gapM = gap)
            }
        }
        return validate(elements)
    }

    /** El primer hueco entre dos tramos, con el número (desde 1) del segundo; null si el eje se encadena. */
    fun validate(elements: List<AlignmentElement>): AlignmentIssue? {
        for (i in 0 until elements.size - 1) {
            val a = elements[i]
            val end = ElementGeometry.stateAt(a, a.length)
            val next = elements[i + 1]
            val gap = hypot(end.easting - next.startEasting, end.northing - next.startNorthing)
            if (gap > MAX_GAP_M) return AlignmentIssue.Gap(element = i + 2, gapM = gap)
        }
        return null
    }
}

/** Comprueba una rasante antes de importarla (S049/RF-10, plan A3). */
object ProfileValidator {
    private const val FIT_TOLERANCE_M = 1e-9

    /**
     * El primer motivo por el que la rasante de [vertices] no vale para un eje que va del PK [axisStart] al [axisEnd], o null. Los
     * vértices se examinan por PK creciente; el número del vértice es el de su posición en esa lista, desde 1.
     */
    fun validate(vertices: List<VerticalVertex>, axisStart: Double, axisEnd: Double): ProfileIssue? {
        val sorted = vertices.sortedBy { it.chainage }
        for ((i, v) in sorted.withIndex()) {
            if (i > 0 && v.chainage == sorted[i - 1].chainage) return ProfileIssue.RepeatedChainage(i + 1, v.chainage)
        }
        for ((i, v) in sorted.withIndex()) {
            if (v.chainage < axisStart - FIT_TOLERANCE_M || v.chainage > axisEnd + FIT_TOLERANCE_M) {
                return ProfileIssue.OutsideAxis(i + 1, v.chainage)
            }
        }
        val spans = sorted.indices.map { span(sorted, it) }
        for ((i, v) in sorted.withIndex()) {
            val own = spans[i] ?: continue
            // Entre su vértice vecino y, si el vecino tiene acuerdo, el final o el principio de ese acuerdo.
            val left = spans.getOrNull(i - 1)?.takeIf { it.isValid }?.end ?: sorted.getOrNull(i - 1)?.chainage
            val right = spans.getOrNull(i + 1)?.takeIf { it.isValid }?.start ?: sorted.getOrNull(i + 1)?.chainage
            val fits = own.isValid &&
                left != null &&
                right != null &&
                own.start >= left - FIT_TOLERANCE_M &&
                own.end <= right + FIT_TOLERANCE_M
            if (!fits) return ProfileIssue.CurveDoesNotFit(i + 1, v.chainage)
        }
        return null
    }

    private class Span(val start: Double, val end: Double) {
        val isValid: Boolean get() = start.isFinite() && end.isFinite() && end > start
    }

    /** El tramo horizontal que ocupa el acuerdo de un vértice; null si no tiene acuerdo. */
    private fun span(vertices: List<VerticalVertex>, i: Int): Span? {
        val v = vertices[i]
        val curve = v.curve
        if (curve == VerticalCurve.None) return null
        // Un acuerdo en un extremo no tiene pendiente a un lado: no cabe.
        if (i == 0 || i == vertices.lastIndex) return Span(Double.NaN, Double.NaN)
        return when (curve) {
            VerticalCurve.None -> null
            is VerticalCurve.Parabola -> Span(v.chainage - curve.length / 2, v.chainage + curve.length / 2)
            is VerticalCurve.Circular -> {
                val a1 = atan(grade(vertices, i - 1))
                val a2 = atan(grade(vertices, i))
                val tangent = curve.radius * tan(abs(a2 - a1) / 2)
                Span(v.chainage - tangent * cos(a1), v.chainage + tangent * cos(a2))
            }
        }
    }

    private fun grade(vertices: List<VerticalVertex>, i: Int) =
        (vertices[i + 1].elevation - vertices[i].elevation) / (vertices[i + 1].chainage - vertices[i].chainage)
}
