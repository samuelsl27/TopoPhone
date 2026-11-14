package es.topophone.app.ui.cogo

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import es.topophone.app.R
import es.topophone.core.designsystem.component.MinTouchTarget

const val COGO_SAVE_DIALOG_TAG = "cogo_save_dialog"
const val COGO_SAVE_BUTTON_TAG = "cogo_save_button"
const val COGO_SAVE_CONFIRM_TAG = "cogo_save_confirm"
const val COGO_SAVE_CLOSE_TAG = "cogo_save_close"
const val COGO_SAVE_STATUS_TAG = "cogo_save_status"

fun cogoSaveNameTag(key: String) = "cogo_save_name_$key"

fun cogoSaveProblemTag(key: String) = "cogo_save_problem_$key"

/** El botón que abre la hoja de guardado, con cuántos resultados hay marcados (S038/RF-19). */
@Composable
fun CogoSaveButton(count: Int, enabled: Boolean, onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        enabled = enabled && count > 0,
        modifier = Modifier.fillMaxWidth().heightIn(min = MinTouchTarget).testTag(COGO_SAVE_BUTTON_TAG),
    ) { Text(stringResource(R.string.cogo_save_count, count), style = MaterialTheme.typography.titleMedium) }
}

/**
 * La hoja de guardado (S038/RF-19 a RF-21, RF-23): un nombre editable por resultado, lo que está mal en cada uno dicho con texto, y el
 * resultado del guardado. [labels] da el nombre con que se presenta cada resultado (por ejemplo «Punto 3» o «A la izquierda»).
 */
@Composable
fun CogoSaveDialog(
    ui: CogoSaveUi,
    labels: Map<String, String>,
    onName: (key: String, name: String) -> Unit,
    onSave: () -> Unit,
    onDismiss: () -> Unit,
) {
    if (!ui.open) return
    val saved = ui.status as? CogoSaveStatus.Saved
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.cogo_save_title)) },
        text = {
            Column(
                modifier = Modifier.testTag(COGO_SAVE_DIALOG_TAG).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                when (val status = ui.status) {
                    is CogoSaveStatus.Saved -> StatusText(
                        pluralStringResource(R.plurals.cogo_save_saved, status.count, status.count),
                        error = false,
                    )
                    CogoSaveStatus.NameProblems -> StatusText(stringResource(R.string.cogo_save_name_problems), error = true)
                    is CogoSaveStatus.OutOfScope -> StatusText(
                        stringResource(R.string.cogo_save_out_of_scope, status.keys.joinToString { labels[it] ?: it }),
                        error = true,
                    )
                    else -> Unit
                }
                if (saved == null) {
                    ui.rows.forEach { row ->
                        OutlinedTextField(
                            value = row.name,
                            onValueChange = { onName(row.key, it) },
                            label = { Text(stringResource(R.string.cogo_save_name, labels[row.key] ?: row.key)) },
                            singleLine = true,
                            isError = row.problem != null,
                            supportingText = row.problem?.let { problem ->
                                { Text(stringResource(problemText(problem)), modifier = Modifier.testTag(cogoSaveProblemTag(row.key))) }
                            },
                            modifier = Modifier.fillMaxWidth().heightIn(min = MinTouchTarget).testTag(cogoSaveNameTag(row.key)),
                        )
                    }
                }
            }
        },
        confirmButton = {
            if (saved == null) {
                TextButton(
                    onClick = onSave,
                    enabled = ui.status != CogoSaveStatus.Saving,
                    modifier = Modifier.heightIn(min = MinTouchTarget).testTag(COGO_SAVE_CONFIRM_TAG),
                ) { Text(stringResource(R.string.cogo_save_confirm)) }
            } else {
                TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = MinTouchTarget).testTag(COGO_SAVE_CLOSE_TAG)) {
                    Text(stringResource(R.string.geometry_close))
                }
            }
        },
        dismissButton = {
            if (saved == null) {
                TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = MinTouchTarget)) {
                    Text(stringResource(R.string.dialog_cancel))
                }
            }
        },
    )
}

@Composable
private fun StatusText(text: String, error: Boolean) {
    Text(
        text,
        color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.testTag(COGO_SAVE_STATUS_TAG),
    )
}

private fun problemText(problem: NameProblem) = when (problem) {
    NameProblem.EMPTY -> R.string.cogo_save_problem_empty
    NameProblem.REPEATED -> R.string.cogo_save_problem_repeated
    NameProblem.EXISTS -> R.string.cogo_save_problem_exists
}
