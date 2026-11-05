package es.topophone.app.ui.geometry

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import es.topophone.app.R
import es.topophone.app.ui.SectionScaffold
import es.topophone.app.ui.projects.unitSymbol
import es.topophone.app.ui.receiver.uiLocale
import es.topophone.core.designsystem.component.MinTouchTarget
import es.topophone.core.model.format.AngleFormat
import es.topophone.core.model.format.LengthFormat
import es.topophone.core.model.geometry.DistanceMode
import es.topophone.core.model.preferences.ThemeState
import es.topophone.core.survey.geometry.AnglePoint
import es.topophone.core.survey.geometry.AngleResult

const val ANGLE_HINT_TAG = "angle_hint"
const val ANGLE_RESULT_TAG = "angle_result"
const val ANGLE_AZIMUTH_BA_TAG = "angle_azimuth_ba"
const val ANGLE_AZIMUTH_BC_TAG = "angle_azimuth_bc"
const val ANGLE_DISTANCE_BA_TAG = "angle_distance_ba"
const val ANGLE_DISTANCE_BC_TAG = "angle_distance_bc"
const val ANGLE_COINCIDENT_TAG = "angle_coincident"
const val ANGLE_PICKER_TAG = "angle_picker"

fun angleSlotTag(slot: AnglePoint) = "angle_slot_${slot.name.lowercase()}"

fun anglePickerPointTag(name: String) = "angle_picker_point_$name"

/** Lo que el usuario hace en el ángulo entre tres puntos (S026/RF-13). */
class ThreePointAngleActions(
    val onPick: (AnglePoint) -> Unit = {},
    val onQuery: (String) -> Unit = {},
    val onChoose: (String) -> Unit = {},
    val onClosePicker: () -> Unit = {},
    val onMode: (DistanceMode) -> Unit = {},
)

@Composable
fun ThreePointAngleRoute(
    projectId: String,
    lineId: String?,
    themeState: ThemeState,
    onToggleSun: () -> Unit,
    viewModel: ThreePointAngleViewModel = hiltViewModel(),
    onBack: () -> Unit,
) {
    LaunchedEffect(projectId, lineId) { viewModel.start(projectId, lineId) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    ThreePointAngleScreen(
        themeState,
        onToggleSun,
        state,
        ThreePointAngleActions(
            onPick = viewModel::openPicker,
            onQuery = viewModel::setQuery,
            onChoose = viewModel::choose,
            onClosePicker = viewModel::closePicker,
            onMode = viewModel::setMode,
        ),
        onBack,
    )
}

/**
 * El ángulo entre tres puntos (S026/RF-13 a RF-15): se eligen A, B y C de la lista y se muestra el ángulo en B, los azimuts y las
 * distancias de BA y BC con la distancia activa. Solo muestra; no modifica ningún dato.
 */
@Composable
fun ThreePointAngleScreen(
    themeState: ThemeState,
    onToggleSun: () -> Unit,
    state: ThreePointAngleUi,
    actions: ThreePointAngleActions,
    onBack: () -> Unit,
) {
    SectionScaffold(
        title = state.lineName ?: stringResource(R.string.angle_title),
        themeState = themeState,
        onToggleSun = onToggleSun,
        onNavigateBack = onBack,
    ) { padding ->
        if (!state.loaded) return@SectionScaffold
        LazyColumn(
            modifier = Modifier.padding(padding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item(key = "mode") { ModeSelector(state.mode, actions.onMode) }
            item(key = "slots") { Slots(state, actions) }
            item(key = "result") { Result(state) }
        }
    }
    if (state.picking != null) Picker(state, actions)
}

@Composable
private fun Slots(state: ThreePointAngleUi, actions: ThreePointAngleActions) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (slot in AnglePoint.entries) {
            val chosen = when (slot) {
                AnglePoint.A -> state.a
                AnglePoint.B -> state.b
                AnglePoint.C -> state.c
            }
            val label = stringResource(slotLabel(slot))
            val text = if (chosen == null) {
                stringResource(R.string.angle_choose, label)
            } else {
                stringResource(R.string.angle_slot_value, label, chosen.name)
            }
            OutlinedButton(
                onClick = { actions.onPick(slot) },
                modifier = Modifier.fillMaxWidth().heightIn(min = MinTouchTarget).testTag(angleSlotTag(slot)),
            ) { Text(text) }
        }
    }
}

private fun slotLabel(slot: AnglePoint) = when (slot) {
    AnglePoint.A -> R.string.angle_slot_a
    AnglePoint.B -> R.string.angle_slot_b
    AnglePoint.C -> R.string.angle_slot_c
}

@Composable
private fun Result(state: ThreePointAngleUi) {
    val locale = uiLocale()
    val unit = unitSymbol(state.linearUnit)
    when (val result = state.result) {
        null -> Text(stringResource(R.string.angle_hint), modifier = Modifier.testTag(ANGLE_HINT_TAG))
        is AngleResult.NoPosition -> Text(stringResource(R.string.geometry_problem_no_position), color = MaterialTheme.colorScheme.error)
        is AngleResult.Coincident -> {
            val names = mapOf(AnglePoint.A to state.a?.name, AnglePoint.B to state.b?.name, AnglePoint.C to state.c?.name)
            val pairs = result.pairs.map { (first, second) ->
                stringResource(R.string.angle_pair, first.name, names[first].orEmpty(), second.name, names[second].orEmpty())
            }.joinToString("; ")
            Card(modifier = Modifier.fillMaxWidth().testTag(ANGLE_COINCIDENT_TAG).semantics(mergeDescendants = true) {}) {
                Text(
                    stringResource(R.string.angle_coincident, pairs),
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(12.dp),
                )
            }
        }
        is AngleResult.Angle -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Line(R.string.angle_result, AngleFormat.direction(result.angleRad, state.angularUnit, locale), ANGLE_RESULT_TAG)
                    Line(
                        R.string.angle_azimuth_ba,
                        AngleFormat.direction(result.azimuthBaRad, state.angularUnit, locale),
                        ANGLE_AZIMUTH_BA_TAG,
                    )
                    Line(
                        R.string.angle_azimuth_bc,
                        AngleFormat.direction(result.azimuthBcRad, state.angularUnit, locale),
                        ANGLE_AZIMUTH_BC_TAG,
                    )
                    Line(
                        R.string.angle_distance_ba,
                        "${LengthFormat.inUnit(result.distanceBa, state.linearUnit, locale)} $unit",
                        ANGLE_DISTANCE_BA_TAG,
                    )
                    Line(
                        R.string.angle_distance_bc,
                        "${LengthFormat.inUnit(result.distanceBc, state.linearUnit, locale)} $unit",
                        ANGLE_DISTANCE_BC_TAG,
                    )
                }
            }
            FactorNotices(result.notices, localPlane = false)
        }
    }
}

@Composable
private fun Line(label: Int, value: String, tag: String) {
    Row(
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().testTag(tag).semantics(mergeDescendants = true) {},
    ) {
        Text(stringResource(label), style = MaterialTheme.typography.titleSmall)
        Text(value, style = MaterialTheme.typography.titleMedium.copy(fontFamily = FontFamily.Monospace))
    }
}

@Composable
private fun Picker(state: ThreePointAngleUi, actions: ThreePointAngleActions) {
    val slot = state.picking ?: return
    AlertDialog(
        onDismissRequest = actions.onClosePicker,
        title = { Text(stringResource(R.string.angle_picker_title, stringResource(slotLabel(slot)))) },
        text = {
            Column(modifier = Modifier.testTag(ANGLE_PICKER_TAG), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = state.query,
                    onValueChange = actions.onQuery,
                    label = { Text(stringResource(R.string.angle_search)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (state.candidates.isEmpty()) {
                    Text(stringResource(R.string.angle_no_points))
                } else {
                    LazyColumn(modifier = Modifier.heightIn(max = 320.dp)) {
                        items(state.candidates, key = { it.id }) { point ->
                            Text(
                                point.name,
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.fillMaxWidth().heightIn(min = MinTouchTarget).clickable { actions.onChoose(point.id) }
                                    .padding(vertical = 14.dp).testTag(anglePickerPointTag(point.name)),
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = actions.onClosePicker, modifier = Modifier.heightIn(min = MinTouchTarget)) {
                Text(stringResource(R.string.geometry_close))
            }
        },
    )
}
