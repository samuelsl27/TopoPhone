package es.topophone.app.ui.stakeout

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import es.topophone.app.R
import es.topophone.app.ui.projects.unitSymbol
import es.topophone.app.ui.receiver.uiLocale
import es.topophone.core.designsystem.component.MinTouchTarget
import es.topophone.core.model.format.LengthFormat
import es.topophone.core.model.point.HeadingChoice
import es.topophone.core.model.point.StakeoutSettings
import es.topophone.core.model.project.LinearUnit

const val STAKEOUT_SETTINGS_TAG = "stakeout_settings"
const val STAKEOUT_TOLERANCE_TAG = "stakeout_tolerance"
const val STAKEOUT_TOLERANCE_ERROR_TAG = "stakeout_tolerance_error"
const val STAKEOUT_TOLERANCE_V_TAG = "stakeout_tolerance_v"
const val STAKEOUT_TOLERANCE_V_ERROR_TAG = "stakeout_tolerance_v_error"
const val STAKEOUT_SETTINGS_CLOSE_TAG = "stakeout_settings_close"

fun stakeHeadingTag(choice: HeadingChoice) = "stakeout_heading_${choice.name}"

/**
 * Los ajustes del replanteo: de dónde sale el rumbo del usuario y las tolerancias horizontal y vertical, en la unidad del
 * proyecto (S018/RF-8, RF-18; S019/RF-10). [onTolerance] y [onToleranceV] dan false si el valor no vale; entonces se avisa con el
 * rango y no se guarda.
 */
@Composable
fun StakeoutSettingsDialog(
    settings: StakeoutSettings,
    unit: LinearUnit,
    onHeading: (HeadingChoice) -> Unit,
    onTolerance: (String) -> Boolean,
    onDismiss: () -> Unit,
    onToleranceV: (String) -> Boolean = { false },
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.stakeout_settings_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.stakeout_heading_source), style = MaterialTheme.typography.titleSmall)
                Row(modifier = Modifier.selectableGroup(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    HeadingChoice.entries.forEach { choice ->
                        FilterChip(
                            selected = settings.heading == choice,
                            onClick = { onHeading(choice) },
                            label = { Text(stringResource(choice.textRes())) },
                            modifier = Modifier.heightIn(min = MinTouchTarget).testTag(stakeHeadingTag(choice)),
                        )
                    }
                }
                ToleranceField(
                    label = R.string.stakeout_tolerance_label,
                    valueM = settings.validToleranceM,
                    unit = unit,
                    onChange = onTolerance,
                    fieldTag = STAKEOUT_TOLERANCE_TAG,
                    errorTag = STAKEOUT_TOLERANCE_ERROR_TAG,
                )
                ToleranceField(
                    label = R.string.stakeout_tolerance_v_label,
                    valueM = settings.validToleranceVM,
                    unit = unit,
                    onChange = onToleranceV,
                    fieldTag = STAKEOUT_TOLERANCE_V_TAG,
                    errorTag = STAKEOUT_TOLERANCE_V_ERROR_TAG,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = MinTouchTarget).testTag(STAKEOUT_SETTINGS_CLOSE_TAG)) {
                Text(stringResource(R.string.stakeout_settings_close))
            }
        },
    )
}

/** Un campo de tolerancia en la unidad del proyecto: avisa con el rango si [onChange] da false (S018/RF-18; S019/RF-10). */
@Composable
private fun ToleranceField(
    label: Int,
    valueM: Double,
    unit: LinearUnit,
    onChange: (String) -> Boolean,
    fieldTag: String,
    errorTag: String,
) {
    val locale = uiLocale()
    val symbol = unitSymbol(unit)
    var text by remember { mutableStateOf(LengthFormat.inUnit(valueM, unit, locale)) }
    var valid by remember { mutableStateOf(true) }
    val range = StakeoutSettings.toleranceRangeIn(unit)
    OutlinedTextField(
        value = text,
        onValueChange = {
            text = it
            valid = onChange(it)
        },
        label = { Text(stringResource(label, symbol)) },
        isError = !valid,
        supportingText = if (valid) {
            null
        } else {
            {
                val low = LengthFormat.inUnit(range.start, unit, locale)
                val high = LengthFormat.inUnit(range.endInclusive, unit, locale)
                Text(stringResource(R.string.stakeout_tolerance_invalid, low, high, symbol), modifier = Modifier.testTag(errorTag))
            }
        },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = Modifier.fillMaxWidth().testTag(fieldTag),
    )
}

private fun HeadingChoice.textRes(): Int = when (this) {
    HeadingChoice.WALKING -> R.string.stakeout_heading_choice_walking
    HeadingChoice.IMU -> R.string.stakeout_heading_choice_imu
}
