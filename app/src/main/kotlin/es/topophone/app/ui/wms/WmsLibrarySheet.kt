package es.topophone.app.ui.wms

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import es.topophone.app.R
import es.topophone.app.ui.projects.ConfirmDialog
import es.topophone.core.designsystem.component.MinTouchTarget
import es.topophone.core.model.wms.WmsAddressProblem
import es.topophone.core.model.wms.WmsLayerProblem
import es.topophone.core.model.wms.WmsRequestMode
import es.topophone.core.model.wms.WmsService
import es.topophone.data.wms.MissingProjectLayer
import es.topophone.data.wms.WmsAddProblem

const val WMS_LIST_TAG = "wms_library_list"
const val WMS_ADDRESS_TAG = "wms_address"
const val WMS_ADD_TAG = "wms_add"
const val WMS_ADD_ERROR_TAG = "wms_add_error"
const val WMS_ADDING_TAG = "wms_adding"
const val WMS_BACK_TAG = "wms_back"
const val WMS_NOTICE_TAG = "wms_notice"
const val WMS_MODE_TILES_TAG = "wms_mode_tiles"
const val WMS_MODE_IMAGE_TAG = "wms_mode_image"
const val WMS_REFRESH_TAG = "wms_refresh"
const val WMS_REFRESH_RESULT_TAG = "wms_refresh_result"
const val WMS_RENAME_TAG = "wms_rename"
const val WMS_RENAME_FIELD_TAG = "wms_rename_field"
const val WMS_RENAME_SAVE_TAG = "wms_rename_save"
const val WMS_DELETE_TAG = "wms_delete"
const val WMS_PICK_MESSAGE_TAG = "wms_pick_message"

fun wmsPickAddTag(ordinal: Int) = "wms_pick_add_$ordinal"

fun wmsPickInProjectTag(ordinal: Int) = "wms_pick_in_project_$ordinal"

fun wmsServiceTag(id: String) = "wms_service_$id"

fun wmsPresetAddTag(key: String) = "wms_preset_add_$key"

fun wmsPresetOpenTag(key: String) = "wms_preset_open_$key"

fun wmsPresetLicenseTag(key: String) = "wms_preset_license_$key"

fun wmsLayerTag(ordinal: Int) = "wms_layer_$ordinal"

/** Lo que la hoja de la biblioteca pide hacer; cada una la atiende [WmsLibraryViewModel] (S036/RF-1 a RF-17). */
class WmsLibraryActions(
    val onAddressChange: (String) -> Unit = {},
    val onAdd: () -> Unit = {},
    val onAddPreset: (String) -> Unit = {},
    val onOpen: (String) -> Unit = {},
    val onClose: () -> Unit = {},
    val onRefresh: () -> Unit = {},
    val onDismissRefresh: () -> Unit = {},
    val onRename: (String) -> Unit = {},
    val onSetRequestMode: (WmsRequestMode) -> Unit = {},
    val onRequestDelete: (String) -> Unit = {},
    val onCancelDelete: () -> Unit = {},
    val onConfirmDelete: () -> Unit = {},
    val onOpenUrl: (String) -> Unit = {},
) {
    companion object {
        fun of(viewModel: WmsLibraryViewModel, onOpenUrl: (String) -> Unit) = WmsLibraryActions(
            onAddressChange = viewModel::onAddressChange,
            onAdd = viewModel::add,
            onAddPreset = viewModel::addPreset,
            onOpen = viewModel::open,
            onClose = viewModel::close,
            onRefresh = viewModel::refresh,
            onDismissRefresh = viewModel::dismissRefresh,
            onRename = viewModel::rename,
            onSetRequestMode = viewModel::setRequestMode,
            onRequestDelete = viewModel::requestDelete,
            onCancelDelete = viewModel::cancelDelete,
            onConfirmDelete = viewModel::confirmDelete,
            onOpenUrl = onOpenUrl,
        )
    }
}

/**
 * La biblioteca abierta desde «Añadir capa» (S036/RF-19): las capas que se pueden añadir llevan «Añadir al proyecto». [inProject] son las
 * capas que el proyecto ya tiene, como pares servicio-capa; con [full] ya no caben más (RF-26) y [message] dice por qué no se añadió la última.
 */
class WmsLayerPicker(
    val inProject: Set<Pair<String, String>>,
    val full: Boolean,
    val message: ProjectLayersMessage?,
    val onAdd: (serviceId: String, layerName: String) -> Unit,
    val onDismissMessage: () -> Unit,
)

/** La biblioteca de servicios WMS en una hoja: añadir por dirección o por preset, ver capas, actualizar, renombrar y borrar (S036/RF-1 a RF-17). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WmsLibrarySheet(
    onDismiss: () -> Unit,
    onOpenUrl: (String) -> Unit,
    pick: WmsLayerPicker? = null,
    viewModel: WmsLibraryViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        WmsLibraryContent(state, WmsLibraryActions.of(viewModel, onOpenUrl), pick)
    }
}

/** El contenido de la hoja, sin la hoja: toda una lista perezosa, porque un servicio puede tener más de mil capas (S036/RF-11). */
@Composable
fun WmsLibraryContent(state: WmsLibraryUiState, actions: WmsLibraryActions, pick: WmsLayerPicker? = null) {
    var renaming by remember { mutableStateOf(false) }
    val opened = state.opened
    LazyColumn(
        modifier = Modifier.fillMaxWidth().testTag(WMS_LIST_TAG),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (opened == null) libraryItems(state, actions, pick != null) else serviceItems(state, opened, actions, pick) { renaming = true }
    }
    state.pendingDelete?.let { pending ->
        val uses = pending.uses.map { use -> pluralResource(R.plurals.wms_delete_use_item, use.layers, use.projectName) }.joinToString("\n")
        val message = stringResource(R.string.wms_delete_message, pending.service.name) +
            if (pending.uses.isEmpty()) "" else "\n\n" + stringResource(R.string.wms_delete_uses) + "\n" + uses
        ConfirmDialog(message, stringResource(R.string.wms_delete), actions.onConfirmDelete, actions.onCancelDelete)
    }
    if (renaming && opened != null) {
        RenameDialog(opened.service.name, onSave = { actions.onRename(it) }, onDismiss = { renaming = false })
    }
}

@Composable
private fun pluralResource(id: Int, count: Int, name: String) = pluralStringResource(id, count, name, count)

private fun LazyListScope.libraryItems(state: WmsLibraryUiState, actions: WmsLibraryActions, picking: Boolean) {
    item(key = "title") {
        Text(
            stringResource(if (picking) R.string.wms_pick_title else R.string.wms_library_title),
            style = MaterialTheme.typography.titleLarge,
        )
    }
    item(key = "add") { AddForm(state, actions) }
    item(key = "services_title") { SectionTitle(stringResource(R.string.wms_services_title)) }
    if (state.services.isEmpty()) {
        item(key = "services_empty") { Text(stringResource(R.string.wms_services_empty), style = MaterialTheme.typography.bodyMedium) }
    }
    items(state.services, key = { "service_${it.id}" }) { service -> ServiceRow(service, actions.onOpen) }
    item(key = "presets_title") { SectionTitle(stringResource(R.string.wms_presets_title)) }
    state.presetGroups.forEach { group ->
        item(key = "group_${group.name}") { Text(group.name, style = MaterialTheme.typography.titleSmall) }
        items(group.items, key = { "preset_${it.preset.key}" }) { item ->
            PresetRow(item, adding = state.add is WmsAddState.Loading, actions)
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    HorizontalDivider()
    Text(text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
}

/** La dirección del servicio con «Añadir», el aviso de que se está pidiendo y, si falla, el motivo (S036/RF-1, RF-8 a RF-10). */
@Composable
private fun AddForm(state: WmsLibraryUiState, actions: WmsLibraryActions) {
    val loading = state.add as? WmsAddState.Loading
    val error = (state.add as? WmsAddState.Error)?.failure
    val canAdd = state.address.isNotBlank() && loading == null
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.wms_add_title), style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(
            value = state.address,
            onValueChange = actions.onAddressChange,
            label = { Text(stringResource(R.string.wms_address_label)) },
            placeholder = { Text(stringResource(R.string.wms_address_hint)) },
            singleLine = true,
            enabled = loading == null,
            isError = error != null,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
            keyboardActions = KeyboardActions(onGo = { if (canAdd) actions.onAdd() }),
            modifier = Modifier.fillMaxWidth().testTag(WMS_ADDRESS_TAG),
        )
        if (loading != null) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp))
                val preset = loading.presetKey?.let { key -> state.presetGroups.flatMap { it.items }.firstOrNull { it.preset.key == key } }
                Text(
                    if (preset ==
                        null
                    ) {
                        stringResource(R.string.wms_adding)
                    } else {
                        stringResource(R.string.wms_adding_preset, preset.preset.name)
                    },
                    modifier = Modifier.testTag(WMS_ADDING_TAG),
                )
            }
        }
        if (error != null) {
            Text(
                wmsFailureText(error),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.testTag(WMS_ADD_ERROR_TAG),
            )
        }
        Button(onClick = actions.onAdd, enabled = canAdd, modifier = Modifier.heightIn(min = MinTouchTarget).testTag(WMS_ADD_TAG)) {
            Text(stringResource(R.string.wms_add_button))
        }
    }
}

@Composable
private fun ServiceRow(service: WmsService, onOpen: (String) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = MinTouchTarget)
            .clickable { onOpen(service.id) }
            .padding(vertical = 4.dp)
            .testTag(wmsServiceTag(service.id)),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(service.name, style = MaterialTheme.typography.bodyLarge)
        Text(
            stringResource(R.string.wms_service_summary, service.version, modeLabel(service.requestMode)),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun PresetRow(item: WmsPresetItem, adding: Boolean, actions: WmsLibraryActions) {
    val preset = item.preset
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = MinTouchTarget),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(preset.name, style = MaterialTheme.typography.bodyLarge)
            Text(
                stringResource(R.string.wms_preset_license, preset.license),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag(wmsPresetLicenseTag(preset.key)),
            )
            preset.licenseUrl?.let { url ->
                TextButton(onClick = { actions.onOpenUrl(url) }, modifier = Modifier.heightIn(min = MinTouchTarget)) {
                    Text(stringResource(R.string.wms_preset_license_link))
                }
            }
        }
        if (item.serviceId == null) {
            Button(
                onClick = { actions.onAddPreset(preset.key) },
                enabled = !adding,
                modifier = Modifier.heightIn(min = MinTouchTarget).testTag(wmsPresetAddTag(preset.key)),
            ) {
                Text(stringResource(R.string.wms_preset_add))
            }
        } else {
            OutlinedButton(
                onClick = { actions.onOpen(item.serviceId) },
                modifier = Modifier.heightIn(min = MinTouchTarget).testTag(wmsPresetOpenTag(preset.key)),
            ) {
                Text(stringResource(R.string.wms_preset_in_library))
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
private fun LazyListScope.serviceItems(
    state: WmsLibraryUiState,
    opened: OpenedWmsService,
    actions: WmsLibraryActions,
    pick: WmsLayerPicker?,
    onRename: () -> Unit,
) {
    val service = opened.service
    item(key = "back") {
        TextButton(onClick = actions.onClose, modifier = Modifier.heightIn(min = MinTouchTarget).testTag(WMS_BACK_TAG)) {
            Text(stringResource(R.string.wms_back))
        }
    }
    item(key = "header") {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(service.name, style = MaterialTheme.typography.titleLarge)
            if (service.title.isNotBlank() && service.title != service.name) Text(service.title, style = MaterialTheme.typography.bodyLarge)
            Text(
                stringResource(R.string.wms_service_summary, service.version, modeLabel(service.requestMode)),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                if (service.attribution.isBlank()) {
                    stringResource(R.string.wms_service_no_attribution)
                } else {
                    stringResource(R.string.wms_service_attribution, service.attribution)
                },
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
    opened.notice?.let { notice ->
        item(key = "notice") {
            Text(
                stringResource(if (notice == WmsOpenNotice.ADDED) R.string.wms_notice_added else R.string.wms_notice_already),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.testTag(WMS_NOTICE_TAG),
            )
        }
    }
    pick?.message?.let { message ->
        item(key = "pick_message") { PickMessage(message, pick.onDismissMessage) }
    }
    item(key = "mode") { ModeChooser(service.requestMode, actions.onSetRequestMode) }
    item(key = "actions") {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val refreshing = state.refresh == WmsRefreshState.Loading
            Button(
                onClick = actions.onRefresh,
                enabled = !refreshing,
                modifier = Modifier.heightIn(min = MinTouchTarget).testTag(WMS_REFRESH_TAG),
            ) {
                Text(stringResource(if (refreshing) R.string.wms_refreshing else R.string.wms_refresh))
            }
            OutlinedButton(onClick = onRename, modifier = Modifier.heightIn(min = MinTouchTarget).testTag(WMS_RENAME_TAG)) {
                Text(stringResource(R.string.wms_rename))
            }
            OutlinedButton(
                onClick = { actions.onRequestDelete(service.id) },
                modifier = Modifier.heightIn(min = MinTouchTarget).testTag(WMS_DELETE_TAG),
            ) {
                Text(stringResource(R.string.wms_delete))
            }
        }
    }
    item(key = "refresh_result") { RefreshResult(state.refresh, actions.onDismissRefresh) }
    item(key = "layers_title") { SectionTitle(stringResource(R.string.wms_layers_title)) }
    items(opened.layers, key = { "layer_${it.info.ordinal}" }) { row -> LayerRow(row, service.id, pick) }
}

/** Por qué no se añadió la última capa pedida al proyecto (S036/RF-12, RF-26). */
@Composable
private fun PickMessage(message: ProjectLayersMessage, onDismiss: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            stringResource(
                when (message) {
                    ProjectLayersMessage.ALREADY_THERE -> R.string.wms_message_already
                    ProjectLayersMessage.LIMIT_REACHED -> R.string.map_wms_full
                    ProjectLayersMessage.NO_WEB_MERCATOR -> R.string.wms_message_no_mercator
                    ProjectLayersMessage.NOT_FOUND -> R.string.wms_message_not_found
                },
            ),
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f).testTag(WMS_PICK_MESSAGE_TAG),
        )
        TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = MinTouchTarget)) { Text(stringResource(R.string.wms_dismiss)) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ModeChooser(mode: WmsRequestMode, onSelect: (WmsRequestMode) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(R.string.wms_mode_title), style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = mode == WmsRequestMode.TILES,
                onClick = { onSelect(WmsRequestMode.TILES) },
                label = { Text(stringResource(R.string.wms_mode_tiles)) },
                modifier = Modifier.heightIn(min = MinTouchTarget).testTag(WMS_MODE_TILES_TAG),
            )
            FilterChip(
                selected = mode == WmsRequestMode.IMAGE,
                onClick = { onSelect(WmsRequestMode.IMAGE) },
                label = { Text(stringResource(R.string.wms_mode_image)) },
                modifier = Modifier.heightIn(min = MinTouchTarget).testTag(WMS_MODE_IMAGE_TAG),
            )
        }
        Text(
            stringResource(if (mode == WmsRequestMode.TILES) R.string.wms_mode_tiles_help else R.string.wms_mode_image_help),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** El resultado de actualizar: «actualizadas», las capas de proyectos que han desaparecido o el fallo (S036/RF-6). */
@Composable
private fun RefreshResult(refresh: WmsRefreshState, onDismiss: () -> Unit) {
    if (refresh == WmsRefreshState.Idle || refresh == WmsRefreshState.Loading) return
    Column(
        modifier = Modifier.fillMaxWidth().testTag(WMS_REFRESH_RESULT_TAG),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        when (refresh) {
            is WmsRefreshState.Done -> {
                Text(stringResource(R.string.wms_refresh_done), style = MaterialTheme.typography.bodyMedium)
                if (refresh.missing.isNotEmpty()) {
                    Text(
                        stringResource(R.string.wms_refresh_missing),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                    refresh.missing.forEach { Text(missingText(it), style = MaterialTheme.typography.bodyMedium) }
                }
            }
            is WmsRefreshState.Failed -> Text(
                stringResource(R.string.wms_refresh_failed, wmsFailureText(refresh.failure)),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
            else -> Unit
        }
        TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = MinTouchTarget)) { Text(stringResource(R.string.wms_dismiss)) }
    }
}

@Composable
private fun missingText(layer: MissingProjectLayer) = stringResource(R.string.wms_refresh_missing_item, layer.projectName, layer.layerName)

/** Una capa del árbol del servicio, con sangría por su profundidad, y por qué no se puede añadir si es el caso (S036/RF-3, RF-12). */
@Composable
private fun LayerRow(row: WmsLayerRow, serviceId: String, pick: WmsLayerPicker?) {
    val info = row.info
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = (minOf(info.depth, MAX_INDENT_LEVELS) * INDENT_DP).dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        LayerTexts(row, Modifier.weight(1f).testTag(wmsLayerTag(info.ordinal)))
        val name = info.name
        if (pick != null && row.problem == null && name != null) {
            if ((serviceId to name) in pick.inProject) {
                Text(
                    stringResource(R.string.wms_pick_in_project),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.testTag(wmsPickInProjectTag(info.ordinal)),
                )
            } else {
                Button(
                    onClick = { pick.onAdd(serviceId, name) },
                    enabled = !pick.full,
                    modifier = Modifier.heightIn(min = MinTouchTarget).testTag(wmsPickAddTag(info.ordinal)),
                ) {
                    Text(stringResource(R.string.wms_pick_add))
                }
            }
        }
    }
}

@Composable
private fun LayerTexts(row: WmsLayerRow, modifier: Modifier) {
    val info = row.info
    Column(
        modifier = modifier.heightIn(min = MinTouchTarget).semantics(mergeDescendants = true) {},
        verticalArrangement = Arrangement.Center,
    ) {
        Text(info.title.ifBlank { info.name.orEmpty() }, style = MaterialTheme.typography.bodyLarge)
        info.name?.let {
            Text(
                stringResource(R.string.wms_layer_name, it),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (info.abstract.isNotBlank()) Text(info.abstract, style = MaterialTheme.typography.bodySmall, maxLines = SUMMARY_LINES)
        val flags = listOfNotNull(
            if (info.transparent) stringResource(R.string.wms_layer_transparent) else null,
            if (info.queryable) stringResource(R.string.wms_layer_queryable) else null,
        )
        if (flags.isNotEmpty()) {
            Text(
                flags.joinToString(" · "),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        row.problem?.let { problem ->
            Text(
                stringResource(
                    if (problem ==
                        WmsLayerProblem.GROUP_ONLY
                    ) {
                        R.string.wms_layer_group_only
                    } else {
                        R.string.wms_layer_no_mercator
                    },
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun RenameDialog(current: String, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.wms_rename_title)) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag(WMS_RENAME_FIELD_TAG),
            )
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onSave(name)
                    onDismiss()
                },
                enabled = name.isNotBlank(),
                modifier = Modifier.heightIn(min = MinTouchTarget).testTag(WMS_RENAME_SAVE_TAG),
            ) {
                Text(stringResource(R.string.wms_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = MinTouchTarget)) {
                Text(stringResource(R.string.dialog_cancel))
            }
        },
    )
}

@Composable
private fun modeLabel(mode: WmsRequestMode) = stringResource(
    if (mode ==
        WmsRequestMode.TILES
    ) {
        R.string.wms_mode_tiles
    } else {
        R.string.wms_mode_image
    },
)

/** El texto del motivo por el que no se pudo añadir o actualizar un servicio (S036/RF-8 a RF-10). */
@Composable
fun wmsFailureText(failure: WmsFailure): String = when (failure) {
    is WmsFailure.Address -> stringResource(
        when (failure.problem) {
            WmsAddressProblem.EMPTY -> R.string.wms_error_address_empty
            WmsAddressProblem.NOT_A_URL -> R.string.wms_error_address_not_url
            WmsAddressProblem.UNSUPPORTED_SCHEME -> R.string.wms_error_address_scheme
            WmsAddressProblem.USER_INFO -> R.string.wms_error_address_user_info
            WmsAddressProblem.PUBLIC_HTTP -> R.string.wms_error_address_public_http
        },
    )
    is WmsFailure.Service -> when (failure.problem) {
        WmsAddProblem.HTTP_ERROR -> stringResource(R.string.wms_error_http, failure.detail.orEmpty())
        WmsAddProblem.SERVICE_EXCEPTION ->
            failure.detail?.let { stringResource(R.string.wms_error_service_exception_detail, it) }
                ?: stringResource(R.string.wms_error_service_exception)
        WmsAddProblem.NO_RESPONSE -> stringResource(R.string.wms_error_no_response)
        WmsAddProblem.TIMEOUT -> stringResource(R.string.wms_error_timeout)
        WmsAddProblem.NOT_CAPABILITIES -> stringResource(R.string.wms_error_not_capabilities)
        WmsAddProblem.MALFORMED -> stringResource(R.string.wms_error_malformed)
        WmsAddProblem.TOO_LARGE -> stringResource(R.string.wms_error_too_large)
        WmsAddProblem.NO_WEB_MERCATOR -> stringResource(R.string.wms_error_no_web_mercator)
    }
}

private const val INDENT_DP = 16
private const val MAX_INDENT_LEVELS = 10
private const val SUMMARY_LINES = 2
