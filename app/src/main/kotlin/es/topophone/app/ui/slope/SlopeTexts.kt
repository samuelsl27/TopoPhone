package es.topophone.app.ui.slope

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import es.topophone.app.R
import es.topophone.app.ui.receiver.uiLocale
import es.topophone.core.model.alignment.ChainageFormat
import es.topophone.core.model.alignment.slope.SlopeIssue
import es.topophone.core.model.alignment.slope.SlopeSide
import es.topophone.core.model.project.LinearUnit
import java.text.NumberFormat
import java.util.Locale

/** La relación de un talud, «1,5 : 1» con la coma o el punto del idioma (S051/RNF-T9). */
@Composable
fun ratioLabel(ratio: Double): String = stringResource(R.string.slope_ratio, ratioText(ratio, uiLocale()))

internal fun ratioText(ratio: Double, locale: Locale): String =
    NumberFormat.getNumberInstance(locale).apply { maximumFractionDigits = 3 }.format(ratio)

/** El lado con la palabra, nunca solo con el signo (S051/RNF-T8). */
@Composable
fun sideLabel(side: SlopeSide): String = stringResource(if (side == SlopeSide.LEFT) R.string.slope_side_left else R.string.slope_side_right)

/** El motivo por el que no se guarda un talud (S051/RF-3). */
@Composable
fun slopeIssueText(issue: SlopeIssue, unit: LinearUnit): String {
    val locale = uiLocale()

    fun pk(value: Double) = ChainageFormat.format(value, unit, locale)
    return when (issue) {
        is SlopeIssue.InvalidRatio ->
            stringResource(R.string.slope_issue_ratio, sideLabel(issue.side), ratioText(issue.value, locale))
        is SlopeIssue.ReversedRange -> stringResource(R.string.slope_issue_reversed, pk(issue.fromChainage), pk(issue.toChainage))
        is SlopeIssue.OverlappingRanges ->
            stringResource(R.string.slope_issue_overlap, sideLabel(issue.side), pk(issue.fromChainage), pk(issue.toChainage))
        is SlopeIssue.ChainageOutsideAxis -> stringResource(R.string.slope_issue_outside, pk(issue.chainage))
        SlopeIssue.InvalidManualEdge -> stringResource(R.string.slope_issue_manual)
    }
}

/** Qué impide guardar lo que se está escribiendo (S051/RF-3). */
@Composable
fun slopesProblemText(problem: SlopesProblem, unit: LinearUnit): String = when (problem) {
    is SlopesProblem.InvalidNumber -> stringResource(
        R.string.slope_problem_number,
        stringResource(
            when (problem.field) {
                SlopeField.FROM -> R.string.slope_name_from
                SlopeField.TO -> R.string.slope_name_to
                SlopeField.CUT -> R.string.slope_name_cut
                SlopeField.FILL -> R.string.slope_name_fill
                SlopeField.OFFSET -> R.string.slope_name_offset
                SlopeField.HEIGHT -> R.string.slope_name_height
            },
        ),
    )
    is SlopesProblem.Rejected -> slopeIssueText(problem.issue, unit)
    SlopesProblem.WriteError -> stringResource(R.string.slope_problem_write)
}
