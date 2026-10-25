package es.topophone.app.ui.points

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
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
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import es.topophone.app.R
import es.topophone.app.ui.SectionScaffold
import es.topophone.core.designsystem.component.MinTouchTarget
import es.topophone.core.formats.csv.ExportFileName
import es.topophone.core.model.point.ColumnRole
import es.topophone.core.model.point.DuplicatePolicy
import es.topophone.core.model.point.EntryForm
import es.topophone.core.model.point.HeightKind
import es.topophone.core.model.point.ImportDecimal
import es.topophone.core.model.point.ImportEncoding
import es.topophone.core.model.point.ImportErrorReason
import es.topophone.core.model.point.ImportPreviewRow
import es.topophone.core.model.point.ImportRowStatus
import es.topophone.core.model.point.ImportSeparator
import es.topophone.core.model.point.MappingProblem
import es.topophone.core.model.preferences.ThemeState
import es.topophone.core.model.project.LinearUnit
import java.time.Instant
import java.time.ZoneId

const val IMPORT_PROBLEM_TAG = "import_problem"
const val IMPORT_HEADER_TAG = "import_header"
const val IMPORT_COMMA_CONFLICT_TAG = "import_comma_conflict"
const val IMPORT_LOADING_TAG = "import_loading"
const val IMPORT_COUNTS_TAG = "import_counts"
const val IMPORT_REPORT_SAVE_TAG = "import_report_save"
const val IMPORT_REPORT_SHARE_TAG = "import_report_share"
const val IMPORT_BUTTON_TAG = "import_button"
const val IMPORT_PENZD_TAG = "import_penzd"
const val IMPORT_PNEZD_TAG = "import_pnezd"
const val IMPORT_MAPPING_PROBLEM_TAG = "import_mapping_problem"
const val IMPORT_ZERO_TAG = "import_zero"
const val IMPORT_DUPLICATES_BLOCK_TAG = "import_duplicates_block"

fun importEncodingTag(encoding: ImportEncoding) = "import_encoding_${encoding.name}"

fun importSeparatorTag(separator: ImportSeparator) = "import_separator_${separator.name}"

fun importDecimalTag(decimal: ImportDecimal) = "import_decimal_${decimal.name}"

fun importColumnTag(index: Int) = "import_column_$index"

fun importPreviewRowTag(line: Int) = "import_preview_row_$line"

fun importRoleTag(index: Int, role: ColumnRole) = "import_role_${index}_${role.name}"

fun importFormTag(form: EntryForm) = "import_form_${form.name}"

fun importHeightKindTag(kind: HeightKind) = "import_height_${kind.name}"

fun importUnitTag(unit: LinearUnit) = "import_unit_${unit.name}"

fun importDuplicatesTag(policy: DuplicatePolicy) = "import_duplicates_${policy.name}"

/** Acciones del asistente de importación (S028). */
class PointImportActions(
    val onEncoding: (ImportEncoding) -> Unit = {},
    val onSeparator: (ImportSeparator) -> Unit = {},
    val onDecimal: (ImportDecimal) -> Unit = {},
    val onHasHeader: (Boolean) -> Unit = {},
    val onColumnRole: (Int, ColumnRole) -> Unit = { _, _ -> },
    val onPenzd: () -> Unit = {},
    val onPnezd: () -> Unit = {},
    val onCoordinates: (EntryForm, HeightKind) -> Unit = { _, _ -> },
    val onUnit: (LinearUnit) -> Unit = {},
    val onZeroAsNoHeight: (Boolean) -> Unit = {},
    val onDuplicates: (DuplicatePolicy) -> Unit = {},
    val onSaveReport: (fileName: String) -> Unit = {},
    val onShareReport: (fileName: String) -> Unit = {},
    val onImport: () -> Unit = {},
)

/**
 * El asistente de importación de puntos de un CSV o TXT (S028), en una sola pantalla con secciones: el fichero, la lectura
 * (codificación, separador, signo decimal y cabecera)… Si el fichero no se puede abrir, dice el motivo (RF-2, RF-3).
 */
@Composable
fun PointImportScreen(
    themeState: ThemeState,
    onToggleSun: () -> Unit,
    state: PointImportUiState,
    actions: PointImportActions,
    onBack: () -> Unit,
    reportOutcome: ReportOutcome? = null,
) {
    SectionScaffold(
        title = stringResource(R.string.import_title),
        themeState = themeState,
        onToggleSun = onToggleSun,
        onNavigateBack = onBack,
    ) { padding ->
        when (state.stage) {
            ImportStage.LOADING, ImportStage.IMPORTING -> Column(
                modifier = Modifier.padding(padding).padding(16.dp).testTag(IMPORT_LOADING_TAG),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                CircularProgressIndicator()
                Text(stringResource(R.string.import_loading))
            }
            ImportStage.PROBLEM -> Column(
                modifier = Modifier.padding(padding).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(state.fileName, style = MaterialTheme.typography.titleMedium)
                Text(
                    stringResource(problemText(state.fileProblem)),
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.testTag(IMPORT_PROBLEM_TAG),
                ) // S028/RF-2, RF-3
                Button(onClick = onBack, modifier = Modifier.heightIn(min = MinTouchTarget)) { Text(stringResource(R.string.import_close)) }
            }
            ImportStage.WIZARD, ImportStage.RESULT -> Column(
                modifier = Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(stringResource(R.string.import_file_name, state.fileName), style = MaterialTheme.typography.titleMedium)
                ReadingSection(state, actions)
                ColumnsSection(state, actions)
                CoordinatesSection(state, actions)
                OptionsSection(state, actions)
                PreviewSection(state, actions, reportOutcome)
                ImportButton(state, actions)
            }
        }
    }
}

@StringRes
private fun problemText(problem: ImportFileProblem?): Int = when (problem) {
    ImportFileProblem.EMPTY -> R.string.import_problem_empty
    ImportFileProblem.NOT_TEXT -> R.string.import_problem_not_text
    ImportFileProblem.NO_COLUMNS -> R.string.import_problem_no_columns
    ImportFileProblem.TOO_BIG -> R.string.import_problem_too_big
    ImportFileProblem.TOO_MANY_ROWS -> R.string.import_problem_too_many_rows
    ImportFileProblem.UNREADABLE, null -> R.string.import_problem_unreadable
}

/** Codificación, separador, signo decimal y cabecera (S028/RF-4 a RF-9). */
@Composable
private fun ReadingSection(state: PointImportUiState, actions: PointImportActions) {
    val options = state.options
    Text(stringResource(R.string.import_section_reading), style = MaterialTheme.typography.titleSmall)
    ChoiceGroup(R.string.import_encoding) {
        ImportEncoding.entries.forEach { encoding ->
            Choice(stringResource(encodingLabel(encoding)), importEncodingTag(encoding), options.encoding == encoding) {
                actions.onEncoding(encoding)
            }
        }
    } // RF-4
    ChoiceGroup(R.string.import_separator) {
        ImportSeparator.entries.forEach { separator ->
            Choice(stringResource(separatorLabel(separator)), importSeparatorTag(separator), options.separator == separator) {
                actions.onSeparator(separator)
            }
        }
    } // RF-5
    ChoiceGroup(R.string.import_decimal) {
        ImportDecimal.entries.forEach { decimal ->
            Choice(stringResource(decimalLabel(decimal)), importDecimalTag(decimal), options.decimal == decimal) {
                actions.onDecimal(decimal)
            }
        }
    } // RF-7
    if (options.decimalConflict) {
        Text(
            stringResource(R.string.import_comma_conflict),
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.testTag(IMPORT_COMMA_CONFLICT_TAG),
        ) // RF-8
    }
    CheckRow(stringResource(R.string.import_has_header), options.hasHeader, IMPORT_HEADER_TAG) { actions.onHasHeader(it) } // RF-9
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

@Composable
private fun Choice(label: String, tag: String, selected: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        enabled = enabled,
        onClick = onClick,
        label = { Text(label) },
        modifier = Modifier.heightIn(min = MinTouchTarget).testTag(tag),
    )
}

@Composable
private fun CheckRow(label: String, checked: Boolean, tag: String, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = MinTouchTarget)
            .toggleable(value = checked, role = Role.Checkbox, onValueChange = onChange)
            .testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = null)
        Text(label, modifier = Modifier.padding(start = 12.dp))
    }
}

/** El papel de cada columna del fichero, con PENZD y PNEZD a un toque (S028/RF-10, RF-11). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ColumnsSection(state: PointImportUiState, actions: PointImportActions) {
    val geographic = state.options.coordinates.form == EntryForm.GEOGRAPHIC
    Text(stringResource(R.string.import_section_columns), style = MaterialTheme.typography.titleSmall)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(
            onClick = actions.onPenzd,
            modifier = Modifier.heightIn(min = MinTouchTarget).testTag(IMPORT_PENZD_TAG),
        ) { Text(stringResource(R.string.import_penzd)) }
        OutlinedButton(
            onClick = actions.onPnezd,
            modifier = Modifier.heightIn(min = MinTouchTarget).testTag(IMPORT_PNEZD_TAG),
        ) { Text(stringResource(R.string.import_pnezd)) }
    }
    repeat(state.columnCount) { index ->
        ColumnRoleRow(
            index,
            state.headerCells.getOrElse(index) {
                ""
            },
            state.options.mapping.roleOf(index),
            geographic,
            actions.onColumnRole,
        )
    }
    state.options.mapping.problem?.let { problem ->
        val text = when (problem) {
            MappingProblem.MISSING_NAME -> stringResource(
                R.string.import_mapping_missing,
                stringResource(roleLabel(ColumnRole.NAME, geographic)),
            )
            MappingProblem.MISSING_FIRST -> stringResource(
                R.string.import_mapping_missing,
                stringResource(roleLabel(ColumnRole.FIRST, geographic)),
            )
            MappingProblem.MISSING_SECOND -> stringResource(
                R.string.import_mapping_missing,
                stringResource(roleLabel(ColumnRole.SECOND, geographic)),
            )
            MappingProblem.REPEATED_ROLE -> stringResource(R.string.import_mapping_repeated)
        }
        Text(text, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag(IMPORT_MAPPING_PROBLEM_TAG)) // RF-11
    }
}

@Composable
private fun ColumnRoleRow(index: Int, title: String, role: ColumnRole, geographic: Boolean, onRole: (Int, ColumnRole) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Column {
        Text(stringResource(R.string.import_column_label, index + 1, title), style = MaterialTheme.typography.bodyMedium)
        Box {
            OutlinedButton(
                onClick = { expanded = true },
                modifier = Modifier.heightIn(min = MinTouchTarget).testTag(importColumnTag(index)),
            ) { Text(stringResource(roleLabel(role, geographic))) }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                ColumnRole.entries.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(stringResource(roleLabel(option, geographic))) },
                        onClick = {
                            expanded = false
                            onRole(index, option)
                        },
                        modifier = Modifier.heightIn(min = MinTouchTarget).testTag(importRoleTag(index, option)),
                    )
                }
            }
        }
    }
}

@StringRes
private fun roleLabel(role: ColumnRole, geographic: Boolean): Int = when (role) {
    ColumnRole.NAME -> R.string.import_role_name
    ColumnRole.FIRST -> if (geographic) R.string.import_role_latitude else R.string.import_role_east
    ColumnRole.SECOND -> if (geographic) R.string.import_role_longitude else R.string.import_role_north
    ColumnRole.HEIGHT -> R.string.import_role_height
    ColumnRole.DESCRIPTION -> R.string.import_role_description
    ColumnRole.IGNORE -> R.string.import_role_ignore
}

/** El SC de las coordenadas del fichero, el tipo de altura y la unidad (S028/RF-12 a RF-14). */
@Composable
private fun CoordinatesSection(state: PointImportUiState, actions: PointImportActions) {
    val coordinates = state.options.coordinates
    Text(stringResource(R.string.import_section_coordinates), style = MaterialTheme.typography.titleSmall)
    ChoiceGroup(R.string.import_section_coordinates) {
        EntryForm.entries.forEach { form ->
            val label = if (form == EntryForm.PROJECT_CRS) R.string.import_form_project else R.string.import_form_geographic
            Choice(stringResource(label), importFormTag(form), coordinates.form == form) {
                actions.onCoordinates(form, coordinates.heightKind)
            }
        }
    } // RF-12
    if (coordinates.form == EntryForm.GEOGRAPHIC) {
        ChoiceGroup(R.string.import_height_kind) {
            Choice(
                stringResource(R.string.import_height_project),
                importHeightKindTag(HeightKind.PROJECT),
                coordinates.heightKind == HeightKind.PROJECT,
            ) {
                actions.onCoordinates(EntryForm.GEOGRAPHIC, HeightKind.PROJECT)
            }
            Choice(
                stringResource(R.string.import_height_ellipsoidal),
                importHeightKindTag(HeightKind.ELLIPSOIDAL),
                coordinates.heightKind == HeightKind.ELLIPSOIDAL,
                enabled = !state.ed50,
            ) { actions.onCoordinates(EntryForm.GEOGRAPHIC, HeightKind.ELLIPSOIDAL) }
        } // RF-13
        if (state.ed50) Text(stringResource(R.string.import_height_ed50), style = MaterialTheme.typography.bodySmall)
    }
    ChoiceGroup(R.string.import_unit) {
        LinearUnit.entries.forEach { unit ->
            Choice(stringResource(unitLabel(unit)), importUnitTag(unit), state.options.unit == unit) { actions.onUnit(unit) }
        }
    } // RF-14
}

@StringRes
private fun unitLabel(unit: LinearUnit) = when (unit) {
    LinearUnit.METRE -> R.string.import_unit_metre
    LinearUnit.INTERNATIONAL_FOOT -> R.string.import_unit_foot
    LinearUnit.US_SURVEY_FOOT -> R.string.import_unit_usft
}

/** Tratar 0 como sin altura y qué hacer con los nombres repetidos (S028/RF-16, RF-17). */
@Composable
private fun OptionsSection(state: PointImportUiState, actions: PointImportActions) {
    Text(stringResource(R.string.import_section_options), style = MaterialTheme.typography.titleSmall)
    CheckRow(stringResource(R.string.import_zero_as_no_height), state.options.zeroAsNoHeight, IMPORT_ZERO_TAG) {
        actions.onZeroAsNoHeight(it)
    }
    ChoiceGroup(R.string.import_duplicates) {
        DuplicatePolicy.entries.forEach { policy ->
            Choice(stringResource(duplicatesLabel(policy)), importDuplicatesTag(policy), state.options.duplicates == policy) {
                actions.onDuplicates(policy)
            }
        }
    }
    if (state.blockedByDuplicates) {
        Text(
            stringResource(R.string.import_duplicates_blocked),
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.testTag(IMPORT_DUPLICATES_BLOCK_TAG),
        ) // RF-17
    }
}

@StringRes
private fun duplicatesLabel(policy: DuplicatePolicy) = when (policy) {
    DuplicatePolicy.SKIP -> R.string.import_duplicates_skip
    DuplicatePolicy.RENAME -> R.string.import_duplicates_rename
    DuplicatePolicy.CANCEL -> R.string.import_duplicates_cancel
}

/** Los recuentos, las primeras filas con su estado y la lista de errores con el informe (S028/RF-21, RF-22, RF-24). */
@Composable
private fun PreviewSection(state: PointImportUiState, actions: PointImportActions, reportOutcome: ReportOutcome?) {
    val counts = state.counts
    Text(stringResource(R.string.import_section_preview), style = MaterialTheme.typography.titleSmall)
    if (state.analyzing) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(modifier = Modifier.size(20.dp))
            Text(stringResource(R.string.import_analyzing))
        }
    }
    val countsDescription = stringResource(R.string.import_counts_description, counts.valid, counts.repeated, counts.errors)
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier
            .testTag(IMPORT_COUNTS_TAG)
            .semantics(mergeDescendants = true) {
                contentDescription = countsDescription
                liveRegion = LiveRegionMode.Polite // TalkBack lo lee al cambiar, sin anunciar cada refresco (RNF-T8)
            },
    ) {
        StatusMark(R.drawable.ic_check, stringResource(R.string.import_count_valid, counts.valid))
        StatusMark(R.drawable.ic_repeat, stringResource(R.string.import_count_repeated, counts.repeated))
        StatusMark(R.drawable.ic_warning, stringResource(R.string.import_count_errors, counts.errors))
    }
    state.preview.forEach { row -> PreviewRow(row) }
    ErrorsSection(state, actions, reportOutcome)
}

@Composable
private fun PreviewRow(row: ImportPreviewRow) {
    val name = row.name.ifEmpty { stringResource(R.string.import_row_no_name) }
    val reason = row.reason?.let { stringResource(reasonText(it)) }.orEmpty()
    val state = when (row.status) {
        ImportRowStatus.VALID -> stringResource(R.string.import_state_valid)
        ImportRowStatus.REPEATED_SKIPPED -> stringResource(R.string.import_state_skipped)
        ImportRowStatus.REPEATED_RENAMED -> stringResource(R.string.import_state_renamed, row.renamedTo.orEmpty())
        ImportRowStatus.ERROR -> stringResource(R.string.import_state_error, reason)
    }
    val description = stringResource(R.string.import_row_description, row.line, name, state)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(importPreviewRowTag(row.line))
            .semantics(mergeDescendants = true) { contentDescription = description },
    ) {
        Text(stringResource(R.string.import_row_title, row.line, row.name), style = MaterialTheme.typography.bodyMedium)
        Text(
            stringResource(R.string.import_row_values, row.first, row.second, row.height, row.description),
            style = MaterialTheme.typography.bodySmall,
        )
        when (row.status) {
            ImportRowStatus.VALID -> StatusMark(R.drawable.ic_check, stringResource(R.string.import_status_valid))
            ImportRowStatus.REPEATED_SKIPPED -> StatusMark(R.drawable.ic_repeat, stringResource(R.string.import_status_skipped))
            ImportRowStatus.REPEATED_RENAMED ->
                StatusMark(R.drawable.ic_repeat, stringResource(R.string.import_status_renamed, row.renamedTo.orEmpty()))
            ImportRowStatus.ERROR -> StatusMark(R.drawable.ic_warning, stringResource(R.string.import_status_error, reason), error = true)
        }
    }
}

@Composable
private fun ErrorsSection(state: PointImportUiState, actions: PointImportActions, reportOutcome: ReportOutcome?) {
    Text(stringResource(R.string.import_section_errors), style = MaterialTheme.typography.titleSmall)
    if (state.counts.errors == 0) {
        Text(stringResource(R.string.import_errors_none))
    } else {
        state.errors.forEach { error ->
            Text(stringResource(R.string.import_errors_line, error.line, stringResource(reasonText(error.reason))))
        }
        val more = state.counts.errors - state.errors.size
        if (more > 0) Text(stringResource(R.string.import_errors_more, more), style = MaterialTheme.typography.bodySmall)
        val opened = remember { Instant.now() }
        val label = stringResource(R.string.import_report_file_label)
        val fileName = ExportFileName.of(state.projectName, label, opened, ZoneId.systemDefault(), "txt")
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(
                onClick = { actions.onSaveReport(fileName) },
                modifier = Modifier.weight(1f).heightIn(min = MinTouchTarget).testTag(IMPORT_REPORT_SAVE_TAG),
            ) { Text(stringResource(R.string.import_report_save)) }
            OutlinedButton(
                onClick = { actions.onShareReport(fileName) },
                modifier = Modifier.weight(1f).heightIn(min = MinTouchTarget).testTag(IMPORT_REPORT_SHARE_TAG),
            ) { Text(stringResource(R.string.import_report_share)) }
        } // RF-24
    }
    when (reportOutcome) {
        ReportOutcome.SAVED -> Text(stringResource(R.string.import_report_saved))
        ReportOutcome.FAILED -> Text(stringResource(R.string.import_report_failed), color = MaterialTheme.colorScheme.error)
        null -> Unit
    }
}

/** El botón Importar, activo solo si hay algo que importar y nada lo impide (S028/RF-25, RF-26). */
@Composable
private fun ImportButton(state: PointImportUiState, actions: PointImportActions) {
    if (state.writeFailed) {
        Text(stringResource(R.string.import_write_failed), color = MaterialTheme.colorScheme.error) // RF-26
    }
    Button(
        onClick = actions.onImport,
        enabled = state.canImport,
        modifier = Modifier.fillMaxWidth().heightIn(min = MinTouchTarget).testTag(IMPORT_BUTTON_TAG),
    ) { Text(stringResource(R.string.import_action)) }
}

@Composable
private fun StatusMark(@DrawableRes icon: Int, text: String, error: Boolean = false) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(
            painterResource(icon),
            contentDescription = null, // el texto de al lado lo dice
            modifier = Modifier.size(18.dp),
            tint = if (error) MaterialTheme.colorScheme.error else LocalContentColor.current,
        )
        Text(text, style = MaterialTheme.typography.bodySmall, color = if (error) MaterialTheme.colorScheme.error else Color.Unspecified)
    }
}

@StringRes
internal fun reasonText(reason: ImportErrorReason): Int = when (reason) {
    ImportErrorReason.MISSING_NAME -> R.string.import_reason_missing_name
    ImportErrorReason.NOT_A_NUMBER -> R.string.import_reason_not_a_number
    ImportErrorReason.MISSING_COLUMNS -> R.string.import_reason_missing_columns
    ImportErrorReason.OUTSIDE_CRS_AREA -> R.string.import_reason_outside_crs_area
    ImportErrorReason.OUTSIDE_ED50_GRID -> R.string.import_reason_outside_ed50_grid
    ImportErrorReason.OUTSIDE_GEOID -> R.string.import_reason_outside_geoid
}
