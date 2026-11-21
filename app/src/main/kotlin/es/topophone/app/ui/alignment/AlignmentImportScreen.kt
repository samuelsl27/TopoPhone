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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import es.topophone.app.ui.crs.CrsLibraryHost
import es.topophone.app.ui.crs.HiltCrsLibraryHost
import es.topophone.app.ui.crs.LibrarySourceRequest
import es.topophone.app.ui.crs.LibrarySourceSection
import es.topophone.app.ui.projects.crsLabel
import es.topophone.app.ui.receiver.uiLocale
import es.topophone.core.designsystem.component.MinTouchTarget
import es.topophone.core.model.alignment.ChainageFormat
import es.topophone.core.model.cad.CadSourceCrs
import es.topophone.core.model.format.LengthFormat
import es.topophone.core.model.preferences.ThemeState
import es.topophone.core.model.project.LibraryCrs
import es.topophone.core.model.project.LinearUnit
import es.topophone.core.model.project.ProjectCrs
import es.topophone.data.alignment.AlignmentImportFailure

const val ALIGNMENT_LOADING_TAG = "alignment_loading"
const val ALIGNMENT_PROBLEM_TAG = "alignment_problem"
const val ALIGNMENT_SCANNING_TAG = "alignment_scanning"
const val ALIGNMENT_CRS_PROJECT_TAG = "alignment_crs_project"
const val ALIGNMENT_CRS_OTHER_TAG = "alignment_crs_other"
const val ALIGNMENT_IMPORT_BUTTON_TAG = "alignment_import_button"
const val ALIGNMENT_PROGRESS_TAG = "alignment_progress"
const val ALIGNMENT_CANCEL_TAG = "alignment_cancel"
const val ALIGNMENT_FAILURE_TAG = "alignment_failure"
const val ALIGNMENT_RESULT_TAG = "alignment_result"

fun alignmentSourceTag(crs: ProjectCrs) = "alignment_source_${crs.name}"

fun alignmentUnitTag(unit: LinearUnit) = "alignment_unit_${unit.name}"

fun alignmentChoiceTag(index: Int) = "alignment_choice_$index"

/** Acciones de la pantalla de importar los ejes de un LandXML (S049). */
class AlignmentImportActions(
    val onToggle: (Int) -> Unit = {},
    val onSource: (CadSourceCrs) -> Unit = {},
    val onChooseLibrary: (LibraryCrs) -> Unit = {},
    val onConfirmLibrary: (Boolean) -> Unit = {},
    val onRefreshLibrary: () -> Unit = {},
    val onUnit: (LinearUnit) -> Unit = {},
    val onImport: () -> Unit = {},
    val onCancel: () -> Unit = {},
)

/**
 * La importación de los ejes de un LandXML a un proyecto (S049), en una sola pantalla: el fichero (o por qué no se abre), las alineaciones
 * con su longitud, su PK inicial y si traen rasante, para marcar cuáles se importan, el sistema y la unidad, el progreso con «Cancelar» y el
 * resumen de lo importado y lo ignorado (RF-2, RF-3, RF-11, RF-13).
 */
@Composable
fun AlignmentImportScreen(
    themeState: ThemeState,
    onToggleSun: () -> Unit,
    state: AlignmentImportUiState,
    actions: AlignmentImportActions,
    onBack: () -> Unit,
    libraryHost: CrsLibraryHost = HiltCrsLibraryHost,
) {
    SectionScaffold(
        title = stringResource(R.string.alignment_import_title),
        themeState = themeState,
        onToggleSun = onToggleSun,
        onNavigateBack = onBack,
    ) { padding ->
        when (state.stage) {
            AlignmentImportStage.LOADING -> Column(
                modifier = Modifier.padding(padding).padding(16.dp).testTag(ALIGNMENT_LOADING_TAG),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                CircularProgressIndicator()
                Text(stringResource(R.string.alignment_import_loading))
            }

            AlignmentImportStage.PROBLEM -> Column(
                modifier = Modifier.padding(padding).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(state.fileName, style = MaterialTheme.typography.titleMedium)
                Text(problemText(state), color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag(ALIGNMENT_PROBLEM_TAG))
                Button(onClick = onBack, modifier = Modifier.heightIn(min = MinTouchTarget)) {
                    Text(stringResource(R.string.alignment_import_close))
                }
            }

            AlignmentImportStage.IMPORTING -> Column(
                modifier = Modifier.padding(padding).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(state.fileName, style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.alignment_importing))
                ProgressBar(state.importBytes, state.totalBytes)
                OutlinedButton(
                    onClick = actions.onCancel,
                    modifier = Modifier.fillMaxWidth().heightIn(min = MinTouchTarget).testTag(ALIGNMENT_CANCEL_TAG),
                ) { Text(stringResource(R.string.alignment_cancel)) }
            }

            AlignmentImportStage.CONFIG -> Column(
                modifier = Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                ConfigSections(state, actions, themeState, onToggleSun, libraryHost)
            }

            AlignmentImportStage.RESULT -> Column(
                modifier = Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                ResultSection(state, onBack)
            }
        }
    }
}

@Composable
private fun problemText(state: AlignmentImportUiState): String = when (state.fileProblem) {
    AlignmentFileProblem.EMPTY -> stringResource(R.string.alignment_problem_empty)
    AlignmentFileProblem.TOO_BIG -> stringResource(R.string.alignment_problem_too_big)
    AlignmentFileProblem.NOT_XML -> stringResource(R.string.alignment_problem_not_xml)
    AlignmentFileProblem.NOT_LANDXML -> stringResource(R.string.alignment_problem_not_landxml)
    AlignmentFileProblem.UNSUPPORTED_VERSION -> stringResource(R.string.alignment_problem_version, state.version.orEmpty())
    AlignmentFileProblem.NO_ALIGNMENT -> stringResource(R.string.alignment_problem_no_alignment)
    AlignmentFileProblem.UNREADABLE, null -> stringResource(R.string.alignment_problem_unreadable)
}

@Composable
private fun ProgressBar(read: Long, total: Long?) {
    val modifier = Modifier.fillMaxWidth().testTag(ALIGNMENT_PROGRESS_TAG)
    if (total != null && total > 0) {
        LinearProgressIndicator(progress = { (read.toFloat() / total).coerceIn(0f, 1f) }, modifier = modifier)
    } else {
        LinearProgressIndicator(modifier = modifier)
    }
}

@Composable
private fun ConfigSections(
    state: AlignmentImportUiState,
    actions: AlignmentImportActions,
    themeState: ThemeState,
    onToggleSun: () -> Unit,
    libraryHost: CrsLibraryHost,
) {
    Text(stringResource(R.string.alignment_section_file), style = MaterialTheme.typography.titleSmall)
    Text(state.fileName, style = MaterialTheme.typography.titleMedium)
    if (state.scanning) {
        Text(stringResource(R.string.alignment_scanning), modifier = Modifier.testTag(ALIGNMENT_SCANNING_TAG))
        ProgressBar(state.bytesRead, state.totalBytes)
    } else {
        AlignmentChoices(state, actions) // RF-2
    }
    FailureMessage(state)
    CrsSection(state, actions, themeState, onToggleSun, libraryHost)
    UnitSection(state, actions)
    Button(
        onClick = actions.onImport,
        enabled = state.canImport,
        modifier = Modifier.fillMaxWidth().heightIn(min = MinTouchTarget).testTag(ALIGNMENT_IMPORT_BUTTON_TAG),
    ) { Text(stringResource(R.string.alignment_import_button)) }
}

/** Las alineaciones del fichero con su longitud, su PK inicial y si traen rasante, para marcar cuáles se importan (S049/RF-2). */
@Composable
private fun AlignmentChoices(state: AlignmentImportUiState, actions: AlignmentImportActions) {
    Text(stringResource(R.string.alignment_section_alignments), style = MaterialTheme.typography.titleSmall)
    val locale = uiLocale()
    val metres = state.unit.metres
    state.alignments.forEach { info ->
        val checked = info.index in state.selected
        val line = stringResource(
            R.string.alignment_choice,
            info.name,
            LengthFormat.metres(info.length * metres, locale, decimals = 1),
            ChainageFormat.format(info.startStation * metres, state.projectUnit, locale),
        )
        val note = when {
            info.hasStationEquation -> stringResource(R.string.alignment_choice_equation)
            info.hasProfile -> stringResource(R.string.alignment_choice_profile)
            else -> stringResource(R.string.alignment_choice_no_profile)
        }
        val description = stringResource(R.string.alignment_choice_description, line, note)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = MinTouchTarget)
                .toggleable(value = checked, role = Role.Checkbox, onValueChange = { actions.onToggle(info.index) })
                .semantics(mergeDescendants = true) { contentDescription = description }
                .testTag(alignmentChoiceTag(info.index)),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(checked = checked, onCheckedChange = null)
            Column(modifier = Modifier.padding(start = 12.dp)) {
                Text(line)
                Text(note, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun FailureMessage(state: AlignmentImportUiState) {
    val text = when {
        state.cancelled -> stringResource(R.string.alignment_cancelled)
        else -> when (state.failure) {
            AlignmentImportFailure.WRITE_ERROR -> stringResource(R.string.alignment_failed_write)
            AlignmentImportFailure.NO_ALIGNMENT -> stringResource(R.string.alignment_failed_none)
            null -> return
            else -> stringResource(R.string.alignment_failed_read)
        }
    }
    Text(
        text,
        color = MaterialTheme.colorScheme.error,
        modifier = Modifier.testTag(ALIGNMENT_FAILURE_TAG).semantics { liveRegion = LiveRegionMode.Polite },
    )
}

/** El del proyecto u otro UTM de los que ofrece la app (S049/RF-11). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CrsSection(
    state: AlignmentImportUiState,
    actions: AlignmentImportActions,
    themeState: ThemeState,
    onToggleSun: () -> Unit,
    libraryHost: CrsLibraryHost,
) {
    val source = state.source
    Text(stringResource(R.string.alignment_section_crs), style = MaterialTheme.typography.titleSmall)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Choice(
            stringResource(R.string.alignment_crs_project, crsLabel(state.projectCrs)),
            ALIGNMENT_CRS_PROJECT_TAG,
            source == CadSourceCrs.Project,
        ) { actions.onSource(CadSourceCrs.Project) }
        Choice(stringResource(R.string.alignment_crs_other), ALIGNMENT_CRS_OTHER_TAG, source is CadSourceCrs.Utm) {
            if (source !is CadSourceCrs.Utm) actions.onSource(sourceOf(state.availableSources.first()))
        }
    }
    // El SC de origen puede ser cualquiera de la biblioteca, con su cambio de datum y su aviso de precisión (S057/RF-1, RF-8 a RF-10).
    LibrarySourceSection(
        chosen = state.library,
        selected = source is CadSourceCrs.Library,
        request = LibrarySourceRequest(state.corrections, state.centre),
        themeState = themeState,
        onToggleSun = onToggleSun,
        host = libraryHost,
        onChosen = actions.onChooseLibrary,
        onSelect = { state.library?.let { actions.onSource(it.source) } },
        onConfirm = actions.onConfirmLibrary,
        onRefresh = actions.onRefreshLibrary,
    )
    if (source is CadSourceCrs.Utm) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            state.availableSources.forEach { crs ->
                Choice(crsLabel(crs), alignmentSourceTag(crs), crs.datum == source.datum && crs.utmZone == source.zone) {
                    actions.onSource(sourceOf(crs))
                }
            }
        }
        Text(stringResource(R.string.alignment_crs_other_note), style = MaterialTheme.typography.bodySmall)
    }
}

private fun sourceOf(crs: ProjectCrs): CadSourceCrs = CadSourceCrs.Utm(crs.datum, requireNotNull(crs.utmZone))

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun UnitSection(state: AlignmentImportUiState, actions: AlignmentImportActions) {
    Text(stringResource(R.string.alignment_section_unit), style = MaterialTheme.typography.titleSmall)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        LinearUnit.entries.forEach { unit ->
            Choice(stringResource(unitLabel(unit)), alignmentUnitTag(unit), state.unit == unit) { actions.onUnit(unit) }
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
private fun Choice(label: String, tag: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
        modifier = Modifier.heightIn(min = MinTouchTarget).testTag(tag),
    )
}

/** El resumen de lo importado e ignorado, con el motivo de cada eje ignorado y de cada rasante omitida (S049/RF-13). */
@Composable
private fun ResultSection(state: AlignmentImportUiState, onClose: () -> Unit) {
    val summary = state.summary ?: return
    val locale = uiLocale()
    val description = stringResource(R.string.alignment_result_description, summary.imported.size, summary.ignored.size)
    Text(stringResource(R.string.alignment_result_title), style = MaterialTheme.typography.titleMedium)
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.testTag(ALIGNMENT_RESULT_TAG).semantics(mergeDescendants = true) {
            contentDescription = description
            liveRegion = LiveRegionMode.Polite
        },
    ) {
        StatusMark(R.drawable.ic_check, stringResource(R.string.alignment_result_imported, summary.imported.size))
        summary.imported.forEach { item ->
            val profile = stringResource(
                if (item.hasProfile) R.string.alignment_result_with_profile else R.string.alignment_result_without_profile,
            )
            Text(
                stringResource(
                    R.string.alignment_result_item,
                    item.name,
                    profile,
                    ChainageFormat.format(item.startChainage, state.projectUnit, locale),
                    ChainageFormat.format(item.startChainage + item.lengthM, state.projectUnit, locale),
                ),
            )
            item.profileOmission?.let {
                StatusMark(R.drawable.ic_warning, profileOmissionText(it, state.projectUnit), error = true)
            }
            if (item.approximated) Text(stringResource(R.string.alignment_result_approximated), style = MaterialTheme.typography.bodySmall)
        }
        if (summary.activatedId != null) Text(stringResource(R.string.alignment_result_active))
        if (summary.ignored.isNotEmpty()) {
            StatusMark(R.drawable.ic_warning, stringResource(R.string.alignment_result_ignored, summary.ignored.size), error = true)
            summary.ignored.forEach { ignored ->
                Text(stringResource(R.string.alignment_ignored_item, ignored.name, alignmentIssueText(ignored.issue)))
            }
        }
    }
    Button(onClick = onClose, modifier = Modifier.fillMaxWidth().heightIn(min = MinTouchTarget)) {
        Text(stringResource(R.string.alignment_import_close))
    }
}

@Composable
private fun StatusMark(drawable: Int, text: String, error: Boolean = false) {
    val color = if (error) MaterialTheme.colorScheme.error else LocalContentColor.current
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(painterResource(drawable), contentDescription = null, tint = color, modifier = Modifier.size(20.dp))
        Text(text, color = color)
    }
}
