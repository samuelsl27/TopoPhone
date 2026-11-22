package es.topophone.app.ui.crs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import es.topophone.app.R
import es.topophone.app.ui.SectionScaffold
import es.topophone.core.designsystem.component.MinTouchTarget
import es.topophone.core.geodesy.catalog.CustomError
import es.topophone.core.geodesy.catalog.CustomFields
import es.topophone.core.model.preferences.ThemeState
import es.topophone.core.model.project.CustomCrs
import es.topophone.core.model.project.LinearUnit

const val CUSTOM_START_EMPTY_TAG = "custom_start_empty"
const val CUSTOM_START_LIBRARY_TAG = "custom_start_library"
const val CUSTOM_START_MINE_TAG = "custom_start_mine"
const val CUSTOM_NAME_TAG = "custom_name"
const val CUSTOM_DATUM_NAME_TAG = "custom_datum_name"
const val CUSTOM_SEMI_MAJOR_TAG = "custom_semi_major"
const val CUSTOM_INVERSE_FLATTENING_TAG = "custom_inverse_flattening"
const val CUSTOM_PRIME_MERIDIAN_TAG = "custom_prime_meridian"
const val CUSTOM_SAVE_TAG = "custom_save"

fun customUnitTag(unit: LinearUnit) = "custom_unit_${unit.name}"

fun customStartMineTag(id: String) = "custom_start_mine_$id"

/** Lo que el editor de SC personalizados hace con lo que el usuario toca (S035/RF-1 a RF-8). */
class CustomCrsEditorActions(
    val onChange: ((CustomCrsForm) -> CustomCrsForm) -> Unit = {},
    val onStartFrom: (EditorStart) -> Unit = {},
    val onOpenLibrary: () -> Unit = {},
    val onSave: () -> Unit = {},
    val onProbe: ((ProbeUi) -> ProbeUi) -> Unit = {},
    val onBack: () -> Unit = {},
)

/** El texto de un error de campo (S035/RF-6). */
@Composable
fun customErrorText(error: CustomError): String = stringResource(
    when (error) {
        CustomError.REQUIRED -> R.string.custom_error_required
        CustomError.NOT_FINITE -> R.string.custom_error_not_finite
        CustomError.NOT_POSITIVE -> R.string.custom_error_not_positive
        CustomError.OUT_OF_RANGE -> R.string.custom_error_out_of_range
        CustomError.AT_POLE -> R.string.custom_error_at_pole
        CustomError.AT_EQUATOR -> R.string.custom_error_at_equator
        CustomError.NOT_A_POLE -> R.string.custom_error_not_a_pole
        CustomError.OPPOSITE_PARALLELS -> R.string.custom_error_opposite_parallels
        CustomError.WRONG_HEMISPHERE -> R.string.custom_error_wrong_hemisphere
        CustomError.UNSUPPORTED_METHOD -> R.string.custom_error_unsupported_method
        CustomError.UNKNOWN_GRID -> R.string.custom_error_unknown_grid
        CustomError.INVALID -> R.string.custom_error_invalid
        CustomError.PROJECTION_FAILED -> R.string.custom_error_projection_failed
    },
)

/** Un campo de texto con su error, si lo hay: lo marca y dice por qué (S035/RF-6). */
@Composable
fun CustomField(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    tag: String,
    error: CustomError? = null,
    errorOverride: String? = null,
    hint: String? = null,
    numeric: Boolean = true,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        isError = error != null || errorOverride != null,
        supportingText = {
            val text = errorOverride ?: error?.let { customErrorText(it) } ?: hint
            if (text != null) Text(text)
        },
        singleLine = true,
        // El teclado numérico de Android no deja escribir el signo menos: las longitudes al oeste son negativas.
        keyboardOptions = if (numeric) KeyboardOptions(keyboardType = KeyboardType.Ascii) else KeyboardOptions.Default,
        modifier = modifier.fillMaxWidth().testTag(tag),
    )
}

/**
 * El editor de un SC personalizado (S035/RF-1 a RF-8). [mine] es Mis SC, para partir de uno de ellos (RF-1); [librarySlot] es el selector de la
 * biblioteca que se abre al partir de un SC de ella.
 */
@Composable
fun CustomCrsEditorScreen(
    themeState: ThemeState,
    onToggleSun: () -> Unit,
    state: CustomCrsEditorUiState,
    actions: CustomCrsEditorActions,
    mine: List<CustomCrs> = emptyList(),
    library: CrsLibraryHost? = null,
    onGridsInstalled: () -> Unit = {},
) {
    SectionScaffold(
        title = stringResource(if (state.editing) R.string.custom_editor_title_edit else R.string.custom_editor_title_new),
        themeState = themeState,
        onToggleSun = onToggleSun,
        onNavigateBack = actions.onBack,
    ) { padding ->
        Column(
            modifier = Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (!state.editing) StartFrom(mine, actions)
            IdentitySection(state, actions)
            EllipsoidSection(state, actions)
            TransformationSection(state, actions)
            ProjectionSection(state, actions)
            UnitsSection(state, actions)
            HeightSection(state, actions)
            MissingGridsSection(state, library, onGridsInstalled)
            ProbeSection(state, actions)
            Button(
                onClick = actions.onSave,
                modifier = Modifier.fillMaxWidth().heightIn(min = MinTouchTarget).testTag(CUSTOM_SAVE_TAG),
            ) { Text(stringResource(R.string.custom_save)) }
        }
    }
}

@Composable
private fun StartFrom(mine: List<CustomCrs>, actions: CustomCrsEditorActions) {
    var menu by remember { mutableStateOf(false) }
    Text(stringResource(R.string.custom_start_from), style = MaterialTheme.typography.titleSmall)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedButton(
            onClick = { actions.onStartFrom(EditorStart.Empty) },
            modifier = Modifier.heightIn(min = MinTouchTarget).testTag(CUSTOM_START_EMPTY_TAG),
        ) { Text(stringResource(R.string.custom_start_empty)) }
        OutlinedButton(
            onClick = actions.onOpenLibrary,
            modifier = Modifier.heightIn(min = MinTouchTarget).testTag(CUSTOM_START_LIBRARY_TAG),
        ) { Text(stringResource(R.string.custom_start_library)) }
        OutlinedButton(
            onClick = { menu = true },
            modifier = Modifier.heightIn(min = MinTouchTarget).testTag(CUSTOM_START_MINE_TAG),
        ) { Text(stringResource(R.string.custom_start_mine)) }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            if (mine.isEmpty()) {
                DropdownMenuItem(text = {
                    Text(stringResource(R.string.custom_start_mine_empty))
                }, onClick = { menu = false }, enabled = false)
            }
            mine.forEach { crs ->
                DropdownMenuItem(
                    text = { Text(crs.name) },
                    onClick = {
                        menu = false
                        actions.onStartFrom(EditorStart.Mine(crs))
                    },
                    modifier = Modifier.testTag(customStartMineTag(crs.id)),
                )
            }
        }
    }
}

@Composable
private fun IdentitySection(state: CustomCrsEditorUiState, actions: CustomCrsEditorActions) {
    val form = state.form
    val errors = state.shownErrors
    CustomField(
        stringResource(R.string.custom_field_name),
        form.name,
        { value -> actions.onChange { it.copy(name = value) } },
        CUSTOM_NAME_TAG,
        error = errors[CustomFields.NAME],
        errorOverride = if (state.nameTaken) stringResource(R.string.custom_name_taken) else null,
        numeric = false,
    )
    CustomField(
        stringResource(R.string.custom_field_datum_name),
        form.datumName,
        { value -> actions.onChange { it.copy(datumName = value) } },
        CUSTOM_DATUM_NAME_TAG,
        numeric = false,
    )
}

@Composable
private fun EllipsoidSection(state: CustomCrsEditorUiState, actions: CustomCrsEditorActions) {
    val form = state.form
    val errors = state.shownErrors
    Text(stringResource(R.string.custom_ellipsoid_title), style = MaterialTheme.typography.titleSmall)
    CustomField(
        stringResource(R.string.custom_field_semi_major),
        form.semiMajor,
        { value -> actions.onChange { it.copy(semiMajor = value) } },
        CUSTOM_SEMI_MAJOR_TAG,
        error = errors[CustomFields.SEMI_MAJOR_AXIS],
    )
    CustomField(
        stringResource(R.string.custom_field_inverse_flattening),
        form.inverseFlattening,
        { value -> actions.onChange { it.copy(inverseFlattening = value) } },
        CUSTOM_INVERSE_FLATTENING_TAG,
        error = errors[CustomFields.INVERSE_FLATTENING],
        hint = stringResource(R.string.custom_inverse_flattening_hint),
    )
    CustomField(
        stringResource(R.string.custom_field_prime_meridian),
        form.primeMeridian,
        { value -> actions.onChange { it.copy(primeMeridian = value) } },
        CUSTOM_PRIME_MERIDIAN_TAG,
        error = errors[CustomFields.PRIME_MERIDIAN],
    )
}

@Composable
private fun UnitsSection(state: CustomCrsEditorUiState, actions: CustomCrsEditorActions) {
    Text(stringResource(R.string.custom_units_title), style = MaterialTheme.typography.titleSmall)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        LinearUnit.entries.forEach { unit ->
            FilterChip(
                selected = state.form.unit == unit,
                onClick = { actions.onChange { it.copy(unit = unit) } },
                label = {
                    Text(
                        stringResource(
                            when (unit) {
                                LinearUnit.METRE -> R.string.custom_unit_metre
                                LinearUnit.INTERNATIONAL_FOOT -> R.string.custom_unit_foot
                                LinearUnit.US_SURVEY_FOOT -> R.string.custom_unit_usfoot
                            },
                        ),
                    )
                },
                modifier = Modifier.heightIn(min = MinTouchTarget).testTag(customUnitTag(unit)),
            )
        }
    }
}
