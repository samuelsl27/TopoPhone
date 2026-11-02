package es.topophone.core.survey

import es.topophone.core.model.alignment.slope.SlopeIssue
import es.topophone.core.model.alignment.slope.SlopeRule

/** Comprueba un talud antes de guardarlo (S051/RF-3). Devuelve el primer motivo o null. */
object SlopeValidator {
    /** Holgura para un PK en el extremo del eje, por redondeo del fichero. */
    private const val CHAINAGE_TOLERANCE_M = 1e-6

    /** [others] son los taludes ya guardados del eje: un lado no puede tener dos rangos que se pisen. */
    fun validate(rule: SlopeRule, axisStart: Double, axisEnd: Double, others: List<SlopeRule>): SlopeIssue? {
        for (ratio in listOf(rule.cutRatio, rule.fillRatio)) {
            if (!ratio.isFinite() || ratio <= 0.0) return SlopeIssue.InvalidRatio(rule.side, ratio)
        }
        for (pk in listOf(rule.fromChainage, rule.toChainage)) {
            if (!pk.isFinite() || pk < axisStart - CHAINAGE_TOLERANCE_M || pk > axisEnd + CHAINAGE_TOLERANCE_M) {
                return SlopeIssue.ChainageOutsideAxis(pk)
            }
        }
        if (rule.toChainage < rule.fromChainage) return SlopeIssue.ReversedRange(rule.fromChainage, rule.toChainage)
        rule.manualEdge?.let { edge ->
            if (!edge.offsetM.isFinite() || !edge.heightM.isFinite() || edge.offsetM * rule.side.sign <= 0.0) {
                return SlopeIssue.InvalidManualEdge
            }
        }
        if (others.any { it.side == rule.side && rule.fromChainage < it.toChainage && it.fromChainage < rule.toChainage }) {
            return SlopeIssue.OverlappingRanges(rule.side, rule.fromChainage, rule.toChainage)
        }
        return null
    }
}
