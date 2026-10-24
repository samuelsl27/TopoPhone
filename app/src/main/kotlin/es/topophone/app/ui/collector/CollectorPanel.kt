package es.topophone.app.ui.collector

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import es.topophone.app.R
import es.topophone.app.ui.projects.unitSymbol
import es.topophone.app.ui.receiver.BaseNoticeBanners
import es.topophone.app.ui.receiver.nameRes
import es.topophone.app.ui.receiver.uiLocale
import es.topophone.core.designsystem.component.MinTouchTarget
import es.topophone.core.model.format.DurationText
import es.topophone.core.model.format.LengthFormat
import es.topophone.core.model.point.AutoTrigger
import es.topophone.core.model.point.CollectorSettings
import es.topophone.core.model.point.MeasurementMode
import es.topophone.core.model.point.TiltMode
import es.topophone.core.model.project.LinearUnit

const val COLLECT_BUTTON_TAG = "collect"
const val COLLECTOR_PANEL_TAG = "collector_panel"
const val COLLECTOR_CLOSE_TAG = "collector_close"
const val COLLECTOR_NAME_TAG = "collector_name"
const val COLLECTOR_DESCRIPTION_TAG = "collector_description"
const val COLLECTOR_POLE_TAG = "collector_pole"
const val COLLECTOR_SECONDS_TAG = "collector_seconds"
const val COLLECTOR_LIVE_TAG = "collector_live"
const val COLLECTOR_MEASURE_TAG = "collector_measure"
const val COLLECTOR_CANCEL_TAG = "collector_cancel"
const val COLLECTOR_BLOCKER_TAG = "collector_blocker"

fun modeOptionTag(mode: MeasurementMode) = "collector_mode_${mode.name}"

/** Lo que el usuario hace en el recolector. */
const val COLLECTOR_RESTARTED_TAG = "collector_restarted"
const val COLLECTOR_OPEN_TILT_TAG = "collector_open_tilt"
const val COLLECTOR_TILT_LOST_TAG = "collector_tilt_lost"
const val COLLECTOR_TILT_LOST_GUIDE_TAG = "collector_tilt_lost_guide"
const val COLLECTOR_TILT_CHANGED_TAG = "collector_tilt_changed"

class CollectorActions(
    val onName: (String) -> Unit = {},
    val onDescription: (String) -> Unit = {},
    val onPoleHeight: (String) -> Unit = {},
    val onMode: (MeasurementMode) -> Unit = {},
    val onSeconds: (String) -> Unit = {},
    val onSettings: (CollectorSettings) -> Unit = {},
    val onMeasure: () -> Unit = {},
    val onCancel: () -> Unit = {},
    val onConfirm: () -> Unit = {},
    val onDiscard: () -> Unit = {},
    /** S015/RF-17. */
    val onDismissBaseChange: (Int) -> Unit = {},
    /** S015/RF-15. */
    val onDismissLongBaseline: (Int) -> Unit = {},
    /** El apartado "Inclinación" del receptor (S016/RF-2, RF-18). */
    val onOpenTilt: () -> Unit = {},
    /** "Terminar" y "Guardar con lo reunido" (S017/RF-6, RF-10). */
    val onFinish: () -> Unit = {},
    /** "Seguir esperando" a la pregunta de 5 min (S017/RF-10). */
    val onKeepWaiting: () -> Unit = {},
    /** El modo automático: encenderlo, el disparo, el intervalo, la distancia, "Iniciar" y "Parar" (S017/RF-15 a RF-17, RF-27). */
    val onAutoEnabled: (Boolean) -> Unit = {},
    val onAutoTrigger: (AutoTrigger) -> Unit = {},
    val onAutoInterval: (String) -> Unit = {},
    val onAutoDistance: (String) -> Unit = {},
    val onStartAuto: () -> Unit = {},
    val onStopAuto: () -> Unit = {},
)

/**
 * Panel del recolector, en lugar del panel de posición y sin tapar el mapa (S010/RF-1): nombre, descripción,
 * jalón, modo, solución y precisiones, y "Medir" o el progreso con "Cancelar". La pantalla no se apaga mientras
 * está abierto (S010/RF-7).
 */
@Composable
fun CollectorPanel(
    state: CollectorUiState,
    unit: LinearUnit,
    actions: CollectorActions,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    stats: AverageStatsUi? = null,
    autoLive: AutoLive? = null,
) {
    KeepScreenOn()
    Card(modifier = modifier.fillMaxWidth().padding(8.dp).testTag(COLLECTOR_PANEL_TAG)) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.collector_title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                TextButton(onClick = onClose, modifier = Modifier.heightIn(min = MinTouchTarget).testTag(COLLECTOR_CLOSE_TAG)) {
                    Text(stringResource(R.string.collector_close))
                }
            }
            // Solo el formulario se desplaza y encoge: la solución y "Medir" o "Cancelar" se ven siempre (S010/RF-6, RF-8, RF-11).
            Column(
                modifier = Modifier.weight(1f, fill = false).heightIn(
                    max = if (state.progress !=
                        null
                    ) {
                        FORM_MEASURING_MAX_HEIGHT
                    } else {
                        FORM_MAX_HEIGHT
                    },
                )
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) { Form(state, unit, actions) }
            BaseNoticeBanners(state.notices, actions.onDismissBaseChange, actions.onDismissLongBaseline) // S015/RF-15, RF-17
            LiveQualityText(state.live, unit)
            Messages(state, actions)
            when {
                state.autoEnabled || state.auto.running -> AutoControls(state, unit, autoLive, actions) // S017/RF-15
                state.measuring -> Measuring(state, unit, stats, actions)
                else -> MeasureButton(state, actions)
            }
        }
    }
    state.pending?.let { OutOfToleranceDialog(it, actions.onConfirm, actions.onDiscard) } // S010/RF-14, RF-15
    if (state.paused == PauseState.LONG_PAUSE) LongPauseDialog(actions.onFinish, actions.onCancel, actions.onKeepWaiting) // S017/RF-10
}

/** Nombre, descripción, jalón, modo y control de precisión. */
@Composable
private fun Form(state: CollectorUiState, unit: LinearUnit, actions: CollectorActions) {
    val receiverPole = state.tiltMode == TiltMode.RECEIVER
    NameField(state, actions)
    OutlinedTextField(
        value = state.description,
        onValueChange = actions.onDescription,
        label = { Text(stringResource(R.string.collector_description)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth().testTag(COLLECTOR_DESCRIPTION_TAG),
    ) // S010/RF-4
    OutlinedTextField(
        value = state.poleHeightText,
        onValueChange = actions.onPoleHeight,
        // S016/RF-15: con el receptor que compensa, es la altura configurada en el receptor y tienen que coincidir.
        label = { Text(stringResource(if (receiverPole) R.string.pole_height_receiver_label else R.string.pole_height_label)) },
        isError = !state.poleHeightValid,
        supportingText = when {
            !state.poleHeightValid -> ({ Text(stringResource(R.string.pole_height_invalid)) })
            receiverPole -> ({ Text(stringResource(R.string.pole_height_receiver_help)) })
            else -> null
        },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = Modifier.fillMaxWidth().testTag(COLLECTOR_POLE_TAG),
    ) // S010/RF-5
    ModeChoice(state, actions)
    AutoChoice(state, unit, actions) // S017/RF-15 a RF-17
    // S010/RF-13; con la inclinación, también la inclinación máxima (S016/RF-20).
    PrecisionControlRow(state.settings.precision, unit, showTilt = state.tiltMode?.usesTilt == true) {
        actions.onSettings(state.settings.copy(precision = it))
    }
}

@Composable
private fun NameField(state: CollectorUiState, actions: CollectorActions) {
    val error = when (state.blocker) {
        MeasureBlocker.NAME_TAKEN -> R.string.collector_name_taken // S010/RF-3
        MeasureBlocker.INVALID_NAME -> R.string.collector_name_invalid
        else -> null
    }
    OutlinedTextField(
        value = state.name,
        onValueChange = actions.onName,
        label = { Text(stringResource(R.string.collector_name)) },
        isError = error != null,
        supportingText = error?.let { { Text(stringResource(it)) } },
        singleLine = true,
        modifier = Modifier.fillMaxWidth().testTag(COLLECTOR_NAME_TAG),
    )
}

/** Instantáneo o promediado, con los segundos del promediado (S010/RF-8, RF-9). */
@Composable
private fun ModeChoice(state: CollectorUiState, actions: CollectorActions) {
    Row(modifier = Modifier.selectableGroup(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        MeasurementMode.entries.forEach { mode ->
            FilterChip(
                selected = state.settings.mode == mode,
                onClick = { actions.onMode(mode) },
                enabled = !state.auto.running, // S017/RF-32
                label = {
                    Text(
                        stringResource(
                            if (mode ==
                                MeasurementMode.INSTANT
                            ) {
                                R.string.collector_mode_instant
                            } else {
                                R.string.collector_mode_averaged
                            },
                        ),
                    )
                },
                modifier = Modifier.heightIn(min = MinTouchTarget).testTag(modeOptionTag(mode)),
            )
        }
    }
    if (state.settings.mode == MeasurementMode.AVERAGED) {
        OutlinedTextField(
            value = state.averagingSecondsText,
            onValueChange = actions.onSeconds,
            enabled = !state.auto.running, // S017/RF-32
            label = { Text(stringResource(R.string.collector_seconds)) },
            isError = !state.averagingSecondsValid,
            supportingText = if (state.averagingSecondsValid) null else ({ Text(stringResource(R.string.collector_seconds_invalid)) }),
            singleLine = true,
            // S017/RF-1: minutos y segundos, con ":", que el teclado numérico no tiene.
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
            modifier = Modifier.fillMaxWidth().testTag(COLLECTOR_SECONDS_TAG),
        )
    }
}

/** Solución y precisiones H y V en las unidades del proyecto (S010/RF-6). */
@Composable
private fun LiveQualityText(live: LiveQuality?, unit: LinearUnit) {
    if (live == null) return
    val locale = uiLocale()
    val symbol = unitSymbol(unit)
    val noAccuracy = stringResource(R.string.collector_no_accuracy)
    fun length(valueM: Double?) = valueM?.let { "${LengthFormat.inUnit(it, unit, locale)} $symbol" } ?: noAccuracy
    Text(
        stringResource(R.string.collector_live, stringResource(live.solution.nameRes()), length(live.horizontalM), length(live.verticalM)),
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.semantics(mergeDescendants = true) {}.testTag(COLLECTOR_LIVE_TAG),
    )
}

/** Por qué no se puede medir, la guía de la inclinación, avisos de la medida y punto guardado (S010/RF-10, RF-12, RF-19; S016). */
@Composable
private fun Messages(state: CollectorUiState, actions: CollectorActions) {
    val blocker = when (state.blocker) {
        MeasureBlocker.NO_POSITION -> R.string.project_no_position
        MeasureBlocker.OUTSIDE_CRS_AREA -> R.string.project_outside_crs
        MeasureBlocker.TILT_MODE_MISSING -> R.string.collector_tilt_mode_missing // S016/RF-2
        MeasureBlocker.AUTO_INTERVAL_TOO_SHORT -> R.string.collector_auto_interval_too_short // S017/RF-18
        else -> null
    }
    blocker?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag(COLLECTOR_BLOCKER_TAG)) }
    state.tiltGuide?.let { TiltGuideBanner(it) } // S016/RF-12, RF-18
    if (state.blocker == MeasureBlocker.TILT_MODE_MISSING || state.blocker == MeasureBlocker.TILT_NOT_READY) {
        TextButton(
            onClick = actions.onOpenTilt,
            modifier = Modifier.heightIn(min = MinTouchTarget).testTag(COLLECTOR_OPEN_TILT_TAG),
        ) { Text(stringResource(R.string.collector_open_tilt)) } // S016/RF-2, RF-18
    }
    if (state.measuring &&
        state.solutionLost
    ) {
        Text(stringResource(R.string.collector_solution_lost), color = MaterialTheme.colorScheme.error)
    }
    if (state.measuring && state.tiltLost != null) {
        Text(
            stringResource(R.string.collector_tilt_lost),
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.testTag(COLLECTOR_TILT_LOST_TAG),
        )
        Text(
            stringResource(state.tiltLost.textRes()),
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.testTag(COLLECTOR_TILT_LOST_GUIDE_TAG),
        )
    } // S016/RF-19
    if (state.measuring && state.restarted) {
        // S015/RF-18; con el tiempo promediado que se descartó, S017/RF-30.
        val discarded = state.restartedDiscardedS?.let { DurationText.format(it.toInt()) }
        val text = if (discarded == null) {
            stringResource(R.string.collector_restarted)
        } else {
            stringResource(R.string.collector_restarted_discarded, discarded)
        }
        Text(text, modifier = Modifier.testTag(COLLECTOR_RESTARTED_TAG))
    }
    if (state.measuring && state.paused == PauseState.PAUSED) PausedBanner() // S017/RF-8
    if (state.measuring && state.noEpochsYet) {
        Text(
            stringResource(R.string.collector_no_epochs_yet),
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.testTag(COLLECTOR_NO_EPOCHS_TAG),
        ) // S017/RF-7
    }
    when (state.error) {
        CollectorError.NO_DATA -> Text(stringResource(R.string.collector_no_data), color = MaterialTheme.colorScheme.error)
        CollectorError.OUTSIDE_CRS_AREA -> Text(stringResource(R.string.project_outside_crs), color = MaterialTheme.colorScheme.error)
        CollectorError.TILT_MODE_CHANGED -> Text(
            stringResource(R.string.collector_tilt_mode_changed),
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.testTag(COLLECTOR_TILT_CHANGED_TAG),
        ) // S016/RF-28
        null -> Unit
    }
    state.savedName?.let { Text(stringResource(R.string.collector_saved, it), style = MaterialTheme.typography.bodyLarge) }
}

@Composable
private fun MeasureButton(state: CollectorUiState, actions: CollectorActions) {
    Button(
        onClick = actions.onMeasure,
        enabled = state.blocker == null && state.pending == null,
        modifier = Modifier.fillMaxWidth().heightIn(min = MinTouchTarget).testTag(COLLECTOR_MEASURE_TAG),
    ) { Text(stringResource(R.string.collector_measure)) }
}

/**
 * El avance del promediado con sus estadísticas, o la espera de la época instantánea, y "Terminar" y "Cancelar"
 * (S010/RF-9, RF-11; S017/RF-2 a RF-6).
 */
@Composable
private fun Measuring(state: CollectorUiState, unit: LinearUnit, stats: AverageStatsUi?, actions: CollectorActions) {
    val progress = state.progress
    if (progress != null) {
        AveragingProgress(progress)
        if (stats != null) {
            Column(modifier = Modifier.heightIn(max = STATS_MAX_HEIGHT).verticalScroll(rememberScrollState())) {
                AverageStatsBlock(stats, unit)
            }
        }
    } else {
        Text(stringResource(R.string.collector_measuring))
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (state.settings.mode == MeasurementMode.AVERAGED) {
            Button(
                onClick = actions.onFinish,
                modifier = Modifier.weight(1f).heightIn(min = MinTouchTarget).testTag(COLLECTOR_FINISH_TAG),
            ) { Text(stringResource(R.string.collector_finish)) } // S017/RF-6
        }
        OutlinedButton(
            onClick = actions.onCancel,
            modifier = Modifier.weight(1f).heightIn(min = MinTouchTarget).testTag(COLLECTOR_CANCEL_TAG),
        ) { Text(stringResource(R.string.collector_cancel)) }
    }
}

/** S010/RF-7 y RNF-T2. */
@Composable
private fun KeepScreenOn() {
    val view = LocalView.current
    DisposableEffect(view) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }
}

/** El formulario no pasa de esto, para que el panel deje ver el mapa; si no cabe, se desplaza. */
private val FORM_MAX_HEIGHT = 168.dp

/** Mientras se promedia, el formulario cede sitio a las estadísticas. */
private val FORM_MEASURING_MAX_HEIGHT = 72.dp
