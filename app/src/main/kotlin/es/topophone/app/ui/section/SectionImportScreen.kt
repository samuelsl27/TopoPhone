package es.topophone.app.ui.section

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import es.topophone.core.formats.section.SectionCsvError
import es.topophone.core.formats.section.SectionMappingProblem
import es.topophone.core.formats.section.SectionRole
import es.topophone.core.model.alignment.ChainageFormat
import es.topophone.core.model.alignment.section.HeightKind
import es.topophone.core.model.alignment.section.SectionKind
import es.topophone.core.model.format.LengthFormat
import es.topophone.core.model.point.ImportDecimal
import es.topophone.core.model.point.ImportEncoding
import es.topophone.core.model.point.ImportSeparator
import es.topophone.core.model.preferences.ThemeState
import es.topophone.core.model.project.LinearUnit

const val SECTION_IMPORT_PROBLEM_TAG = "section_import_problem"
const val SECTION_IMPORT_HEADER_TAG = "section_import_header"
const val SECTION_IMPORT_PREVIEW_TAG = "section_import_preview"
const val SECTION_IMPORT_ERRORS_TAG = "section_import_errors"
const val SECTION_IMPORT_MAPPING_TAG = "section_import_mapping"
const val SECTION_IMPORT_CONFLICT_TAG = "section_import_conflict"
const val SECTION_IMPORT_BUTTON_TAG = "section_import_button"
const val SECTION_IMPORT_DONE_TAG = "section_import_done"
const val SECTION_IMPORT_REJECTED_TAG = "section_import_rejected"
const val SECTION_IMPORT_FAILED_TAG = "section_import_failed"
const val SECTION_IMPORT_LANDXML_NOTE_TAG = "section_import_landxml_note"

fun sectionImportUnitTag(unit: LinearUnit) = "section_import_unit_${unit.name}"

fun sectionImportHeightTag(kind: HeightKind) = "section_import_height_${kind.name}"

fun sectionImportRoleTag(index: Int, role: SectionRole) = "section_import_role_${index}_${role.name}"

fun sectionImportEncodingTag(encoding: ImportEncoding) = "section_import_encoding_${encoding.name}"

fun sectionImportSeparatorTag(separator: ImportSeparator) = "section_import_separator_${separator.name}"

fun sectionImportDecimalTag(decimal: ImportDecimal) = "section_import_decimal_${decimal.name}"

fun sectionImportItemTag(index: Int) = "section_import_item_$index"

fun sectionImportSelectKindTag(kind: SectionKind) = "section_import_select_${kind.name}"

/** Acciones de la pantalla de importar secciones (S050). */
class SectionImportActions(
    val onUnit: (LinearUnit) -> Unit = {},
    val onHeightKind: (HeightKind) -> Unit = {},
    val onEncoding: (ImportEncoding) -> Unit = {},
    val onSeparator: (ImportSeparator) -> Unit = {},
    val onDecimal: (ImportDecimal) -> Unit = {},
    val onHasHeader: (Boolean) -> Unit = {},
    val onRole: (Int, SectionRole) -> Unit = { _, _ -> },
    val onToggle: (Int) -> Unit = {},
    val onSelectKind: (SectionKind, Boolean) -> Unit = { _, _ -> },
    val onImport: () -> Unit = {},
)

/**
 * Importar las secciones de un CSV o de un LandXML a un eje (S050/RF-4, RF-5, RF-7): la lectura del CSV como en S028 con la asignación de
 * las columnas, o la lista de secciones del LandXML por PK con diseño y terreno; la unidad; y el resumen con lo guardado y, de lo que
 * no, el PK, el tipo y el motivo.
 */
@Composable
fun SectionImportScreen(
    themeState: ThemeState,
    onToggleSun: () -> Unit,
    state: SectionImportUiState,
    actions: SectionImportActions,
    onBack: () -> Unit,
) {
    SectionScaffold(
        title = stringResource(R.string.section_import_title),
        themeState = themeState,
        onToggleSun = onToggleSun,
        onNavigateBack = onBack,
    ) { padding ->
        when (state.stage) {
            SectionImportStage.LOADING -> Column(
                modifier = Modifier.padding(padding).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                CircularProgressIndicator()
                Text(stringResource(R.string.alignment_import_loading))
            }

            SectionImportStage.PROBLEM -> Column(
                modifier = Modifier.padding(padding).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(state.fileName, style = MaterialTheme.typography.titleMedium)
                Text(
                    problemText(state),
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.testTag(SECTION_IMPORT_PROBLEM_TAG),
                )
                Button(onClick = onBack, modifier = Modifier.heightIn(min = MinTouchTarget)) { Text(stringResource(R.string.import_close)) }
            }

            SectionImportStage.CONFIG -> Column(
                modifier = Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Config(state, actions)
            }

            SectionImportStage.DONE -> Column(
                modifier = Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Done(state)
                Button(onClick = onBack, modifier = Modifier.fillMaxWidth().heightIn(min = MinTouchTarget)) {
                    Text(stringResource(R.string.import_close))
                }
            }
        }
    }
}

@Composable
private fun problemText(state: SectionImportUiState): String = when (state.problem) {
    SectionImportProblem.EMPTY -> stringResource(R.string.import_problem_empty)
    SectionImportProblem.NOT_TEXT -> stringResource(R.string.import_problem_not_text)
    SectionImportProblem.NO_COLUMNS -> stringResource(R.string.import_problem_no_columns)
    SectionImportProblem.TOO_BIG -> stringResource(R.string.import_problem_too_big)
    SectionImportProblem.TOO_MANY_ROWS -> stringResource(R.string.import_problem_too_many_rows)
    SectionImportProblem.NOT_XML -> stringResource(R.string.section_import_problem_not_xml)
    SectionImportProblem.NOT_LANDXML -> stringResource(R.string.section_import_problem_not_landxml)
    SectionImportProblem.UNSUPPORTED_VERSION -> stringResource(R.string.section_import_problem_version, state.version.orEmpty())
    SectionImportProblem.NO_SECTIONS -> stringResource(R.string.section_import_problem_no_sections)
    SectionImportProblem.NO_ALIGNMENT -> stringResource(R.string.section_import_problem_no_alignment)
    SectionImportProblem.UNREADABLE, null -> stringResource(R.string.import_problem_unreadable)
}

@Composable
private fun Config(state: SectionImportUiState, actions: SectionImportActions) {
    Text(state.fileName, style = MaterialTheme.typography.titleMedium)
    state.alignment?.let { Text(it.name) }
    UnitSection(state, actions)
    if (state.kind == SectionImportKind.CSV) {
        Reading(state, actions)
        Columns(state, actions)
        Preview(state)
    } else {
        Text(
            stringResource(R.string.section_import_landxml_note),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.testTag(SECTION_IMPORT_LANDXML_NOTE_TAG),
        )
        Sections(state, actions)
    }
    if (state.writeError) {
        Text(
            stringResource(R.string.section_import_failed_write),
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.testTag(SECTION_IMPORT_FAILED_TAG).semantics { liveRegion = LiveRegionMode.Polite },
        )
    }
    Button(
        onClick = actions.onImport,
        enabled = state.canImport,
        modifier = Modifier.fillMaxWidth().heightIn(min = MinTouchTarget).testTag(SECTION_IMPORT_BUTTON_TAG),
    ) { Text(stringResource(R.string.section_import_button)) }
}

@Composable
private fun UnitSection(state: SectionImportUiState, actions: SectionImportActions) {
    Text(stringResource(R.string.section_import_unit), style = MaterialTheme.typography.titleSmall)
    ChoiceRow {
        LinearUnit.entries.forEach { unit ->
            Choice(stringResource(unitLabel(unit)), sectionImportUnitTag(unit), state.unit == unit) { actions.onUnit(unit) }
        }
    }
    if (state.kind == SectionImportKind.CSV) {
        Text(stringResource(R.string.section_import_height), style = MaterialTheme.typography.titleSmall)
        ChoiceRow {
            HeightKind.entries.forEach { kind ->
                Choice(
                    stringResource(if (kind == HeightKind.ABSOLUTE) R.string.section_height_absolute else R.string.section_height_relative),
                    sectionImportHeightTag(kind),
                    state.heightKind == kind,
                ) { actions.onHeightKind(kind) }
            }
        }
    }
}

@StringRes
private fun unitLabel(unit: LinearUnit) = when (unit) {
    LinearUnit.METRE -> R.string.import_unit_metre
    LinearUnit.INTERNATIONAL_FOOT -> R.string.import_unit_foot
    LinearUnit.US_SURVEY_FOOT -> R.string.import_unit_usft
}

@Composable
private fun Reading(state: SectionImportUiState, actions: SectionImportActions) {
    Text(stringResource(R.string.import_section_reading), style = MaterialTheme.typography.titleSmall)
    Text(stringResource(R.string.import_encoding), style = MaterialTheme.typography.labelLarge)
    ChoiceRow {
        ImportEncoding.entries.forEach { e ->
            Choice(
                stringResource(if (e == ImportEncoding.UTF8) R.string.import_encoding_utf8 else R.string.import_encoding_windows1252),
                sectionImportEncodingTag(e),
                state.encoding == e,
            ) { actions.onEncoding(e) }
        }
    }
    Text(stringResource(R.string.import_separator), style = MaterialTheme.typography.labelLarge)
    ChoiceRow {
        ImportSeparator.entries.forEach { s ->
            Choice(
                stringResource(
                    when (s) {
                        ImportSeparator.COMMA -> R.string.import_separator_comma
                        ImportSeparator.SEMICOLON -> R.string.import_separator_semicolon
                        ImportSeparator.TAB -> R.string.import_separator_tab
                        ImportSeparator.SPACES -> R.string.import_separator_spaces
                    },
                ),
                sectionImportSeparatorTag(s),
                state.separator == s,
            ) { actions.onSeparator(s) }
        }
    }
    Text(stringResource(R.string.import_decimal), style = MaterialTheme.typography.labelLarge)
    ChoiceRow {
        ImportDecimal.entries.forEach { d ->
            Choice(
                stringResource(if (d == ImportDecimal.POINT) R.string.import_decimal_point else R.string.import_decimal_comma),
                sectionImportDecimalTag(d),
                state.decimal == d,
            ) { actions.onDecimal(d) }
        }
    }
    if (state.decimalConflict) {
        Text(
            stringResource(R.string.import_comma_conflict),
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.testTag(SECTION_IMPORT_CONFLICT_TAG),
        )
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = MinTouchTarget)
            .toggleable(value = state.hasHeader, role = Role.Checkbox, onValueChange = actions.onHasHeader)
            .testTag(SECTION_IMPORT_HEADER_TAG),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = state.hasHeader, onCheckedChange = null)
        Text(stringResource(R.string.import_has_header), modifier = Modifier.padding(start = 12.dp))
    }
}

@Composable
private fun Columns(state: SectionImportUiState, actions: SectionImportActions) {
    Text(stringResource(R.string.import_section_columns), style = MaterialTheme.typography.titleSmall)
    state.columns.roles.indices.forEach { index ->
        Text(
            stringResource(R.string.import_column_label, index + 1, state.firstRow.getOrNull(index).orEmpty()),
            style = MaterialTheme.typography.labelLarge,
        )
        ChoiceRow {
            SectionRole.entries.forEach { role ->
                Choice(stringResource(roleLabel(role)), sectionImportRoleTag(index, role), state.columns.roleOf(index) == role) {
                    actions.onRole(index, role)
                }
            }
        }
    }
    val missing = when (state.columns.problem) {
        SectionMappingProblem.MISSING_CHAINAGE -> R.string.section_import_mapping_chainage
        SectionMappingProblem.MISSING_OFFSET -> R.string.section_import_mapping_offset
        SectionMappingProblem.MISSING_HEIGHT -> R.string.section_import_mapping_height
        SectionMappingProblem.REPEATED_ROLE, null -> null
    }
    if (missing != null) {
        Text(stringResource(missing), color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag(SECTION_IMPORT_MAPPING_TAG))
    }
}

@StringRes
private fun roleLabel(role: SectionRole) = when (role) {
    SectionRole.CHAINAGE -> R.string.section_import_role_chainage
    SectionRole.CODE -> R.string.section_import_role_code
    SectionRole.OFFSET -> R.string.section_import_role_offset
    SectionRole.HEIGHT -> R.string.section_import_role_height
    SectionRole.KIND -> R.string.section_import_role_kind
    SectionRole.IGNORE -> R.string.section_import_role_ignore
}

@Composable
private fun Preview(state: SectionImportUiState) {
    val locale = uiLocale()
    Column(modifier = Modifier.testTag(SECTION_IMPORT_PREVIEW_TAG), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(stringResource(R.string.profile_csv_preview_count, state.rowCount))
        state.preview.forEach { row ->
            Text(
                stringResource(
                    R.string.section_import_preview_row,
                    LengthFormat.metres(row.chainage, locale, decimals = 3).removeSuffix(" m"),
                    row.code ?: "—",
                    LengthFormat.metres(row.offset, locale, decimals = 3).removeSuffix(" m"),
                    LengthFormat.metres(row.height, locale, decimals = 3).removeSuffix(" m"),
                ),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
    if (state.errors.isNotEmpty()) {
        Column(modifier = Modifier.testTag(SECTION_IMPORT_ERRORS_TAG), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            state.errors.take(MAX_ERRORS_SHOWN).forEach { error ->
                Text(
                    stringResource(
                        R.string.section_import_error_row,
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
private fun reasonLabel(error: SectionCsvError<SectionRole>) = when (error.reason) {
    SectionCsvError.Reason.MISSING -> R.string.section_import_error_missing
    SectionCsvError.Reason.NOT_A_NUMBER -> R.string.section_import_error_number
    SectionCsvError.Reason.UNKNOWN_KIND -> R.string.section_import_error_kind
}

@Composable
private fun Sections(state: SectionImportUiState, actions: SectionImportActions) {
    val locale = uiLocale()
    Text(stringResource(R.string.section_import_sections), style = MaterialTheme.typography.titleSmall)
    ChoiceRow {
        SectionKind.entries.forEach { kind ->
            val all = state.sections.filter { it.kind == kind }
            Choice(
                stringResource(
                    if (kind ==
                        SectionKind.DESIGN
                    ) {
                        R.string.section_import_select_design
                    } else {
                        R.string.section_import_select_ground
                    },
                ),
                sectionImportSelectKindTag(kind),
                all.isNotEmpty() && all.all { it.index in state.selected },
            ) { actions.onSelectKind(kind, !(all.isNotEmpty() && all.all { it.index in state.selected })) }
        }
    }
    state.sections.forEach { info ->
        val checked = info.index in state.selected
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = MinTouchTarget)
                .toggleable(value = checked, role = Role.Checkbox, onValueChange = { actions.onToggle(info.index) })
                .testTag(sectionImportItemTag(info.index)),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(checked = checked, onCheckedChange = null)
            Text(
                stringResource(
                    R.string.section_import_surface_row,
                    ChainageFormat.format(state.unit.toMetres(info.station), state.projectUnit, locale),
                    stringResource(if (info.kind == SectionKind.DESIGN) R.string.section_kind_design else R.string.section_kind_ground),
                    info.pointCount,
                ),
                modifier = Modifier.padding(start = 12.dp),
            )
        }
    }
}

@Composable
private fun Done(state: SectionImportUiState) {
    val locale = uiLocale()
    Text(
        stringResource(R.string.section_import_done, state.savedCount),
        modifier = Modifier.testTag(SECTION_IMPORT_DONE_TAG).semantics { liveRegion = LiveRegionMode.Polite },
    )
    if (state.rejected.isNotEmpty()) {
        Column(modifier = Modifier.testTag(SECTION_IMPORT_REJECTED_TAG), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(stringResource(R.string.section_import_rejected_title), style = MaterialTheme.typography.titleSmall)
            state.rejected.forEach { r ->
                Text(
                    stringResource(
                        R.string.section_import_rejected_row,
                        ChainageFormat.format(r.chainageM, state.projectUnit, locale),
                        stringResource(if (r.kind == SectionKind.DESIGN) R.string.section_kind_design else R.string.section_kind_ground),
                        sectionIssueText(r.issue, state.projectUnit),
                    ),
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

private const val MAX_ERRORS_SHOWN = 10
