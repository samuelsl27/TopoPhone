package es.topophone.app.ui.cad

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import es.topophone.app.R
import es.topophone.core.designsystem.component.MinTouchTarget
import es.topophone.core.model.cad.DesignLayer
import es.topophone.core.model.cad.DesignLayerKind
import es.topophone.data.cad.CadDrawingWithLayers
import java.text.DateFormat
import java.util.Date

const val DRAWINGS_BUTTON_TAG = "drawings_button"
const val DRAWINGS_SHEET_TAG = "drawings_sheet"
const val DRAWINGS_EMPTY_TAG = "drawings_empty"
const val DRAWING_DELETE_CONFIRM_TAG = "drawing_delete_confirm"

fun drawingDeleteTag(drawingId: String) = "drawing_delete_$drawingId"

fun layerSwitchTag(layerId: String) = "layer_switch_$layerId"

fun layerGroupTag(drawingId: String, kind: DesignLayerKind) = "layer_group_${drawingId}_${kind.name}"

/** Acciones del panel «Dibujos» (S040/RF-27, RF-30). */
class DrawingsActions(
    val onLayerVisible: (layerId: String, visible: Boolean) -> Unit = { _, _ -> },
    val onDeleteDrawing: (drawingId: String) -> Unit = {},
)

/**
 * El panel «Dibujos» del mapa: cada dibujo importado con sus capas agrupadas en puntos y líneas, cada una con su color, su
 * nombre, cuántos elementos tiene y un interruptor; y el borrado de un dibujo entero con su confirmación (S040/RF-26 a RF-28, RF-30).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DrawingsSheet(drawings: List<CadDrawingWithLayers>, actions: DrawingsActions, onDismiss: () -> Unit) {
    var toDelete by remember { mutableStateOf<CadDrawingWithLayers?>(null) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp)
                .testTag(DRAWINGS_SHEET_TAG),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.cad_drawings_title), style = MaterialTheme.typography.titleLarge)
            if (drawings.isEmpty()) {
                Text(stringResource(R.string.cad_drawings_empty), modifier = Modifier.testTag(DRAWINGS_EMPTY_TAG))
            }
            drawings.forEach { entry -> DrawingSection(entry, actions) { toDelete = entry } }
        }
    }
    toDelete?.let { entry ->
        AlertDialog(
            onDismissRequest = { toDelete = null },
            title = { Text(stringResource(R.string.cad_drawing_delete_title)) },
            text = { Text(stringResource(R.string.cad_drawing_delete_message, entry.drawing.fileName)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        toDelete = null
                        actions.onDeleteDrawing(entry.drawing.id)
                    },
                    modifier = Modifier.heightIn(min = MinTouchTarget).testTag(DRAWING_DELETE_CONFIRM_TAG),
                ) { Text(stringResource(R.string.cad_drawing_delete_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { toDelete = null }, modifier = Modifier.heightIn(min = MinTouchTarget)) {
                    Text(stringResource(R.string.dialog_cancel))
                }
            },
        ) // RF-30: la confirmación va antes de borrar nada
    }
}

@Composable
private fun DrawingSection(entry: CadDrawingWithLayers, actions: DrawingsActions, onDelete: () -> Unit) {
    val drawing = entry.drawing
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(drawing.fileName, style = MaterialTheme.typography.titleMedium)
        val date = DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(drawing.importedAtMs))
        Text(stringResource(R.string.cad_drawing_imported_at, date), style = MaterialTheme.typography.bodySmall)
        if (drawing.incomplete) {
            Text(
                stringResource(R.string.cad_drawing_incomplete),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
    DesignLayerKind.entries.forEach { kind ->
        val layers = entry.layers.filter { it.kind == kind }
        if (layers.isNotEmpty()) {
            Column(modifier = Modifier.testTag(layerGroupTag(drawing.id, kind)), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    stringResource(if (kind == DesignLayerKind.POINTS) R.string.cad_layers_points else R.string.cad_layers_lines),
                    style = MaterialTheme.typography.labelLarge,
                )
                layers.forEach { layer -> LayerRow(layer, drawing.fileName) { actions.onLayerVisible(layer.id, it) } }
            }
        }
    }
    OutlinedButton(
        onClick = onDelete,
        modifier = Modifier.fillMaxWidth().heightIn(min = MinTouchTarget).testTag(drawingDeleteTag(drawing.id)),
    ) { Text(stringResource(R.string.cad_drawing_delete)) }
}

/** Una capa: su color, su nombre, cuántos elementos tiene y su interruptor; el estado va en texto, no solo en el interruptor (RNF-T8). */
@Composable
private fun LayerRow(layer: DesignLayer, drawingName: String, onChange: (Boolean) -> Unit) {
    val count = pluralStringResource(R.plurals.cad_layer_count, layer.entityCount, layer.entityCount)
    val state = stringResource(if (layer.visible) R.string.cad_layer_visible else R.string.cad_layer_hidden)
    val description = stringResource(R.string.cad_layer_description, layer.name, drawingName, count, state)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = MinTouchTarget)
            .toggleable(value = layer.visible, role = Role.Switch, onValueChange = onChange)
            .semantics { contentDescription = description }
            .testTag(layerSwitchTag(layer.id)),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        val swatch = if (layer.color.autoContrast) MaterialTheme.colorScheme.onSurface else Color(layer.color.argb)
        Box(
            Modifier
                .size(20.dp)
                .background(swatch, RoundedCornerShape(4.dp))
                .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(4.dp)),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(layer.name, style = MaterialTheme.typography.bodyLarge)
            Text(stringResource(R.string.cad_layer_summary, count, state), style = MaterialTheme.typography.bodySmall)
        }
        Switch(checked = layer.visible, onCheckedChange = null)
    }
}
