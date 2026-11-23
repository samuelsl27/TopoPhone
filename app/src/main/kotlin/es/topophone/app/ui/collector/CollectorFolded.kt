package es.topophone.app.ui.collector

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import es.topophone.app.R
import es.topophone.app.ui.field.NumericEntryDialog
import es.topophone.app.ui.field.SettingPillData
import es.topophone.app.ui.field.SettingsRow
import es.topophone.app.ui.field.ShortListDialog
import es.topophone.app.ui.field.TimeEntryDialog
import es.topophone.app.ui.projects.unitSymbol
import es.topophone.app.ui.receiver.labelRes
import es.topophone.core.designsystem.component.MinTouchTarget
import es.topophone.core.model.format.DurationText
import es.topophone.core.model.point.CollectorSettings
import es.topophone.core.model.project.LinearUnit
import es.topophone.core.survey.PoleHeight

const val COLLECTOR_POLE_PILL_TAG = "collector_pill_pole"
const val COLLECTOR_TIME_PILL_TAG = "collector_pill_time"
const val COLLECTOR_MODE_PILL_TAG = "collector_pill_mode"
const val COLLECTOR_TILT_PILL_TAG = "collector_pill_tilt"

private enum class PillDialog { POLE, TIME, MODE }

/**
 * El recolector con la hoja plegada, de arriba abajo: la línea de estado, la fila de ajustes (Jalón, Tiempo, Modo, Inclinación), las
 * pastillas informativas, el motivo de bloqueo si lo hay y «Medir» con el nombre del punto (S060/RF-8, RF-9, RF-11, RF-13, RF-16).
 * Los selectores usan las acciones de siempre. Mientras se mide, las pastillas se desactivan (RF-15).
 */
@Composable
fun CollectorFolded(
    state: CollectorUiState,
    unit: LinearUnit,
    actions: CollectorActions,
    onExpand: () -> Unit,
    modifier: Modifier = Modifier,
    stats: AverageStatsUi? = null,
    autoLive: AutoLive? = null,
) {
    var dialog by remember { mutableStateOf<PillDialog?>(null) }
    val symbol = unitSymbol(unit)
    val mode = CollectionModes.current(state)
    val idle = !state.measuring && !state.auto.running
    val pills = listOf(
        SettingPillData(stringResource(R.string.field_pill_pole), "${state.poleHeightText} $symbol", COLLECTOR_POLE_PILL_TAG, idle) {
            dialog = PillDialog.POLE
        },
        SettingPillData(
            stringResource(R.string.field_pill_time),
            CollectionModes.timeText(state, symbol) ?: stringResource(R.string.collector_mode_instant),
            COLLECTOR_TIME_PILL_TAG,
            idle && CollectionModes.timeEditable(mode),
        ) { dialog = PillDialog.TIME },
        SettingPillData(stringResource(R.string.field_pill_mode), stringResource(mode.labelRes()), COLLECTOR_MODE_PILL_TAG, idle) {
            dialog = PillDialog.MODE
        },
        SettingPillData(
            stringResource(R.string.field_pill_tilt),
            stringResource(state.tiltMode?.labelRes() ?: R.string.field_tilt_unset),
            COLLECTOR_TILT_PILL_TAG,
            idle,
            actions.onOpenTilt,
        ),
    )
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        StatusLine(state.live, unit, state.settings.precision)
        SettingsRow(pills)
        InfoPills(state.settings.precision, state.baseShiftName, state.localized, unit) { kind ->
            when (kind) {
                InfoPillKind.PRECISION -> onExpand()
                InfoPillKind.BASE_SHIFT -> actions.onRepeatBaseShift()
                InfoPillKind.LOCALIZATION -> actions.onOpenLocalization()
            }
        }
        if (!state.measuring && !state.auto.running) {
            MeasureReasonLine(MeasureReasons.of(state.blocker)) { action ->
                when (action) {
                    ReasonAction.OPEN_RECEIVER -> actions.onOpenReceiver()
                    ReasonAction.OPEN_PROJECT_CRS -> actions.onOpenProjectCrs()
                    ReasonAction.OPEN_TILT -> actions.onOpenTilt()
                    ReasonAction.OPEN_GEAR -> actions.onOpenGear()
                    ReasonAction.EDIT_POLE -> dialog = PillDialog.POLE
                    ReasonAction.EDIT_TIME -> dialog = PillDialog.TIME
                    ReasonAction.EXPAND -> onExpand()
                }
            }
        }
        when {
            state.autoEnabled || state.auto.running -> AutoControls(state, unit, autoLive, actions)
            state.measuring -> Measuring(state, unit, stats, actions)
            else -> NamedMeasureButton(state, actions)
        }
    }
    FoldedDialogs(dialog, state, unit, mode, actions) { dialog = null }
}

@Composable
private fun FoldedDialogs(
    dialog: PillDialog?,
    state: CollectorUiState,
    unit: LinearUnit,
    mode: CollectionMode,
    actions: CollectorActions,
    onDismiss: () -> Unit,
) {
    val symbol = unitSymbol(unit)
    when (dialog) {
        null -> Unit
        PillDialog.POLE -> NumericEntryDialog(
            title = stringResource(R.string.field_pole_title),
            label = stringResource(R.string.field_pole_label, symbol),
            initial = state.poleHeightText,
            validate = { text -> R.string.pole_height_invalid.takeIf { PoleHeight.parse(text) == null } },
            onConfirm = actions.onPoleHeight,
            onDismiss = onDismiss,
        )
        PillDialog.TIME -> when (mode) {
            CollectionMode.AVERAGED -> TimeEntryDialog(
                title = stringResource(R.string.field_time_title),
                initialSeconds = DurationText.parse(state.averagingSecondsText, CollectorSettings.AVERAGING_SECONDS) ?: 0,
                validate = { text ->
                    R.string.collector_seconds_invalid.takeIf {
                        DurationText.parse(text, CollectorSettings.AVERAGING_SECONDS) ==
                            null
                    }
                },
                onConfirm = { CollectionModes.applyTime(mode, it, actions) },
                onDismiss = onDismiss,
            )
            CollectionMode.AUTO_TIME -> TimeEntryDialog(
                title = stringResource(R.string.field_time_title),
                initialSeconds = DurationText.parse(state.autoIntervalText, CollectorSettings.AUTO_INTERVAL_SECONDS) ?: 0,
                validate = { text ->
                    R.string.collector_seconds_invalid.takeIf {
                        DurationText.parse(text, CollectorSettings.AUTO_INTERVAL_SECONDS) ==
                            null
                    }
                },
                onConfirm = { CollectionModes.applyTime(mode, it, actions) },
                onDismiss = onDismiss,
            )
            CollectionMode.AUTO_DISTANCE -> NumericEntryDialog(
                title = stringResource(R.string.field_time_title),
                label = stringResource(R.string.field_distance_label, symbol),
                initial = state.autoDistanceText,
                validate = { text -> R.string.field_distance_invalid.takeIf { CollectorSettings.parseAutoDistance(text, unit) == null } },
                onConfirm = { CollectionModes.applyTime(mode, it, actions) },
                onDismiss = onDismiss,
            )
            CollectionMode.INSTANT -> Unit
        }
        PillDialog.MODE -> ShortListDialog(
            title = stringResource(R.string.field_mode_title),
            options = CollectionMode.entries.map { it to stringResource(it.labelRes()) },
            selected = mode,
            onSelect = { CollectionModes.choose(it, state, actions) },
            onDismiss = onDismiss,
        )
    }
}

internal fun CollectionMode.labelRes(): Int = when (this) {
    CollectionMode.INSTANT -> R.string.collector_mode_instant
    CollectionMode.AVERAGED -> R.string.collector_mode_averaged
    CollectionMode.AUTO_TIME -> R.string.field_mode_auto_time
    CollectionMode.AUTO_DISTANCE -> R.string.field_mode_auto_distance
}

/** «Medir» con el nombre del punto que se medirá; sin nombre, solo «Medir» (S060/RF-8). */
@Composable
internal fun NamedMeasureButton(state: CollectorUiState, actions: CollectorActions) {
    Button(
        onClick = actions.onMeasure,
        enabled = state.blocker == null && state.pending == null,
        modifier = Modifier.fillMaxWidth().heightIn(min = MinTouchTarget).testTag(COLLECTOR_MEASURE_TAG),
    ) {
        Text(
            if (state.name.isBlank()) {
                stringResource(
                    R.string.collector_measure,
                )
            } else {
                stringResource(R.string.field_measure_named, state.name)
            },
        )
    }
}
