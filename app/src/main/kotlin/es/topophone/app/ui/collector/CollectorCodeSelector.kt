package es.topophone.app.ui.collector

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import es.topophone.app.R
import es.topophone.core.designsystem.component.MinTouchTarget
import es.topophone.core.model.codes.CodeGeometry
import es.topophone.core.model.codes.SurveyCode

const val COLLECTOR_CODE_TAG = "collector_code"
const val COLLECTOR_CODE_SEARCH_TAG = "collector_code_search"
const val COLLECTOR_CODE_NONE_TAG = "collector_code_none"
const val COLLECTOR_CODE_NEW_TAG = "collector_code_new"
const val COLLECTOR_CODE_CLOSE_TAG = "collector_code_close"
const val COLLECTOR_CODE_EMPTY_TAG = "collector_code_empty"

fun codeOptionTag(code: String) = "collector_code_option_$code"

/** Un color 0xRRGGBB como `Color` opaco. */
internal fun rgbColor(rgb: Int): Color = Color(0xFF000000.toInt() or (rgb and 0xFFFFFF))

/**
 * El código elegido para el siguiente punto, junto al nombre y la descripción, y el selector que se abre con un toque: los recientes
 * arriba, el buscador y «Sin código» (S024/RF-1 a RF-3). Con una medida en marcha no se cambia (RF-11).
 */
@Composable
internal fun CodeSelectorRow(code: CollectorCodeUi, enabled: Boolean, actions: CollectorActions) {
    var open by rememberSaveable { mutableStateOf(false) }
    val selected = code.selected
    val description = if (selected == null) stringResource(R.string.collector_code_none) else codeText(selected.code, selected.description)
    val action = stringResource(R.string.collector_code_choose, description)
    OutlinedButton(
        onClick = { open = true },
        enabled = enabled,
        modifier = Modifier.fillMaxWidth().heightIn(min = MinTouchTarget).testTag(COLLECTOR_CODE_TAG).semantics {
            contentDescription =
                action
        },
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (selected != null) Swatch(selected.color)
            Text(
                stringResource(R.string.collector_code_label, description),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f),
            )
        }
    }
    if (open) {
        CodePickerDialog(
            code = code,
            onQuery = actions.onCodeQuery,
            onSelect = {
                actions.onSelectCode(it)
                open = false
            },
            onEdit = actions.onEditCode?.let { edit ->
                { code ->
                    open = false
                    edit(code)
                }
            },
            onNew = actions.onNewCode?.let { create ->
                {
                    open = false
                    create()
                }
            },
            onDismiss = {
                actions.onCodeQuery("")
                open = false
            },
        )
    }
}

/** «ARB · Árbol», o solo el código si no tiene descripción. */
@Composable
private fun codeText(code: String, description: String): String =
    if (description.isBlank()) code else stringResource(R.string.collector_code_selected, code, description)

@Composable
private fun Swatch(rgb: Int) {
    Box(modifier = Modifier.size(16.dp).background(rgbColor(rgb), CircleShape))
}

@Composable
internal fun CodePickerDialog(
    code: CollectorCodeUi,
    onQuery: (String) -> Unit,
    onSelect: (SurveyCode?) -> Unit,
    onNew: (() -> Unit)?,
    onEdit: ((SurveyCode) -> Unit)?,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.collector_code_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = code.query,
                    onValueChange = onQuery,
                    label = { Text(stringResource(R.string.collector_code_search)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag(COLLECTOR_CODE_SEARCH_TAG),
                )
                LazyColumn(modifier = Modifier.heightIn(max = 360.dp)) {
                    item(key = "none") {
                        OptionRow(
                            label = stringResource(R.string.collector_code_none),
                            description = stringResource(R.string.collector_code_none),
                            rgb = null,
                            tag = COLLECTOR_CODE_NONE_TAG,
                        ) { onSelect(null) }
                    }
                    if (code.options.isEmpty()) {
                        item(key = "empty") {
                            Text(
                                stringResource(
                                    if (code.totalCodes ==
                                        0
                                    ) {
                                        R.string.collector_code_empty
                                    } else {
                                        R.string.collector_code_no_match
                                    },
                                ),
                                modifier = Modifier.padding(vertical = 12.dp).testTag(COLLECTOR_CODE_EMPTY_TAG),
                            )
                        }
                    }
                    if (code.recentCount > 0) {
                        item(key = "recent") { SectionTitle(stringResource(R.string.collector_code_recent)) }
                        items(code.options.take(code.recentCount), key = {
                            "r-" + it.id
                        }) { option -> CodeOption(option, code.ownIds, onSelect, onEdit) }
                        item(key = "all") { SectionTitle(stringResource(R.string.collector_code_all)) }
                        items(code.options.drop(code.recentCount), key = {
                            it.id
                        }) { option -> CodeOption(option, code.ownIds, onSelect, onEdit) }
                    } else {
                        items(code.options, key = { it.id }) { option -> CodeOption(option, code.ownIds, onSelect, onEdit) }
                    }
                }
            }
        },
        confirmButton = {
            if (onNew != null) {
                TextButton(onClick = onNew, modifier = Modifier.heightIn(min = MinTouchTarget).testTag(COLLECTOR_CODE_NEW_TAG)) {
                    Text(stringResource(R.string.collector_code_new))
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = MinTouchTarget).testTag(COLLECTOR_CODE_CLOSE_TAG)) {
                Text(stringResource(R.string.collector_close))
            }
        },
    )
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 8.dp),
    )
}

@Composable
private fun CodeOption(option: SurveyCode, ownIds: Set<String>, onSelect: (SurveyCode?) -> Unit, onEdit: ((SurveyCode) -> Unit)?) {
    val geometry = geometryLabel(option.geometry)
    val text = codeText(option.code, option.description)
    val description = if (geometry == null) text else stringResource(R.string.collector_code_with_geometry, text, geometry)
    val editable = onEdit != null && option.id in ownIds
    OptionRow(
        label = option.code,
        description = description,
        rgb = option.color,
        tag = codeOptionTag(option.code),
        detail = option.description,
        geometry = geometry,
        trailing = if (editable) {
            { EditButton(option, onEdit) }
        } else {
            null
        },
    ) {
        onSelect(option)
    }
}

/** Una fila de 56 dp con el color y el texto del código: nunca distingue solo por el color (S024/RNF-T8). */
@Composable
private fun OptionRow(
    label: String,
    description: String,
    rgb: Int?,
    tag: String,
    detail: String = "",
    geometry: String? = null,
    trailing: (@Composable () -> Unit)? = null,
    onClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxWidth().heightIn(min = MinTouchTarget).clickable(onClick = onClick).padding(horizontal = 4.dp)
            .semantics(mergeDescendants = true) { contentDescription = description }.testTag(tag),
    ) {
        if (rgb != null) Swatch(rgb) else Box(modifier = Modifier.size(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.titleMedium)
            if (detail.isNotBlank()) Text(detail, style = MaterialTheme.typography.bodyMedium)
            // S025/RF-1: los códigos de línea y de polígono se marcan con su geometría, con texto y no solo con color.
            if (geometry != null) Text(geometry, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        }
        trailing?.invoke()
    }
}

/** El nombre de la geometría de un código de línea o de polígono; null para un punto (S025/RF-1). */
@Composable
internal fun geometryLabel(geometry: CodeGeometry): String? = when (geometry) {
    CodeGeometry.POINT -> null
    CodeGeometry.LINE -> stringResource(R.string.collector_code_geometry_line)
    CodeGeometry.POLYGON -> stringResource(R.string.collector_code_geometry_polygon)
}

fun codeEditTag(code: String) = "collector_code_edit_$code"

@Composable
private fun EditButton(option: SurveyCode, onEdit: ((SurveyCode) -> Unit)?) {
    TextButton(
        onClick = { onEdit?.invoke(option) },
        modifier = Modifier.heightIn(min = MinTouchTarget).testTag(codeEditTag(option.code)),
    ) {
        Text(stringResource(R.string.collector_code_edit))
    }
}
