package es.topophone.app.ui.collector

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import es.topophone.app.R
import es.topophone.app.ui.projects.unitSymbol
import es.topophone.app.ui.receiver.uiLocale
import es.topophone.core.designsystem.component.MinTouchTarget
import es.topophone.core.model.format.LengthFormat
import es.topophone.core.model.point.PrecisionControl
import es.topophone.core.model.point.PrecisionFailure
import es.topophone.core.model.project.LinearUnit

const val PRECISION_SUMMARY_TAG = "precision_summary"
const val PRECISION_ADJUST_TAG = "precision_adjust"
const val PRECISION_FIX_ONLY_TAG = "precision_fix_only"
const val PRECISION_HORIZONTAL_SWITCH_TAG = "precision_horizontal_switch"
const val PRECISION_HORIZONTAL_VALUE_TAG = "precision_horizontal_value"
const val PRECISION_VERTICAL_SWITCH_TAG = "precision_vertical_switch"
const val PRECISION_VERTICAL_VALUE_TAG = "precision_vertical_value"
const val PRECISION_SAVE_TAG = "precision_save"
const val PRECISION_TILT_SWITCH_TAG = "precision_tilt_switch"
const val PRECISION_TILT_VALUE_TAG = "precision_tilt_value"
const val OUT_OF_TOLERANCE_SAVE_TAG = "out_of_tolerance_save"
const val OUT_OF_TOLERANCE_DISCARD_TAG = "out_of_tolerance_discard"

/** Resumen del control de precisión y "Ajustar", que abre su diálogo (S010/RF-13). */
@Composable
internal fun PrecisionControlRow(
    precision: PrecisionControl,
    unit: LinearUnit,
    showTilt: Boolean = false,
    onChange: (PrecisionControl) -> Unit,
) {
    var open by rememberSaveable { mutableStateOf(false) }
    val locale = uiLocale()
    val symbol = unitSymbol(unit)
    fun length(valueM: Double) = "${LengthFormat.inUnit(valueM, unit, locale)} $symbol"
    val parts = listOfNotNull(
        stringResource(R.string.precision_fix_only).takeIf { precision.fixOnly },
        precision.horizontalLimitM?.let { stringResource(R.string.precision_summary_h, length(it)) },
        precision.verticalLimitM?.let { stringResource(R.string.precision_summary_v, length(it)) },
        // S016/RF-20
        precision.maxTiltRad?.takeIf { showTilt }?.let { stringResource(R.string.precision_summary_tilt, degrees(it)) },
    )
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            stringResource(
                R.string.precision_summary,
                parts.ifEmpty {
                    listOf(stringResource(R.string.precision_none))
                }.joinToString(" · "),
            ),
            modifier = Modifier.weight(1f).testTag(PRECISION_SUMMARY_TAG),
        )
        TextButton(onClick = { open = true }, modifier = Modifier.heightIn(min = MinTouchTarget).testTag(PRECISION_ADJUST_TAG)) {
            Text(stringResource(R.string.precision_adjust))
        }
    }
    if (open) {
        PrecisionDialog(precision, unit, showTilt, onDismiss = { open = false }) {
            onChange(it)
            open = false
        }
    }
}

/**
 * Solo FIX y los límites H y V, en la unidad lineal del proyecto; se guardan en metros (S010/RF-13). Con la inclinación,
 * la inclinación máxima en grados enteros de 1 a 60; sin ella no se toca (S016/RF-20).
 */
@Composable
private fun PrecisionDialog(
    precision: PrecisionControl,
    unit: LinearUnit,
    showTilt: Boolean,
    onDismiss: () -> Unit,
    onSave: (PrecisionControl) -> Unit,
) {
    val locale = uiLocale()
    fun text(valueM: Double?) = valueM?.let { LengthFormat.inUnit(it, unit, locale) }.orEmpty()
    var fixOnly by rememberSaveable { mutableStateOf(precision.fixOnly) }
    var horizontalOn by rememberSaveable { mutableStateOf(precision.horizontalLimitM != null) }
    var horizontal by rememberSaveable { mutableStateOf(text(precision.horizontalLimitM)) }
    var verticalOn by rememberSaveable { mutableStateOf(precision.verticalLimitM != null) }
    var vertical by rememberSaveable { mutableStateOf(text(precision.verticalLimitM)) }
    var tiltOn by rememberSaveable { mutableStateOf(precision.maxTiltRad != null) }
    var tilt by rememberSaveable { mutableStateOf(degrees(precision.maxTiltRad ?: PrecisionControl.DEFAULT_MAX_TILT_RAD)) }
    val horizontalM = parseLimit(horizontal, unit)
    val verticalM = parseLimit(vertical, unit)
    val tiltDeg = parseTilt(tilt)
    val valid = (!horizontalOn || horizontalM != null) &&
        (!verticalOn || verticalM != null) &&
        (!showTilt || !tiltOn || tiltDeg != null)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.precision_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SwitchRow(stringResource(R.string.precision_fix_only), fixOnly, PRECISION_FIX_ONLY_TAG) { fixOnly = it }
                LimitRow(
                    LimitFields(R.string.precision_horizontal_limit, PRECISION_HORIZONTAL_SWITCH_TAG, PRECISION_HORIZONTAL_VALUE_TAG),
                    unit,
                    horizontalOn,
                    horizontal,
                    onEnabled = { horizontalOn = it },
                ) { horizontal = it }
                LimitRow(
                    LimitFields(R.string.precision_vertical_limit, PRECISION_VERTICAL_SWITCH_TAG, PRECISION_VERTICAL_VALUE_TAG),
                    unit,
                    verticalOn,
                    vertical,
                    onEnabled = { verticalOn = it },
                ) { vertical = it }
                if (showTilt) {
                    SwitchRow(stringResource(R.string.precision_tilt_limit), tiltOn, PRECISION_TILT_SWITCH_TAG) { tiltOn = it }
                    if (tiltOn) {
                        OutlinedTextField(
                            value = tilt,
                            onValueChange = { tilt = it },
                            label = { Text(stringResource(R.string.precision_tilt_value)) },
                            isError = tiltDeg == null,
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.fillMaxWidth().testTag(PRECISION_TILT_VALUE_TAG),
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val chosenTilt = tiltDeg?.takeIf { tiltOn }?.let { Math.toRadians(it.toDouble()) }
                    val maxTilt = if (showTilt) chosenTilt else precision.maxTiltRad
                    onSave(PrecisionControl(fixOnly, horizontalM.takeIf { horizontalOn }, verticalM.takeIf { verticalOn }, maxTilt))
                },
                enabled = valid,
                modifier = Modifier.heightIn(min = MinTouchTarget).testTag(PRECISION_SAVE_TAG),
            ) { Text(stringResource(R.string.precision_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = MinTouchTarget)) {
                Text(stringResource(R.string.collector_cancel))
            }
        },
    )
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, tag: String, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = MinTouchTarget)
            .toggleable(value = checked, role = Role.Switch, onValueChange = onChange)
            .testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = null)
    }
}

/** Texto y etiquetas de prueba de uno de los dos límites. */
private class LimitFields(val label: Int, val switchTag: String, val valueTag: String)

@Composable
private fun LimitRow(
    fields: LimitFields,
    unit: LinearUnit,
    enabled: Boolean,
    value: String,
    onEnabled: (Boolean) -> Unit,
    onValue: (String) -> Unit,
) {
    SwitchRow(stringResource(fields.label), enabled, fields.switchTag, onEnabled)
    if (enabled) {
        OutlinedTextField(
            value = value,
            onValueChange = onValue,
            label = { Text(stringResource(R.string.precision_limit_value, unitSymbol(unit))) },
            isError = parseLimit(value, unit) == null,
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier.fillMaxWidth().testTag(fields.valueTag),
        )
    }
}

/** Inclinación máxima en grados enteros, de 1 a 60 (S016/RF-20); null si no es válida. */
private fun parseTilt(text: String): Int? = text.trim().toIntOrNull()?.takeIf { it in PrecisionControl.MAX_TILT_RANGE_DEG }

private fun degrees(valueRad: Double): String = Math.round(Math.toDegrees(valueRad)).toString()

/** Un límite positivo en [unit], con punto o coma decimal, pasado a metros; null si no es válido. */
private fun parseLimit(text: String, unit: LinearUnit): Double? =
    text.trim().replace(',', '.').toDoubleOrNull()?.takeIf { it > 0 && it.isFinite() }?.let { it * unit.metres }

/** Medida que no cumple el control de precisión: qué falla, y guardarla marcada o descartarla (S010/RF-14, RF-15). */
@Composable
internal fun OutOfToleranceDialog(pending: PendingPoint, onConfirm: () -> Unit, onDiscard: () -> Unit) {
    AlertDialog(
        onDismissRequest = {}, // hay que decidir: guardar o descartar
        title = { Text(stringResource(R.string.out_of_tolerance_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                pending.failures.sorted().forEach { failure ->
                    Text(
                        "• " + stringResource(
                            when (failure) {
                                PrecisionFailure.NOT_FIX -> R.string.out_of_tolerance_not_fix
                                PrecisionFailure.HORIZONTAL -> R.string.out_of_tolerance_horizontal
                                PrecisionFailure.VERTICAL -> R.string.out_of_tolerance_vertical
                                PrecisionFailure.NO_ACCURACY -> R.string.out_of_tolerance_no_accuracy
                                PrecisionFailure.TILT -> R.string.out_of_tolerance_tilt
                            },
                        ),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm, modifier = Modifier.heightIn(min = MinTouchTarget).testTag(OUT_OF_TOLERANCE_SAVE_TAG)) {
                Text(stringResource(R.string.out_of_tolerance_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDiscard, modifier = Modifier.heightIn(min = MinTouchTarget).testTag(OUT_OF_TOLERANCE_DISCARD_TAG)) {
                Text(stringResource(R.string.out_of_tolerance_discard))
            }
        },
    )
}
