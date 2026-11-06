package es.topophone.app.ui.alignment

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import es.topophone.app.R
import es.topophone.app.ui.receiver.uiLocale
import es.topophone.core.designsystem.component.MinTouchTarget
import es.topophone.core.model.alignment.ChainageFormat
import es.topophone.core.model.format.LengthFormat
import es.topophone.core.model.format.NumberInput
import es.topophone.core.model.project.LinearUnit

const val ALIGNMENT_CONVERT_NAME_TAG = "alignment_convert_name"
const val ALIGNMENT_CONVERT_START_TAG = "alignment_convert_start"
const val ALIGNMENT_CONVERT_CREATE_TAG = "alignment_convert_create"
const val ALIGNMENT_CONVERT_CANCEL_TAG = "alignment_convert_cancel"
const val ALIGNMENT_CONVERT_RESULT_TAG = "alignment_convert_result"
const val ALIGNMENT_CONVERT_ERROR_TAG = "alignment_convert_error"
const val ALIGNMENT_CONVERT_CLOSE_TAG = "alignment_convert_close"

/**
 * «Convertir en eje» una polilínea que se ha tocado en el mapa (S049/RF-6, RF-7): el nombre del eje y el PK inicial en la unidad del
 * proyecto, y, al crearlo, lo que salió o por qué no se pudo crear. [onConvert] recibe el PK inicial ya en metros.
 */
@Composable
fun ConvertToAlignmentDialog(
    defaultName: String,
    unit: LinearUnit,
    unitSymbol: String,
    state: AlignmentFromDrawingUiState,
    onConvert: (name: String, startChainageM: Double) -> Unit,
    onDismiss: () -> Unit,
    /** La línea es del proyecto y el eje empieza en su primer vértice, no en un extremo tocado (S027/RF-17). */
    fromProjectLine: Boolean = false,
) {
    val result = state.result
    if (result != null) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(R.string.alignment_convert_title)) },
            text = { ResultText(result, unit) },
            confirmButton = {
                TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = MinTouchTarget).testTag(ALIGNMENT_CONVERT_CLOSE_TAG)) {
                    Text(stringResource(R.string.alignment_import_close))
                }
            },
        )
        return
    }
    var name by rememberSaveable { mutableStateOf(defaultName) }
    var start by rememberSaveable { mutableStateOf("0") }
    val parsed = if (start.isBlank()) 0.0 else NumberInput.parse(start)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.alignment_convert_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    stringResource(if (fromProjectLine) R.string.alignment_convert_help_line else R.string.alignment_convert_help),
                    style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.alignment_convert_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag(ALIGNMENT_CONVERT_NAME_TAG),
                )
                OutlinedTextField(
                    value = start,
                    onValueChange = { start = it },
                    label = { Text(stringResource(R.string.alignment_convert_start, unitSymbol)) },
                    isError = parsed == null,
                    supportingText = { if (parsed == null) Text(stringResource(R.string.alignment_convert_invalid)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth().testTag(ALIGNMENT_CONVERT_START_TAG),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { if (parsed != null) onConvert(name, unit.toMetres(parsed)) },
                enabled = !state.working && parsed != null && name.isNotBlank(),
                modifier = Modifier.heightIn(min = MinTouchTarget).testTag(ALIGNMENT_CONVERT_CREATE_TAG),
            ) { Text(stringResource(R.string.alignment_convert_create)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = MinTouchTarget).testTag(ALIGNMENT_CONVERT_CANCEL_TAG)) {
                Text(stringResource(R.string.alignment_cancel))
            }
        },
    )
}

@Composable
private fun ResultText(result: DrawingAlignmentResult, unit: LinearUnit) {
    val locale = uiLocale()
    when (result) {
        is DrawingAlignmentResult.Created -> Column(
            verticalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.testTag(ALIGNMENT_CONVERT_RESULT_TAG),
        ) {
            Text(
                stringResource(
                    R.string.alignment_convert_done,
                    result.name,
                    LengthFormat.metres(result.lengthM, locale, decimals = 1),
                    ChainageFormat.format(result.startChainage, unit, locale),
                    ChainageFormat.format(result.startChainage + result.lengthM, unit, locale),
                ),
            )
            Text(
                stringResource(
                    if (result.hasProfile) R.string.alignment_convert_with_profile else R.string.alignment_convert_without_profile,
                ),
            )
        }
        is DrawingAlignmentResult.Rejected -> Text(
            stringResource(R.string.alignment_convert_failed, alignmentIssueText(result.issue)),
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.testTag(ALIGNMENT_CONVERT_ERROR_TAG),
        )
        DrawingAlignmentResult.WriteError -> Text(
            stringResource(R.string.alignment_convert_failed_write),
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.testTag(ALIGNMENT_CONVERT_ERROR_TAG),
        )
    }
}
