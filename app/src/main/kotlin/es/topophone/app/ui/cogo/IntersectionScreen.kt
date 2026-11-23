package es.topophone.app.ui.cogo

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
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
import es.topophone.core.model.cogo.IntersectionSide
import es.topophone.core.model.format.LengthFormat
import es.topophone.core.model.geometry.DistanceMode
import es.topophone.core.model.preferences.ThemeState
import es.topophone.core.survey.cogo.IntersectionResult
import es.topophone.core.survey.cogo.NoSolutionReason

const val INTERSECTION_HINT_TAG = "intersection_hint"
const val INTERSECTION_NO_SOLUTION_TAG = "intersection_no_solution"

fun intersectionKindTag(kind: IntersectionKind) = "intersection_kind_${kind.name.lowercase()}"

fun intersectionSolutionTag(side: IntersectionSide) = "intersection_solution_${side.name.lowercase()}"

fun intersectionMarkTag(side: IntersectionSide) = "intersection_mark_${side.name.lowercase()}"

fun intersectionBehindTag(side: IntersectionSide) = "intersection_behind_${side.name.lowercase()}"

/** Lo que el usuario hace en la intersección (S038/RF-3, RF-5, RF-13, RF-18, RF-19). */
class IntersectionActions(
    val onKind: (IntersectionKind) -> Unit = {},
    val onPick: (String) -> Unit = {},
    val onQuery: (String) -> Unit = {},
    val onChoose: (String) -> Unit = {},
    val onTyped: (slot: String, easting: String, northing: String) -> Unit = { _, _, _ -> },
    val onClosePicker: () -> Unit = {},
    val onMode: (DistanceMode) -> Unit = {},
    val onValue1: (String) -> Unit = {},
    val onValue2: (String) -> Unit = {},
    val onHeight: (IntersectionSide, String) -> Unit = { _, _ -> },
    val onToggle: (IntersectionSide) -> Unit = {},
    val onOpenSave: () -> Unit = {},
    val onSaveName: (key: String, name: String) -> Unit = { _, _ -> },
    val onSave: () -> Unit = {},
    val onDismissSave: () -> Unit = {},
)

@Composable
fun IntersectionRoute(
    projectId: String,
    themeState: ThemeState,
    onToggleSun: () -> Unit,
    viewModel: IntersectionViewModel = hiltViewModel(),
    onBack: () -> Unit,
) {
    LaunchedEffect(projectId) { viewModel.start(projectId) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val saveUi by viewModel.save.state.collectAsStateWithLifecycle()
    IntersectionScreen(
        themeState,
        onToggleSun,
        state,
        IntersectionActions(
            onKind = viewModel::setKind,
            onPick = viewModel.inputs::open,
            onQuery = viewModel.inputs::setQuery,
            onChoose = viewModel.inputs::chooseProject,
            onTyped = viewModel.inputs::setTyped,
            onClosePicker = viewModel.inputs::close,
            onMode = viewModel::setMode,
            onValue1 = viewModel::setValue1,
            onValue2 = viewModel::setValue2,
            onHeight = viewModel::setHeight,
            onToggle = viewModel::toggleMarked,
            onOpenSave = viewModel::openSave,
            onSaveName = viewModel.save::setName,
            onSave = viewModel.save::save,
            onDismissSave = viewModel.save::dismiss,
        ),
        onBack,
        saveUi = saveUi,
    )
}

/**
 * La intersección (S038/RF-13 a RF-18): la clase, los dos puntos con su condición y, con ellos, cada solución con su posición, su
 * distancia desde cada punto y su altura opcional. La izquierda y la derecha se dicen con texto (RNF-T8).
 */
@Composable
fun IntersectionScreen(
    themeState: ThemeState,
    onToggleSun: () -> Unit,
    state: IntersectionUi,
    actions: IntersectionActions,
    onBack: () -> Unit,
    footer: @Composable () -> Unit = {},
    saveUi: CogoSaveUi = CogoSaveUi(),
) {
    SectionScaffold(
        title = stringResource(R.string.cogo_intersection),
        themeState = themeState,
        onToggleSun = onToggleSun,
        onNavigateBack = onBack,
    ) { padding ->
        if (!state.loaded) return@SectionScaffold
        val first = stringResource(R.string.cogo_point_first)
        val second = stringResource(R.string.cogo_point_second)
        Column(
            modifier = Modifier.padding(padding).padding(horizontal = 16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Kinds(state.kind, actions.onKind)
            CogoModeOrTerrain(state.mode, actions.onMode, state.terrain)
            CogoPointField(SLOT_FIRST, first, state.first, state.linearUnit, actions.onPick)
            CogoPointField(SLOT_SECOND, second, state.second, state.linearUnit, actions.onPick)
            Conditions(state, actions)
            Result(state, actions)
            CogoSaveButton(state.markedCount, state.canSave, actions.onOpenSave)
            footer()
        }
    }
    val sideLabels = IntersectionSide.entries.associate { it.name to stringResource(shortSideLabel(it)) }
    CogoSaveDialog(saveUi, sideLabels, actions.onSaveName, actions.onSave, actions.onDismissSave)
    val picker = state.picker
    if (picker.slot != null) {
        val label = if (picker.slot == SLOT_FIRST) stringResource(R.string.cogo_point_first) else stringResource(R.string.cogo_point_second)
        val typed = (if (picker.slot == SLOT_FIRST) state.firstSelection else state.secondSelection) as? CogoSelection.Typed
        CogoPickerDialog(picker, label, typed, actions.onQuery, actions.onChoose, actions.onTyped, actions.onClosePicker)
    }
}

@Composable
private fun Kinds(selected: IntersectionKind, onKind: (IntersectionKind) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (kind in IntersectionKind.entries) {
            FilterChip(
                selected = kind == selected,
                onClick = { onKind(kind) },
                label = { Text(stringResource(kindLabel(kind))) },
                modifier = Modifier.fillMaxWidth().heightIn(min = MinTouchTarget).testTag(intersectionKindTag(kind)),
            )
        }
    }
}

private fun kindLabel(kind: IntersectionKind) = when (kind) {
    IntersectionKind.DISTANCE_DISTANCE -> R.string.cogo_kind_distance_distance
    IntersectionKind.DIRECTION_DIRECTION -> R.string.cogo_kind_direction_direction
    IntersectionKind.DISTANCE_DIRECTION -> R.string.cogo_kind_distance_direction
}

@Composable
private fun Conditions(state: IntersectionUi, actions: IntersectionActions) {
    val unit = unitSymbol(state.linearUnit)
    val (label1, label2) = when (state.kind) {
        IntersectionKind.DISTANCE_DISTANCE ->
            stringResource(R.string.cogo_distance_from_first, unit) to stringResource(R.string.cogo_distance_from_second, unit)
        IntersectionKind.DIRECTION_DIRECTION ->
            stringResource(R.string.cogo_azimuth_from_first) to stringResource(R.string.cogo_azimuth_from_second)
        IntersectionKind.DISTANCE_DIRECTION ->
            stringResource(R.string.cogo_distance_from_first, unit) to stringResource(R.string.cogo_azimuth_from_second)
    }
    CogoNumberField("value1", label1, state.value1Text, state.value1Invalid, actions.onValue1)
    CogoNumberField("value2", label2, state.value2Text, state.value2Invalid, actions.onValue2)
}

@Composable
private fun Result(state: IntersectionUi, actions: IntersectionActions) {
    when (val result = state.result) {
        null -> Text(stringResource(R.string.cogo_intersection_choose_hint), modifier = Modifier.testTag(INTERSECTION_HINT_TAG))
        is IntersectionResult.NoSolution -> Card(
            modifier = Modifier.fillMaxWidth().testTag(INTERSECTION_NO_SOLUTION_TAG).semantics(mergeDescendants = true) {},
        ) {
            Text(
                stringResource(R.string.cogo_no_solution, stringResource(reasonLabel(result.reason))),
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(12.dp),
            )
        }
        is IntersectionResult.Solutions -> {
            state.solutions.forEach { Solution(it, state, actions) }
            FactorNotices(state.notices)
        }
    }
}

private fun reasonLabel(reason: NoSolutionReason) = when (reason) {
    NoSolutionReason.CONCENTRIC -> R.string.cogo_reason_concentric
    NoSolutionReason.TOO_FAR -> R.string.cogo_reason_too_far
    NoSolutionReason.TOO_CLOSE -> R.string.cogo_reason_too_close
    NoSolutionReason.PARALLEL -> R.string.cogo_reason_parallel
    NoSolutionReason.LINE_MISSES -> R.string.cogo_reason_line_misses
}

private fun shortSideLabel(side: IntersectionSide) = when (side) {
    IntersectionSide.LEFT -> R.string.cogo_side_left_short
    IntersectionSide.RIGHT -> R.string.cogo_side_right_short
    IntersectionSide.FIRST -> R.string.cogo_side_first_short
    IntersectionSide.SECOND -> R.string.cogo_side_second_short
    IntersectionSide.SINGLE -> R.string.cogo_side_single_short
}

private fun sideLabel(side: IntersectionSide) = when (side) {
    IntersectionSide.LEFT -> R.string.cogo_side_left
    IntersectionSide.RIGHT -> R.string.cogo_side_right
    IntersectionSide.FIRST -> R.string.cogo_side_first
    IntersectionSide.SECOND -> R.string.cogo_side_second
    IntersectionSide.SINGLE -> R.string.cogo_side_single
}

@Composable
private fun Solution(solution: IntersectionSolutionUi, state: IntersectionUi, actions: IntersectionActions) {
    val locale = uiLocale()
    val unit = unitSymbol(state.linearUnit)
    fun length(valueM: Double) = "${LengthFormat.inUnit(valueM, state.linearUnit, locale)} $unit"
    val mono = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace)
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Column(
                modifier = Modifier.testTag(intersectionSolutionTag(solution.side)).semantics(mergeDescendants = true) {},
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(stringResource(sideLabel(solution.side)), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.cogo_point_coordinates, length(solution.easting), length(solution.northing)), style = mono)
                Text(
                    stringResource(R.string.cogo_solution_distances, length(solution.distance1), length(solution.distance2)),
                    style = mono,
                )
                if (solution.behind) {
                    Text(
                        stringResource(R.string.cogo_behind),
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.testTag(intersectionBehindTag(solution.side)),
                    )
                }
            }
            CogoNumberField(
                key = "height_${solution.side.name.lowercase()}",
                label = stringResource(R.string.cogo_solution_height, unit),
                text = solution.heightText,
                invalid = solution.heightInvalid,
                onChange = { actions.onHeight(solution.side, it) },
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().heightIn(min = MinTouchTarget).testTag(intersectionMarkTag(solution.side))
                    .toggleable(value = solution.marked, role = Role.Checkbox, onValueChange = { actions.onToggle(solution.side) }),
            ) {
                Checkbox(checked = solution.marked, onCheckedChange = null)
                Text(stringResource(R.string.cogo_mark_to_save), modifier = Modifier.padding(start = 8.dp))
            }
        }
    }
}
