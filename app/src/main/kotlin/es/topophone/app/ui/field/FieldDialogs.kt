package es.topophone.app.ui.field

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import es.topophone.core.designsystem.component.MinTouchTarget
import es.topophone.core.designsystem.component.SingleChoiceDialog

const val FIELD_ENTRY_TAG = "field_entry"
const val FIELD_ENTRY_MINUTES_TAG = "field_entry_minutes"
const val FIELD_ENTRY_SECONDS_TAG = "field_entry_seconds"
const val FIELD_ENTRY_ERROR_TAG = "field_entry_error"
const val FIELD_ENTRY_CONFIRM_TAG = "field_entry_confirm"
const val FIELD_ENTRY_CANCEL_TAG = "field_entry_cancel"

fun shortListOptionTag(value: Any) = "field_list_option_$value"

/** El teclado numérico de los selectores: con coma o punto decimal, o solo cifras (S060/RF-11). */
fun fieldEntryKeyboard(decimal: Boolean): KeyboardType = if (decimal) KeyboardType.Decimal else KeyboardType.Number

/**
 * Diálogo pequeño para escribir un número con el teclado numérico: abre con el valor actual, dice el motivo junto al campo si no vale
 * y no deja aplicarlo hasta que valga (S060/RF-11, RF-12). [validate] da el texto del error o null si el valor vale. La hoja sube con
 * el teclado y el campo queda a la vista (RF-7).
 */
@Composable
fun NumericEntryDialog(
    title: String,
    label: String,
    initial: String,
    validate: (String) -> Int?,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
    decimal: Boolean = true,
    /** Los argumentos del texto del error, si lleva (por ejemplo, el rango válido). */
    errorArgs: List<Any> = emptyList(),
) {
    var text by remember { mutableStateOf(initial) }
    val error = validate(text)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text(label) },
                isError = error != null,
                supportingText = error?.let {
                    { Text(stringResource(it, *errorArgs.toTypedArray()), modifier = Modifier.testTag(FIELD_ENTRY_ERROR_TAG)) }
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = fieldEntryKeyboard(decimal)),
                modifier = Modifier.fillMaxWidth().testTag(FIELD_ENTRY_TAG),
            )
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onConfirm(text)
                    onDismiss()
                },
                enabled = error == null,
                modifier = Modifier.heightIn(min = MinTouchTarget).testTag(FIELD_ENTRY_CONFIRM_TAG),
            ) { Text(stringResource(R.string.field_apply)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = MinTouchTarget).testTag(FIELD_ENTRY_CANCEL_TAG)) {
                Text(stringResource(R.string.field_cancel))
            }
        },
    )
}

/**
 * El tiempo con dos campos numéricos, minutos y segundos, que se juntan en el «m:ss» que entiende S017: el teclado numérico no tiene
 * «:» (S060/RF-11). [validate] recibe ese texto.
 */
@Composable
fun TimeEntryDialog(title: String, initialSeconds: Int, validate: (String) -> Int?, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var minutes by remember { mutableStateOf((initialSeconds / SECONDS_PER_MINUTE).toString()) }
    var seconds by remember { mutableStateOf((initialSeconds % SECONDS_PER_MINUTE).toString()) }
    val combined = "${minutes.trim()}:${seconds.trim().padStart(2, '0')}"
    val error = validate(combined)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = minutes,
                        onValueChange = { minutes = it },
                        label = { Text(stringResource(R.string.field_minutes)) },
                        isError = error != null,
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = fieldEntryKeyboard(decimal = false)),
                        modifier = Modifier.weight(1f).testTag(FIELD_ENTRY_MINUTES_TAG),
                    )
                    OutlinedTextField(
                        value = seconds,
                        onValueChange = { seconds = it },
                        label = { Text(stringResource(R.string.field_seconds)) },
                        isError = error != null,
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = fieldEntryKeyboard(decimal = false)),
                        modifier = Modifier.weight(1f).testTag(FIELD_ENTRY_SECONDS_TAG),
                    )
                }
                error?.let { Text(stringResource(it), modifier = Modifier.testTag(FIELD_ENTRY_ERROR_TAG)) }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onConfirm(combined)
                    onDismiss()
                },
                enabled = error == null,
                modifier = Modifier.heightIn(min = MinTouchTarget).testTag(FIELD_ENTRY_CONFIRM_TAG),
            ) { Text(stringResource(R.string.field_apply)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = MinTouchTarget).testTag(FIELD_ENTRY_CANCEL_TAG)) {
                Text(stringResource(R.string.field_cancel))
            }
        },
    )
}

/** Una lista corta de opciones con la vigente marcada; elegir una la aplica y cierra (S060/RF-13). */
@Composable
fun <T : Any> ShortListDialog(title: String, options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit, onDismiss: () -> Unit) {
    SingleChoiceDialog(
        title = title,
        options = options,
        selected = selected,
        onSelect = {
            onSelect(it)
            onDismiss()
        },
        onDismiss = onDismiss,
        optionTag = ::shortListOptionTag,
    )
}

private const val SECONDS_PER_MINUTE = 60
