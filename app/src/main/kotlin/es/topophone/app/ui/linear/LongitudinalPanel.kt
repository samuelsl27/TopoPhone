package es.topophone.app.ui.linear

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
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
import es.topophone.core.model.alignment.ChainageFormat
import es.topophone.core.model.format.LengthFormat
import es.topophone.core.model.survey.linear.SurveyFlag
import java.io.OutputStream

const val LONGITUDINAL_PANEL_TAG = "longitudinal_panel"
const val LONGITUDINAL_CLOSE_TAG = "longitudinal_close"
const val LONGITUDINAL_GUIDED_TAG = "longitudinal_guided"
const val LONGITUDINAL_FREE_TAG = "longitudinal_free"
const val LONGITUDINAL_FROM_TAG = "longitudinal_from"
const val LONGITUDINAL_TO_TAG = "longitudinal_to"
const val LONGITUDINAL_INTERVAL_TAG = "longitudinal_interval"
const val LONGITUDINAL_TOLERANCE_TAG = "longitudinal_tolerance"
const val LONGITUDINAL_BEGIN_TAG = "longitudinal_begin"
const val LONGITUDINAL_SELECTION_TAG = "longitudinal_selection"
const val LONGITUDINAL_SUMMARY_TAG = "longitudinal_summary"
const val LONGITUDINAL_REJECTED_TAG = "longitudinal_rejected"
const val LONGITUDINAL_GUIDE_TAG = "longitudinal_guide"
const val LONGITUDINAL_FINISH_TAG = "longitudinal_finish"
const val LONGITUDINAL_DISCARD_TAG = "longitudinal_discard"
const val LONGITUDINAL_PROBLEM_TAG = "longitudinal_problem"
const val LONGITUDINAL_LIST_TAG = "longitudinal_list"

fun longitudinalRemoveTag(pointId: String) = "longitudinal_remove_$pointId"

/** Lo que el panel del perfil longitudinal pide a quien lo usa (S052/RF-10, RF-14, RF-21). */
class LongitudinalActions(
    val onClose: () -> Unit = {},
    val onSetup: ((LongitudinalSetup) -> LongitudinalSetup) -> Unit = {},
    val onBegin: () -> Unit = {},
    val onUseSelection: () -> Unit = {},
    val onOpenGuide: () -> Unit = {},
    val onRemove: (pointId: String) -> Unit = {},
    val onFinish: () -> Unit = {},
    val onDiscard: () -> Unit = {},
)

/**
 * El panel del perfil longitudinal de un eje (S052/RF-10 a RF-15, RF-21): para empezarlo, guiado o libre con la tolerancia de eje, o con los
 * puntos elegidos en la lista de objetos; con el perfil abierto, el alzado, los puntos que quedan fuera, quitar un punto, exportar el CSV,
 * terminar y descartar. Medir lo hace el recolector (o la guía de replanteo) que se pone debajo. Mantiene la pantalla encendida (RNF-T2).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LongitudinalPanel(
    state: LongitudinalUiState,
    projectName: String,
    actions: LongitudinalActions,
    writeCsv: suspend (OutputStream) -> Boolean,
    modifier: Modifier = Modifier,
) {
    KeepScreenOn()
    val symbol = unitSymbol(state.unit)
    Card(modifier = modifier.fillMaxWidth().padding(8.dp).testTag(LONGITUDINAL_PANEL_TAG)) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.long_title, state.alignment?.name.orEmpty()),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = actions.onClose, modifier = Modifier.heightIn(min = MinTouchTarget).testTag(LONGITUDINAL_CLOSE_TAG)) {
                    Text(stringResource(R.string.collector_close))
                }
            }
            if (state.loading || state.alignment == null) return@Column
            Column(
                modifier = Modifier.weight(1f, fill = false).heightIn(max = BODY_MAX_HEIGHT).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (state.survey == null) Setup(state, actions, symbol) else Open(state, projectName, actions, writeCsv)
                state.problem?.let {
                    Text(
                        problemText(it),
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.testTag(LONGITUDINAL_PROBLEM_TAG).semantics { liveRegion = LiveRegionMode.Polite },
                    )
                }
            }
        }
    }
}

/** Guiado o libre, los PK y el intervalo del guiado, la tolerancia de eje y los puntos elegidos (S052/RF-10, RF-12, RF-14). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Setup(state: LongitudinalUiState, actions: LongitudinalActions, symbol: String) {
    val setup = state.setup
    val numbers = KeyboardOptions(keyboardType = KeyboardType.Decimal)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(
            selected = setup.guided,
            onClick = { actions.onSetup { it.copy(guided = true) } },
            label = { Text(stringResource(R.string.long_mode_guided)) },
            modifier = Modifier.heightIn(min = MinTouchTarget).testTag(LONGITUDINAL_GUIDED_TAG),
        )
        FilterChip(
            selected = !setup.guided,
            onClick = { actions.onSetup { it.copy(guided = false) } },
            label = { Text(stringResource(R.string.long_mode_free)) },
            modifier = Modifier.heightIn(min = MinTouchTarget).testTag(LONGITUDINAL_FREE_TAG),
        )
    }
    Text(
        stringResource(if (setup.guided) R.string.long_mode_guided_help else R.string.long_mode_free_help),
        style = MaterialTheme.typography.bodySmall,
    )
    if (setup.guided) {
        Field(setup.from, R.string.long_from, symbol, LONGITUDINAL_FROM_TAG, numbers) { actions.onSetup { s -> s.copy(from = it) } }
        Field(setup.to, R.string.long_to, symbol, LONGITUDINAL_TO_TAG, numbers) { actions.onSetup { s -> s.copy(to = it) } }
        Field(setup.interval, R.string.long_interval, symbol, LONGITUDINAL_INTERVAL_TAG, numbers) {
            actions.onSetup { s -> s.copy(interval = it) }
        }
    }
    Field(setup.tolerance, R.string.long_tolerance, symbol, LONGITUDINAL_TOLERANCE_TAG, numbers) {
        actions.onSetup { s -> s.copy(tolerance = it) }
    }
    Button(
        onClick = actions.onBegin,
        modifier = Modifier.fillMaxWidth().heightIn(min = MinTouchTarget).testTag(LONGITUDINAL_BEGIN_TAG),
    ) { Text(stringResource(R.string.long_begin)) }
    if (state.selectionCount > 0) {
        OutlinedButton(
            onClick = actions.onUseSelection,
            modifier = Modifier.fillMaxWidth().heightIn(min = MinTouchTarget).testTag(LONGITUDINAL_SELECTION_TAG),
        ) { Text(stringResource(R.string.long_use_selection, state.selectionCount)) }
    } else {
        Text(stringResource(R.string.long_selection_hint), style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun Field(value: String, label: Int, symbol: String, tag: String, keyboard: KeyboardOptions, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(stringResource(label, symbol)) },
        singleLine = true,
        keyboardOptions = keyboard,
        modifier = Modifier.fillMaxWidth().heightIn(min = MinTouchTarget).testTag(tag),
    )
}

/** El perfil abierto: resumen, alzado, puntos fuera, lista para quitar, exportar, terminar y descartar (S052/RF-12, RF-13, RF-15, RF-21). */
@Composable
private fun Open(
    state: LongitudinalUiState,
    projectName: String,
    actions: LongitudinalActions,
    writeCsv: suspend (OutputStream) -> Boolean,
) {
    val unit = state.unit
    val locale = uiLocale()
    val symbol = unitSymbol(unit)
    val survey = state.survey ?: return
    Text(
        stringResource(R.string.long_summary, state.inProfile.size, state.rejected.size),
        modifier = Modifier.testTag(LONGITUDINAL_SUMMARY_TAG).semantics { liveRegion = LiveRegionMode.Polite },
    )
    if (!state.hasGrade) Text(stringResource(R.string.long_no_grade), style = MaterialTheme.typography.bodySmall)
    if (survey.guided) {
        OutlinedButton(
            onClick = actions.onOpenGuide,
            modifier = Modifier.fillMaxWidth().heightIn(min = MinTouchTarget).testTag(LONGITUDINAL_GUIDE_TAG),
        ) { Text(stringResource(R.string.long_open_guide)) }
    }
    ProfileView(state.chart, unit)
    if (state.rejected.isNotEmpty()) {
        Column(modifier = Modifier.testTag(LONGITUDINAL_REJECTED_TAG), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            state.rejected.forEach { row ->
                Text(
                    stringResource(
                        R.string.long_rejected_row,
                        row.name,
                        stringResource(
                            when (row.flag) {
                                SurveyFlag.NO_PROJECTION -> R.string.profile_flag_no_projection
                                else -> R.string.profile_flag_out_axis
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
        modifier = Modifier.fillMaxWidth().heightIn(max = LIST_MAX_HEIGHT).testTag(LONGITUDINAL_LIST_TAG),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        items(state.rows.filter { it.included }, key = { it.pointId }) { row ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                val chainage = row.chainageM?.let { ChainageFormat.format(it, unit, locale) } ?: stringResource(R.string.long_no_chainage)
                val label = row.dHeightM?.let {
                    stringResource(R.string.long_row_difference, row.name, chainage, LengthFormat.inUnit(it, unit, locale), symbol)
                } ?: stringResource(R.string.long_row, row.name, chainage)
                Text(label, modifier = Modifier.weight(1f))
                TextButton(
                    onClick = { actions.onRemove(row.pointId) },
                    modifier = Modifier.heightIn(min = MinTouchTarget).testTag(longitudinalRemoveTag(row.pointId)),
                ) { Text(stringResource(R.string.long_remove)) }
            }
        }
    }
    ProfileExportButtons(
        projectName,
        stringResource(R.string.long_file_label),
        enabled = state.inProfile.isNotEmpty(),
        write = writeCsv,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(
            onClick = actions.onFinish,
            modifier = Modifier.weight(1f).heightIn(min = MinTouchTarget).testTag(LONGITUDINAL_FINISH_TAG),
        ) { Text(stringResource(R.string.long_finish)) }
        OutlinedButton(
            onClick = actions.onDiscard,
            modifier = Modifier.weight(1f).heightIn(min = MinTouchTarget).testTag(LONGITUDINAL_DISCARD_TAG),
        ) { Text(stringResource(R.string.long_discard)) }
    }
}

@Composable
internal fun problemText(problem: LongitudinalProblem): String = when (problem) {
    is LongitudinalProblem.InvalidNumber -> stringResource(R.string.long_problem_number, fieldName(problem.field))
    is LongitudinalProblem.OutsideAxis -> stringResource(R.string.long_problem_outside, fieldName(problem.field))
    is LongitudinalProblem.NotPositive -> stringResource(R.string.long_problem_not_positive, fieldName(problem.field))
    LongitudinalProblem.NoPoints -> stringResource(R.string.long_problem_no_points)
    LongitudinalProblem.WriteError -> stringResource(R.string.long_problem_write)
}

@Composable
private fun fieldName(field: LongitudinalField): String = stringResource(
    when (field) {
        LongitudinalField.FROM -> R.string.long_field_from
        LongitudinalField.TO -> R.string.long_field_to
        LongitudinalField.INTERVAL -> R.string.long_field_interval
        LongitudinalField.TOLERANCE -> R.string.long_field_tolerance
    },
)

private val BODY_MAX_HEIGHT = 420.dp
private val LIST_MAX_HEIGHT = 180.dp
