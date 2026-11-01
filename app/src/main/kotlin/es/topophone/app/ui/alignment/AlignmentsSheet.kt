package es.topophone.app.ui.alignment

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import es.topophone.app.R
import es.topophone.app.ui.receiver.uiLocale
import es.topophone.core.designsystem.component.MinTouchTarget
import es.topophone.core.model.alignment.AlignmentSummary
import es.topophone.core.model.alignment.ChainageFormat
import es.topophone.core.model.format.LengthFormat
import java.text.DateFormat
import java.util.Date

const val ALIGNMENTS_BUTTON_TAG = "alignments_button"
const val ALIGNMENTS_SHEET_TAG = "alignments_sheet"
const val ALIGNMENTS_EMPTY_TAG = "alignments_empty"
const val ALIGNMENT_DELETE_CONFIRM_TAG = "alignment_delete_confirm"
const val ALIGNMENT_RENAME_FIELD_TAG = "alignment_rename_field"
const val ALIGNMENT_RENAME_CONFIRM_TAG = "alignment_rename_confirm"

fun alignmentActivateTag(id: String) = "alignment_activate_$id"

fun alignmentDeactivateTag(id: String) = "alignment_deactivate_$id"

fun alignmentVisibilityTag(id: String) = "alignment_visibility_$id"

fun alignmentRenameTag(id: String) = "alignment_rename_$id"

fun alignmentDeleteTag(id: String) = "alignment_delete_$id"

fun alignmentStateTag(id: String) = "alignment_state_$id"

fun alignmentShownTag(id: String) = "alignment_shown_$id"

fun alignmentCalculateTag(id: String) = "alignment_calculate_$id"

fun alignmentStakeTag(id: String) = "alignment_stake_$id"

fun alignmentAnalyzeTag(id: String) = "alignment_analyze_$id"

fun alignmentSectionsTag(id: String) = "alignment_sections_$id"

/** Acciones del panel «Ejes» (S049/RF-14, RF-15, RF-19). [onRename] da false si el nombre está en blanco. */
class AlignmentsActions(
    val onActivate: (alignmentId: String?) -> Unit = {},
    val onRename: (alignmentId: String, name: String) -> Boolean = { _, _ -> true },
    val onVisible: (alignmentId: String, visible: Boolean) -> Unit = { _, _ -> },
    val onDelete: (alignmentId: String) -> Unit = {},
    val onCalculate: (alignmentId: String) -> Unit = {},
    /** "Replantear eje" por PK y desplazamiento (S054/RF-1). */
    val onStake: (alignmentId: String) -> Unit = {},
    /** "Analizar" la posición del jalón respecto al eje (S054/RF-12). */
    val onAnalyze: (alignmentId: String) -> Unit = {},
    /** «Secciones» del eje: secciones tipo, secciones importadas y peralte (S050/RF-1). */
    val onSections: (alignmentId: String) -> Unit = {},
)

/**
 * El panel «Ejes» del proyecto: cada eje con su fecha, su longitud y su PK, si es el activo (con texto, no solo con color), un botón para
 * activarlo o desactivarlo, otro para mostrarlo u ocultarlo en el mapa, otro para renombrarlo y otro para borrarlo con confirmación
 * (S049/RF-14, RF-15, RF-19).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlignmentsSheet(state: AlignmentsUiState, actions: AlignmentsActions, onDismiss: () -> Unit) {
    var toDelete by remember { mutableStateOf<AlignmentSummary?>(null) }
    var toRename by remember { mutableStateOf<AlignmentSummary?>(null) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp)
                .testTag(ALIGNMENTS_SHEET_TAG),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.alignments_title), style = MaterialTheme.typography.titleLarge)
            if (state.alignments.isEmpty()) {
                Text(stringResource(R.string.alignments_empty), modifier = Modifier.testTag(ALIGNMENTS_EMPTY_TAG))
            }
            state.alignments.forEach { alignment ->
                AlignmentSection(alignment, state, alignment.id == state.activeId, actions, onRename = { toRename = alignment }) {
                    toDelete = alignment
                }
            }
        }
    }
    toRename?.let { alignment -> RenameDialog(alignment, actions, onDismiss = { toRename = null }) }
    toDelete?.let { alignment ->
        AlertDialog(
            onDismissRequest = { toDelete = null },
            title = { Text(stringResource(R.string.alignment_delete_title)) },
            text = { Text(stringResource(R.string.alignment_delete_message, alignment.name)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        toDelete = null
                        actions.onDelete(alignment.id)
                    },
                    modifier = Modifier.heightIn(min = MinTouchTarget).testTag(ALIGNMENT_DELETE_CONFIRM_TAG),
                ) { Text(stringResource(R.string.alignment_delete_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { toDelete = null }, modifier = Modifier.heightIn(min = MinTouchTarget)) {
                    Text(stringResource(R.string.dialog_cancel))
                }
            },
        ) // RF-14: la confirmación va antes de borrar nada
    }
}

@Composable
private fun RenameDialog(alignment: AlignmentSummary, actions: AlignmentsActions, onDismiss: () -> Unit) {
    var name by remember(alignment.id) { mutableStateOf(alignment.name) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.alignment_rename_title)) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(R.string.alignment_convert_name)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag(ALIGNMENT_RENAME_FIELD_TAG),
            )
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (actions.onRename(alignment.id, name)) onDismiss()
                },
                enabled = name.isNotBlank(),
                modifier = Modifier.heightIn(min = MinTouchTarget).testTag(ALIGNMENT_RENAME_CONFIRM_TAG),
            ) { Text(stringResource(R.string.alignment_rename_confirm)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = MinTouchTarget)) {
                Text(stringResource(R.string.dialog_cancel))
            }
        },
    )
}

@Composable
private fun AlignmentSection(
    alignment: AlignmentSummary,
    state: AlignmentsUiState,
    active: Boolean,
    actions: AlignmentsActions,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    val locale = uiLocale()
    val date = DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(alignment.importedAtMs))
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(alignment.name, style = MaterialTheme.typography.titleMedium)
        alignment.fileName?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        Text(stringResource(R.string.alignment_imported_at, date), style = MaterialTheme.typography.bodySmall)
        Text(
            stringResource(
                R.string.alignment_summary,
                LengthFormat.metres(alignment.lengthM, locale, decimals = 1),
                ChainageFormat.format(alignment.startChainage, state.unit, locale),
                ChainageFormat.format(alignment.endChainage, state.unit, locale),
            ),
        )
        Text(stringResource(if (alignment.hasProfile) R.string.alignment_has_profile else R.string.alignment_no_profile))
        if (alignment.approximated) Text(stringResource(R.string.alignment_result_approximated), style = MaterialTheme.typography.bodySmall)
        // RNF-T8: el estado va en texto, no solo en el color.
        Text(
            stringResource(if (active) R.string.alignment_state_active else R.string.alignment_state_inactive),
            style = MaterialTheme.typography.labelLarge,
            color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag(alignmentStateTag(alignment.id)),
        )
        Text(
            stringResource(if (alignment.visible) R.string.alignment_visibility_visible else R.string.alignment_visibility_hidden),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.testTag(alignmentShownTag(alignment.id)),
        )
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        if (active) {
            OutlinedButton(
                onClick = { actions.onActivate(null) },
                modifier = Modifier.weight(1f).heightIn(min = MinTouchTarget).testTag(alignmentDeactivateTag(alignment.id)),
            ) { Text(stringResource(R.string.alignment_deactivate)) }
        } else {
            Button(
                onClick = { actions.onActivate(alignment.id) },
                modifier = Modifier.weight(1f).heightIn(min = MinTouchTarget).testTag(alignmentActivateTag(alignment.id)),
            ) { Text(stringResource(R.string.alignment_activate)) }
        }
        OutlinedButton(
            onClick = { actions.onVisible(alignment.id, !alignment.visible) },
            modifier = Modifier.weight(1f).heightIn(min = MinTouchTarget).testTag(alignmentVisibilityTag(alignment.id)),
        ) { Text(stringResource(if (alignment.visible) R.string.alignment_hide else R.string.alignment_show)) }
    }
    OutlinedButton(
        onClick = { actions.onCalculate(alignment.id) },
        modifier = Modifier.fillMaxWidth().heightIn(min = MinTouchTarget).testTag(alignmentCalculateTag(alignment.id)),
    ) { Text(stringResource(R.string.alignment_point_open)) } // S049/RF-20
    OutlinedButton(
        onClick = { actions.onStake(alignment.id) },
        modifier = Modifier.fillMaxWidth().heightIn(min = MinTouchTarget).testTag(alignmentStakeTag(alignment.id)),
    ) { Text(stringResource(R.string.alignment_stake_open)) } // S054/RF-1
    OutlinedButton(
        onClick = { actions.onAnalyze(alignment.id) },
        modifier = Modifier.fillMaxWidth().heightIn(min = MinTouchTarget).testTag(alignmentAnalyzeTag(alignment.id)),
    ) { Text(stringResource(R.string.alignment_analyze_open)) } // S054/RF-12
    OutlinedButton(
        onClick = { actions.onSections(alignment.id) },
        modifier = Modifier.fillMaxWidth().heightIn(min = MinTouchTarget).testTag(alignmentSectionsTag(alignment.id)),
    ) { Text(stringResource(R.string.section_open)) } // S050/RF-1
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        OutlinedButton(
            onClick = onRename,
            modifier = Modifier.weight(1f).heightIn(min = MinTouchTarget).testTag(alignmentRenameTag(alignment.id)),
        ) { Text(stringResource(R.string.alignment_rename)) }
        OutlinedButton(
            onClick = onDelete,
            modifier = Modifier.weight(1f).heightIn(min = MinTouchTarget).testTag(alignmentDeleteTag(alignment.id)),
        ) { Text(stringResource(R.string.alignment_delete)) }
    }
}
