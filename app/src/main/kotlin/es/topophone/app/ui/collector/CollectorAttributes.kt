package es.topophone.app.ui.collector

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
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
import es.topophone.core.model.codes.AttributeError
import es.topophone.core.model.codes.AttributeType

fun attributeFieldTag(index: Int) = "collector_attr_$index"

fun attributeOptionTag(index: Int, option: String) = "collector_attr_${index}_option_$option"

const val COLLECTOR_ATTR_EMPTY_OPTION = "collector_attr_empty_option"

/**
 * Un campo por atributo del código elegido, precargado con su valor por defecto: texto libre, entero, número (con el signo decimal de la
 * interfaz) o una lista de opciones. Un valor no válido se marca con su motivo y bloquea medir; vacío es válido (S024/RF-8, RF-9).
 */
@Composable
internal fun AttributeFields(code: CollectorCodeUi, enabled: Boolean, actions: CollectorActions) {
    if (code.fields.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        code.fields.forEachIndexed { index, field ->
            when (field.type) {
                AttributeType.LIST -> ListField(index, field, enabled, actions)
                else -> TextField(index, field, enabled, actions)
            }
        }
    }
}

@Composable
private fun TextField(index: Int, field: AttributeFieldUi, enabled: Boolean, actions: CollectorActions) {
    OutlinedTextField(
        value = field.text,
        onValueChange = { actions.onCodeAttribute(index, it) },
        enabled = enabled,
        label = { Text(field.name) },
        isError = field.error != null,
        supportingText = field.error?.let { { Text(stringResource(it.messageRes())) } },
        singleLine = true,
        keyboardOptions = KeyboardOptions(
            keyboardType = when (field.type) {
                AttributeType.INTEGER -> KeyboardType.Number
                AttributeType.NUMBER -> KeyboardType.Decimal
                else -> KeyboardType.Text
            },
        ),
        modifier = Modifier.fillMaxWidth().testTag(attributeFieldTag(index)),
    )
}

@Composable
private fun ListField(index: Int, field: AttributeFieldUi, enabled: Boolean, actions: CollectorActions) {
    var open by remember { mutableStateOf(false) }
    Column {
        OutlinedButton(
            onClick = { open = true },
            enabled = enabled,
            modifier = Modifier.fillMaxWidth().heightIn(min = MinTouchTarget).testTag(attributeFieldTag(index)),
        ) {
            Text(
                stringResource(
                    R.string.collector_attr_list_value,
                    field.name,
                    field.text.ifEmpty {
                        stringResource(R.string.collector_attr_no_value)
                    },
                ),
                modifier = Modifier.weight(1f),
            )
        }
        field.error?.let { Text(stringResource(it.messageRes()), color = MaterialTheme.colorScheme.error) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.collector_attr_no_value)) },
                onClick = {
                    actions.onCodeAttribute(index, "")
                    open = false
                },
                modifier = Modifier.heightIn(min = MinTouchTarget).testTag(COLLECTOR_ATTR_EMPTY_OPTION),
            )
            field.options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option) },
                    onClick = {
                        actions.onCodeAttribute(index, option)
                        open = false
                    },
                    modifier = Modifier.heightIn(min = MinTouchTarget).testTag(attributeOptionTag(index, option)),
                )
            }
        }
    }
}

private fun AttributeError.messageRes(): Int = when (this) {
    AttributeError.NOT_INTEGER -> R.string.collector_attr_not_integer
    AttributeError.NOT_NUMBER -> R.string.collector_attr_not_number
    AttributeError.NOT_IN_OPTIONS -> R.string.collector_attr_not_in_options
}
