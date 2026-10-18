package es.topophone.app.ui.points

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
import es.topophone.app.R
import es.topophone.core.designsystem.component.MinTouchTarget
import es.topophone.core.survey.PoleHeight

/** La altura del jalón de uno o varios puntos, en metros como en Coordenadas (S011/RF-14; S002/RF-29). */
@Composable
internal fun PoleDialog(onApply: (Double) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf("") }
    val value = PoleHeight.parse(text)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.objects_pole)) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text(stringResource(R.string.pole_height_label)) },
                isError = text.isNotEmpty() && value == null,
                supportingText = if (text.isNotEmpty() && value == null) ({ Text(stringResource(R.string.pole_height_invalid)) }) else null,
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth().testTag(POLE_FIELD_TAG),
            )
        },
        confirmButton = {
            TextButton(
                onClick = { value?.let(onApply) },
                enabled = value != null,
                modifier = Modifier.heightIn(min = MinTouchTarget).testTag(POLE_APPLY_TAG),
            ) { Text(stringResource(R.string.objects_pole_apply)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = MinTouchTarget)) {
                Text(stringResource(R.string.dialog_cancel))
            }
        },
    )
}
