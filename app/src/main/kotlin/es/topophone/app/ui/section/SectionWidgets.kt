package es.topophone.app.ui.section

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import es.topophone.core.designsystem.component.MinTouchTarget

/** Una fila de opciones que pasa a la línea de abajo si no caben (S050). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ChoiceRow(content: @Composable () -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { content() }
}

/** Una opción de 56 dp (RNF-T8). */
@Composable
internal fun Choice(label: String, tag: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
        modifier = Modifier.heightIn(min = MinTouchTarget).testTag(tag),
    )
}

/** Un campo de texto de una línea, de 56 dp, para escribir un número o un código. */
@Composable
internal fun LabeledField(
    label: String,
    value: String,
    tag: String,
    onChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    numeric: Boolean = true,
    error: Boolean = false,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        isError = error,
        keyboardOptions = KeyboardOptions(keyboardType = if (numeric) KeyboardType.Text else KeyboardType.Ascii),
        modifier = modifier.fillMaxWidth().heightIn(min = MinTouchTarget).testTag(tag),
    )
}
