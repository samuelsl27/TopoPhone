package es.topophone.app.ui.codes

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import es.topophone.app.R
import es.topophone.app.ui.SectionScaffold
import es.topophone.app.ui.projects.ConfirmDialog
import es.topophone.core.designsystem.component.MinTouchTarget
import es.topophone.core.model.codes.AttributeType
import es.topophone.core.model.codes.CodeError
import es.topophone.core.model.codes.CodeGeometry
import es.topophone.core.model.codes.CodePalette
import es.topophone.core.model.preferences.ThemeState

const val CODE_FIELD_TAG = "code_field"
const val CODE_DESCRIPTION_TAG = "code_description"
const val CODE_SAVE_TAG = "code_save"
const val CODE_DELETE_TAG = "code_delete"
const val CODE_ERROR_TAG = "code_error"
const val CODE_COLOR_AUTO_TAG = "code_color_auto"
const val ATTRIBUTE_ADD_TAG = "attribute_add"
const val OPTION_ADD_TAG_PREFIX = "option_add_"

fun geometryTag(geometry: CodeGeometry) = "code_geometry_${geometry.name}"

fun colorTag(rgb: Int) = "code_color_${CodePalette.format(rgb).drop(1)}"

fun attributeNameTag(index: Int) = "attribute_name_$index"

fun attributeTypeTag(index: Int, type: AttributeType) = "attribute_type_${index}_${type.name}"

fun attributeDefaultTag(index: Int) = "attribute_default_$index"

fun attributeDefaultChoiceTag(index: Int, option: String) = "attribute_default_${index}_$option"

fun attributeRemoveTag(index: Int) = "attribute_remove_$index"

fun attributeUpTag(index: Int) = "attribute_up_$index"

fun attributeDownTag(index: Int) = "attribute_down_$index"

fun optionTag(index: Int, option: Int) = "option_${index}_$option"

fun optionRemoveTag(index: Int, option: Int) = "option_remove_${index}_$option"

/** Lo que el usuario hace en el editor de un código (S023/RF-5 a RF-10). */
data class CodeEditorActions(
    val onCode: (String) -> Unit = {},
    val onDescription: (String) -> Unit = {},
    val onGeometry: (CodeGeometry) -> Unit = {},
    val onColor: (Int?) -> Unit = {},
    val onAddAttribute: () -> Unit = {},
    val onRemoveAttribute: (Int) -> Unit = {},
    val onMoveAttribute: (Int, Int) -> Unit = { _, _ -> },
    val onAttributeName: (Int, String) -> Unit = { _, _ -> },
    val onAttributeType: (Int, AttributeType) -> Unit = { _, _ -> },
    val onAttributeDefault: (Int, String) -> Unit = { _, _ -> },
    val onAddOption: (Int) -> Unit = {},
    val onOption: (Int, Int, String) -> Unit = { _, _, _ -> },
    val onRemoveOption: (Int, Int) -> Unit = { _, _ -> },
    val onSave: () -> Unit = {},
    val onAskDelete: () -> Unit = {},
    val onConfirmDelete: () -> Unit = {},
    val onCancelDelete: () -> Unit = {},
)

@Composable
fun CodeEditorRoute(
    libraryId: String,
    codeId: String?,
    themeState: ThemeState,
    onToggleSun: () -> Unit,
    viewModel: CodeEditorViewModel = hiltViewModel(),
    onBack: () -> Unit,
) {
    LaunchedEffect(libraryId, codeId) { viewModel.open(libraryId, codeId) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.finished) { if (state.finished) onBack() }
    CodeEditorScreen(
        themeState,
        onToggleSun,
        state,
        CodeEditorActions(
            onCode = viewModel::setCode,
            onDescription = viewModel::setDescription,
            onGeometry = viewModel::setGeometry,
            onColor = viewModel::setColor,
            onAddAttribute = viewModel::addAttribute,
            onRemoveAttribute = viewModel::removeAttribute,
            onMoveAttribute = viewModel::moveAttribute,
            onAttributeName = viewModel::setAttributeName,
            onAttributeType = viewModel::setAttributeType,
            onAttributeDefault = viewModel::setAttributeDefault,
            onAddOption = viewModel::addOption,
            onOption = viewModel::setOption,
            onRemoveOption = viewModel::removeOption,
            onSave = viewModel::save,
            onAskDelete = viewModel::askDelete,
            onConfirmDelete = viewModel::confirmDelete,
            onCancelDelete = viewModel::cancelDelete,
        ),
        onBack,
    )
}

/** El editor de un código: código, descripción, geometría, color y atributos (S023/RF-5 a RF-10). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CodeEditorScreen(
    themeState: ThemeState,
    onToggleSun: () -> Unit,
    state: CodeEditorUiState,
    actions: CodeEditorActions,
    onBack: () -> Unit,
) {
    SectionScaffold(
        title = stringResource(if (state.isNew) R.string.codes_editor_new else R.string.codes_editor_edit),
        themeState = themeState,
        onToggleSun = onToggleSun,
        onNavigateBack = onBack,
    ) { padding ->
        if (!state.available) {
            if (state.loaded) Text(stringResource(R.string.codes_not_found), modifier = Modifier.padding(padding).padding(16.dp))
            return@SectionScaffold
        }
        Column(
            modifier = Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            OutlinedTextField(
                value = state.code,
                onValueChange = actions.onCode,
                label = { Text(stringResource(R.string.codes_field_code)) },
                singleLine = true,
                isError = state.error.concernsCode(),
                modifier = Modifier.fillMaxWidth().testTag(CODE_FIELD_TAG),
            )
            OutlinedTextField(
                value = state.description,
                onValueChange = actions.onDescription,
                label = { Text(stringResource(R.string.codes_field_description)) },
                modifier = Modifier.fillMaxWidth().testTag(CODE_DESCRIPTION_TAG),
            )
            Text(stringResource(R.string.codes_field_geometry), style = MaterialTheme.typography.titleSmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CodeGeometry.entries.forEach { geometry ->
                    Choice(stringResource(geometryLabel(geometry)), geometryTag(geometry), state.geometry == geometry) {
                        actions.onGeometry(geometry)
                    }
                }
            }
            Text(stringResource(R.string.codes_field_color), style = MaterialTheme.typography.titleSmall)
            ColorPalette(state, actions)
            Text(stringResource(R.string.codes_attributes), style = MaterialTheme.typography.titleSmall)
            state.attributes.forEachIndexed { index, attribute ->
                AttributeCard(index, attribute, state.attributes.size, actions)
            }
            OutlinedButton(
                onClick = actions.onAddAttribute,
                modifier = Modifier.heightIn(min = MinTouchTarget).testTag(ATTRIBUTE_ADD_TAG),
            ) {
                Text(stringResource(R.string.codes_attribute_add))
            }
            state.error?.let {
                Text(
                    codeErrorText(it),
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.testTag(CODE_ERROR_TAG),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = actions.onSave, modifier = Modifier.heightIn(min = MinTouchTarget).testTag(CODE_SAVE_TAG)) {
                    Text(stringResource(R.string.codes_save))
                }
                if (!state.isNew) {
                    OutlinedButton(
                        onClick = actions.onAskDelete,
                        modifier = Modifier.heightIn(min = MinTouchTarget).testTag(CODE_DELETE_TAG),
                    ) {
                        Text(stringResource(R.string.codes_delete_code))
                    }
                }
            }
        }
    }
    if (state.confirmDelete) {
        ConfirmDialog(
            message = stringResource(R.string.codes_delete_code_message, state.code.trim()),
            confirm = stringResource(R.string.codes_library_delete),
            onConfirm = actions.onConfirmDelete,
            onDismiss = actions.onCancelDelete,
        )
    }
}

/** La paleta de 12 colores más «Automático»; el elegido lleva una marca y la palabra «seleccionado», no solo un borde (RNF-T8, RF-8). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ColorPalette(state: CodeEditorUiState, actions: CodeEditorActions) {
    Choice(stringResource(R.string.codes_color_auto), CODE_COLOR_AUTO_TAG, state.color == null) { actions.onColor(null) }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        CodePalette.COLORS.forEach { rgb ->
            val selected = state.color == rgb
            val hex = CodePalette.format(rgb)
            val description = stringResource(R.string.codes_color_option, hex) +
                if (selected) ", " + stringResource(R.string.codes_color_selected) else ""
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(MinTouchTarget)
                    .clip(CircleShape)
                    .border(BorderStroke(if (selected) 4.dp else 1.dp, MaterialTheme.colorScheme.onSurface), CircleShape)
                    .clickable(role = Role.RadioButton) { actions.onColor(rgb) }
                    .semantics {
                        contentDescription = description
                        this.selected = selected
                        role = Role.RadioButton
                    }
                    .testTag(colorTag(rgb)),
            ) {
                ColorSwatch(rgb, Modifier.size(MinTouchTarget - 8.dp))
                if (selected) {
                    Icon(painterResource(R.drawable.ic_check), contentDescription = null, tint = Color.White)
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AttributeCard(index: Int, attribute: AttributeDraft, count: Int, actions: CodeEditorActions) {
    val number = index + 1
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.codes_attribute_title, number), style = MaterialTheme.typography.titleSmall)
                Row {
                    IconTextButton("↑", stringResource(R.string.codes_attribute_up, number), attributeUpTag(index), enabled = index > 0) {
                        actions.onMoveAttribute(index, -1)
                    }
                    IconTextButton(
                        "↓",
                        stringResource(R.string.codes_attribute_down, number),
                        attributeDownTag(index),
                        enabled =
                        index < count - 1,
                    ) {
                        actions.onMoveAttribute(index, 1)
                    }
                    IconTextButton("✕", stringResource(R.string.codes_attribute_remove, number), attributeRemoveTag(index)) {
                        actions.onRemoveAttribute(index)
                    }
                }
            }
            OutlinedTextField(
                value = attribute.name,
                onValueChange = { actions.onAttributeName(index, it) },
                label = { Text(stringResource(R.string.codes_attribute_name)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag(attributeNameTag(index)),
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AttributeType.entries.forEach { type ->
                    Choice(stringResource(typeLabel(type)), attributeTypeTag(index, type), attribute.type == type) {
                        actions.onAttributeType(index, type)
                    }
                }
            }
            if (attribute.type == AttributeType.LIST) {
                ListOptions(index, attribute, actions)
            } else {
                OutlinedTextField(
                    value = attribute.default,
                    onValueChange = { actions.onAttributeDefault(index, it) },
                    label = { Text(stringResource(R.string.codes_attribute_default)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = if (attribute.type == AttributeType.TEXT) KeyboardType.Text else KeyboardType.Decimal,
                    ),
                    modifier = Modifier.fillMaxWidth().testTag(attributeDefaultTag(index)),
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ListOptions(index: Int, attribute: AttributeDraft, actions: CodeEditorActions) {
    Text(stringResource(R.string.codes_attribute_options), style = MaterialTheme.typography.labelLarge)
    attribute.options.forEachIndexed { option, value ->
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            OutlinedTextField(
                value = value,
                onValueChange = { actions.onOption(index, option, it) },
                label = { Text(stringResource(R.string.codes_option_value, option + 1)) },
                singleLine = true,
                modifier = Modifier.weight(1f).testTag(optionTag(index, option)),
            )
            IconTextButton("✕", stringResource(R.string.codes_option_remove, option + 1), optionRemoveTag(index, option)) {
                actions.onRemoveOption(index, option)
            }
        }
    }
    OutlinedButton(
        onClick = { actions.onAddOption(index) },
        modifier = Modifier.heightIn(min = MinTouchTarget).testTag(OPTION_ADD_TAG_PREFIX + index),
    ) { Text(stringResource(R.string.codes_option_add)) }
    Text(stringResource(R.string.codes_attribute_default), style = MaterialTheme.typography.labelLarge)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Choice(stringResource(R.string.codes_attribute_default_none), attributeDefaultChoiceTag(index, ""), attribute.default.isEmpty()) {
            actions.onAttributeDefault(index, "")
        }
        attribute.options.filter { it.isNotBlank() }.forEach { value ->
            Choice(value, attributeDefaultChoiceTag(index, value), attribute.default == value) { actions.onAttributeDefault(index, value) }
        }
    }
}

@Composable
private fun Choice(label: String, tag: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
        modifier = Modifier.heightIn(min = MinTouchTarget).testTag(tag),
    )
}

/** Un botón de texto de 56 dp con su descripción para TalkBack (RNF-T8). */
@Composable
private fun IconTextButton(symbol: String, description: String, tag: String, enabled: Boolean = true, onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .size(MinTouchTarget)
            .semantics { contentDescription = description }
            .testTag(tag),
    ) { Text(symbol, style = MaterialTheme.typography.titleLarge) }
}

private fun typeLabel(type: AttributeType): Int = when (type) {
    AttributeType.TEXT -> R.string.codes_type_text
    AttributeType.INTEGER -> R.string.codes_type_integer
    AttributeType.NUMBER -> R.string.codes_type_number
    AttributeType.LIST -> R.string.codes_type_list
}

private fun CodeError?.concernsCode(): Boolean =
    this is CodeError.Empty || this is CodeError.TooLong || this is CodeError.NewLine || this is CodeError.Duplicate

/** El motivo por el que no se guardó, con el código en conflicto o el número del atributo (RF-6, RF-7, RF-9). */
@Composable
internal fun codeErrorText(error: CodeError): String = when (error) {
    CodeError.Empty -> stringResource(R.string.codes_error_empty)
    CodeError.TooLong -> stringResource(R.string.codes_error_too_long)
    CodeError.NewLine -> stringResource(R.string.codes_error_newline)
    is CodeError.Duplicate -> stringResource(R.string.codes_error_duplicate, error.code)
    CodeError.TooManyAttributes -> stringResource(R.string.codes_error_too_many_attributes)
    is CodeError.AttributeName -> stringResource(R.string.codes_error_attribute_name, error.index + 1)
    is CodeError.ListOptions -> stringResource(R.string.codes_error_list_options, error.index + 1)
    is CodeError.DefaultValue -> stringResource(R.string.codes_error_default_value, error.index + 1)
}
