package es.topophone.app.ui.stakeout

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import es.topophone.app.R
import es.topophone.app.ui.baseshift.BaseShiftActiveBanner
import es.topophone.app.ui.collector.AverageStatsBlock
import es.topophone.app.ui.collector.AverageStatsUi
import es.topophone.app.ui.collector.CollectorActions
import es.topophone.app.ui.collector.CollectorExpandedAlert
import es.topophone.app.ui.collector.CollectorGearSelector
import es.topophone.app.ui.collector.CollectorNotice
import es.topophone.app.ui.collector.CollectorNoticeLine
import es.topophone.app.ui.collector.CollectorNotices
import es.topophone.app.ui.collector.CollectorPhotoBar
import es.topophone.app.ui.collector.CollectorUiState
import es.topophone.app.ui.collector.LiveQuality
import es.topophone.app.ui.collector.LongPauseDialog
import es.topophone.app.ui.collector.MeasureReasonLine
import es.topophone.app.ui.collector.MeasureReasons
import es.topophone.app.ui.collector.MeasuringBar
import es.topophone.app.ui.collector.NamedMeasureButton
import es.topophone.app.ui.collector.OutOfToleranceDialog
import es.topophone.app.ui.collector.PauseState
import es.topophone.app.ui.collector.ReasonAction
import es.topophone.app.ui.collector.StatusLine
import es.topophone.app.ui.field.FieldSheet
import es.topophone.app.ui.field.FieldSheetValue
import es.topophone.app.ui.field.NumericEntryDialog
import es.topophone.app.ui.field.SettingPillData
import es.topophone.app.ui.field.SettingsRow
import es.topophone.app.ui.field.ShortListDialog
import es.topophone.app.ui.projects.unitSymbol
import es.topophone.app.ui.receiver.uiLocale
import es.topophone.core.designsystem.component.MinTouchTarget
import es.topophone.core.model.format.LengthFormat
import es.topophone.core.model.point.StakeMode
import es.topophone.core.model.point.StakeoutSettings
import es.topophone.core.model.project.LinearUnit

const val STAKEOUT_TITLE_TAG = "stakeout_title"
const val STAKEOUT_PILL_TOLERANCE_TAG = "stakeout_pill_tolerance"
const val STAKEOUT_PILL_TOLERANCE_V_TAG = "stakeout_pill_tolerance_v"
const val STAKEOUT_PILL_ELEVATION_TAG = "stakeout_elevation"
const val STAKEOUT_PILL_MODE_TAG = "stakeout_pill_mode"

/** «Anterior» y «Siguiente» entre los puntos o los elementos del replanteo (S060/RF-30; S018). */
internal class StakeNavigation(val hasPrevious: Boolean, val hasNext: Boolean, val onPrevious: () -> Unit, val onNext: () -> Unit)

/**
 * La hoja común de los replanteos de punto, línea, eje y talud (S060/RF-23): la cabecera con el título y el estado de la solución, y
 * plegada la placa de guiado, la fila de ajustes, la fila extra del motivo o el aviso y, abajo, «Anterior», «Medir» y «Siguiente» (o el
 * progreso y «Cancelar» midiendo). Expandida añade lo propio del replanteo y del recolector (jalón, fotos, avisos). La pantalla no se apaga
 * mientras está abierta (RNF-T2).
 */
@Composable
internal fun StakeoutSheet(
    title: String,
    onClose: () -> Unit,
    live: LiveQuality?,
    collector: CollectorUiState?,
    collectorActions: CollectorActions,
    navigation: StakeNavigation?,
    plate: @Composable () -> Unit,
    pills: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    unit: LinearUnit = LinearUnit.METRE,
    tag: String = STAKEOUT_PANEL_TAG,
    closeTag: String = STAKEOUT_CLOSE_TAG,
    titleTag: String = STAKEOUT_TITLE_TAG,
    stats: AverageStatsUi? = null,
    result: (@Composable () -> Unit)? = null,
    expandedExtra: @Composable ColumnScope.() -> Unit = {},
) {
    KeepScreenOn()
    var value by rememberSaveable { mutableStateOf(FieldSheetValue.Folded) }
    FieldSheet(
        value = value,
        onValueChange = { value = it },
        onClose = onClose,
        modifier = modifier,
        tag = tag,
        closeTag = closeTag,
        header = {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.semantics(mergeDescendants = true) {}.testTag(titleTag),
            )
            StatusLine(live, unit, collector?.settings?.precision ?: es.topophone.core.model.point.PrecisionControl())
        },
        folded = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                plate()
                pills()
                collector?.let { Slot(it, collectorActions, result) { value = FieldSheetValue.Expanded } }
                collector?.let { Actions(it, collectorActions, navigation) }
            }
        },
        expanded = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                expandedExtra()
                collector?.let {
                    CollectorGearSelector(it, collectorActions) // S046/RF-23
                    it.baseShiftName?.let { name -> BaseShiftActiveBanner(name, collectorActions.onDeactivateBaseShift) } // S020/RF-11
                    CollectorExpandedAlert(it, collectorActions) // S060/RF-22
                    CollectorPhotoBar(it.photos, collectorActions) // S017b
                    if (it.progress != null && stats != null) AverageStatsBlock(stats, unit) // S017/RF-3 a RF-5
                }
            }
        },
    )
    collector?.pending?.let { OutOfToleranceDialog(it, collectorActions.onConfirm, collectorActions.onDiscard) } // S018/RF-26
    if (collector?.paused == PauseState.LONG_PAUSE) {
        LongPauseDialog(collectorActions.onFinish, collectorActions.onCancel, collectorActions.onKeepWaiting)
    }
}

/** La fila extra de la hoja plegada: el motivo de bloqueo o, si no hay, el aviso (S060/RF-16, RF-22). */
@Composable
private fun Slot(collector: CollectorUiState, actions: CollectorActions, result: (@Composable () -> Unit)?, onExpand: () -> Unit) {
    val reason = if (collector.measuring) null else MeasureReasons.of(collector.blocker)
    val notice = CollectorNotices.current(collector)?.takeUnless { it is CollectorNotice.Saved }
    when {
        reason != null -> MeasureReasonLine(reason) { action ->
            when (action) {
                ReasonAction.OPEN_RECEIVER -> actions.onOpenReceiver()
                ReasonAction.OPEN_PROJECT_CRS -> actions.onOpenProjectCrs()
                ReasonAction.OPEN_TILT -> actions.onOpenTilt()
                ReasonAction.OPEN_GEAR -> actions.onOpenGear()
                // El jalón y el tiempo del recolector se cambian en su pantalla; aquí se lleva a la hoja expandida (S060/RF-16).
                ReasonAction.EDIT_POLE, ReasonAction.EDIT_TIME, ReasonAction.EXPAND -> onExpand()
            }
        }
        notice != null -> CollectorNoticeLine(notice)
        else -> result?.invoke() // lo que se desvió el último punto, en la misma fila (S018/RF-28)
    }
}

/** «Anterior», «Medir» y «Siguiente» en una fila de 56 dp, o el progreso y «Cancelar» midiendo (S060/RF-15, RF-30). */
@Composable
private fun Actions(collector: CollectorUiState, actions: CollectorActions, navigation: StakeNavigation?) {
    if (collector.measuring) {
        MeasuringBar(collector, actions)
        return
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        navigation?.let {
            OutlinedButton(
                onClick = it.onPrevious,
                enabled = it.hasPrevious,
                modifier = Modifier.heightIn(min = MinTouchTarget).testTag(STAKEOUT_PREVIOUS_TAG),
            ) { Text(stringResource(R.string.stakeout_previous)) }
        }
        NamedMeasureButton(collector, actions, Modifier.weight(1f))
        navigation?.let {
            OutlinedButton(
                onClick = it.onNext,
                enabled = it.hasNext,
                modifier = Modifier.heightIn(min = MinTouchTarget).testTag(STAKEOUT_NEXT_TAG),
            ) { Text(stringResource(R.string.stakeout_next)) }
        }
    }
}

private enum class StakePillDialog { TOLERANCE, TOLERANCE_V, MODE }

/**
 * La fila de ajustes del replanteo: «Tolerancia H», «Tolerancia V» (solo con cota de diseño), «Cota» y «Modo». Las tolerancias se escriben
 * con el teclado numérico en la unidad del proyecto y con el rango de S018; «Cota» abre el diálogo de siempre; «Modo» una lista corta
 * (S060/RF-27). Con una medida en marcha las pastillas se desactivan (RF-15).
 */
@Composable
internal fun StakeoutPills(
    unit: LinearUnit,
    settings: StakeoutSettings,
    enabled: Boolean,
    showToleranceV: Boolean,
    elevationText: String?,
    onTolerance: (String) -> Boolean,
    onToleranceV: (String) -> Boolean,
    onMode: (StakeMode) -> Unit,
    onElevation: () -> Unit,
) {
    var dialog by remember { mutableStateOf<StakePillDialog?>(null) }
    val locale = uiLocale()
    val symbol = unitSymbol(unit)

    fun length(valueM: Double) = "${LengthFormat.inUnit(valueM, unit, locale)} $symbol"
    val pills = buildList {
        add(
            SettingPillData(
                stringResource(R.string.field_pill_tol_h),
                length(settings.validToleranceM),
                STAKEOUT_PILL_TOLERANCE_TAG,
                enabled,
            ) {
                dialog = StakePillDialog.TOLERANCE
            },
        )
        if (showToleranceV) {
            add(
                SettingPillData(
                    stringResource(R.string.field_pill_tol_v),
                    length(settings.validToleranceVM),
                    STAKEOUT_PILL_TOLERANCE_V_TAG,
                    enabled,
                ) { dialog = StakePillDialog.TOLERANCE_V },
            )
        }
        add(
            SettingPillData(
                stringResource(R.string.field_pill_elevation),
                elevationText ?: stringResource(R.string.collector_no_accuracy),
                STAKEOUT_PILL_ELEVATION_TAG,
                enabled,
                onElevation,
            ),
        )
        add(
            SettingPillData(
                stringResource(R.string.field_pill_mode),
                stringResource(settings.mode.textRes()),
                STAKEOUT_PILL_MODE_TAG,
                enabled,
            ) { dialog = StakePillDialog.MODE },
        )
    }
    SettingsRow(pills)
    val range = StakeoutSettings.toleranceRangeIn(unit)
    val rangeArgs = listOf(LengthFormat.inUnit(range.start, unit, locale), LengthFormat.inUnit(range.endInclusive, unit, locale), symbol)
    when (dialog) {
        null -> Unit
        StakePillDialog.TOLERANCE -> NumericEntryDialog(
            title = stringResource(R.string.field_pill_tol_h),
            label = stringResource(R.string.stakeout_tolerance_label, symbol),
            initial = LengthFormat.inUnit(settings.validToleranceM, unit, locale),
            validate = { text -> R.string.stakeout_tolerance_invalid.takeIf { StakeoutSettings.parseTolerance(text, unit) == null } },
            errorArgs = rangeArgs,
            onConfirm = { onTolerance(it) },
            onDismiss = { dialog = null },
        )
        StakePillDialog.TOLERANCE_V -> NumericEntryDialog(
            title = stringResource(R.string.field_pill_tol_v),
            label = stringResource(R.string.stakeout_tolerance_v_label, symbol),
            initial = LengthFormat.inUnit(settings.validToleranceVM, unit, locale),
            validate = { text -> R.string.stakeout_tolerance_invalid.takeIf { StakeoutSettings.parseTolerance(text, unit) == null } },
            errorArgs = rangeArgs,
            onConfirm = { onToleranceV(it) },
            onDismiss = { dialog = null },
        )
        StakePillDialog.MODE -> ShortListDialog(
            title = stringResource(R.string.field_mode_title),
            options = StakeMode.entries.map { it to stringResource(it.textRes()) },
            selected = settings.mode,
            onSelect = onMode,
            onDismiss = { dialog = null },
        )
    }
}
