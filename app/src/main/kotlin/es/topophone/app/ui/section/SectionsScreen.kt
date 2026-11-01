package es.topophone.app.ui.section

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
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import es.topophone.app.ui.projects.unitSymbol
import es.topophone.app.ui.receiver.uiLocale
import es.topophone.core.designsystem.component.MinTouchTarget
import es.topophone.core.formats.section.SuperelevationRole
import es.topophone.core.model.alignment.ChainageFormat
import es.topophone.core.model.alignment.section.HeightKind
import es.topophone.core.model.alignment.section.SectionKind
import es.topophone.core.model.preferences.ThemeState

const val SECTIONS_LIST_TAG = "sections_list"
const val SECTIONS_TEMPLATES_EMPTY_TAG = "sections_templates_empty"
const val SECTIONS_PROFILES_EMPTY_TAG = "sections_profiles_empty"
const val SECTIONS_SUPER_EMPTY_TAG = "sections_super_empty"
const val SECTIONS_ADD_TEMPLATE_TAG = "sections_add_template"
const val SECTIONS_IMPORT_CSV_TAG = "sections_import_csv"
const val SECTIONS_IMPORT_LANDXML_TAG = "sections_import_landxml"
const val SECTIONS_EDIT_SUPER_TAG = "sections_edit_super"
const val SECTIONS_SUPER_CSV_TAG = "sections_super_csv"
const val SECTIONS_CALCULATE_TAG = "sections_calculate"
const val SECTIONS_PROBLEM_TAG = "sections_problem"
const val SECTIONS_TEMPLATE_EDITOR_TAG = "sections_template_editor"
const val SECTIONS_TEMPLATE_FROM_TAG = "sections_template_from"
const val SECTIONS_TEMPLATE_TO_TAG = "sections_template_to"
const val SECTIONS_TEMPLATE_GRADE_TAG = "sections_template_grade"
const val SECTIONS_TEMPLATE_SAVE_TAG = "sections_template_save"
const val SECTIONS_TEMPLATE_CANCEL_TAG = "sections_template_cancel"
const val SECTIONS_SUPER_EDITOR_TAG = "sections_super_editor"
const val SECTIONS_SUPER_ADD_TAG = "sections_super_add"
const val SECTIONS_SUPER_SAVE_TAG = "sections_super_save"
const val SECTIONS_SUPER_CANCEL_TAG = "sections_super_cancel"
const val SECTIONS_CSV_PANEL_TAG = "sections_csv_panel"
const val SECTIONS_CSV_APPLY_TAG = "sections_csv_apply"
const val SECTIONS_CSV_UNREADABLE_TAG = "sections_csv_unreadable"

fun sectionsTemplateDeleteTag(id: String) = "sections_template_delete_$id"

fun sectionsProfileDeleteTag(id: String) = "sections_profile_delete_$id"

fun sectionsSegmentTag(left: Boolean, index: Int, field: String) = "sections_segment_${if (left) "left" else "right"}_${index}_$field"

fun sectionsSegmentAddTag(left: Boolean) = "sections_segment_add_${if (left) "left" else "right"}"

fun sectionsSegmentRemoveTag(left: Boolean, index: Int) = "sections_segment_remove_${if (left) "left" else "right"}_$index"

fun sectionsSuperTag(index: Int, field: String) = "sections_super_${index}_$field"

fun sectionsSuperRemoveTag(index: Int) = "sections_super_remove_$index"

fun sectionsCsvRoleTag(index: Int, role: SuperelevationRole) = "sections_csv_role_${index}_${role.name}"

/** Acciones de la pantalla «Secciones» de un eje (S050). Los ficheros los elige la ruta con el selector de documentos (RNF-T5). */
class SectionsActions(
    val onImportCsv: () -> Unit = {},
    val onImportLandXml: () -> Unit = {},
    val onSuperelevationCsv: () -> Unit = {},
    val onCalculate: () -> Unit = {},
    val onStartTemplate: () -> Unit = {},
    val onCancelTemplate: () -> Unit = {},
    val onTemplate: ((TemplateDraft) -> TemplateDraft) -> Unit = {},
    val onSegment: (left: Boolean, index: Int, (SegmentRow) -> SegmentRow) -> Unit = { _, _, _ -> },
    val onAddSegment: (left: Boolean) -> Unit = {},
    val onRemoveSegment: (left: Boolean, index: Int) -> Unit = { _, _ -> },
    val onSaveTemplate: () -> Unit = {},
    val onDeleteTemplate: (String) -> Unit = {},
    val onDeleteSection: (String) -> Unit = {},
    val onStartSuperelevation: () -> Unit = {},
    val onCancelSuperelevation: () -> Unit = {},
    val onSuperRow: (index: Int, (SuperelevationDraftRow) -> SuperelevationDraftRow) -> Unit = { _, _ -> },
    val onAddSuperRow: () -> Unit = {},
    val onRemoveSuperRow: (Int) -> Unit = {},
    val onSaveSuperelevation: () -> Unit = {},
    val onCsvRole: (Int, SuperelevationRole) -> Unit = { _, _ -> },
    val onApplyCsv: () -> Unit = {},
)

/**
 * Las secciones de un eje (S050/RF-1 a RF-3, RF-6, RF-7): las secciones tipo y las importadas por PK, la tabla de peralte, los editores
 * a mano y la lectura de un CSV de peralte. Los PK y los anchos van en la unidad del proyecto y las pendientes en porcentaje con
 * «baja» o «sube» (RNF-T9, RNF-T8); un rechazo dice el punto y el motivo (RF-7).
 */
@Composable
fun SectionsScreen(themeState: ThemeState, onToggleSun: () -> Unit, state: SectionsUiState, actions: SectionsActions, onBack: () -> Unit) {
    SectionScaffold(
        title = stringResource(R.string.section_title),
        themeState = themeState,
        onToggleSun = onToggleSun,
        onNavigateBack = onBack,
    ) { padding ->
        Column(
            modifier = Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(16.dp).testTag(SECTIONS_LIST_TAG),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            state.alignment?.let { Text(it.name, style = MaterialTheme.typography.titleMedium) }
            state.problem?.let { problem ->
                Text(
                    sectionsProblemText(problem, state.unit),
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.testTag(SECTIONS_PROBLEM_TAG).semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
            when {
                state.templateDraft != null -> TemplateEditor(state, state.templateDraft, actions)
                state.superelevationDraft != null -> SuperelevationEditor(state, state.superelevationDraft, actions)
                else -> Lists(state, actions)
            }
        }
    }
}

@Composable
private fun Lists(state: SectionsUiState, actions: SectionsActions) {
    val locale = uiLocale()
    Text(stringResource(R.string.section_templates_title), style = MaterialTheme.typography.titleSmall)
    if (state.templates.isEmpty()) {
        Text(
            stringResource(R.string.section_templates_empty),
            modifier = Modifier.testTag(SECTIONS_TEMPLATES_EMPTY_TAG),
        )
    }
    state.templates.forEach { stored ->
        val t = stored.template
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    stringResource(
                        R.string.section_template_row,
                        ChainageFormat.format(t.fromChainage, state.unit, locale),
                        ChainageFormat.format(t.toChainage, state.unit, locale),
                        t.gradeCode,
                    ),
                )
                OutlinedButton(
                    onClick = { actions.onDeleteTemplate(stored.id) },
                    modifier = Modifier.heightIn(min = MinTouchTarget).testTag(sectionsTemplateDeleteTag(stored.id)),
                ) { Text(stringResource(R.string.section_delete)) }
            }
        }
    }
    OutlinedButton(
        onClick = actions.onStartTemplate,
        modifier = Modifier.fillMaxWidth().heightIn(min = MinTouchTarget).testTag(SECTIONS_ADD_TEMPLATE_TAG),
    ) { Text(stringResource(R.string.section_template_add)) }

    Text(stringResource(R.string.section_profiles_title), style = MaterialTheme.typography.titleSmall)
    if (state.sections.isEmpty()) {
        Text(
            stringResource(R.string.section_profiles_empty),
            modifier = Modifier.testTag(SECTIONS_PROFILES_EMPTY_TAG),
        )
    }
    state.sections.forEach { stored ->
        val s = stored.section
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    stringResource(
                        R.string.section_profile_row,
                        stringResource(if (s.kind == SectionKind.DESIGN) R.string.section_kind_design else R.string.section_kind_ground),
                        ChainageFormat.format(s.chainage, state.unit, locale),
                        s.points.size,
                        stringResource(
                            if (s.heightKind ==
                                HeightKind.ABSOLUTE
                            ) {
                                R.string.section_height_absolute
                            } else {
                                R.string.section_height_relative
                            },
                        ),
                    ),
                )
                OutlinedButton(
                    onClick = { actions.onDeleteSection(stored.id) },
                    modifier = Modifier.heightIn(min = MinTouchTarget).testTag(sectionsProfileDeleteTag(stored.id)),
                ) { Text(stringResource(R.string.section_delete)) }
            }
        }
    }
    OutlinedButton(
        onClick = actions.onImportCsv,
        modifier = Modifier.fillMaxWidth().heightIn(min = MinTouchTarget).testTag(SECTIONS_IMPORT_CSV_TAG),
    ) { Text(stringResource(R.string.section_import_csv)) }
    OutlinedButton(
        onClick = actions.onImportLandXml,
        modifier = Modifier.fillMaxWidth().heightIn(min = MinTouchTarget).testTag(SECTIONS_IMPORT_LANDXML_TAG),
    ) { Text(stringResource(R.string.section_import_landxml)) }

    Text(stringResource(R.string.section_superelevation_title), style = MaterialTheme.typography.titleSmall)
    if (state.superelevation.isEmpty()) {
        Text(stringResource(R.string.section_superelevation_empty), modifier = Modifier.testTag(SECTIONS_SUPER_EMPTY_TAG))
    }
    state.superelevation.forEach { row ->
        Text(
            stringResource(
                R.string.section_superelevation_row,
                ChainageFormat.format(row.chainage, state.unit, locale),
                slopeLabel(row.leftSlope),
                slopeLabel(row.rightSlope),
            ),
        )
    }
    OutlinedButton(
        onClick = actions.onStartSuperelevation,
        modifier = Modifier.fillMaxWidth().heightIn(min = MinTouchTarget).testTag(SECTIONS_EDIT_SUPER_TAG),
    ) { Text(stringResource(R.string.section_superelevation_edit)) }
    Button(
        onClick = actions.onCalculate,
        modifier = Modifier.fillMaxWidth().heightIn(min = MinTouchTarget).testTag(SECTIONS_CALCULATE_TAG),
    ) { Text(stringResource(R.string.section_calculate)) }
}

@Composable
private fun TemplateEditor(state: SectionsUiState, draft: TemplateDraft, actions: SectionsActions) {
    val symbol = unitSymbol(state.unit)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.testTag(SECTIONS_TEMPLATE_EDITOR_TAG)) {
        Text(stringResource(R.string.section_editor_title), style = MaterialTheme.typography.titleSmall)
        LabeledField(
            stringResource(R.string.section_editor_from, symbol),
            draft.from,
            SECTIONS_TEMPLATE_FROM_TAG,
            { v -> actions.onTemplate { it.copy(from = v) } },
        )
        LabeledField(
            stringResource(R.string.section_editor_to, symbol),
            draft.to,
            SECTIONS_TEMPLATE_TO_TAG,
            { v -> actions.onTemplate { it.copy(to = v) } },
        )
        LabeledField(
            stringResource(R.string.section_editor_grade),
            draft.grade,
            SECTIONS_TEMPLATE_GRADE_TAG,
            { v -> actions.onTemplate { it.copy(grade = v) } },
            numeric = false,
        )
        Side(true, stringResource(R.string.section_editor_left), draft.left, symbol, actions)
        Side(false, stringResource(R.string.section_editor_right), draft.right, symbol, actions)
        Button(
            onClick = actions.onSaveTemplate,
            enabled = !state.saving,
            modifier = Modifier.fillMaxWidth().heightIn(min = MinTouchTarget).testTag(SECTIONS_TEMPLATE_SAVE_TAG),
        ) { Text(stringResource(R.string.section_editor_save)) }
        OutlinedButton(
            onClick = actions.onCancelTemplate,
            modifier = Modifier.fillMaxWidth().heightIn(min = MinTouchTarget).testTag(SECTIONS_TEMPLATE_CANCEL_TAG),
        ) { Text(stringResource(R.string.dialog_cancel)) }
    }
}

@Composable
private fun Side(left: Boolean, title: String, rows: List<SegmentRow>, symbol: String, actions: SectionsActions) {
    Text(title, style = MaterialTheme.typography.labelLarge)
    rows.forEachIndexed { index, row ->
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                LabeledField(
                    stringResource(R.string.section_segment_code),
                    row.code,
                    sectionsSegmentTag(left, index, "code"),
                    { v -> actions.onSegment(left, index) { it.copy(code = v) } },
                    numeric = false,
                )
                LabeledField(
                    stringResource(R.string.section_segment_width, symbol),
                    row.width,
                    sectionsSegmentTag(left, index, "width"),
                    { v -> actions.onSegment(left, index) { it.copy(width = v) } },
                )
                LabeledField(
                    stringResource(R.string.section_segment_slope),
                    row.slope,
                    sectionsSegmentTag(left, index, "slope"),
                    { v -> actions.onSegment(left, index) { it.copy(slope = v) } },
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = MinTouchTarget)
                        .toggleable(
                            value = row.follows,
                            role = Role.Checkbox,
                            onValueChange = { v -> actions.onSegment(left, index) { it.copy(follows = v) } },
                        )
                        .testTag(sectionsSegmentTag(left, index, "follows")),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = row.follows, onCheckedChange = null)
                    Text(stringResource(R.string.section_segment_follows), modifier = Modifier.padding(start = 12.dp))
                }
                OutlinedButton(
                    onClick = { actions.onRemoveSegment(left, index) },
                    modifier = Modifier.heightIn(min = MinTouchTarget).testTag(sectionsSegmentRemoveTag(left, index)),
                ) { Text(stringResource(R.string.section_segment_remove)) }
            }
        }
    }
    OutlinedButton(
        onClick = { actions.onAddSegment(left) },
        modifier = Modifier.fillMaxWidth().heightIn(min = MinTouchTarget).testTag(sectionsSegmentAddTag(left)),
    ) { Text(stringResource(R.string.section_segment_add)) }
}

@Composable
private fun SuperelevationEditor(state: SectionsUiState, rows: List<SuperelevationDraftRow>, actions: SectionsActions) {
    val symbol = unitSymbol(state.unit)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.testTag(SECTIONS_SUPER_EDITOR_TAG)) {
        Text(stringResource(R.string.section_super_title), style = MaterialTheme.typography.titleSmall)
        rows.forEachIndexed { index, row ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    LabeledField(
                        stringResource(R.string.section_super_chainage, symbol),
                        row.chainage,
                        sectionsSuperTag(index, "chainage"),
                        { v -> actions.onSuperRow(index) { it.copy(chainage = v) } },
                    )
                    LabeledField(
                        stringResource(R.string.section_super_left),
                        row.left,
                        sectionsSuperTag(index, "left"),
                        { v -> actions.onSuperRow(index) { it.copy(left = v) } },
                    )
                    LabeledField(
                        stringResource(R.string.section_super_right),
                        row.right,
                        sectionsSuperTag(index, "right"),
                        { v -> actions.onSuperRow(index) { it.copy(right = v) } },
                    )
                    OutlinedButton(
                        onClick = { actions.onRemoveSuperRow(index) },
                        modifier = Modifier.heightIn(min = MinTouchTarget).testTag(sectionsSuperRemoveTag(index)),
                    ) { Text(stringResource(R.string.section_super_remove_row)) }
                }
            }
        }
        OutlinedButton(
            onClick = actions.onAddSuperRow,
            modifier = Modifier.fillMaxWidth().heightIn(min = MinTouchTarget).testTag(SECTIONS_SUPER_ADD_TAG),
        ) { Text(stringResource(R.string.section_super_add_row)) }
        OutlinedButton(
            onClick = actions.onSuperelevationCsv,
            modifier = Modifier.fillMaxWidth().heightIn(min = MinTouchTarget).testTag(SECTIONS_SUPER_CSV_TAG),
        ) { Text(stringResource(R.string.section_superelevation_csv)) }
        state.csv?.let { Csv(it, actions) }
        Button(
            onClick = actions.onSaveSuperelevation,
            enabled = !state.saving,
            modifier = Modifier.fillMaxWidth().heightIn(min = MinTouchTarget).testTag(SECTIONS_SUPER_SAVE_TAG),
        ) { Text(stringResource(R.string.section_editor_save)) }
        OutlinedButton(
            onClick = actions.onCancelSuperelevation,
            modifier = Modifier.fillMaxWidth().heightIn(min = MinTouchTarget).testTag(SECTIONS_SUPER_CANCEL_TAG),
        ) { Text(stringResource(R.string.dialog_cancel)) }
    }
}

@Composable
private fun Csv(csv: SuperelevationCsvState, actions: SectionsActions) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.testTag(SECTIONS_CSV_PANEL_TAG)) {
        if (csv.unreadable) {
            Text(
                stringResource(R.string.section_csv_unreadable),
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.testTag(SECTIONS_CSV_UNREADABLE_TAG),
            )
            return
        }
        Text(stringResource(R.string.section_csv_title, csv.fileName), style = MaterialTheme.typography.labelLarge)
        csv.columns.roles.indices.forEach { index ->
            Text(csv.firstRow.getOrNull(index).orEmpty(), style = MaterialTheme.typography.bodySmall)
            ChoiceRow {
                SuperelevationRole.entries.forEach { role ->
                    Choice(roleLabel(role), sectionsCsvRoleTag(index, role), csv.columns.roles[index] == role) {
                        actions.onCsvRole(index, role)
                    }
                }
            }
        }
        Text(stringResource(R.string.section_csv_rows, csv.rowCount))
        csv.errors.take(MAX_ERRORS_SHOWN).forEach { error ->
            Text(
                stringResource(
                    R.string.section_import_error_row,
                    error.line,
                    roleLabel(error.role),
                    stringResource(
                        when (error.reason) {
                            es.topophone.core.formats.section.SectionCsvError.Reason.MISSING -> R.string.section_import_error_missing
                            es.topophone.core.formats.section.SectionCsvError.Reason.NOT_A_NUMBER -> R.string.section_import_error_number
                            es.topophone.core.formats.section.SectionCsvError.Reason.UNKNOWN_KIND -> R.string.section_import_error_kind
                        },
                    ),
                ),
                color = MaterialTheme.colorScheme.error,
            )
        }
        Button(
            onClick = actions.onApplyCsv,
            enabled = csv.columns.isValid && csv.errors.isEmpty() && csv.rowCount > 0,
            modifier = Modifier.fillMaxWidth().heightIn(min = MinTouchTarget).testTag(SECTIONS_CSV_APPLY_TAG),
        ) { Text(stringResource(R.string.section_csv_apply)) }
    }
}

@Composable
private fun roleLabel(role: SuperelevationRole): String = stringResource(
    when (role) {
        SuperelevationRole.CHAINAGE -> R.string.section_role_chainage
        SuperelevationRole.LEFT -> R.string.section_role_left
        SuperelevationRole.RIGHT -> R.string.section_role_right
        SuperelevationRole.IGNORE -> R.string.section_role_ignore
    },
)

private const val MAX_ERRORS_SHOWN = 10
