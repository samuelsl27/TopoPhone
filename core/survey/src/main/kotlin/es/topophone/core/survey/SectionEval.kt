package es.topophone.core.survey

import es.topophone.core.model.alignment.section.CrossSection
import es.topophone.core.model.alignment.section.HeightKind
import es.topophone.core.model.alignment.section.SectionPoint

/** Un punto de la sección en un PK, o por qué no se puede calcular (S050/RF-9 a RF-11). */
sealed interface SectionPointResult {
    /**
     * @property code el código del punto, o null si se pidió por desplazamiento.
     * @property elevationM la cota; null si la sección es relativa y el eje no tiene rasante en ese PK (RF-11).
     * @property slope la pendiente transversal, positiva si el terreno baja al alejarse del eje (plan D2); null si la sección tiene un
     *   solo punto.
     * @property axisPoint el punto del eje en planta con su azimut, para la guía del replanteo.
     */
    data class Value(
        val code: String?,
        val chainageM: Double,
        val offsetM: Double,
        val easting: Double,
        val northing: Double,
        val elevationM: Double?,
        val slope: Double?,
        val axisPoint: AxisPoint,
    ) : SectionPointResult

    /** El código no está en la sección de ese PK. */
    data object NoCode : SectionPointResult

    /** El desplazamiento queda más allá de los puntos exteriores de la sección: "Fuera de la sección" sin cota (RF-10). */
    data object OutsideSection : SectionPointResult

    /** El PK queda fuera del eje, así que no hay planta. */
    data object OutsideAxis : SectionPointResult
}

/**
 * Calcula un punto de una sección en su PK (S050/RF-9 a RF-11; plan A3): la planta con el desplazamiento de la sección sobre el eje, la
 * cota (la de la rasante más la diferencia, o la absoluta de la sección) y la pendiente transversal.
 */
object SectionEval {
    fun byCode(section: CrossSection, geometry: AlignmentGeometry, profile: VerticalProfileEval?, code: String): SectionPointResult {
        val index = section.points.indexOfFirst { it.code == code }
        if (index < 0) return SectionPointResult.NoCode
        val p = section.points[index]
        return value(section, geometry, profile, code, p.offsetM, p.heightM, slopeOfPoint(section.points, index))
    }

    fun byOffset(section: CrossSection, geometry: AlignmentGeometry, profile: VerticalProfileEval?, offsetM: Double): SectionPointResult {
        val points = section.points
        if (points.isEmpty() ||
            offsetM < points.first().offsetM ||
            offsetM > points.last().offsetM
        ) {
            return SectionPointResult.OutsideSection
        }
        if (points.size == 1) return value(section, geometry, profile, null, offsetM, points[0].heightM, null)
        val i = (0 until points.size - 1).first { offsetM <= points[it + 1].offsetM }
        val a = points[i]
        val b = points[i + 1]
        val height = a.heightM + (b.heightM - a.heightM) * (offsetM - a.offsetM) / (b.offsetM - a.offsetM)
        return value(section, geometry, profile, null, offsetM, height, slopeOfSegment(a, b, offsetM))
    }

    private fun value(
        section: CrossSection,
        geometry: AlignmentGeometry,
        profile: VerticalProfileEval?,
        code: String?,
        offsetM: Double,
        heightM: Double,
        slope: Double?,
    ): SectionPointResult {
        val axis = geometry.pointAt(section.chainage, offsetM) ?: return SectionPointResult.OutsideAxis
        val elevation = when (section.heightKind) {
            HeightKind.ABSOLUTE -> heightM
            HeightKind.RELATIVE -> profile?.at(section.chainage)?.elevation?.plus(heightM)
        }
        return SectionPointResult.Value(code, section.chainage, offsetM, axis.easting, axis.northing, elevation, slope, axis)
    }

    /** Pendiente del tramo entre el punto y su vecino hacia el eje; en el eje (o a su derecha sin vecino interior), la del tramo derecho. */
    private fun slopeOfPoint(points: List<SectionPoint>, index: Int): Double? {
        if (points.size < 2) return null
        val p = points[index]
        return when {
            p.offsetM < 0.0 -> slopeOfSegment(p, points[index + 1], p.offsetM)
            index + 1 < points.size && p.offsetM == 0.0 -> slopeOfSegment(p, points[index + 1], 0.0)
            index > 0 -> slopeOfSegment(points[index - 1], p, p.offsetM)
            else -> slopeOfSegment(p, points[index + 1], 0.0)
        }
    }

    /** Positiva si baja al alejarse del eje; en un tramo que cruza el eje manda el lado de [at] (el derecho si es 0). */
    private fun slopeOfSegment(a: SectionPoint, b: SectionPoint, at: Double): Double {
        val rise = (b.heightM - a.heightM) / (b.offsetM - a.offsetM)
        return if (at < 0.0) rise else -rise
    }
}
