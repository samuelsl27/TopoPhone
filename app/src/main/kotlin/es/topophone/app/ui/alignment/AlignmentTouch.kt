package es.topophone.app.ui.alignment

import es.topophone.core.model.alignment.Alignment
import es.topophone.core.survey.AlignmentGeometry
import es.topophone.core.survey.VerticalProfileEval

/**
 * El eje que el usuario ha tocado en el mapa (S049/RF-18): el eje, el PK del punto tocado en él y la cota de su rasante en ese PK, si la
 * tiene; [active] es que ya es el eje activo.
 */
data class AlignmentTouch(val alignment: Alignment, val chainage: Double, val elevationM: Double?, val active: Boolean)

/** El eje visible más cercano a una posición, a menos de la tolerancia de un dedo (S049/RF-18, plan A4). */
object AlignmentHitTest {
    /**
     * El eje de [alignments] visible más cercano a (Este, Norte) a menos de [toleranceM], con el PK de su pie de la perpendicular y la cota
     * de su rasante ahí; null si ninguno está lo bastante cerca. Un eje oculto no se toca (RF-19).
     */
    fun nearest(alignments: List<Alignment>, activeId: String?, easting: Double, northing: Double, toleranceM: Double): AlignmentTouch? {
        var best: AlignmentTouch? = null
        var bestDistance = Double.MAX_VALUE
        for (alignment in alignments) {
            if (!alignment.visible) continue
            val located = AlignmentGeometry(alignment).locate(easting, northing) ?: continue
            if (located.distance > toleranceM || located.distance >= bestDistance) continue
            bestDistance = located.distance
            val elevation = alignment.profile?.let { VerticalProfileEval(it).at(located.chainage)?.elevation }
            best = AlignmentTouch(alignment, located.chainage, elevation, alignment.id == activeId)
        }
        return best
    }
}
