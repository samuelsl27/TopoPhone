package es.topophone.app.ui.alignment

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import es.topophone.app.R
import es.topophone.app.ui.SectionScaffold
import es.topophone.app.ui.receiver.uiLocale
import es.topophone.core.designsystem.component.MinTouchTarget
import es.topophone.core.formats.alignment.ProfileCsvError
import es.topophone.core.formats.alignment.ProfileMappingProblem
import es.topophone.core.formats.alignment.ProfileRole
import es.topophone.core.model.alignment.ChainageFormat
import es.topophone.core.model.alignment.ProfileOmission
import es.topophone.core.model.format.LengthFormat
import es.topophone.core.model.point.ImportDecimal
import es.topophone.core.model.point.ImportEncoding
import es.topophone.core.model.point.ImportSeparator
import es.topophone.core.model.preferences.ThemeState

const val PROFILE_CSV_PROBLEM_TAG = "profile_csv_problem"
const val PROFILE_CSV_HEADER_TAG = "profile_csv_header"
const val PROFILE_CSV_PREVIEW_TAG = "profile_csv_preview"
const val PROFILE_CSV_ERRORS_TAG = "profile_csv_errors"
const val PROFILE_CSV_ATTACH_TAG = "profile_csv_attach"
const val PROFILE_CSV_REJECTED_TAG = "profile_csv_rejected"
const val PROFILE_CSV_FAILED_TAG = "profile_csv_failed"
const val PROFILE_CSV_DONE_TAG = "profile_csv_done"
const val PROFILE_CSV_MAPPING_TAG = "profile_csv_mapping"
const val PROFILE_CSV_CONFLICT_TAG = "profile_csv_conflict"

fun profileCsvAlignmentTag(id: String) = "profile_csv_alignment_$id"

fun profileCsvRoleTag(index: Int, role: ProfileRole) = "profile_csv_role_${index}_${role.name}"

fun profileCsvEncodingTag(encoding: ImportEncoding) = "profile_csv_encoding_${encoding.name}"

fun profileCsvSeparatorTag(separator: ImportSeparator) = "profile_csv_separator_${separator.name}"

fun profileCsvDecimalTag(decimal: ImportDecimal) = "profile_csv_decimal_${decimal.name}"

/** Acciones de la pantalla de asociar una rasante de un CSV a un eje (S049). */
class ProfileCsvImportActions(
    val onAlignment: (String) -> Unit = {},
    val onEncoding: (ImportEncoding) -> Unit = {},
    val onSeparator: (ImportSeparator) -> Unit = {},
    val onDecimal: (ImportDecimal) -> Unit = {},
    val onHasHeader: (Boolean) -> Unit = {},
    val onRole: (Int, ProfileRole) -> Unit = { _, _ -> },
    val onAttach: () -> Unit = {},
)

/**
 * Asociar a un eje la rasante de un CSV (S049/RF-9, RF-10): el eje, la lectura del fichero (codificación, separador y signo decimal como
 * en S028), la asignación de las columnas PK, cota y Kv, una vista previa con los errores por línea y, si la rasante no vale para el eje,
 * el vértice y el motivo.
 */
@Composable
fun ProfileCsvImportScreen(
    themeState: ThemeState,
    onToggleSun: () -> Unit,
    state: ProfileCsvUiState,
    actions: ProfileCsvImportActions,
    onBack: () -> Unit,
) {
    SectionScaffold(
        title = stringResource(R.string.profile_csv_title),
        themeState = themeState,
        onToggleSun = onToggleSun,
        onNavigateBack = onBack,
    ) { padding ->
        when (state.stage) {
            ProfileCsvStage.LOADING -> Column(
                modifier = Modifier.padding(padding).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                CircularProgressIndicator()
                Text(stringResource(R.string.alignment_import_loading))
            }

            ProfileCsvStage.PROBLEM -> Column(
                modifier = Modifier.padding(padding).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(state.fileName, style = MaterialTheme.typography.titleMedium)
                Text(
                    stringResource(problemText(state.problem)),
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.testTag(PROFILE_CSV_PROBLEM_TAG),
                )
                Button(onClick = onBack, modifier = Modifier.heightIn(min = MinTouchTarget)) { Text(stringResource(R.string.import_close)) }
            }

            ProfileCsvStage.CONFIG -> Column(
                modifier = Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                ConfigSections(state, actions)
            }

            ProfileCsvStage.DONE -> Column(
                modifier = Modifier.padding(padding).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    stringResource(R.string.profile_csv_done, state.attachedTo.orEmpty()),
                    modifier = Modifier.testTag(PROFILE_CSV_DONE_TAG).semantics { liveRegion = LiveRegionMode.Polite },
                )
                Button(onClick = onBack, modifier = Modifier.fillMaxWidth().heightIn(min = MinTouchTarget)) {
                    Text(stringResource(R.string.import_close))
                }
            }
        }
    }
}

@StringRes
private fun problemText(problem: ProfileCsvProblem?): Int = when (problem) {
    ProfileCsvProblem.EMPTY -> R.string.import_problem_empty
    ProfileCsvProblem.NOT_TEXT -> R.string.import_problem_not_text
    ProfileCsvProblem.NO_COLUMNS -> R.string.import_problem_no_columns
    ProfileCsvProblem.TOO_BIG -> R.string.import_problem_too_big
    ProfileCsvProblem.TOO_MANY_ROWS -> R.string.import_problem_too_many_rows
    ProfileCsvProblem.NO_ALIGNMENT -> R.string.profile_csv_problem_no_alignment
    ProfileCsvProblem.UNREADABLE, null -> R.string.import_problem_unreadable
}

@Composable
private fun ConfigSections(state: ProfileCsvUiState, actions: ProfileCsvImportActions) {
    Text(state.fileName, style = MaterialTheme.typography.titleMedium)
    AlignmentSection(state, actions)
    ReadingSection(state, actions)
    ColumnsSection(state, actions)
    PreviewSection(state)
    Rejection(state)
    Button(
        onClick = actions.onAttach,
        enabled = state.canAttach,
        modifier = Modifier.fillMaxWidth().heightIn(min = MinTouchTarget).testTag(PROFILE_CSV_ATTACH_TAG),
    ) { Text(stringResource(R.string.profile_csv_button)) }
}

/** Los ejes del proyecto, de los que se elige uno (S049/RF-9). */
@Composable
private fun AlignmentSection(state: ProfileCsvUiState, actions: ProfileCsvImportActions) {
    val locale = uiLocale()
    Text(stringResource(R.string.profile_csv_section_alignment), style = MaterialTheme.typography.titleSmall)
    state.alignments.forEach { axis ->
        val selected = axis.id == state.selectedAlignment
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = MinTouchTarget)
                .selectable(selected = selected, role = Role.RadioButton, onClick = { actions.onAlignment(axis.id) })
                .testTag(profileCsvAlignmentTag(axis.id)),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(selected = selected, onClick = null)
            Column(modifier = Modifier.padding(start = 12.dp)) {
                Text(
                    stringResource(
                        R.string.profile_csv_alignment_item,
                        axis.name,
                        ChainageFormat.format(axis.startChainage, state.projectUnit, locale),
                        ChainageFormat.format(axis.endChainage, state.projectUnit, locale),
                    ),
                )
                if (axis.hasProfile) Text(stringResource(R.string.profile_csv_replaces), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

/** Codificación, separador, signo decimal y cabecera, como en S028 (S049/RF-9). */
@Composable
private fun ReadingSection(state: ProfileCsvUiState, actions: ProfileCsvImportActions) {
    Text(stringResource(R.string.import_section_reading), style = MaterialTheme.typography.titleSmall)
    ChoiceGroup(R.string.import_encoding) {
        ImportEncoding.entries.forEach { encoding ->
            Choice(stringResource(encodingLabel(encoding)), profileCsvEncodingTag(encoding), state.encoding == encoding) {
                actions.onEncoding(encoding)
            }
        }
    }
    ChoiceGroup(R.string.import_separator) {
        ImportSeparator.entries.forEach { separator ->
            Choice(stringResource(separatorLabel(separator)), profileCsvSeparatorTag(separator), state.separator == separator) {
                actions.onSeparator(separator)
            }
        }
    }
    ChoiceGroup(R.string.import_decimal) {
        ImportDecimal.entries.forEach { decimal ->
            Choice(stringResource(decimalLabel(decimal)), profileCsvDecimalTag(decimal), state.decimal == decimal) {
                actions.onDecimal(decimal)
            }
        }
    }
    if (state.decimalConflict) {
        Text(
            stringResource(R.string.import_comma_conflict),
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.testTag(PROFILE_CSV_CONFLICT_TAG),
        )
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = MinTouchTarget)
            .toggleable(value = state.hasHeader, role = Role.Checkbox, onValueChange = actions.onHasHeader)
            .testTag(PROFILE_CSV_HEADER_TAG),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = state.hasHeader, onCheckedChange = null)
        Text(stringResource(R.string.import_has_header), modifier = Modifier.padding(start = 12.dp))
    }
}

@Composable
private fun ColumnsSection(state: ProfileCsvUiState, actions: ProfileCsvImportActions) {
    Text(stringResource(R.string.import_section_columns), style = MaterialTheme.typography.titleSmall)
    Text(stringResource(R.string.profile_csv_unit_note, stringResource(unitName(state))), style = MaterialTheme.typography.bodySmall)
    state.columns.roles.indices.forEach { index ->
        val title = state.firstRow.getOrNull(index).orEmpty()
        Text(stringResource(R.string.import_column_label, index + 1, title), style = MaterialTheme.typography.labelLarge)
        ChoiceGroupRow {
            ProfileRole.entries.forEach { role ->
                Choice(stringResource(roleLabel(role)), profileCsvRoleTag(index, role), state.columns.roleOf(index) == role) {
                    actions.onRole(index, role)
                }
            }
        }
    }
    when (state.columns.problem) {
        ProfileMappingProblem.MISSING_CHAINAGE -> MappingError(R.string.profile_csv_mapping_missing_chainage)
        ProfileMappingProblem.MISSING_ELEVATION -> MappingError(R.string.profile_csv_mapping_missing_elevation)
        ProfileMappingProblem.REPEATED_ROLE, null -> Unit
    }
}

@Composable
private fun MappingError(@StringRes text: Int) {
    Text(stringResource(text), color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag(PROFILE_CSV_MAPPING_TAG))
}

@StringRes
private fun unitName(state: ProfileCsvUiState) = when (state.projectUnit) {
    es.topophone.core.model.project.LinearUnit.METRE -> R.string.import_unit_metre
    es.topophone.core.model.project.LinearUnit.INTERNATIONAL_FOOT -> R.string.import_unit_foot
    es.topophone.core.model.project.LinearUnit.US_SURVEY_FOOT -> R.string.import_unit_usft
}

@StringRes
private fun roleLabel(role: ProfileRole) = when (role) {
    ProfileRole.CHAINAGE -> R.string.profile_csv_role_chainage
    ProfileRole.ELEVATION -> R.string.profile_csv_role_elevation
    ProfileRole.KV -> R.string.profile_csv_role_kv
    ProfileRole.IGNORE -> R.string.profile_csv_role_ignore
}

/** Las primeras filas leídas y los errores por línea (S049/RF-9). */
@Composable
private fun PreviewSection(state: ProfileCsvUiState) {
    val locale = uiLocale()
    Text(stringResource(R.string.profile_csv_section_preview), style = MaterialTheme.typography.titleSmall)
    Column(modifier = Modifier.testTag(PROFILE_CSV_PREVIEW_TAG), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(stringResource(R.string.profile_csv_preview_count, state.rowCount))
        state.preview.forEach { row ->
            val kv =
                row.kv?.let {
                    stringResource(R.string.profile_csv_preview_kv, LengthFormat.metres(it, locale, decimals = 1).removeSuffix(" m"))
                }
                    ?: stringResource(R.string.profile_csv_preview_no_kv)
            Text(
                stringResource(
                    R.string.profile_csv_preview_row,
                    LengthFormat.metres(row.chainage, locale, decimals = 3).removeSuffix(" m"),
                    LengthFormat.metres(row.elevation, locale, decimals = 3).removeSuffix(" m"),
                    kv,
                ),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
    if (state.errors.isNotEmpty()) {
        Column(modifier = Modifier.testTag(PROFILE_CSV_ERRORS_TAG), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            state.errors.take(MAX_ERRORS_SHOWN).forEach { error ->
                Text(
                    stringResource(
                        R.string.profile_csv_error,
                        error.line,
                        stringResource(roleLabel(error.role)),
                        stringResource(reasonLabel(error)),
                    ),
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@StringRes
private fun reasonLabel(error: ProfileCsvError) = when (error.reason) {
    ProfileCsvError.Reason.MISSING -> R.string.profile_csv_error_missing
    ProfileCsvError.Reason.NOT_A_NUMBER -> R.string.profile_csv_error_not_number
}

/** Por qué la rasante no vale para el eje, con el vértice (RF-10), o que no se pudo guardar. */
@Composable
private fun Rejection(state: ProfileCsvUiState) {
    state.rejected?.let { issue ->
        Text(
            profileOmissionText(ProfileOmission.Invalid(issue), state.projectUnit),
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.testTag(PROFILE_CSV_REJECTED_TAG).semantics { liveRegion = LiveRegionMode.Polite },
        )
    }
    if (state.writeError) {
        Text(
            stringResource(R.string.profile_csv_failed_write),
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.testTag(PROFILE_CSV_FAILED_TAG).semantics { liveRegion = LiveRegionMode.Polite },
        )
    }
}

@StringRes
private fun encodingLabel(encoding: ImportEncoding) = when (encoding) {
    ImportEncoding.UTF8 -> R.string.import_encoding_utf8
    ImportEncoding.WINDOWS_1252 -> R.string.import_encoding_windows1252
}

@StringRes
private fun separatorLabel(separator: ImportSeparator) = when (separator) {
    ImportSeparator.COMMA -> R.string.import_separator_comma
    ImportSeparator.SEMICOLON -> R.string.import_separator_semicolon
    ImportSeparator.TAB -> R.string.import_separator_tab
    ImportSeparator.SPACES -> R.string.import_separator_spaces
}

@StringRes
private fun decimalLabel(decimal: ImportDecimal) = when (decimal) {
    ImportDecimal.POINT -> R.string.import_decimal_point
    ImportDecimal.COMMA -> R.string.import_decimal_comma
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChoiceGroup(@StringRes title: Int, content: @Composable () -> Unit) {
    Text(stringResource(title), style = MaterialTheme.typography.labelLarge)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { content() }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChoiceGroupRow(content: @Composable () -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { content() }
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

private const val MAX_ERRORS_SHOWN = 10
