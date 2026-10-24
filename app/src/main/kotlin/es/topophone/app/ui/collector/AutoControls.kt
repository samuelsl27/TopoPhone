package es.topophone.app.ui.collector

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import es.topophone.core.designsystem.component.MinTouchTarget
import es.topophone.core.model.format.DurationText
import es.topophone.core.model.format.LengthFormat
import es.topophone.core.model.point.AutoTrigger
import es.topophone.core.model.point.CollectorSettings
import es.topophone.core.model.project.LinearUnit
import kotlin.math.ceil

const val COLLECTOR_AUTO_TAG = "collector_auto"
const val COLLECTOR_AUTO_INTERVAL_TAG = "collector_auto_interval"
const val COLLECTOR_AUTO_DISTANCE_TAG = "collector_auto_distance"
const val COLLECTOR_START_AUTO_TAG = "collector_start_auto"
const val COLLECTOR_STOP_AUTO_TAG = "collector_stop_auto"
const val COLLECTOR_AUTO_PAUSED_TAG = "collector_auto_paused"
const val COLLECTOR_AUTO_SUMMARY_TAG = "collector_auto_summary"

fun autoTriggerTag(trigger: AutoTrigger) = "collector_auto_trigger_${trigger.name}"

/**
 * "Automático", con el disparo por tiempo o por distancia y su intervalo o su distancia (S017/RF-15 a RF-17). Con el modo en
 * marcha nada de esto cambia (RF-32).
 */
@Composable
internal fun AutoChoice(state: CollectorUiState, unit: LinearUnit, actions: CollectorActions) {
    val running = state.auto.running
    FilterChip(
        selected = state.autoEnabled,
        onClick = { actions.onAutoEnabled(!state.autoEnabled) },
        enabled = !running,
        label = { Text(stringResource(R.string.collector_auto)) },
        modifier = Modifier.heightIn(min = MinTouchTarget).testTag(COLLECTOR_AUTO_TAG),
    )
    if (!state.autoEnabled) return
    Row(modifier = Modifier.selectableGroup(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        AutoTrigger.entries.forEach { trigger ->
            FilterChip(
                selected = state.settings.autoTrigger == trigger,
                onClick = { actions.onAutoTrigger(trigger) },
                enabled = !running,
                label = {
                    Text(
                        stringResource(
                            if (trigger ==
                                AutoTrigger.TIME
                            ) {
                                R.string.collector_auto_time
                            } else {
                                R.string.collector_auto_distance
                            },
                        ),
                    )
                },
                modifier = Modifier.heightIn(min = MinTouchTarget).testTag(autoTriggerTag(trigger)),
            )
        }
    }
    when (state.settings.autoTrigger) {
        AutoTrigger.TIME -> OutlinedTextField(
            value = state.autoIntervalText,
            onValueChange = actions.onAutoInterval,
            enabled = !running,
            label = { Text(stringResource(R.string.collector_auto_interval)) },
            isError = !state.autoIntervalValid,
            supportingText = if (state.autoIntervalValid) null else ({ Text(stringResource(R.string.collector_seconds_invalid)) }),
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
            modifier = Modifier.fillMaxWidth().testTag(COLLECTOR_AUTO_INTERVAL_TAG),
        )
        AutoTrigger.DISTANCE -> {
            val locale = uiLocale()
            val symbol = unitSymbol(unit)
            val range = CollectorSettings.autoDistanceRangeIn(unit)
            OutlinedTextField(
                value = state.autoDistanceText,
                onValueChange = actions.onAutoDistance,
                enabled = !running,
                label = { Text(stringResource(R.string.collector_auto_distance_label, symbol)) },
                isError = !state.autoDistanceValid,
                supportingText = if (state.autoDistanceValid) {
                    null
                } else {
                    {
                        Text(
                            stringResource(
                                R.string.collector_auto_distance_invalid,
                                "${LengthFormat.inUnit(range.start, unit, locale, decimals = 1)} $symbol",
                                "${LengthFormat.inUnit(range.endInclusive, unit, locale, decimals = 1)} $symbol",
                            ),
                        )
                    }
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth().testTag(COLLECTOR_AUTO_DISTANCE_TAG),
            )
        }
    }
}

/** "Iniciar", o con el modo en marcha lo guardado, lo omitido, lo que falta y "Parar" (S017/RF-15, RF-26, RF-27). */
@Composable
internal fun AutoControls(state: CollectorUiState, unit: LinearUnit, live: AutoLive?, actions: CollectorActions) {
    if (state.auto.running) {
        Text(stringResource(R.string.collector_auto_counts, state.auto.saved, state.auto.omitted))
        val locale = uiLocale()
        live?.nextInS?.let { Text(stringResource(R.string.collector_auto_next_time, DurationText.format(ceil(it).toInt()))) }
        live?.nextDistanceM?.let {
            Text(stringResource(R.string.collector_auto_next_distance, "${LengthFormat.inUnit(it, unit, locale)} ${unitSymbol(unit)}"))
        }
        if (state.auto.paused) {
            Text(
                stringResource(R.string.collector_auto_paused),
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.testTag(COLLECTOR_AUTO_PAUSED_TAG).semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
        state.progress?.let { AveragingProgress(it) }
        Button(
            onClick = actions.onStopAuto,
            modifier = Modifier.fillMaxWidth().heightIn(min = MinTouchTarget).testTag(COLLECTOR_STOP_AUTO_TAG),
        ) { Text(stringResource(R.string.collector_auto_stop)) }
    } else {
        state.auto.summary?.let {
            Text(
                stringResource(R.string.collector_auto_summary, it.saved, it.omitted),
                modifier = Modifier.testTag(COLLECTOR_AUTO_SUMMARY_TAG).semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
        Button(
            onClick = actions.onStartAuto,
            enabled = state.blocker == null && state.pending == null && !state.measuring,
            modifier = Modifier.fillMaxWidth().heightIn(min = MinTouchTarget).testTag(COLLECTOR_START_AUTO_TAG),
        ) { Text(stringResource(R.string.collector_auto_start)) }
    }
}
