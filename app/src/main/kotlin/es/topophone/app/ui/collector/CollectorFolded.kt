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
import es.topophone.app.ui.receiver.uiLocale
import es.topophone.core.designsystem.component.MinTouchTarget
import es.topophone.core.model.format.DurationText
import es.topophone.core.model.format.LengthFormat
import es.topophone.core.model.point.CollectorSettings
import es.topophone.core.model.point.TiltMode
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
    /** Falso cuando la línea de estado ya va en la cabecera de la hoja (S060/RF-8). */
    showStatus: Boolean = true,
) {
    var dialog by remember { mutableStateOf<PillDialog?>(null) }
    val symbol = unitSymbol(unit)
    val mode = CollectionModes.current(state)
    val idle = !state.measuring && !state.auto.running
    val pills = listOf(
        // S017/RF-32: con el automático en marcha el jalón sigue siendo editable; solo la medida en curso lo bloquea.
        SettingPillData(
            stringResource(R.string.field_pill_pole),
            "${state.poleHeightText} $symbol",
            COLLECTOR_POLE_PILL_TAG,
            !state.measuring,
        ) {
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
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (showStatus) StatusLine(state.live, unit, state.settings.precision, savedName = state.savedName)
        SettingsRow(pills)
        // Una sola fila extra en la hoja plegada, para dejar el mapa libre (S060/RF-2): el motivo de bloqueo, o el aviso, o las pastillas
        // informativas, por ese orden de prioridad. Las pastillas informativas se ven siempre al expandir (RF-14, RF-22).
        val reason = if (idle) MeasureReasons.of(state.blocker) else null
        val notice = CollectorNotices.current(state)?.takeUnless { it is CollectorNotice.Saved }
        when {
            reason != null -> MeasureReasonLine(reason) { action ->
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
            notice != null -> CollectorNoticeLine(notice)
            else -> InfoPills(state.settings.precision, state.baseShiftName, state.localized, unit) { kind ->
                when (kind) {
                    InfoPillKind.PRECISION -> onExpand()
                    InfoPillKind.BASE_SHIFT -> actions.onRepeatBaseShift()
                    InfoPillKind.LOCALIZATION -> actions.onOpenLocalization()
                }
            }
        }
        when {
            state.autoEnabled || state.auto.running -> AutoControls(state, unit, autoLive, actions)
            state.measuring -> MeasuringBar(state, actions)
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
            // S016/RF-15: con el receptor que compensa, la altura es la configurada en el receptor y se dice.
            label = if (state.tiltMode == TiltMode.RECEIVER) {
                stringResource(R.string.pole_height_receiver_label)
            } else {
                stringResource(R.string.field_pole_label, symbol)
            },
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
                validate = { text ->
                    R.string.collector_auto_distance_invalid.takeIf {
                        CollectorSettings.parseAutoDistance(text, unit) ==
                            null
                    }
                },
                errorArgs = distanceRangeArgs(unit),
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
internal fun NamedMeasureButton(state: CollectorUiState, actions: CollectorActions, modifier: Modifier = Modifier.fillMaxWidth()) {
    Button(
        onClick = actions.onMeasure,
        enabled = state.blocker == null && state.pending == null,
        modifier = modifier.heightIn(min = MinTouchTarget).testTag(COLLECTOR_MEASURE_TAG),
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

/** El rango de la distancia del modo automático en la unidad del proyecto, para decirlo junto al campo (S017/RF-17). */
@Composable
private fun distanceRangeArgs(unit: LinearUnit): List<Any> {
    val locale = uiLocale()
    val symbol = unitSymbol(unit)
    val range = CollectorSettings.autoDistanceRangeIn(unit)
    return listOf(
        "${LengthFormat.inUnit(range.start, unit, locale, decimals = 1)} $symbol",
        "${LengthFormat.inUnit(range.endInclusive, unit, locale, decimals = 1)} $symbol",
    )
}
