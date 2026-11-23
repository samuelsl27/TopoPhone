package es.topophone.app.ui.linear

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import es.topophone.app.R
import es.topophone.app.ui.projects.unitSymbol
import es.topophone.app.ui.receiver.uiLocale
import es.topophone.app.ui.stakeout.KeepScreenOn
import es.topophone.core.designsystem.component.MinTouchTarget
import es.topophone.core.model.format.LengthFormat

const val AXIS_SURVEY_PANEL_TAG = "axis_survey_panel"
const val AXIS_SURVEY_CLOSE_TAG = "axis_survey_close"
const val AXIS_SURVEY_BEGIN_TAG = "axis_survey_begin"
const val AXIS_SURVEY_SUMMARY_TAG = "axis_survey_summary"
const val AXIS_SURVEY_LAST_TAG = "axis_survey_last"
const val AXIS_SURVEY_REMOVE_LAST_TAG = "axis_survey_remove_last"
const val AXIS_SURVEY_FINISH_TAG = "axis_survey_finish"
const val AXIS_SURVEY_DISCARD_TAG = "axis_survey_discard"
const val AXIS_SURVEY_PROBLEM_TAG = "axis_survey_problem"
const val AXIS_SURVEY_FORM_TAG = "axis_survey_form"
const val AXIS_SURVEY_NAME_TAG = "axis_survey_name"
const val AXIS_SURVEY_START_TAG = "axis_survey_start_chainage"
const val AXIS_SURVEY_GRADE_TAG = "axis_survey_grade"
const val AXIS_SURVEY_CREATE_TAG = "axis_survey_create"
const val AXIS_SURVEY_CANCEL_TAG = "axis_survey_cancel"
const val AXIS_SURVEY_RESULT_TAG = "axis_survey_result"
const val AXIS_SURVEY_RESULT_OK_TAG = "axis_survey_result_ok"

fun axisSurveyRadiusTag(vertex: Int) = "axis_survey_radius_$vertex"

/** Lo que el panel de «Tomar eje» pide a quien lo usa (S052/RF-1, RF-4, RF-5). */
class AxisSurveyActions(
    val onClose: () -> Unit = {},
    val onBegin: () -> Unit = {},
    val onRemoveLast: () -> Unit = {},
    /** Abre el formulario de terminar con [String] como nombre propuesto. */
    val onStartFinish: (baseName: String) -> Unit = {},
    val onFinishDraft: ((AxisFinishDraft) -> AxisFinishDraft) -> Unit = {},
    val onCancelFinish: () -> Unit = {},
    val onCreate: () -> Unit = {},
    val onDiscard: () -> Unit = {},
    val onDismissResult: () -> Unit = {},
)

/**
 * El panel de «Tomar eje» (S052/RF-1 a RF-5, RF-7): cuántos puntos lleva la toma, su longitud acumulada, el último punto, «Quitar
 * último», «Terminar» y «Descartar». Medir lo hace el recolector que se pone debajo. Mantiene la pantalla encendida (RNF-T2).
 */
@Composable
fun AxisSurveyPanel(state: AxisSurveyUiState, actions: AxisSurveyActions, modifier: Modifier = Modifier) {
    KeepScreenOn()
    val locale = uiLocale()
    val symbol = unitSymbol(state.unit)
    val defaultName = stringResource(R.string.axis_survey_default_name)
    Card(modifier = modifier.fillMaxWidth().padding(8.dp).testTag(AXIS_SURVEY_PANEL_TAG)) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.axis_survey_title),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = actions.onClose, modifier = Modifier.heightIn(min = MinTouchTarget).testTag(AXIS_SURVEY_CLOSE_TAG)) {
                    Text(stringResource(R.string.collector_close))
                }
            }
            if (state.loading) return@Column
            if (state.survey == null) {
                Text(stringResource(R.string.axis_survey_intro))
                Button(
                    onClick = actions.onBegin,
                    modifier = Modifier.fillMaxWidth().heightIn(min = MinTouchTarget).testTag(AXIS_SURVEY_BEGIN_TAG),
                ) { Text(stringResource(R.string.axis_survey_begin)) }
            } else {
                val included = state.included
                Text(
                    stringResource(
                        R.string.axis_survey_summary,
                        included.size,
                        LengthFormat.inUnit(state.terrain.distance(state.lengthM), state.unit, locale),
                        symbol,
                    ),
                    modifier = Modifier.testTag(AXIS_SURVEY_SUMMARY_TAG).semantics { liveRegion = LiveRegionMode.Polite },
                )
                included.lastOrNull()?.let {
                    Text(stringResource(R.string.axis_survey_last, it.name), modifier = Modifier.testTag(AXIS_SURVEY_LAST_TAG))
                }
                state.problem?.takeIf { state.finish == null }?.let {
                    Text(
                        axisSurveyProblemText(it, symbol),
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.testTag(AXIS_SURVEY_PROBLEM_TAG).semantics { liveRegion = LiveRegionMode.Polite },
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = actions.onRemoveLast,
                        enabled = included.isNotEmpty(),
                        modifier = Modifier.weight(1f).heightIn(min = MinTouchTarget).testTag(AXIS_SURVEY_REMOVE_LAST_TAG),
                    ) { Text(stringResource(R.string.axis_survey_remove_last)) }
                    Button(
                        onClick = { actions.onStartFinish(defaultName) },
                        enabled = included.isNotEmpty() && !state.saving,
                        modifier = Modifier.weight(1f).heightIn(min = MinTouchTarget).testTag(AXIS_SURVEY_FINISH_TAG),
                    ) { Text(stringResource(R.string.axis_survey_finish)) }
                }
                TextButton(
                    onClick = actions.onDiscard,
                    modifier = Modifier.heightIn(min = MinTouchTarget).testTag(AXIS_SURVEY_DISCARD_TAG),
                ) { Text(stringResource(R.string.axis_survey_discard)) }
            }
        }
    }
    state.finish?.let { FinishDialog(state, it, actions, symbol) }
    state.result?.let { ResultDialog(it, state, actions, symbol) }
}

/** El formulario de terminar: nombre, PK inicial, radio de cada vértice interior y rasante (S052/RF-5, RF-7). */
@Composable
private fun FinishDialog(state: AxisSurveyUiState, draft: AxisFinishDraft, actions: AxisSurveyActions, symbol: String) {
    val numbers = KeyboardOptions(keyboardType = KeyboardType.Decimal)
    val interior = (2 until state.included.size).toList()
    AlertDialog(
        onDismissRequest = actions.onCancelFinish,
        title = { Text(stringResource(R.string.axis_survey_finish_title)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()).testTag(AXIS_SURVEY_FORM_TAG),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = draft.name,
                    onValueChange = { text -> actions.onFinishDraft { it.copy(name = text) } },
                    label = { Text(stringResource(R.string.axis_survey_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().heightIn(min = MinTouchTarget).testTag(AXIS_SURVEY_NAME_TAG),
                )
                OutlinedTextField(
                    value = draft.startChainage,
                    onValueChange = { text -> actions.onFinishDraft { it.copy(startChainage = text) } },
                    label = { Text(stringResource(R.string.axis_survey_start_chainage, symbol)) },
                    singleLine = true,
                    keyboardOptions = numbers,
                    modifier = Modifier.fillMaxWidth().heightIn(min = MinTouchTarget).testTag(AXIS_SURVEY_START_TAG),
                )
                if (interior.isNotEmpty()) Text(stringResource(R.string.axis_survey_radii_help), style = MaterialTheme.typography.bodySmall)
                interior.forEach { vertex ->
                    OutlinedTextField(
                        value = draft.radii[vertex].orEmpty(),
                        onValueChange = { text -> actions.onFinishDraft { it.copy(radii = it.radii + (vertex to text)) } },
                        label = { Text(stringResource(R.string.axis_survey_radius, vertex, symbol)) },
                        singleLine = true,
                        keyboardOptions = numbers,
                        modifier = Modifier.fillMaxWidth().heightIn(min = MinTouchTarget).testTag(axisSurveyRadiusTag(vertex)),
                    )
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().heightIn(min = MinTouchTarget).toggleable(
                        value = draft.withGrade && state.canGrade,
                        enabled = state.canGrade,
                        role = Role.Checkbox,
                        onValueChange = { on -> actions.onFinishDraft { it.copy(withGrade = on) } },
                    ).testTag(AXIS_SURVEY_GRADE_TAG),
                ) {
                    Checkbox(checked = draft.withGrade && state.canGrade, onCheckedChange = null, enabled = state.canGrade)
                    Text(
                        stringResource(if (state.canGrade) R.string.axis_survey_grade else R.string.axis_survey_grade_unavailable),
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
                state.problem?.let {
                    Text(
                        axisSurveyProblemText(it, symbol),
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.testTag(AXIS_SURVEY_PROBLEM_TAG).semantics { liveRegion = LiveRegionMode.Polite },
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = actions.onCreate,
                enabled = !state.saving,
                modifier = Modifier.heightIn(min = MinTouchTarget).testTag(AXIS_SURVEY_CREATE_TAG),
            ) { Text(stringResource(R.string.axis_survey_create)) }
        },
        dismissButton = {
            TextButton(
                onClick = actions.onCancelFinish,
                modifier = Modifier.heightIn(min = MinTouchTarget).testTag(AXIS_SURVEY_CANCEL_TAG),
            ) {
                Text(stringResource(R.string.axis_survey_cancel))
            }
        },
    )
}

/** El eje recién creado: nombre, longitud, si quedó activo y si lleva rasante (S052/RF-5, RF-8). */
@Composable
private fun ResultDialog(result: AxisSurveyResult, state: AxisSurveyUiState, actions: AxisSurveyActions, symbol: String) {
    val locale = uiLocale()
    AlertDialog(
        onDismissRequest = actions.onDismissResult,
        title = { Text(stringResource(R.string.axis_survey_done_title)) },
        text = {
            Column(modifier = Modifier.testTag(AXIS_SURVEY_RESULT_TAG), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    stringResource(
                        R.string.axis_survey_done_body,
                        result.name,
                        LengthFormat.inUnit(state.terrain.distance(result.lengthM), state.unit, locale),
                        symbol,
                    ),
                )
                if (result.activated) Text(stringResource(R.string.axis_survey_done_active))
                if (result.hasGrade) Text(stringResource(R.string.axis_survey_done_grade))
            }
        },
        confirmButton = {
            TextButton(
                onClick = actions.onDismissResult,
                modifier = Modifier.heightIn(min = MinTouchTarget).testTag(AXIS_SURVEY_RESULT_OK_TAG),
            ) { Text(stringResource(R.string.axis_survey_done_ok)) }
        },
    )
}

/** El motivo por el que no se pudo crear el eje, con el vértice cuando lo hay (S052/RF-6). */
@Composable
internal fun axisSurveyProblemText(problem: AxisSurveyProblem, symbol: String): String = when (problem) {
    AxisSurveyProblem.EmptyName -> stringResource(R.string.axis_survey_problem_empty_name)
    AxisSurveyProblem.NameTaken -> stringResource(R.string.axis_survey_problem_name_taken)
    is AxisSurveyProblem.InvalidNumber ->
        if (problem.vertex == null) {
            stringResource(R.string.axis_survey_problem_start_number, symbol)
        } else {
            stringResource(R.string.axis_survey_problem_radius_number, problem.vertex, symbol)
        }
    AxisSurveyProblem.TooFewPoints -> stringResource(R.string.axis_survey_problem_few_points)
    is AxisSurveyProblem.CoincidentPoints -> stringResource(R.string.axis_survey_problem_coincident, problem.vertex)
    is AxisSurveyProblem.InvalidRadius -> stringResource(R.string.axis_survey_problem_radius, problem.vertex)
    is AxisSurveyProblem.RadiusDoesNotFit -> stringResource(R.string.axis_survey_problem_radius_fit, problem.vertex)
    AxisSurveyProblem.WriteError -> stringResource(R.string.axis_survey_problem_write)
}
