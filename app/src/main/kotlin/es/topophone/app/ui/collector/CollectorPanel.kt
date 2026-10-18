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
import androidx.compose.material3.LinearProgressIndicator
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
import es.topophone.app.ui.receiver.nameRes
import es.topophone.app.ui.receiver.uiLocale
import es.topophone.core.designsystem.component.MinTouchTarget
import es.topophone.core.model.format.LengthFormat
import es.topophone.core.model.point.CollectorSettings
import es.topophone.core.model.point.MeasurementMode
import es.topophone.core.model.project.LinearUnit
import java.text.NumberFormat
import java.util.Locale

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
                modifier = Modifier.weight(1f, fill = false).heightIn(max = FORM_MAX_HEIGHT).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) { Form(state, unit, actions) }
            LiveQualityText(state.live, unit)
            Messages(state)
            if (state.measuring) Measuring(state, actions) else MeasureButton(state, actions)
        }
    }
    state.pending?.let { OutOfToleranceDialog(it, actions.onConfirm, actions.onDiscard) } // S010/RF-14, RF-15
}

/** Nombre, descripción, jalón, modo y control de precisión. */
@Composable
private fun Form(state: CollectorUiState, unit: LinearUnit, actions: CollectorActions) {
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
        label = { Text(stringResource(R.string.pole_height_label)) },
        isError = !state.poleHeightValid,
        supportingText = if (state.poleHeightValid) null else ({ Text(stringResource(R.string.pole_height_invalid)) }),
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = Modifier.fillMaxWidth().testTag(COLLECTOR_POLE_TAG),
    ) // S010/RF-5
    ModeChoice(state, actions)
    PrecisionControlRow(state.settings.precision, unit) { actions.onSettings(state.settings.copy(precision = it)) } // S010/RF-13
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
            label = { Text(stringResource(R.string.collector_seconds)) },
            isError = !state.averagingSecondsValid,
            supportingText = if (state.averagingSecondsValid) null else ({ Text(stringResource(R.string.collector_seconds_invalid)) }),
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
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

/** Por qué no se puede medir, avisos de la medida y punto guardado (S010/RF-10, RF-12, RF-19). */
@Composable
private fun Messages(state: CollectorUiState) {
    val blocker = when (state.blocker) {
        MeasureBlocker.NO_POSITION -> R.string.project_no_position
        MeasureBlocker.OUTSIDE_CRS_AREA -> R.string.project_outside_crs
        else -> null
    }
    blocker?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag(COLLECTOR_BLOCKER_TAG)) }
    if (state.measuring &&
        state.solutionLost
    ) {
        Text(stringResource(R.string.collector_solution_lost), color = MaterialTheme.colorScheme.error)
    }
    when (state.error) {
        CollectorError.NO_DATA -> Text(stringResource(R.string.collector_no_data), color = MaterialTheme.colorScheme.error)
        CollectorError.OUTSIDE_CRS_AREA -> Text(stringResource(R.string.project_outside_crs), color = MaterialTheme.colorScheme.error)
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

/** Progreso del promediado, o espera de la época instantánea, con "Cancelar" (S010/RF-9, RF-11). */
@Composable
private fun Measuring(state: CollectorUiState, actions: CollectorActions) {
    val progress = state.progress
    if (progress != null) {
        val locale = uiLocale()
        Text(stringResource(R.string.collector_progress, seconds(progress.elapsedS, locale), seconds(progress.requiredS, locale)))
        LinearProgressIndicator(progress = { (progress.elapsedS / progress.requiredS).toFloat() }, modifier = Modifier.fillMaxWidth())
    } else {
        Text(stringResource(R.string.collector_measuring))
    }
    OutlinedButton(
        onClick = actions.onCancel,
        modifier = Modifier.fillMaxWidth().heightIn(min = MinTouchTarget).testTag(COLLECTOR_CANCEL_TAG),
    ) { Text(stringResource(R.string.collector_cancel)) }
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

private fun seconds(value: Double, locale: Locale): String = NumberFormat.getNumberInstance(locale).apply {
    maximumFractionDigits = 1
}.format(value)

/** El formulario no pasa de esto, para que el panel deje ver el mapa; si no cabe, se desplaza. */
private val FORM_MAX_HEIGHT = 168.dp
