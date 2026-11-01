package es.topophone.core.survey

import es.topophone.core.model.alignment.section.CrossSection
import es.topophone.core.model.alignment.section.HeightKind
import es.topophone.core.model.alignment.section.SectionCode
import es.topophone.core.model.alignment.section.SectionKind
import es.topophone.core.model.alignment.section.SectionPoint
import es.topophone.core.model.alignment.section.SectionTemplate
import es.topophone.core.model.alignment.section.Superelevation
import es.topophone.core.model.alignment.section.TemplateSegment

/** De una sección tipo y el peralte a los puntos de una sección en un PK (S050/RF-2, RF-9; plan A1). */
object SectionBuilder {
    /**
     * La sección de diseño, relativa a la rasante, de [template] en [chainage]. Los tramos que siguen el peralte toman la pendiente
     * de la tabla: lineal entre filas; con una sola fila, la suya en todo el eje; con dos o más, fuera de la primera y de la última
     * vale la propia del tramo (plan D3). La sección gira alrededor del punto de rasante, que queda a diferencia de cota cero.
     */
    fun build(template: SectionTemplate, superelevation: Superelevation?, chainage: Double): CrossSection {
        val slopes = superelevation?.let { slopesAt(it, chainage) }
        val points = ArrayList<SectionPoint>(template.left.size + template.right.size + 1)
        points += SectionPoint(SectionCode.AXIS, 0.0, 0.0)
        points += outward(template.left, -1, slopes?.first)
        points += outward(template.right, 1, slopes?.second)
        points.sortBy { it.offsetM }
        val pivot = points.firstOrNull { it.code == template.gradeCode }?.heightM ?: 0.0
        return CrossSection(SectionKind.DESIGN, chainage, HeightKind.RELATIVE, points.map { it.copy(heightM = it.heightM - pivot) })
    }

    private fun outward(segments: List<TemplateSegment>, side: Int, followed: Double?): List<SectionPoint> {
        var offset = 0.0
        var height = 0.0
        return segments.map { s ->
            val slope = if (s.followsSuperelevation && followed != null) followed else s.slope
            offset += s.widthM * side
            height -= s.widthM * slope
            SectionPoint(s.code, offset, height)
        }
    }

    /** Pendientes (izquierda, derecha) del peralte en [chainage], o null si no hay ninguna que aplicar (plan D3). */
    private fun slopesAt(superelevation: Superelevation, chainage: Double): Pair<Double, Double>? {
        val rows = superelevation.rows.sortedBy { it.chainage }
        if (rows.isEmpty()) return null
        if (rows.size == 1) return rows[0].leftSlope to rows[0].rightSlope
        if (chainage < rows.first().chainage || chainage > rows.last().chainage) return null
        for (i in 0 until rows.size - 1) {
            val a = rows[i]
            val b = rows[i + 1]
            if (chainage <= b.chainage) {
                val t = (chainage - a.chainage) / (b.chainage - a.chainage)
                return (a.leftSlope + (b.leftSlope - a.leftSlope) * t) to (a.rightSlope + (b.rightSlope - a.rightSlope) * t)
            }
        }
        return null
    }
}
