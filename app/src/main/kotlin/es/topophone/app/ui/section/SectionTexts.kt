package es.topophone.app.ui.section

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import es.topophone.app.R
import es.topophone.app.ui.receiver.uiLocale
import es.topophone.core.model.alignment.ChainageFormat
import es.topophone.core.model.alignment.section.SectionIssue
import es.topophone.core.model.alignment.section.SectionSlope
import es.topophone.core.model.project.LinearUnit
import java.text.NumberFormat
import java.util.Locale

/** La pendiente transversal con la palabra «baja» o «sube», nunca solo con el signo (S050/RNF-T8, plan D2). */
@Composable
fun slopeLabel(slope: Double): String {
    val locale = uiLocale()
    val percent = SectionSlope.toPercent(kotlin.math.abs(slope))
    return when {
        slope > 0.0 -> stringResource(R.string.section_slope_down, percentText(percent, locale))
        slope < 0.0 -> stringResource(R.string.section_slope_up, percentText(percent, locale))
        else -> stringResource(R.string.section_slope_flat)
    }
}

internal fun percentText(percent: Double, locale: Locale): String =
    NumberFormat.getNumberInstance(locale).apply { maximumFractionDigits = 3 }.format(percent)

/** El motivo por el que no se guarda una sección, una sección tipo o un peralte (S050/RF-7). */
@Composable
fun sectionIssueText(issue: SectionIssue, unit: LinearUnit): String {
    val locale = uiLocale()
    fun pk(value: Double) = ChainageFormat.format(value, unit, locale)
    return when (issue) {
        is SectionIssue.InvalidCode -> stringResource(R.string.section_issue_invalid_code, issue.code)
        is SectionIssue.RepeatedCode -> stringResource(R.string.section_issue_repeated_code, issue.code)
        is SectionIssue.UnknownGradeCode -> stringResource(R.string.section_issue_unknown_grade, issue.code)
        is SectionIssue.Unordered -> stringResource(R.string.section_issue_unordered, issue.code)
        is SectionIssue.SlopeTooSteep ->
            if (issue.code.isEmpty()) {
                stringResource(
                    R.string.section_issue_slope_row,
                )
            } else {
                stringResource(R.string.section_issue_slope, issue.code)
            }
        is SectionIssue.ChainageOutsideAxis -> stringResource(R.string.section_issue_outside_axis, pk(issue.chainage))
        is SectionIssue.RepeatedChainage -> stringResource(R.string.section_issue_repeated_chainage, pk(issue.chainage))
        is SectionIssue.ReversedRange -> stringResource(R.string.section_issue_reversed, pk(issue.fromChainage), pk(issue.toChainage))
        is SectionIssue.OverlappingTemplates -> stringResource(R.string.section_issue_overlap, pk(issue.fromChainage), pk(issue.toChainage))
    }
}

/** Qué impide guardar lo que se está escribiendo (S050/RF-7). */
@Composable
fun sectionsProblemText(problem: SectionsProblem, unit: LinearUnit): String = when (problem) {
    is SectionsProblem.InvalidNumber -> {
        val field = stringResource(
            when (problem.field) {
                SectionField.FROM -> R.string.section_field_from
                SectionField.TO -> R.string.section_field_to
                SectionField.WIDTH -> R.string.section_field_width
                SectionField.SLOPE -> R.string.section_field_slope
                SectionField.CHAINAGE -> R.string.section_field_chainage
                SectionField.LEFT_SLOPE -> R.string.section_field_left_slope
                SectionField.RIGHT_SLOPE -> R.string.section_field_right_slope
            },
        )
        val where = when {
            problem.row == null -> field
            problem.left != null -> stringResource(
                R.string.section_where_segment,
                field,
                problem.row,
                stringResource(if (problem.left) R.string.section_side_left else R.string.section_side_right),
            )
            else -> stringResource(R.string.section_where_row, field, problem.row)
        }
        stringResource(R.string.section_problem_number, where)
    }
    SectionsProblem.NoSegments -> stringResource(R.string.section_problem_no_segments)
    is SectionsProblem.Rejected -> sectionIssueText(problem.issue, unit)
    SectionsProblem.WriteError -> stringResource(R.string.section_problem_write)
}
