package es.topophone.app.ui.lines

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import es.topophone.app.R
import es.topophone.app.ui.SectionScaffold
import es.topophone.app.ui.collector.geometryLabel
import es.topophone.app.ui.collector.messageRes
import es.topophone.app.ui.points.ChangeCodeActions
import es.topophone.app.ui.points.ChangeCodeDialogs
import es.topophone.app.ui.projects.ConfirmDialog
import es.topophone.app.ui.projects.formatDateTime
import es.topophone.app.ui.receiver.uiLocale
import es.topophone.core.designsystem.component.MinTouchTarget
import es.topophone.core.model.alignment.ChainageFormat
import es.topophone.core.model.codes.AttributeForm
import es.topophone.core.model.line.LineChange
import es.topophone.core.model.line.LineHistoryItem
import es.topophone.core.model.line.LineHistoryLine
import es.topophone.core.model.line.ProjectLine
import es.topophone.core.model.preferences.ThemeState
import java.text.DecimalFormatSymbols

const val LINE_DETAILS_TAG = "line_details"
const val LINE_STATE_TAG = "line_state"
const val LINE_RENAME_TAG = "line_rename"
const val LINE_RENAME_FIELD_TAG = "line_rename_field"
const val LINE_RENAME_SAVE_TAG = "line_rename_save"
const val LINE_TOGGLE_CLOSED_TAG = "line_toggle_closed"
const val LINE_REVERSE_TAG = "line_reverse"
const val LINE_ADD_VERTEX_TAG = "line_add_vertex"
const val LINE_CODE_TAG = "line_code"
const val LINE_CHANGE_CODE_TAG = "line_change_code"
const val LINE_EDIT_ATTRIBUTES_TAG = "line_edit_attributes"
const val LINE_ATTRIBUTES_SAVE_TAG = "line_attributes_save"
const val LINE_ISSUE_TAG = "line_issue"
const val LINE_HISTORY_BUTTON_TAG = "line_history_button"
const val LINE_INFORMATION_TAG = "line_information_button"
const val LINE_ANGLE_TAG = "line_angle_button"
const val LINE_HISTORY_TAG = "line_history"
const val LINE_PICKER_SEARCH_TAG = "line_picker_search"
const val LINE_DELETE_TAG = "line_delete"

fun lineVertexTag(index: Int) = "line_vertex_$index"

fun lineVertexMenuTag(index: Int) = "line_vertex_menu_$index"

fun lineVertexUpTag(index: Int) = "line_vertex_up_$index"

fun lineVertexDownTag(index: Int) = "line_vertex_down_$index"

fun lineVertexReplaceTag(index: Int) = "line_vertex_replace_$index"

fun lineVertexInsertTag(index: Int) = "line_vertex_insert_$index"

fun lineVertexRemoveTag(index: Int) = "line_vertex_remove_$index"

fun linePickerPointTag(name: String) = "line_picker_point_$name"

fun lineAttributeTag(index: Int) = "line_attribute_$index"

/** Lo que el usuario hace en la ficha de una línea (S025/RF-10, RF-16 a RF-20). */
class LineDetailsActions(
    val onRename: () -> Unit = {},
    val onRenameText: (String) -> Unit = {},
    val onRenameSave: () -> Unit = {},
    val onRenameCancel: () -> Unit = {},
    val onToggleClosed: () -> Unit = {},
    val onReverse: () -> Unit = {},
    val onAddVertex: () -> Unit = {},
    val onMoveVertex: (row: Int, toRow: Int) -> Unit = { _, _ -> },
    val onReplaceVertex: (Int) -> Unit = {},
    val onInsertVertex: (Int) -> Unit = {},
    val onRemoveVertex: (Int) -> Unit = {},
    val onPickQuery: (String) -> Unit = {},
    val onPickPoint: (String) -> Unit = {},
    val onClosePicker: () -> Unit = {},
    val onChangeCode: () -> Unit = {},
    val onEditAttributes: () -> Unit = {},
    val onAttribute: (Int, String) -> Unit = { _, _ -> },
    val onAttributesSave: () -> Unit = {},
    val onAttributesCancel: () -> Unit = {},
    val onDelete: () -> Unit = {},
    /** Abre la información geométrica de la línea (S026/RF-1). */
    val onInformation: () -> Unit = {},
    /** Abre el ángulo entre tres vértices de la línea (S026/RF-13). */
    val onAngle: () -> Unit = {},
)

@Composable
fun LineDetailsRoute(
    projectId: String,
    lineId: String,
    themeState: ThemeState,
    onToggleSun: () -> Unit,
    onInformation: () -> Unit = {},
    onAngle: () -> Unit = {},
    viewModel: LineDetailsViewModel = hiltViewModel(),
    onBack: () -> Unit,
) {
    LaunchedEffect(projectId, lineId) { viewModel.start(projectId, lineId) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val change by viewModel.changeCode.ui.collectAsStateWithLifecycle()
    LineDetailsScreen(
        themeState,
        onToggleSun,
        state,
        LineDetailsActions(
            onRename = viewModel::startRename,
            onRenameText = viewModel::setRename,
            onRenameSave = viewModel::confirmRename,
            onRenameCancel = viewModel::cancelRename,
            onToggleClosed = viewModel::toggleClosed,
            onReverse = viewModel::reverse,
            onAddVertex = { viewModel.openPicker(VertexPick.Append) },
            onMoveVertex = viewModel::moveVertex,
            onReplaceVertex = { viewModel.openPicker(VertexPick.Replace(it)) },
            onInsertVertex = { viewModel.openPicker(VertexPick.Insert(it)) },
            onRemoveVertex = viewModel::removeVertex,
            onPickQuery = viewModel::setPickQuery,
            onPickPoint = viewModel::pickPoint,
            onClosePicker = viewModel::closePicker,
            onChangeCode = viewModel::openChangeCode,
            onEditAttributes = viewModel::startAttributes,
            onAttribute = viewModel::setAttribute,
            onAttributesSave = viewModel::saveAttributes,
            onAttributesCancel = viewModel::cancelAttributes,
            onDelete = viewModel::delete,
            onInformation = onInformation,
            onAngle = onAngle,
        ),
        onBack = onBack,
    )
    LaunchedEffect(state.deleted) { if (state.deleted) onBack() }
    ChangeCodeDialogs(
        change,
        ChangeCodeActions(
            onQuery = viewModel.changeCode::setQuery,
            onChoose = viewModel.changeCode::choose,
            onConfirm = viewModel.changeCode::confirm,
            onCancel = viewModel.changeCode::cancel,
        ),
        allowNone = false,
    )
}

/**
 * La ficha de una línea del proyecto (S025/RF-22): estado, código con sus atributos, vértices con su PK y sus acciones, y el historial.
 * Cada edición se guarda al momento con el valor anterior en el historial (RF-18).
 */
@Composable
fun LineDetailsScreen(
    themeState: ThemeState,
    onToggleSun: () -> Unit,
    state: LineDetailsUi,
    actions: LineDetailsActions,
    onBack: () -> Unit,
) {
    var showHistory by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    SectionScaffold(
        title = state.line?.name ?: stringResource(R.string.line_details_title),
        themeState = themeState,
        onToggleSun = onToggleSun,
        onNavigateBack = onBack,
    ) { padding ->
        val line = state.line
        if (line == null) {
            if (state.loaded) Text(stringResource(R.string.line_not_found), modifier = Modifier.padding(padding).padding(16.dp))
            return@SectionScaffold
        }
        LazyColumn(
            modifier = Modifier.padding(padding).padding(horizontal = 16.dp).testTag(LINE_DETAILS_TAG),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item(key = "head") { Header(line, state, actions, onHistory = { showHistory = true }, onDelete = { confirmDelete = true }) }
            itemsIndexed(state.vertices, key = { _, row -> "v-${row.index}-${row.pointId}" }) { row, vertex ->
                VertexItem(row, vertex, state, actions, last = row == state.vertices.lastIndex)
            }
        }
    }
    if (state.pick != null) PointPicker(state, actions)
    state.renaming?.let { RenameDialog(it, state.issue == LineEditIssue.NAME_TAKEN, actions) }
    state.attributes?.let { AttributesDialog(it, actions) }
    if (showHistory) HistoryDialog(state.history, onDismiss = { showHistory = false })
    val toDelete = state.line
    if (confirmDelete && toDelete != null) {
        ConfirmDialog(
            message = stringResource(R.string.line_delete_confirm, toDelete.name),
            confirm = stringResource(R.string.objects_move_to_trash),
            onConfirm = {
                confirmDelete = false
                actions.onDelete()
            },
            onDismiss = { confirmDelete = false },
        )
    }
}

@Composable
private fun Header(line: ProjectLine, state: LineDetailsUi, actions: LineDetailsActions, onHistory: () -> Unit, onDelete: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 12.dp)) {
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(line.name, style = MaterialTheme.typography.titleLarge)
                val stateText = buildString {
                    append(stringResource(if (line.closed) R.string.line_state_polygon else R.string.line_state_open))
                    if (line.active) append(" · ").append(stringResource(R.string.line_state_active))
                }
                Text(stateText, style = MaterialTheme.typography.titleSmall, modifier = Modifier.testTag(LINE_STATE_TAG))
                Text(
                    pluralStringResource(R.plurals.collector_line_vertices, state.vertices.size, state.vertices.size),
                    style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedButton(
                    onClick = actions.onRename,
                    modifier = Modifier.fillMaxWidth().heightIn(min = MinTouchTarget).testTag(LINE_RENAME_TAG),
                ) { Text(stringResource(R.string.line_rename)) }
            }
        }
        CodeCard(line, actions)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(
                onClick = actions.onToggleClosed,
                modifier = Modifier.weight(1f).heightIn(min = MinTouchTarget).testTag(LINE_TOGGLE_CLOSED_TAG),
            ) { Text(stringResource(if (line.closed) R.string.line_open else R.string.line_close)) }
            OutlinedButton(
                onClick = actions.onReverse,
                modifier = Modifier.weight(1f).heightIn(min = MinTouchTarget).testTag(LINE_REVERSE_TAG),
            ) { Text(stringResource(R.string.line_reverse)) }
        }
        state.issue?.let {
            Text(stringResource(it.messageRes()), color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag(LINE_ISSUE_TAG))
        }
        Button(
            onClick = actions.onAddVertex,
            modifier = Modifier.fillMaxWidth().heightIn(min = MinTouchTarget).testTag(LINE_ADD_VERTEX_TAG),
        ) { Text(stringResource(R.string.line_add_vertex)) }
        OutlinedButton(
            onClick = actions.onInformation,
            modifier = Modifier.fillMaxWidth().heightIn(min = MinTouchTarget).testTag(LINE_INFORMATION_TAG),
        ) { Text(stringResource(R.string.geometry_button)) }
        OutlinedButton(
            onClick = actions.onAngle,
            modifier = Modifier.fillMaxWidth().heightIn(min = MinTouchTarget).testTag(LINE_ANGLE_TAG),
        ) { Text(stringResource(R.string.angle_button)) }
        OutlinedButton(
            onClick = onHistory,
            modifier = Modifier.fillMaxWidth().heightIn(min = MinTouchTarget).testTag(LINE_HISTORY_BUTTON_TAG),
        ) { Text(stringResource(R.string.line_history)) }
        OutlinedButton(
            onClick = onDelete,
            modifier = Modifier.fillMaxWidth().heightIn(min = MinTouchTarget).testTag(LINE_DELETE_TAG),
        ) { Text(stringResource(R.string.line_delete)) }
        Text(stringResource(R.string.line_vertices_title), style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun CodeCard(line: ProjectLine, actions: LineDetailsActions) {
    val code = line.code
    val decimal = DecimalFormatSymbols.getInstance(uiLocale()).decimalSeparator
    val empty = stringResource(R.string.point_history_none)
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp).testTag(LINE_CODE_TAG), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.point_code_title), style = MaterialTheme.typography.titleMedium)
            if (code == null) {
                Text(stringResource(R.string.collector_code_none))
            } else {
                val title = if (code.description.isBlank()) {
                    code.code
                } else {
                    stringResource(
                        R.string.collector_code_selected,
                        code.code,
                        code.description,
                    )
                }
                Text(title, style = MaterialTheme.typography.titleSmall)
                geometryLabel(line.geometry)?.let { Text(it, style = MaterialTheme.typography.labelMedium) }
                val texts = AttributeForm.texts(code.attributes, decimal)
                code.attributes.forEachIndexed { i, attribute ->
                    Text(stringResource(R.string.point_history_attribute_value, attribute.name, texts[i].ifEmpty { empty }))
                }
            }
            OutlinedButton(
                onClick = actions.onChangeCode,
                modifier = Modifier.fillMaxWidth().heightIn(min = MinTouchTarget).testTag(LINE_CHANGE_CODE_TAG),
            ) { Text(stringResource(R.string.change_code_button)) }
            if (code != null && code.attributes.isNotEmpty()) {
                OutlinedButton(
                    onClick = actions.onEditAttributes,
                    modifier = Modifier.fillMaxWidth().heightIn(min = MinTouchTarget).testTag(LINE_EDIT_ATTRIBUTES_TAG),
                ) { Text(stringResource(R.string.line_edit_attributes)) }
            }
        }
    }
}

@Composable
private fun VertexItem(row: Int, vertex: VertexRow, state: LineDetailsUi, actions: LineDetailsActions, last: Boolean) {
    var menu by remember { mutableStateOf(false) }
    val locale = uiLocale()
    val chainage = vertex.chainageM?.let { ChainageFormat.format(it, state.unit, locale) }
    val menuDescription = stringResource(R.string.line_vertex_menu_for, vertex.name)
    val label = if (chainage == null) {
        stringResource(R.string.line_vertex_row, row + 1, vertex.name)
    } else {
        stringResource(R.string.line_vertex_row_pk, row + 1, vertex.name, chainage)
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().heightIn(min = MinTouchTarget).testTag(lineVertexTag(row))
            .semantics(mergeDescendants = false) {},
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        TextButton(
            onClick = { menu = true },
            modifier = Modifier.heightIn(min = MinTouchTarget).testTag(lineVertexMenuTag(row)).semantics {
                contentDescription = menuDescription
            },
        ) { Text(stringResource(R.string.line_vertex_menu)) }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            if (row > 0) {
                MenuItem(R.string.line_vertex_up, lineVertexUpTag(row)) {
                    menu = false
                    actions.onMoveVertex(row, row - 1)
                }
            }
            if (!last) {
                MenuItem(R.string.line_vertex_down, lineVertexDownTag(row)) {
                    menu = false
                    actions.onMoveVertex(row, row + 1)
                }
            }
            MenuItem(R.string.line_vertex_replace, lineVertexReplaceTag(row)) {
                menu = false
                actions.onReplaceVertex(row)
            }
            MenuItem(R.string.line_vertex_insert, lineVertexInsertTag(row)) {
                menu = false
                actions.onInsertVertex(row)
            }
            MenuItem(R.string.line_vertex_remove, lineVertexRemoveTag(row)) {
                menu = false
                actions.onRemoveVertex(row)
            }
        }
    }
}

@Composable
private fun MenuItem(text: Int, tag: String, onClick: () -> Unit) {
    DropdownMenuItem(text = {
        Text(stringResource(text))
    }, onClick = onClick, modifier = Modifier.heightIn(min = MinTouchTarget).testTag(tag))
}

@Composable
private fun PointPicker(state: LineDetailsUi, actions: LineDetailsActions) {
    AlertDialog(
        onDismissRequest = actions.onClosePicker,
        title = { Text(stringResource(R.string.line_picker_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = state.pickQuery,
                    onValueChange = actions.onPickQuery,
                    label = { Text(stringResource(R.string.line_wizard_search)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag(LINE_PICKER_SEARCH_TAG),
                )
                LazyColumn(modifier = Modifier.heightIn(max = 320.dp)) {
                    itemsIndexed(state.candidates, key = { _, c -> c.id }) { _, point ->
                        Text(
                            point.name,
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.fillMaxWidth().heightIn(min = MinTouchTarget).clickable { actions.onPickPoint(point.id) }
                                .padding(horizontal = 4.dp, vertical = 14.dp).testTag(linePickerPointTag(point.name)),
                        )
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = actions.onClosePicker, modifier = Modifier.heightIn(min = MinTouchTarget)) {
                Text(stringResource(R.string.dialog_cancel))
            }
        },
    )
}

@Composable
private fun RenameDialog(name: String, taken: Boolean, actions: LineDetailsActions) {
    AlertDialog(
        onDismissRequest = actions.onRenameCancel,
        title = { Text(stringResource(R.string.line_rename)) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = actions.onRenameText,
                label = { Text(stringResource(R.string.line_wizard_name)) },
                isError = taken,
                supportingText = if (taken) ({ Text(stringResource(R.string.line_wizard_issue_name)) }) else null,
                singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag(LINE_RENAME_FIELD_TAG),
            )
        },
        confirmButton = {
            TextButton(
                onClick = actions.onRenameSave,
                enabled = name.isNotBlank(),
                modifier = Modifier.heightIn(min = MinTouchTarget).testTag(LINE_RENAME_SAVE_TAG),
            ) { Text(stringResource(R.string.project_save)) }
        },
        dismissButton = {
            TextButton(onClick = actions.onRenameCancel, modifier = Modifier.heightIn(min = MinTouchTarget)) {
                Text(stringResource(R.string.dialog_cancel))
            }
        },
    )
}

@Composable
private fun AttributesDialog(edit: LineAttributesEdit, actions: LineDetailsActions) {
    AlertDialog(
        onDismissRequest = actions.onAttributesCancel,
        title = { Text(stringResource(R.string.line_edit_attributes)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                edit.fields.forEachIndexed { index, field ->
                    OutlinedTextField(
                        value = field.text,
                        onValueChange = { actions.onAttribute(index, it) },
                        label = { Text(field.name) },
                        isError = field.error != null,
                        supportingText = field.error?.let { { Text(stringResource(it.messageRes())) } },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().testTag(lineAttributeTag(index)),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = actions.onAttributesSave,
                enabled = edit.fields.all { it.error == null },
                modifier = Modifier.heightIn(min = MinTouchTarget).testTag(LINE_ATTRIBUTES_SAVE_TAG),
            ) { Text(stringResource(R.string.project_save)) }
        },
        dismissButton = {
            TextButton(onClick = actions.onAttributesCancel, modifier = Modifier.heightIn(min = MinTouchTarget)) {
                Text(stringResource(R.string.dialog_cancel))
            }
        },
    )
}

@Composable
private fun HistoryDialog(history: List<LineHistoryItem>, onDismiss: () -> Unit) {
    val locale = uiLocale()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.line_history_title)) },
        text = {
            if (history.isEmpty()) {
                Text(stringResource(R.string.line_history_empty), modifier = Modifier.testTag(LINE_HISTORY_TAG))
            } else {
                LazyColumn(
                    modifier = Modifier.heightIn(max = 400.dp).testTag(LINE_HISTORY_TAG),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    itemsIndexed(history) { _, item ->
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                stringResource(
                                    R.string.point_history_entry,
                                    formatDateTime(item.changedAtMs, locale),
                                    stringResource(item.change.titleRes()),
                                ),
                                style = MaterialTheme.typography.labelLarge,
                            )
                            item.lines.forEach { Text(historyLine(it)) }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = MinTouchTarget)) {
                Text(stringResource(R.string.collector_close))
            }
        },
    )
}

private fun LineChange.titleRes(): Int = when (this) {
    LineChange.NAME -> R.string.line_change_name
    LineChange.CODE -> R.string.line_change_code
    LineChange.ATTRIBUTES -> R.string.line_change_attributes
    LineChange.VERTICES -> R.string.line_change_vertices
    LineChange.CLOSED -> R.string.line_change_closed
    LineChange.VERTEX_REMOVED -> R.string.line_change_removed
}

@Composable
private fun historyLine(line: LineHistoryLine): String {
    val noCode = stringResource(R.string.point_history_no_code)
    val empty = stringResource(R.string.point_history_none)
    return when (line) {
        is LineHistoryLine.Name -> stringResource(R.string.point_history_name, line.from, line.to)
        is LineHistoryLine.Code -> stringResource(R.string.point_history_code, line.from ?: noCode, line.to ?: noCode)
        is LineHistoryLine.Attributes -> line.changes.map {
            stringResource(R.string.point_history_attribute, it.name, it.from.ifEmpty { empty }, it.to.ifEmpty { empty })
        }.joinToString("; ")
        is LineHistoryLine.Closed -> stringResource(
            R.string.line_history_closed,
            stringResource(if (line.from) R.string.line_state_polygon else R.string.line_state_open),
            stringResource(if (line.to) R.string.line_state_polygon else R.string.line_state_open),
        )
        is LineHistoryLine.Vertices -> buildString {
            append(stringResource(R.string.line_history_vertices, line.fromCount, line.toCount))
            if (line.removed.isNotEmpty()) {
                append(
                    ". ",
                ).append(stringResource(R.string.line_history_removed, line.removed.joinToString(", ")))
            }
            if (line.added.isNotEmpty()) append(". ").append(stringResource(R.string.line_history_added, line.added.joinToString(", ")))
        }
    }
}

private fun LineEditIssue.messageRes(): Int = when (this) {
    LineEditIssue.TOO_FEW_VERTICES -> R.string.line_wizard_issue_few
    LineEditIssue.REPEATED_VERTEX -> R.string.line_wizard_issue_repeated
    LineEditIssue.NAME_TAKEN -> R.string.line_wizard_issue_name
    LineEditIssue.ATTRIBUTES -> R.string.line_wizard_issue_attributes
    LineEditIssue.MISSING_POINT -> R.string.line_wizard_issue_missing
}
