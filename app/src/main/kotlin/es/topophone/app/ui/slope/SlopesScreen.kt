package es.topophone.app.ui.slope

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import es.topophone.app.R
import es.topophone.app.ui.SectionScaffold
import es.topophone.app.ui.projects.unitSymbol
import es.topophone.app.ui.receiver.uiLocale
import es.topophone.core.designsystem.component.MinTouchTarget
import es.topophone.core.model.alignment.ChainageFormat
import es.topophone.core.model.alignment.slope.SlopeSide
import es.topophone.core.model.format.LengthFormat
import es.topophone.core.model.preferences.ThemeState

const val SLOPES_LIST_TAG = "slopes_list"
const val SLOPES_EMPTY_TAG = "slopes_empty"
const val SLOPES_ADD_TAG = "slopes_add"
const val SLOPES_CALCULATE_TAG = "slopes_calculate"
const val SLOPES_PROBLEM_TAG = "slopes_problem"
const val SLOPES_EDITOR_TAG = "slopes_editor"
const val SLOPES_FROM_TAG = "slopes_from"
const val SLOPES_TO_TAG = "slopes_to"
const val SLOPES_CUT_TAG = "slopes_cut"
const val SLOPES_FILL_TAG = "slopes_fill"
const val SLOPES_CODE_TAG = "slopes_code"
const val SLOPES_MANUAL_TAG = "slopes_manual"
const val SLOPES_OFFSET_TAG = "slopes_offset"
const val SLOPES_HEIGHT_TAG = "slopes_height"
const val SLOPES_RELATIVE_TAG = "slopes_relative"
const val SLOPES_SAVE_TAG = "slopes_save"
const val SLOPES_CANCEL_TAG = "slopes_cancel"

fun slopesSideTag(side: SlopeSide) = "slopes_side_${side.name}"

fun slopesDeleteTag(id: String) = "slopes_delete_$id"

/** Acciones de la pantalla «Taludes» de un eje (S051). */
class SlopesActions(
    val onAdd: () -> Unit = {},
    val onCancel: () -> Unit = {},
    val onDraft: ((SlopeDraft) -> SlopeDraft) -> Unit = {},
    val onSave: () -> Unit = {},
    val onDelete: (String) -> Unit = {},
    val onCalculate: () -> Unit = {},
)

/**
 * Los taludes de un eje (S051/RF-1 a RF-3): la lista de los definidos por lado y rango de PK con sus relaciones H:V, el alta de uno nuevo
 * (con borde manual si no hay sección) y el motivo junto al botón Guardar cuando no se acepta. El lado se dice con la palabra y los PK y
 * las cotas van en la unidad del proyecto (RNF-T8, RNF-T9).
 */
@Composable
fun SlopesScreen(themeState: ThemeState, onToggleSun: () -> Unit, state: SlopesUiState, actions: SlopesActions, onBack: () -> Unit) {
    SectionScaffold(
        title = stringResource(R.string.slope_title),
        themeState = themeState,
        onToggleSun = onToggleSun,
        onNavigateBack = onBack,
    ) { padding ->
        Column(
            modifier = Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(16.dp).testTag(SLOPES_LIST_TAG),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            state.alignment?.let { Text(it.name, style = MaterialTheme.typography.titleMedium) }
            val draft = state.draft
            if (draft == null) Rules(state, actions) else Editor(state, draft, actions)
        }
    }
}

@Composable
private fun Rules(state: SlopesUiState, actions: SlopesActions) {
    val locale = uiLocale()
    if (state.rules.isEmpty()) Text(stringResource(R.string.slope_rules_empty), modifier = Modifier.testTag(SLOPES_EMPTY_TAG))
    state.rules.forEach { stored ->
        val rule = stored.rule
        val edge = rule.manualEdge?.let {
            stringResource(
                R.string.slope_edge_manual,
                LengthFormat.inUnit(kotlin.math.abs(it.offsetM), state.unit, locale) + " " + unitSymbol(state.unit),
                LengthFormat.inUnit(it.heightM, state.unit, locale) + " " + unitSymbol(state.unit),
            )
        } ?: rule.edgeCode?.let { stringResource(R.string.slope_edge_code, it) } ?: stringResource(R.string.slope_edge_exterior)
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    stringResource(
                        R.string.slope_rule_row,
                        sideLabel(rule.side),
                        ChainageFormat.format(rule.fromChainage, state.unit, locale),
                        ChainageFormat.format(rule.toChainage, state.unit, locale),
                        ratioLabel(rule.cutRatio),
                        ratioLabel(rule.fillRatio),
                        edge,
                    ),
                )
                OutlinedButton(
                    onClick = { actions.onDelete(stored.id) },
                    modifier = Modifier.heightIn(min = MinTouchTarget).testTag(slopesDeleteTag(stored.id)),
                ) { Text(stringResource(R.string.slope_delete)) }
            }
        }
    }
    OutlinedButton(
        onClick = actions.onAdd,
        modifier = Modifier.fillMaxWidth().heightIn(min = MinTouchTarget).testTag(SLOPES_ADD_TAG),
    ) { Text(stringResource(R.string.slope_add)) }
    if (state.rules.isNotEmpty()) {
        OutlinedButton(
            onClick = actions.onCalculate,
            modifier = Modifier.fillMaxWidth().heightIn(min = MinTouchTarget).testTag(SLOPES_CALCULATE_TAG),
        ) { Text(stringResource(R.string.slope_point_calculate)) }
    }
}

@Composable
private fun Editor(state: SlopesUiState, draft: SlopeDraft, actions: SlopesActions) {
    val symbol = unitSymbol(state.unit)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.testTag(SLOPES_EDITOR_TAG)) {
        Text(stringResource(R.string.slope_editor_title), style = MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SlopeSide.entries.forEach { side ->
                FilterChip(
                    selected = draft.side == side,
                    onClick = { actions.onDraft { it.copy(side = side) } },
                    label = { Text(sideLabel(side)) },
                    modifier = Modifier.heightIn(min = MinTouchTarget).testTag(slopesSideTag(side)),
                )
            }
        }
        Field(stringResource(R.string.slope_field_from, symbol), draft.from, SLOPES_FROM_TAG) { v -> actions.onDraft { it.copy(from = v) } }
        Field(stringResource(R.string.slope_field_to, symbol), draft.to, SLOPES_TO_TAG) { v -> actions.onDraft { it.copy(to = v) } }
        Field(stringResource(R.string.slope_field_cut), draft.cut, SLOPES_CUT_TAG) { v -> actions.onDraft { it.copy(cut = v) } }
        Field(stringResource(R.string.slope_field_fill), draft.fill, SLOPES_FILL_TAG) { v -> actions.onDraft { it.copy(fill = v) } }
        if (!draft.manual) {
            Field(stringResource(R.string.slope_field_code), draft.edgeCode, SLOPES_CODE_TAG) { v ->
                actions.onDraft { it.copy(edgeCode = v) }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = MinTouchTarget).testTag(SLOPES_MANUAL_TAG).toggleable(
                value = draft.manual,
                role = Role.Switch,
                onValueChange = { v -> actions.onDraft { it.copy(manual = v) } },
            ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(stringResource(R.string.slope_manual))
            Switch(checked = draft.manual, onCheckedChange = null)
        }
        if (draft.manual) {
            Field(stringResource(R.string.slope_manual_offset, symbol), draft.manualOffset, SLOPES_OFFSET_TAG) { v ->
                actions.onDraft { it.copy(manualOffset = v) }
            }
            Field(stringResource(R.string.slope_manual_height, symbol), draft.manualHeight, SLOPES_HEIGHT_TAG) { v ->
                actions.onDraft { it.copy(manualHeight = v) }
            }
            Row(
                modifier = Modifier.fillMaxWidth().heightIn(min = MinTouchTarget).testTag(SLOPES_RELATIVE_TAG).toggleable(
                    value = draft.manualRelative,
                    role = Role.Checkbox,
                    onValueChange = { v -> actions.onDraft { it.copy(manualRelative = v) } },
                ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(checked = draft.manualRelative, onCheckedChange = null)
                Text(stringResource(R.string.slope_manual_relative), modifier = Modifier.padding(start = 12.dp))
            }
        }
        state.problem?.let { problem ->
            Text(
                slopesProblemText(problem, state.unit),
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.testTag(SLOPES_PROBLEM_TAG).semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
        Button(
            onClick = actions.onSave,
            enabled = !state.saving,
            modifier = Modifier.fillMaxWidth().heightIn(min = MinTouchTarget).testTag(SLOPES_SAVE_TAG),
        ) { Text(stringResource(R.string.slope_save)) }
        OutlinedButton(
            onClick = actions.onCancel,
            modifier = Modifier.fillMaxWidth().heightIn(min = MinTouchTarget).testTag(SLOPES_CANCEL_TAG),
        ) { Text(stringResource(R.string.dialog_cancel)) }
    }
}

@Composable
private fun Field(label: String, value: String, tag: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth().heightIn(min = MinTouchTarget).testTag(tag),
    )
}
