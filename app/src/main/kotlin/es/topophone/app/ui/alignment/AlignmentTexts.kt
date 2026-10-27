package es.topophone.app.ui.alignment

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import es.topophone.app.R
import es.topophone.app.ui.receiver.uiLocale
import es.topophone.core.model.alignment.AlignmentIssue
import es.topophone.core.model.alignment.ChainageFormat
import es.topophone.core.model.alignment.ProfileIssue
import es.topophone.core.model.alignment.ProfileOmission
import es.topophone.core.model.project.LinearUnit
import java.text.NumberFormat

/** Por qué no se importa un eje, con el tramo o el vértice (S049/RF-5, RF-6, RF-12). */
@Composable
internal fun alignmentIssueText(issue: AlignmentIssue): String = when (issue) {
    is AlignmentIssue.Gap -> stringResource(R.string.alignment_reason_gap, issue.element, centimetres(issue.gapM))
    is AlignmentIssue.UnsupportedElement -> stringResource(R.string.alignment_reason_unsupported, issue.element, issue.name)
    is AlignmentIssue.InconsistentElement -> stringResource(
        R.string.alignment_reason_inconsistent,
        issue.element,
        centimetres(issue.differenceM),
    )
    AlignmentIssue.StationEquation -> stringResource(R.string.alignment_reason_station_equation)
    is AlignmentIssue.NonCircularCurve -> stringResource(R.string.alignment_reason_non_circular, issue.element)
    AlignmentIssue.NotAPolyline -> stringResource(R.string.alignment_reason_not_polyline)
    AlignmentIssue.GeographicProject -> stringResource(R.string.alignment_reason_geographic)
    AlignmentIssue.OutsideCrsArea -> stringResource(R.string.alignment_reason_outside)
}

/** Por qué un eje se importó sin su rasante (S049/RF-10, decisión D3); el PK con el formato del proyecto (RNF-T9). */
@Composable
internal fun profileOmissionText(omission: ProfileOmission, unit: LinearUnit): String {
    val locale = uiLocale()
    fun pk(value: Double) = ChainageFormat.format(value, unit, locale)
    return when (omission) {
        ProfileOmission.MultipleProfiles -> stringResource(R.string.alignment_omission_multiple)
        is ProfileOmission.UnsupportedCurve -> stringResource(R.string.alignment_omission_unsupported, omission.name)
        is ProfileOmission.Invalid -> when (val issue = omission.issue) {
            is ProfileIssue.RepeatedChainage -> stringResource(R.string.alignment_omission_repeated, issue.vertex, pk(issue.chainage))
            is ProfileIssue.OutsideAxis -> stringResource(R.string.alignment_omission_outside, issue.vertex, pk(issue.chainage))
            is ProfileIssue.CurveDoesNotFit -> stringResource(R.string.alignment_omission_curve, issue.vertex, pk(issue.chainage))
        }
    }
}

private fun centimetres(metres: Double): String = NumberFormat.getNumberInstance().apply {
    minimumFractionDigits = 1
    maximumFractionDigits = 1
}.format(metres * 100)
