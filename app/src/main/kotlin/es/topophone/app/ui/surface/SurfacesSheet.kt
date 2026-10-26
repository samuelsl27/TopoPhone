package es.topophone.app.ui.surface

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import es.topophone.app.R
import es.topophone.app.ui.projects.unitSymbol
import es.topophone.app.ui.receiver.uiLocale
import es.topophone.core.designsystem.component.MinTouchTarget
import es.topophone.core.model.format.LengthFormat
import es.topophone.core.model.surface.Surface
import java.text.DateFormat
import java.text.NumberFormat
import java.util.Date

const val SURFACES_BUTTON_TAG = "surfaces_button"
const val SURFACES_SHEET_TAG = "surfaces_sheet"
const val SURFACES_EMPTY_TAG = "surfaces_empty"
const val SURFACE_DELETE_CONFIRM_TAG = "surface_delete_confirm"
const val SURFACE_OFFSET_FIELD_TAG = "surface_offset_field"
const val SURFACE_OFFSET_APPLY_TAG = "surface_offset_apply"
const val SURFACE_OFFSET_ERROR_TAG = "surface_offset_error"

fun surfaceActivateTag(surfaceId: String) = "surface_activate_$surfaceId"

fun surfaceDeactivateTag(surfaceId: String) = "surface_deactivate_$surfaceId"

fun surfaceDeleteTag(surfaceId: String) = "surface_delete_$surfaceId"

fun surfaceStateTag(surfaceId: String) = "surface_state_$surfaceId"

/** Acciones del panel «Superficies» (S042/RF-16, RF-18 a RF-20). [onOffset] da false si lo escrito no es un número. */
class SurfacesActions(
    val onActivate: (surfaceId: String?) -> Unit = {},
    val onDelete: (surfaceId: String) -> Unit = {},
    val onOffset: (text: String) -> Boolean = { true },
)

/**
 * El panel «Superficies» del proyecto: cada superficie importada con su fecha, su extensión y sus cotas, si es la activa, un botón
 * para activarla o desactivarla y otro para borrarla con confirmación; y el desplazamiento vertical del proyecto (S042/RF-16 a RF-20).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SurfacesSheet(state: SurfacesUiState, actions: SurfacesActions, onDismiss: () -> Unit) {
    var toDelete by remember { mutableStateOf<Surface?>(null) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp)
                .testTag(SURFACES_SHEET_TAG),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.surfaces_title), style = MaterialTheme.typography.titleLarge)
            if (state.surfaces.isEmpty()) {
                Text(stringResource(R.string.surfaces_empty), modifier = Modifier.testTag(SURFACES_EMPTY_TAG))
            }
            state.surfaces.forEach { surface ->
                SurfaceSection(surface, surface.id == state.activeId, actions) { toDelete = surface }
            }
            OffsetSection(state, actions)
        }
    }
    toDelete?.let { surface ->
        AlertDialog(
            onDismissRequest = { toDelete = null },
            title = { Text(stringResource(R.string.surface_delete_title)) },
            text = { Text(stringResource(R.string.surface_delete_message, surface.name, surface.fileName)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        toDelete = null
                        actions.onDelete(surface.id)
                    },
                    modifier = Modifier.heightIn(min = MinTouchTarget).testTag(SURFACE_DELETE_CONFIRM_TAG),
                ) { Text(stringResource(R.string.surface_delete_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { toDelete = null }, modifier = Modifier.heightIn(min = MinTouchTarget)) {
                    Text(stringResource(R.string.dialog_cancel))
                }
            },
        ) // RF-18: la confirmación va antes de borrar nada
    }
}

@Composable
private fun SurfaceSection(surface: Surface, active: Boolean, actions: SurfacesActions, onDelete: () -> Unit) {
    val number = NumberFormat.getNumberInstance().apply {
        minimumFractionDigits = 1
        maximumFractionDigits = 1
    }
    val date = DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(surface.importedAtMs))
    val width = surface.extent.maxEasting - surface.extent.minEasting
    val height = surface.extent.maxNorthing - surface.extent.minNorthing
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(surface.name, style = MaterialTheme.typography.titleMedium)
        Text(surface.fileName, style = MaterialTheme.typography.bodySmall)
        Text(stringResource(R.string.surface_imported_at, date), style = MaterialTheme.typography.bodySmall)
        Text(stringResource(R.string.surface_summary, surface.vertexCount, surface.triangleCount, surface.holeCount))
        Text(
            stringResource(
                R.string.surface_size,
                number.format(width),
                number.format(height),
                number.format(surface.minElevationM),
                number.format(surface.maxElevationM),
            ),
        )
        // RNF-T8: el estado va en texto, no solo en el color.
        Text(
            stringResource(if (active) R.string.surface_state_active else R.string.surface_state_inactive),
            style = MaterialTheme.typography.labelLarge,
            color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag(surfaceStateTag(surface.id)),
        )
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        if (active) {
            OutlinedButton(
                onClick = { actions.onActivate(null) },
                modifier = Modifier.weight(1f).heightIn(min = MinTouchTarget).testTag(surfaceDeactivateTag(surface.id)),
            ) { Text(stringResource(R.string.surface_deactivate)) }
        } else {
            Button(
                onClick = { actions.onActivate(surface.id) },
                modifier = Modifier.weight(1f).heightIn(min = MinTouchTarget).testTag(surfaceActivateTag(surface.id)),
            ) { Text(stringResource(R.string.surface_activate)) }
        }
        OutlinedButton(
            onClick = onDelete,
            modifier = Modifier.weight(1f).heightIn(min = MinTouchTarget).testTag(surfaceDeleteTag(surface.id)),
        ) { Text(stringResource(R.string.surface_delete)) }
    }
}

/** El desplazamiento vertical del proyecto, en la unidad del proyecto y con signo (S042/RF-19, RF-20). */
@Composable
private fun OffsetSection(state: SurfacesUiState, actions: SurfacesActions) {
    val locale = uiLocale()
    val symbol = unitSymbol(state.unit)
    val shown = LengthFormat.inUnit(state.offsetM, state.unit, locale)
    var text by remember(shown) { mutableStateOf(shown) }
    var valid by remember { mutableStateOf(true) }
    Text(stringResource(R.string.surface_offset_title), style = MaterialTheme.typography.titleMedium)
    Text(stringResource(R.string.surface_offset_help), style = MaterialTheme.typography.bodySmall)
    OutlinedTextField(
        value = text,
        onValueChange = {
            text = it
            valid = true
        },
        label = { Text(stringResource(R.string.surface_offset_label, symbol)) },
        isError = !valid,
        supportingText = if (valid) {
            null
        } else {
            { Text(stringResource(R.string.surface_offset_invalid), modifier = Modifier.testTag(SURFACE_OFFSET_ERROR_TAG)) }
        },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = Modifier.fillMaxWidth().testTag(SURFACE_OFFSET_FIELD_TAG),
    )
    Button(
        onClick = { valid = actions.onOffset(text) },
        modifier = Modifier.fillMaxWidth().heightIn(min = MinTouchTarget).testTag(SURFACE_OFFSET_APPLY_TAG),
    ) { Text(stringResource(R.string.surface_offset_apply)) }
}
