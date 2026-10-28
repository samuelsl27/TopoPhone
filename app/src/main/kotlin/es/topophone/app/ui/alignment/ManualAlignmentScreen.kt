package es.topophone.app.ui.alignment

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import es.topophone.app.R
import es.topophone.app.ui.SectionScaffold
import es.topophone.app.ui.receiver.uiLocale
import es.topophone.core.designsystem.component.MinTouchTarget
import es.topophone.core.model.alignment.ChainageFormat
import es.topophone.core.model.alignment.ProfileIssue
import es.topophone.core.model.format.LengthFormat
import es.topophone.core.model.preferences.ThemeState
import es.topophone.core.model.project.LinearUnit
import es.topophone.core.survey.PiIssue

const val MANUAL_NAME_TAG = "manual_name"
const val MANUAL_START_TAG = "manual_start"
const val MANUAL_ADD_VERTEX_TAG = "manual_add_vertex"
const val MANUAL_ADD_PROFILE_TAG = "manual_add_profile"
const val MANUAL_SUMMARY_TAG = "manual_summary"
const val MANUAL_PROBLEM_TAG = "manual_problem"
const val MANUAL_CREATE_TAG = "manual_create"
const val MANUAL_RESULT_TAG = "manual_result"
const val MANUAL_ERROR_TAG = "manual_error"

fun manualVertexTag(index: Int, field: ManualField) = "manual_vertex_${index}_${field.name}"

fun manualProfileTag(index: Int, field: ManualField) = "manual_profile_${index}_${field.name}"

fun manualRemoveVertexTag(index: Int) = "manual_remove_vertex_$index"

fun manualRemoveProfileTag(index: Int) = "manual_remove_profile_$index"

/** Acciones de la pantalla de definir un eje a mano (S049). */
class ManualAlignmentActions(
    val onName: (String) -> Unit = {},
    val onStart: (String) -> Unit = {},
    val onAddVertex: () -> Unit = {},
    val onRemoveVertex: (Int) -> Unit = {},
    val onVertex: (Int, VertexRow) -> Unit = { _, _ -> },
    val onAddProfile: () -> Unit = {},
    val onRemoveProfile: (Int) -> Unit = {},
    val onProfile: (Int, ProfileRow) -> Unit = { _, _ -> },
    val onCreate: () -> Unit = {},
)

/**
 * Definir un eje a mano (S049/RF-8): el nombre y el PK inicial, los vértices con su Este, su Norte, el radio de su curva y la longitud de
 * sus clotoides, y los vértices de la rasante con su PK, su cota y su Kv. Con lo escrito se ve la longitud del eje y lo primero que
 * impide crearlo; al crearlo, el resultado.
 */
@Composable
fun ManualAlignmentScreen(
    themeState: ThemeState,
    onToggleSun: () -> Unit,
    state: ManualAlignmentUiState,
    actions: ManualAlignmentActions,
    onBack: () -> Unit,
) {
    SectionScaffold(
        title = stringResource(R.string.alignment_manual_title),
        themeState = themeState,
        onToggleSun = onToggleSun,
        onNavigateBack = onBack,
    ) { padding ->
        Column(
            modifier = Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val result = state.result
            if (result != null) {
                ResultSection(state, result, onBack)
            } else {
                Form(state, actions)
            }
        }
    }
}

@Composable
private fun Form(state: ManualAlignmentUiState, actions: ManualAlignmentActions) {
    val unit = unitSymbol(state.projectUnit)
    OutlinedTextField(
        value = state.name,
        onValueChange = actions.onName,
        label = { Text(stringResource(R.string.alignment_convert_name)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth().testTag(MANUAL_NAME_TAG),
    )
    OutlinedTextField(
        value = state.startChainage,
        onValueChange = actions.onStart,
        label = { Text(stringResource(R.string.alignment_convert_start, unit)) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = Modifier.fillMaxWidth().testTag(MANUAL_START_TAG),
    )
    Text(stringResource(R.string.alignment_manual_section_vertices), style = MaterialTheme.typography.titleSmall)
    Text(stringResource(R.string.alignment_manual_vertices_help), style = MaterialTheme.typography.bodySmall)
    state.vertices.forEachIndexed { index, row -> VertexCard(index, row, unit, state.vertices.size > 2, actions) }
    OutlinedButton(
        onClick = actions.onAddVertex,
        modifier = Modifier.fillMaxWidth().heightIn(min = MinTouchTarget).testTag(MANUAL_ADD_VERTEX_TAG),
    ) { Text(stringResource(R.string.alignment_manual_add_vertex)) }

    Text(stringResource(R.string.alignment_manual_section_profile), style = MaterialTheme.typography.titleSmall)
    Text(stringResource(R.string.alignment_manual_profile_help), style = MaterialTheme.typography.bodySmall)
    state.profile.forEachIndexed { index, row -> ProfileCard(index, row, unit, actions) }
    OutlinedButton(
        onClick = actions.onAddProfile,
        modifier = Modifier.fillMaxWidth().heightIn(min = MinTouchTarget).testTag(MANUAL_ADD_PROFILE_TAG),
    ) { Text(stringResource(R.string.alignment_manual_add_profile)) }

    Summary(state)
    Button(
        onClick = actions.onCreate,
        enabled = state.canCreate,
        modifier = Modifier.fillMaxWidth().heightIn(min = MinTouchTarget).testTag(MANUAL_CREATE_TAG),
    ) { Text(stringResource(R.string.alignment_manual_create)) }
}

@Composable
private fun VertexCard(index: Int, row: VertexRow, unit: String, removable: Boolean, actions: ManualAlignmentActions) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(stringResource(R.string.alignment_manual_vertex_title, index + 1), style = MaterialTheme.typography.labelLarge)
            if (removable) {
                TextButton(
                    onClick = { actions.onRemoveVertex(index) },
                    modifier = Modifier.heightIn(min = MinTouchTarget).testTag(manualRemoveVertexTag(index)),
                ) { Text(stringResource(R.string.alignment_manual_remove_vertex, index + 1)) }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            Field(R.string.alignment_manual_east, unit, row.easting, manualVertexTag(index, ManualField.EASTING), Modifier.weight(1f)) {
                actions.onVertex(index, row.copy(easting = it))
            }
            Field(R.string.alignment_manual_north, unit, row.northing, manualVertexTag(index, ManualField.NORTHING), Modifier.weight(1f)) {
                actions.onVertex(index, row.copy(northing = it))
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            Field(R.string.alignment_manual_radius, unit, row.radius, manualVertexTag(index, ManualField.RADIUS), Modifier.weight(1f)) {
                actions.onVertex(index, row.copy(radius = it))
            }
            Field(R.string.alignment_manual_spiral, unit, row.spiral, manualVertexTag(index, ManualField.SPIRAL), Modifier.weight(1f)) {
                actions.onVertex(index, row.copy(spiral = it))
            }
        }
    }
}

@Composable
private fun ProfileCard(index: Int, row: ProfileRow, unit: String, actions: ManualAlignmentActions) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(stringResource(R.string.alignment_manual_profile_title, index + 1), style = MaterialTheme.typography.labelLarge)
            TextButton(
                onClick = { actions.onRemoveProfile(index) },
                modifier = Modifier.heightIn(min = MinTouchTarget).testTag(manualRemoveProfileTag(index)),
            ) { Text(stringResource(R.string.alignment_manual_remove_profile, index + 1)) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            Field(
                R.string.alignment_manual_chainage,
                unit,
                row.chainage,
                manualProfileTag(index, ManualField.CHAINAGE),
                Modifier.weight(1f),
            ) {
                actions.onProfile(index, row.copy(chainage = it))
            }
            Field(
                R.string.alignment_manual_elevation,
                unit,
                row.elevation,
                manualProfileTag(index, ManualField.ELEVATION),
                Modifier.weight(1f),
            ) {
                actions.onProfile(index, row.copy(elevation = it))
            }
            Field(R.string.alignment_manual_kv, unit, row.kv, manualProfileTag(index, ManualField.KV), Modifier.weight(1f)) {
                actions.onProfile(index, row.copy(kv = it))
            }
        }
    }
}

@Composable
private fun Field(@StringRes label: Int, unit: String, value: String, tag: String, modifier: Modifier, onChange: (String) -> Unit) {
    val text = stringResource(label, unit)
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(text) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = modifier.testTag(tag).semantics { contentDescription = text },
    )
}

/** La longitud del eje con lo escrito y, si algo impide crearlo, lo primero que es (S049/RF-8, RF-10). */
@Composable
private fun Summary(state: ManualAlignmentUiState) {
    val locale = uiLocale()
    val length = state.lengthM
    if (length != null) {
        Text(
            stringResource(
                R.string.alignment_manual_summary,
                LengthFormat.metres(length, locale, decimals = 1),
                ChainageFormat.format(state.startChainageM, state.projectUnit, locale),
                ChainageFormat.format(state.startChainageM + length, state.projectUnit, locale),
            ),
            modifier = Modifier.testTag(MANUAL_SUMMARY_TAG),
        )
    }
    val problem = state.problem
    if (problem != null && !(problem == ManualProblem.NameBlank && state.name.isEmpty())) {
        Text(
            problemText(problem, state.projectUnit),
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.testTag(MANUAL_PROBLEM_TAG).semantics { liveRegion = LiveRegionMode.Polite },
        )
    }
}

@Composable
private fun problemText(problem: ManualProblem, unit: LinearUnit): String {
    val locale = uiLocale()
    return when (problem) {
        ManualProblem.NameBlank -> stringResource(R.string.alignment_manual_problem_name)
        ManualProblem.InvalidStart -> stringResource(R.string.alignment_manual_problem_start)
        ManualProblem.TooFewVertices -> stringResource(R.string.alignment_manual_problem_few_vertices)
        is ManualProblem.InvalidNumber -> {
            val field = stringResource(fieldLabel(problem.field))
            val where = stringResource(
                if (problem.profile) R.string.alignment_manual_row_profile else R.string.alignment_manual_row_vertex,
                problem.row,
                field,
            )
            stringResource(R.string.alignment_manual_problem_number, where)
        }
        ManualProblem.GeographicProject -> stringResource(R.string.alignment_manual_problem_geographic)
        is ManualProblem.Geometry -> when (val issue = problem.issue) {
            PiIssue.TooFewVertices -> stringResource(R.string.alignment_manual_problem_few_vertices)
            is PiIssue.CoincidentVertices -> stringResource(R.string.alignment_manual_problem_coincident, issue.vertex)
            is PiIssue.InvalidParameter -> stringResource(R.string.alignment_manual_problem_parameter, issue.vertex)
            is PiIssue.SpiralTooLong -> stringResource(R.string.alignment_manual_problem_spiral, issue.vertex)
            is PiIssue.TangentsOverlap -> stringResource(R.string.alignment_manual_problem_tangents, issue.vertex)
        }
        ManualProblem.ProfileTooFew -> stringResource(R.string.alignment_manual_problem_profile_few)
        is ManualProblem.Profile -> when (val issue = problem.issue) {
            is ProfileIssue.RepeatedChainage ->
                stringResource(
                    R.string.alignment_manual_problem_profile_repeated,
                    issue.vertex,
                    ChainageFormat.format(issue.chainage, unit, locale),
                )
            is ProfileIssue.OutsideAxis ->
                stringResource(
                    R.string.alignment_manual_problem_profile_outside,
                    issue.vertex,
                    ChainageFormat.format(issue.chainage, unit, locale),
                )
            is ProfileIssue.CurveDoesNotFit ->
                stringResource(
                    R.string.alignment_manual_problem_profile_curve,
                    issue.vertex,
                    ChainageFormat.format(issue.chainage, unit, locale),
                )
        }
    }
}

@StringRes
private fun fieldLabel(field: ManualField) = when (field) {
    ManualField.EASTING -> R.string.alignment_manual_field_east
    ManualField.NORTHING -> R.string.alignment_manual_field_north
    ManualField.RADIUS -> R.string.alignment_manual_field_radius
    ManualField.SPIRAL -> R.string.alignment_manual_field_spiral
    ManualField.CHAINAGE -> R.string.alignment_manual_field_chainage
    ManualField.ELEVATION -> R.string.alignment_manual_field_elevation
    ManualField.KV -> R.string.alignment_manual_field_kv
}

@Composable
private fun ResultSection(state: ManualAlignmentUiState, result: ManualAlignmentResult, onClose: () -> Unit) {
    val locale = uiLocale()
    when (result) {
        is ManualAlignmentResult.Created -> Column(
            verticalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.testTag(MANUAL_RESULT_TAG).semantics { liveRegion = LiveRegionMode.Polite },
        ) {
            Text(
                stringResource(
                    R.string.alignment_convert_done,
                    result.name,
                    LengthFormat.metres(result.lengthM, locale, decimals = 1),
                    ChainageFormat.format(result.startChainage, state.projectUnit, locale),
                    ChainageFormat.format(result.startChainage + result.lengthM, state.projectUnit, locale),
                ),
            )
            Text(
                stringResource(
                    if (result.hasProfile) R.string.alignment_manual_with_profile else R.string.alignment_convert_without_profile,
                ),
            )
        }
        is ManualAlignmentResult.Rejected -> Text(
            stringResource(R.string.alignment_convert_failed, alignmentIssueText(result.issue)),
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.testTag(MANUAL_ERROR_TAG),
        )
        ManualAlignmentResult.WriteError -> Text(
            stringResource(R.string.alignment_convert_failed_write),
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.testTag(MANUAL_ERROR_TAG),
        )
    }
    Button(onClick = onClose, modifier = Modifier.fillMaxWidth().heightIn(min = MinTouchTarget)) {
        Text(stringResource(R.string.alignment_import_close))
    }
}

/** El símbolo de la unidad lineal del proyecto para las etiquetas de los campos. */
private fun unitSymbol(unit: LinearUnit): String = when (unit) {
    LinearUnit.METRE -> "m"
    LinearUnit.INTERNATIONAL_FOOT -> "ft"
    LinearUnit.US_SURVEY_FOOT -> "usft"
}
