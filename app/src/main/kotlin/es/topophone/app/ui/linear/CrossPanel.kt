package es.topophone.app.ui.linear

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
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
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import es.topophone.app.R
import es.topophone.app.ui.projects.unitSymbol
import es.topophone.app.ui.receiver.uiLocale
import es.topophone.app.ui.stakeout.KeepScreenOn
import es.topophone.core.designsystem.component.MinTouchTarget
import es.topophone.core.model.alignment.OffsetFormat
import es.topophone.core.model.alignment.OffsetSide
import es.topophone.core.model.survey.linear.SurveyFlag
import java.io.OutputStream

const val CROSS_PANEL_TAG = "cross_panel"
const val CROSS_CLOSE_TAG = "cross_close"
const val CROSS_CHAINAGE_TAG = "cross_chainage"
const val CROSS_TOLERANCE_TAG = "cross_tolerance"
const val CROSS_BEGIN_TAG = "cross_begin"
const val CROSS_SUMMARY_TAG = "cross_summary"
const val CROSS_NO_DESIGN_TAG = "cross_no_design"
const val CROSS_CODE_TAG = "cross_code"
const val CROSS_GUIDE_TAG = "cross_guide"
const val CROSS_REJECTED_TAG = "cross_rejected"
const val CROSS_LIST_TAG = "cross_list"
const val CROSS_SAVE_TAG = "cross_save"
const val CROSS_DISCARD_TAG = "cross_discard"
const val CROSS_PROBLEM_TAG = "cross_problem"
const val CROSS_SAVED_TAG = "cross_saved"
const val CROSS_SAVED_OK_TAG = "cross_saved_ok"

fun crossRemoveTag(pointId: String) = "cross_remove_$pointId"

/** Lo que el panel del perfil transversal pide a quien lo usa (S052/RF-16, RF-17, RF-20, RF-21). */
class CrossActions(
    val onClose: () -> Unit = {},
    val onSetup: ((CrossSetup) -> CrossSetup) -> Unit = {},
    val onBegin: () -> Unit = {},
    val onOpenGuide: () -> Unit = {},
    val onRemove: (pointId: String) -> Unit = {},
    val onSave: () -> Unit = {},
    val onDiscard: () -> Unit = {},
    val onDismissSaved: () -> Unit = {},
)

/**
 * El panel del perfil transversal en un PK de un eje (S052/RF-16 a RF-21): el PK de la sección y la tolerancia de sección para empezar; con
 * el perfil abierto, el alzado con la sección de diseño, los puntos fuera de sección, el código del siguiente punto, quitar un punto,
 * exportar el CSV, «Guardar perfil» y descartar. Medir lo hace el recolector con la guía del replanteo por PK que se pone debajo.
 * Mantiene la pantalla encendida (RNF-T2).
 */
@Composable
fun CrossPanel(
    state: CrossUiState,
    projectName: String,
    actions: CrossActions,
    writeCsv: suspend (OutputStream) -> Boolean,
    modifier: Modifier = Modifier,
) {
    KeepScreenOn()
    val symbol = unitSymbol(state.unit)
    Card(modifier = modifier.fillMaxWidth().padding(8.dp).testTag(CROSS_PANEL_TAG)) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.cross_title, state.alignment?.name.orEmpty()),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = actions.onClose, modifier = Modifier.heightIn(min = MinTouchTarget).testTag(CROSS_CLOSE_TAG)) {
                    Text(stringResource(R.string.collector_close))
                }
            }
            if (state.loading || state.alignment == null) return@Column
            Column(
                modifier = Modifier.weight(1f, fill = false).heightIn(max = BODY_MAX_HEIGHT).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (state.survey == null) Setup(state, actions, symbol) else Open(state, projectName, actions, writeCsv, symbol)
                state.problem?.let {
                    Text(
                        crossProblemText(it),
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.testTag(CROSS_PROBLEM_TAG).semantics { liveRegion = LiveRegionMode.Polite },
                    )
                }
            }
        }
    }
    state.saved?.let { saved ->
        AlertDialog(
            onDismissRequest = actions.onDismissSaved,
            title = { Text(stringResource(R.string.cross_saved_title)) },
            text = {
                Column(modifier = Modifier.testTag(CROSS_SAVED_TAG), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(stringResource(R.string.cross_saved_body, saved.points))
                    if (saved.replacedPrevious) Text(stringResource(R.string.cross_saved_history))
                }
            },
            confirmButton = {
                TextButton(
                    onClick = actions.onDismissSaved,
                    modifier = Modifier.heightIn(min = MinTouchTarget).testTag(CROSS_SAVED_OK_TAG),
                ) {
                    Text(stringResource(R.string.axis_survey_done_ok))
                }
            },
        )
    }
}

@Composable
private fun Setup(state: CrossUiState, actions: CrossActions, symbol: String) {
    val numbers = KeyboardOptions(keyboardType = KeyboardType.Decimal)
    Text(stringResource(R.string.cross_intro), style = MaterialTheme.typography.bodySmall)
    OutlinedTextField(
        value = state.setup.chainage,
        onValueChange = { text -> actions.onSetup { it.copy(chainage = text) } },
        label = { Text(stringResource(R.string.cross_chainage, symbol)) },
        singleLine = true,
        keyboardOptions = numbers,
        modifier = Modifier.fillMaxWidth().heightIn(min = MinTouchTarget).testTag(CROSS_CHAINAGE_TAG),
    )
    OutlinedTextField(
        value = state.setup.tolerance,
        onValueChange = { text -> actions.onSetup { it.copy(tolerance = text) } },
        label = { Text(stringResource(R.string.cross_tolerance, symbol)) },
        singleLine = true,
        keyboardOptions = numbers,
        modifier = Modifier.fillMaxWidth().heightIn(min = MinTouchTarget).testTag(CROSS_TOLERANCE_TAG),
    )
    Button(
        onClick = actions.onBegin,
        modifier = Modifier.fillMaxWidth().heightIn(min = MinTouchTarget).testTag(CROSS_BEGIN_TAG),
    ) { Text(stringResource(R.string.cross_begin)) }
}

@Composable
private fun Open(
    state: CrossUiState,
    projectName: String,
    actions: CrossActions,
    writeCsv: suspend (OutputStream) -> Boolean,
    symbol: String,
) {
    val locale = uiLocale()
    Text(
        stringResource(R.string.cross_summary, state.inProfile.size, state.rejected.size),
        modifier = Modifier.testTag(CROSS_SUMMARY_TAG).semantics { liveRegion = LiveRegionMode.Polite },
    )
    if (!state.hasDesign) {
        Text(
            stringResource(R.string.cross_no_design),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.testTag(CROSS_NO_DESIGN_TAG),
        )
    }
    OutlinedButton(
        onClick = actions.onOpenGuide,
        modifier = Modifier.fillMaxWidth().heightIn(min = MinTouchTarget).testTag(CROSS_GUIDE_TAG),
    ) { Text(stringResource(R.string.long_open_guide)) }
    OutlinedTextField(
        value = state.setup.nextCode,
        onValueChange = { text -> actions.onSetup { it.copy(nextCode = text) } },
        label = { Text(stringResource(R.string.cross_next_code)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth().heightIn(min = MinTouchTarget).testTag(CROSS_CODE_TAG),
    )
    ProfileView(state.chart, state.unit)
    if (state.rejected.isNotEmpty()) {
        Column(modifier = Modifier.testTag(CROSS_REJECTED_TAG), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            state.rejected.forEach { row ->
                Text(
                    stringResource(
                        R.string.long_rejected_row,
                        row.name,
                        stringResource(
                            when (row.flag) {
                                SurveyFlag.NO_PROJECTION -> R.string.profile_flag_no_projection
                                else -> R.string.profile_flag_out_section
                            },
                        ),
                    ),
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
    Text(stringResource(R.string.long_points_title), style = MaterialTheme.typography.titleSmall)
    LazyColumn(
        modifier = Modifier.fillMaxWidth().heightIn(max = LIST_MAX_HEIGHT).testTag(CROSS_LIST_TAG),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        items(state.rows.filter { it.included }, key = { it.pointId }) { row ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                val where = row.offsetM?.let { offsetText(it, state, symbol, locale) } ?: stringResource(R.string.long_no_chainage)
                val label = row.code?.let { stringResource(R.string.cross_row_code, row.name, it, where) }
                    ?: stringResource(R.string.long_row, row.name, where)
                Text(label, modifier = Modifier.weight(1f))
                TextButton(
                    onClick = { actions.onRemove(row.pointId) },
                    modifier = Modifier.heightIn(min = MinTouchTarget).testTag(crossRemoveTag(row.pointId)),
                ) { Text(stringResource(R.string.long_remove)) }
            }
        }
    }
    ProfileExportButtons(projectName, stringResource(R.string.cross_file_label), enabled = state.inProfile.isNotEmpty(), write = writeCsv)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(
            onClick = actions.onSave,
            modifier = Modifier.weight(1f).heightIn(min = MinTouchTarget).testTag(CROSS_SAVE_TAG),
        ) { Text(stringResource(R.string.cross_save)) }
        OutlinedButton(
            onClick = actions.onDiscard,
            modifier = Modifier.weight(1f).heightIn(min = MinTouchTarget).testTag(CROSS_DISCARD_TAG),
        ) { Text(stringResource(R.string.long_discard)) }
    }
}

/** El desplazamiento con su lado en palabras y la unidad del proyecto (RNF-T8). */
@Composable
private fun offsetText(offsetM: Double, state: CrossUiState, symbol: String, locale: java.util.Locale): String {
    val magnitude = OffsetFormat.magnitude(offsetM, state.unit, locale) + " " + symbol
    return when (OffsetFormat.side(offsetM)) {
        OffsetSide.LEFT -> stringResource(R.string.alignment_point_side_left, magnitude)
        OffsetSide.RIGHT -> stringResource(R.string.alignment_point_side_right, magnitude)
        OffsetSide.ON_AXIS -> stringResource(R.string.alignment_point_side_on_axis)
    }
}

@Composable
internal fun crossProblemText(problem: CrossProblem): String = when (problem) {
    is CrossProblem.InvalidNumber -> stringResource(
        R.string.long_problem_number,
        stringResource(if (problem.field == CrossField.CHAINAGE) R.string.cross_field_chainage else R.string.cross_field_tolerance),
    )
    CrossProblem.OutsideAxis -> stringResource(R.string.long_problem_outside, stringResource(R.string.cross_field_chainage))
    CrossProblem.NotPositive -> stringResource(R.string.long_problem_not_positive, stringResource(R.string.cross_field_tolerance))
    CrossProblem.NoPoints -> stringResource(R.string.cross_problem_no_points)
    is CrossProblem.SameOffset -> stringResource(R.string.cross_problem_same_offset)
    is CrossProblem.MissingHeight -> stringResource(R.string.cross_problem_missing_height)
    is CrossProblem.Rejected -> stringResource(R.string.cross_problem_rejected)
    CrossProblem.WriteError -> stringResource(R.string.long_problem_write)
}

private val BODY_MAX_HEIGHT = 420.dp
private val LIST_MAX_HEIGHT = 180.dp
