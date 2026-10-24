package es.topophone.app.ui.stakeout

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
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
import es.topophone.core.model.project.LinearUnit

const val STAKEOUT_ELEVATION_TAG = "stakeout_elevation"
const val STAKEOUT_ELEVATION_FIELD_TAG = "stakeout_elevation_field"
const val STAKEOUT_ELEVATION_ERROR_TAG = "stakeout_elevation_error"
const val STAKEOUT_ELEVATION_APPLY_TAG = "stakeout_elevation_apply"
const val STAKEOUT_ELEVATION_USE_POINT_TAG = "stakeout_elevation_use_point"
const val STAKEOUT_ELEVATION_CANCEL_TAG = "stakeout_elevation_cancel"
const val STAKEOUT_ELEVATION_TEXT_TAG = "stakeout_elevation_text"

/**
 * La cota de diseño del replanteo (S019/RF-2 a RF-4): la del punto de diseño o la que escribe el usuario, en la unidad del
 * proyecto. [onApply] da false si lo escrito no es un número; entonces se avisa y el diálogo sigue abierto. "Usar la del punto"
 * solo vale con una cota escrita ([overridden]) y la descarta.
 */
@Composable
fun StakeoutElevationDialog(
    elevationM: Double?,
    overridden: Boolean,
    unit: LinearUnit,
    onApply: (String) -> Boolean,
    onUsePoint: () -> Unit,
    onDismiss: () -> Unit,
) {
    val locale = uiLocale()
    val symbol = unitSymbol(unit)
    var text by remember { mutableStateOf(elevationM?.let { LengthFormat.inUnit(it, unit, locale) }.orEmpty()) }
    var valid by remember { mutableStateOf(true) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.stakeout_elevation_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.stakeout_elevation_help))
                OutlinedTextField(
                    value = text,
                    onValueChange = {
                        text = it
                        valid = true
                    },
                    label = { Text(stringResource(R.string.stakeout_elevation_label, symbol)) },
                    isError = !valid,
                    supportingText = if (valid) {
                        null
                    } else {
                        {
                            Text(
                                stringResource(R.string.stakeout_elevation_invalid),
                                modifier = Modifier.testTag(STAKEOUT_ELEVATION_ERROR_TAG),
                            )
                        }
                    },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth().testTag(STAKEOUT_ELEVATION_FIELD_TAG),
                )
                TextButton(
                    onClick = {
                        onUsePoint()
                        onDismiss()
                    },
                    enabled = overridden,
                    modifier = Modifier.heightIn(min = MinTouchTarget).testTag(STAKEOUT_ELEVATION_USE_POINT_TAG),
                ) { Text(stringResource(R.string.stakeout_elevation_use_point)) }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    valid = onApply(text)
                    if (valid) onDismiss()
                },
                modifier = Modifier.heightIn(min = MinTouchTarget).testTag(STAKEOUT_ELEVATION_APPLY_TAG),
            ) { Text(stringResource(R.string.stakeout_elevation_apply)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = MinTouchTarget).testTag(STAKEOUT_ELEVATION_CANCEL_TAG)) {
                Text(stringResource(R.string.stakeout_elevation_cancel))
            }
        },
    )
}
