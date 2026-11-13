package es.topophone.app.ui.wms

import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import es.topophone.app.R
import es.topophone.core.designsystem.component.MinTouchTarget
import es.topophone.data.wms.ProjectLayerView
import kotlin.math.roundToInt

const val PROJECT_LAYERS_SECTION_TAG = "project_wms_section"
const val PROJECT_LAYERS_EMPTY_TAG = "project_wms_empty"
const val PROJECT_LAYERS_FULL_TAG = "project_wms_full"
const val PROJECT_LAYERS_ADD_LAYER_TAG = "project_wms_add_layer"
const val PROJECT_LAYERS_ADD_SERVICE_TAG = "project_wms_add_service"

fun projectLayerTag(id: String) = "project_wms_layer_$id"

fun projectLayerVisibleTag(id: String) = "project_wms_visible_$id"

fun projectLayerOpacityTag(id: String) = "project_wms_opacity_$id"

fun projectLayerOpacityZoneTag(id: String) = "project_wms_opacity_zone_$id"

fun projectLayerOpacityValueTag(id: String) = "project_wms_opacity_value_$id"

fun projectLayerUpTag(id: String) = "project_wms_up_$id"

fun projectLayerDownTag(id: String) = "project_wms_down_$id"

fun projectLayerDragTag(id: String) = "project_wms_drag_$id"

fun projectLayerRemoveTag(id: String) = "project_wms_remove_$id"

fun projectLayerUnavailableTag(id: String) = "project_wms_unavailable_$id"

/** Lo que la sección de capas del proyecto pide hacer; lo atiende [ProjectLayersViewModel] (S036/RF-19 a RF-23). */
class ProjectLayersActions(
    val onVisible: (layerId: String, visible: Boolean) -> Unit = { _, _ -> },
    val onOpacity: (layerId: String, percent: Int) -> Unit = { _, _ -> },
    val onMoveUp: (layerId: String) -> Unit = {},
    val onMoveDown: (layerId: String) -> Unit = {},
    val onRemove: (layerId: String) -> Unit = {},
    val onAddLayer: (serviceId: String, layerName: String) -> Unit = { _, _ -> },
    val onDismissMessage: () -> Unit = {},
) {
    companion object {
        fun of(viewModel: ProjectLayersViewModel) = ProjectLayersActions(
            onVisible = viewModel::setVisible,
            onOpacity = viewModel::setOpacity,
            onMoveUp = viewModel::moveUp,
            onMoveDown = viewModel::moveDown,
            onRemove = viewModel::remove,
            onAddLayer = viewModel::add,
            onDismissMessage = viewModel::dismissMessage,
        )
    }
}

/**
 * Las capas WMS del proyecto dentro de la hoja «Capas»: de arriba abajo, cada una con su visibilidad, su opacidad de 5 en 5 con su valor,
 * subir, bajar y arrastrar, y quitar; con «Añadir capa» y «Añadir servicio», y el tope de 10 (S036/RF-18 a RF-23, RF-26 a RF-28). Cada
 * zona que se toca mide 56 dp como mínimo.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ProjectLayersSection(state: ProjectLayersUiState, actions: ProjectLayersActions, onAddLayer: () -> Unit, onAddService: () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().testTag(PROJECT_LAYERS_SECTION_TAG), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.map_wms_title), style = MaterialTheme.typography.titleSmall)
        if (state.layers.isEmpty()) {
            Text(
                stringResource(R.string.map_wms_empty),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.testTag(PROJECT_LAYERS_EMPTY_TAG),
            )
        }
        state.layers.forEachIndexed { index, view ->
            key(view.layer.id) { LayerCard(view, first = index == 0, last = index == state.layers.lastIndex, actions) }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = onAddLayer,
                enabled = !state.full,
                modifier = Modifier.heightIn(min = MinTouchTarget).testTag(PROJECT_LAYERS_ADD_LAYER_TAG),
            ) {
                Text(stringResource(R.string.map_wms_add_layer))
            }
            Button(onClick = onAddService, modifier = Modifier.heightIn(min = MinTouchTarget).testTag(PROJECT_LAYERS_ADD_SERVICE_TAG)) {
                Text(stringResource(R.string.map_wms_add_service))
            }
        }
        if (state.full) {
            Text(
                stringResource(R.string.map_wms_full),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.testTag(PROJECT_LAYERS_FULL_TAG),
            )
        }
    }
}

@Composable
private fun LayerCard(view: ProjectLayerView, first: Boolean, last: Boolean, actions: ProjectLayersActions) {
    val layer = view.layer
    val latest by rememberUpdatedState(actions)
    var heightPx by remember { mutableIntStateOf(1) }
    OutlinedCard(modifier = Modifier.fillMaxWidth().onSizeChanged { heightPx = it.height }.testTag(projectLayerTag(layer.id))) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = MinTouchTarget)
                    .toggleable(value = layer.visible, role = Role.Switch, onValueChange = { actions.onVisible(layer.id, it) })
                    .testTag(projectLayerVisibleTag(layer.id)),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(view.title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                Switch(checked = layer.visible, onCheckedChange = null)
            }
            if (view.showService) {
                Text(
                    stringResource(R.string.map_wms_layer_service, view.service.name),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (!view.available) {
                Text(
                    stringResource(R.string.map_wms_unavailable),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.testTag(projectLayerUnavailableTag(layer.id)),
                )
            }
            Opacity(layer.id, layer.opacity, actions.onOpacity)
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(
                    onClick = { actions.onMoveUp(layer.id) },
                    enabled = !first,
                    modifier = Modifier.heightIn(min = MinTouchTarget).testTag(projectLayerUpTag(layer.id)),
                ) {
                    Text(stringResource(R.string.map_wms_move_up))
                }
                TextButton(
                    onClick = { actions.onMoveDown(layer.id) },
                    enabled = !last,
                    modifier = Modifier.heightIn(min = MinTouchTarget).testTag(projectLayerDownTag(layer.id)),
                ) {
                    Text(stringResource(R.string.map_wms_move_down))
                }
                Icon(
                    painterResource(R.drawable.ic_list),
                    contentDescription = stringResource(R.string.map_wms_drag),
                    modifier = Modifier
                        .size(MinTouchTarget)
                        .padding(16.dp)
                        .testTag(projectLayerDragTag(layer.id))
                        .pointerInput(layer.id) {
                            // Cada vez que el dedo recorre la altura de la tarjeta, la capa sube o baja un puesto (RF-22).
                            var travelled = 0f
                            detectVerticalDragGestures(onDragStart = { travelled = 0f }) { change, delta ->
                                change.consume()
                                travelled += delta
                                val step = heightPx.coerceAtLeast(1)
                                while (travelled >= step) {
                                    latest.onMoveDown(layer.id)
                                    travelled -= step
                                }
                                while (travelled <= -step) {
                                    latest.onMoveUp(layer.id)
                                    travelled += step
                                }
                            }
                        },
                )
                Spacer(Modifier.weight(1f))
                TextButton(
                    onClick = { actions.onRemove(layer.id) },
                    modifier = Modifier.heightIn(min = MinTouchTarget).testTag(projectLayerRemoveTag(layer.id)),
                ) {
                    Text(stringResource(R.string.map_wms_remove), color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

/** La opacidad de 5 en 5 con su valor a la vista; el cambio se guarda al soltar, no en cada paso del dedo (RF-21). */
@Composable
private fun Opacity(layerId: String, percent: Int, onChange: (String, Int) -> Unit) {
    var shown by remember(percent) { mutableFloatStateOf(percent.toFloat()) }
    Text(
        stringResource(R.string.map_wms_opacity, shown.roundToInt()),
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.testTag(projectLayerOpacityValueTag(layerId)),
    )
    // El control de Material mide 44 dp por dentro: la zona que se toca es la de 56 dp que lo envuelve (RNF-T8).
    Box(
        modifier = Modifier.fillMaxWidth().heightIn(min = MinTouchTarget).testTag(projectLayerOpacityZoneTag(layerId)),
        contentAlignment = Alignment.Center,
    ) {
        Slider(
            value = shown,
            onValueChange = { shown = it },
            onValueChangeFinished = { onChange(layerId, shown.roundToInt()) },
            valueRange = 0f..OPACITY_MAX,
            steps = OPACITY_STEPS,
            modifier = Modifier.testTag(projectLayerOpacityTag(layerId)),
        )
    }
}

private const val OPACITY_MAX = 100f

/** De 0 a 100 de 5 en 5 hay 21 posiciones, así que el control tiene 19 pasos intermedios. */
private const val OPACITY_STEPS = 19
