package es.topophone.app.ui.cogo

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import es.topophone.app.R
import es.topophone.app.ui.SectionScaffold
import es.topophone.app.ui.geometry.FactorNotices
import es.topophone.app.ui.projects.unitSymbol
import es.topophone.app.ui.receiver.uiLocale
import es.topophone.core.designsystem.component.MinTouchTarget
import es.topophone.core.model.cogo.LegKind
import es.topophone.core.model.format.LengthFormat
import es.topophone.core.model.geometry.DistanceMode
import es.topophone.core.model.preferences.ThemeState
import es.topophone.core.survey.cogo.ClosureError
import es.topophone.core.survey.cogo.TraverseStop
import java.text.NumberFormat

const val TRAVERSE_LIST_TAG = "traverse_list"
const val TRAVERSE_ADD_TAG = "traverse_add"
const val TRAVERSE_STOP_TAG = "traverse_stop"
const val TRAVERSE_CLOSURE_TAG = "traverse_closure"
const val TRAVERSE_CLOSES_TAG = "traverse_closes"
const val TRAVERSE_CLEAR_REFERENCE_TAG = "traverse_clear_reference"
const val TRAVERSE_CLEAR_CLOSURE_TAG = "traverse_clear_closure"
const val TRAVERSE_NEEDS_REFERENCE_TAG = "traverse_needs_reference"

fun traverseLegTag(number: Int) = "traverse_leg_$number"

fun traverseRemoveTag(number: Int) = "traverse_remove_$number"

fun traverseKindTag(number: Int) = "traverse_kind_$number"

fun traverseKindOptionTag(kind: LegKind) = "traverse_kind_option_${kind.name.lowercase()}"

fun traverseStepTag(number: Int) = "traverse_step_$number"

fun traverseFieldKey(number: Int, field: String) = "leg${number}_$field"

/** Lo que el usuario hace en la poligonal (S038/RF-3, RF-5, RF-10). */
class TraverseActions(
    val onPick: (String) -> Unit = {},
    val onQuery: (String) -> Unit = {},
    val onChoose: (String) -> Unit = {},
    val onTyped: (slot: String, easting: String, northing: String) -> Unit = { _, _, _ -> },
    val onClosePicker: () -> Unit = {},
    val onClear: (String) -> Unit = {},
    val onMode: (DistanceMode) -> Unit = {},
    val onAddLeg: () -> Unit = {},
    val onRemoveLeg: (Int) -> Unit = {},
    val onKind: (index: Int, kind: LegKind) -> Unit = { _, _ -> },
    val onValue: (index: Int, text: String) -> Unit = { _, _ -> },
    val onDistance: (index: Int, text: String) -> Unit = { _, _ -> },
    val onDHeight: (index: Int, text: String) -> Unit = { _, _ -> },
)

@Composable
fun TraverseRoute(
    projectId: String,
    themeState: ThemeState,
    onToggleSun: () -> Unit,
    viewModel: TraverseViewModel = hiltViewModel(),
    onBack: () -> Unit,
) {
    LaunchedEffect(projectId) { viewModel.start(projectId) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    TraverseScreen(
        themeState,
        onToggleSun,
        state,
        TraverseActions(
            onPick = viewModel.inputs::open,
            onQuery = viewModel.inputs::setQuery,
            onChoose = viewModel.inputs::chooseProject,
            onTyped = viewModel.inputs::setTyped,
            onClosePicker = viewModel.inputs::close,
            onClear = viewModel::clear,
            onMode = viewModel::setMode,
            onAddLeg = { viewModel.addLeg(LegKind.AZIMUTH) },
            onRemoveLeg = viewModel::removeLeg,
            onKind = viewModel::setKind,
            onValue = viewModel::setValue,
            onDistance = viewModel::setDistance,
            onDHeight = viewModel::setDHeight,
        ),
        onBack,
    )
}

/**
 * La poligonal (S038/RF-10 a RF-12, RF-31): los puntos de inicio, de referencia y de cierre, los tramos con su dirección, su distancia y
 * su desnivel, el punto que da cada uno en vivo, el error de cierre y lo que impide calcular. Es una lista perezosa: una poligonal puede
 * tener cientos de tramos.
 */
@Composable
fun TraverseScreen(
    themeState: ThemeState,
    onToggleSun: () -> Unit,
    state: TraverseUi,
    actions: TraverseActions,
    onBack: () -> Unit,
    footer: @Composable () -> Unit = {},
) {
    SectionScaffold(
        title = stringResource(R.string.cogo_traverse),
        themeState = themeState,
        onToggleSun = onToggleSun,
        onNavigateBack = onBack,
    ) { padding ->
        if (!state.loaded) return@SectionScaffold
        LazyColumn(
            modifier = Modifier.padding(padding).padding(horizontal = 16.dp).testTag(TRAVERSE_LIST_TAG),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item(key = "mode") { CogoModeSelector(state.mode, actions.onMode) }
            item(key = "start") {
                CogoPointField(SLOT_START, stringResource(R.string.cogo_point_start), state.start, state.linearUnit, actions.onPick)
            }
            item(key = "reference") {
                OptionalPoint(SLOT_REFERENCE, R.string.cogo_point_reference, state.reference, state, TRAVERSE_CLEAR_REFERENCE_TAG, actions)
            }
            if (state.needsReference && state.reference !is CogoSlotValue.Found) {
                item(key = "needs-reference") {
                    Text(
                        stringResource(R.string.cogo_needs_reference),
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.testTag(TRAVERSE_NEEDS_REFERENCE_TAG),
                    )
                }
            }
            items(state.legs, key = { "leg-${it.number}" }) { leg -> Leg(leg, state, actions) }
            item(key = "add") {
                Button(
                    onClick = actions.onAddLeg,
                    modifier = Modifier.fillMaxWidth().heightIn(min = MinTouchTarget).testTag(TRAVERSE_ADD_TAG),
                ) { Text(stringResource(R.string.cogo_add_leg)) }
            }
            state.stoppedAt?.let { number -> item(key = "stop") { Stop(number, state.stopReason) } }
            item(key = "closure-point") {
                OptionalPoint(SLOT_CLOSURE, R.string.cogo_point_closure, state.closurePoint, state, TRAVERSE_CLEAR_CLOSURE_TAG, actions)
            }
            state.closure?.let { closure -> item(key = "closure") { Closure(closure, state) } }
            item(key = "notices") { FactorNotices(state.notices) }
            item(key = "footer") { footer() }
        }
    }
    val picker = state.picker
    if (picker.slot != null) {
        val label = when (picker.slot) {
            SLOT_START -> stringResource(R.string.cogo_point_start)
            SLOT_REFERENCE -> stringResource(R.string.cogo_point_reference)
            else -> stringResource(R.string.cogo_point_closure)
        }
        val typed = when (picker.slot) {
            SLOT_START -> state.startSelection
            SLOT_REFERENCE -> state.referenceSelection
            else -> state.closureSelection
        } as? CogoSelection.Typed
        CogoPickerDialog(picker, label, typed, actions.onQuery, actions.onChoose, actions.onTyped, actions.onClosePicker)
    }
}

@Composable
private fun OptionalPoint(slot: String, label: Int, value: CogoSlotValue, state: TraverseUi, clearTag: String, actions: TraverseActions) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        CogoPointField(slot, stringResource(label), value, state.linearUnit, actions.onPick)
        if (value != CogoSlotValue.Empty) {
            TextButton(onClick = { actions.onClear(slot) }, modifier = Modifier.heightIn(min = MinTouchTarget).testTag(clearTag)) {
                Text(stringResource(R.string.cogo_remove_point))
            }
        }
    }
}

private fun kindLabel(kind: LegKind) = when (kind) {
    LegKind.AZIMUTH -> R.string.cogo_leg_azimuth
    LegKind.ANGLE_RIGHT -> R.string.cogo_leg_angle_right
    LegKind.ANGLE_LEFT -> R.string.cogo_leg_angle_left
    LegKind.DEFLECTION_RIGHT -> R.string.cogo_leg_deflection_right
    LegKind.DEFLECTION_LEFT -> R.string.cogo_leg_deflection_left
}

@Composable
private fun Leg(leg: TraverseLegUi, state: TraverseUi, actions: TraverseActions) {
    val index = leg.number - 1
    val unit = unitSymbol(state.linearUnit)
    val locale = uiLocale()
    fun length(valueM: Double) = "${LengthFormat.inUnit(valueM, state.linearUnit, locale)} $unit"
    val mono = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace)
    val removeDescription = stringResource(R.string.cogo_remove_leg, leg.number)
    Card(modifier = Modifier.fillMaxWidth().testTag(traverseLegTag(leg.number))) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.cogo_leg_title, leg.number), style = MaterialTheme.typography.titleMedium)
                TextButton(
                    onClick = { actions.onRemoveLeg(index) },
                    modifier = Modifier.heightIn(min = MinTouchTarget).testTag(traverseRemoveTag(leg.number))
                        .semantics { contentDescription = removeDescription },
                ) { Text(stringResource(R.string.cogo_remove_leg_short)) }
            }
            KindPicker(leg.number, leg.kind) { actions.onKind(index, it) }
            CogoNumberField(
                traverseFieldKey(
                    leg.number,
                    "value",
                ),
                stringResource(kindLabel(leg.kind)),
                leg.valueText,
                leg.valueInvalid,
                onChange = {
                    actions.onValue(index, it)
                },
            )
            CogoNumberField(
                traverseFieldKey(leg.number, "distance"),
                stringResource(R.string.cogo_leg_distance, unit),
                leg.distanceText,
                leg.distanceInvalid,
                onChange = { actions.onDistance(index, it) },
            )
            CogoNumberField(
                traverseFieldKey(leg.number, "dh"),
                stringResource(R.string.cogo_leg_dheight, unit),
                leg.dHeightText,
                leg.dHeightInvalid,
                onChange = { actions.onDHeight(index, it) },
            )
            leg.step?.let { step ->
                Column(
                    modifier = Modifier.testTag(traverseStepTag(leg.number)).semantics(mergeDescendants = true) {},
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(stringResource(R.string.cogo_leg_point, leg.number), style = MaterialTheme.typography.titleSmall)
                    Text(stringResource(R.string.cogo_point_coordinates, length(step.easting), length(step.northing)), style = mono)
                    Text(
                        leg.height?.let { stringResource(R.string.cogo_point_height, length(it)) }
                            ?: stringResource(R.string.cogo_point_no_height),
                        style = mono,
                    )
                }
            }
        }
    }
}

@Composable
private fun KindPicker(number: Int, kind: LegKind, onKind: (LegKind) -> Unit) {
    var open by remember { mutableStateOf(false) }
    OutlinedButton(
        onClick = { open = true },
        modifier = Modifier.fillMaxWidth().heightIn(min = MinTouchTarget).testTag(traverseKindTag(number)),
    ) { Text(stringResource(R.string.cogo_leg_kind, stringResource(kindLabel(kind)))) }
    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
        for (option in LegKind.entries) {
            DropdownMenuItem(
                text = { Text(stringResource(kindLabel(option))) },
                onClick = {
                    open = false
                    onKind(option)
                },
                modifier = Modifier.heightIn(min = MinTouchTarget).testTag(traverseKindOptionTag(option)),
            )
        }
    }
}

@Composable
private fun Stop(number: Int, reason: TraverseStop?) {
    val text = when (reason) {
        TraverseStop.BACKSIGHT_UNDEFINED -> stringResource(R.string.cogo_stop_backsight, number)
        TraverseStop.NO_REFERENCE -> stringResource(R.string.cogo_stop_no_reference, number)
        TraverseStop.INCOMPLETE_LEG -> stringResource(R.string.cogo_stop_incomplete, number)
        null -> return
    }
    val error = reason != TraverseStop.INCOMPLETE_LEG
    Card(modifier = Modifier.fillMaxWidth().testTag(TRAVERSE_STOP_TAG).semantics(mergeDescendants = true) {}) {
        Text(
            text,
            color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(12.dp),
        )
    }
}

@Composable
private fun Closure(closure: ClosureError, state: TraverseUi) {
    val locale = uiLocale()
    val unit = unitSymbol(state.linearUnit)
    fun length(valueM: Double) = "${LengthFormat.inUnit(valueM, state.linearUnit, locale)} $unit"
    val mono = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace)
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp).testTag(TRAVERSE_CLOSURE_TAG).semantics(mergeDescendants = true) {},
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(stringResource(R.string.cogo_closure_title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.cogo_closure_delta, length(closure.dEasting), length(closure.dNorthing)), style = mono)
            Text(stringResource(R.string.cogo_closure_linear, length(closure.linear)), style = mono)
            Text(stringResource(R.string.cogo_closure_total, length(closure.totalLength)), style = mono)
            val ratio = closure.ratio
            if (ratio == null) {
                Text(stringResource(R.string.cogo_closure_closes), modifier = Modifier.testTag(TRAVERSE_CLOSES_TAG), style = mono)
            } else {
                val n = NumberFormat.getIntegerInstance(locale).format(Math.round(ratio))
                Text(stringResource(R.string.cogo_closure_precision, n), style = mono)
            }
        }
    }
}
