package es.topophone.app.ui.cad

import android.content.res.Resources
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
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import es.topophone.app.R
import es.topophone.app.ui.SectionScaffold
import es.topophone.app.ui.points.ReportOutcome
import es.topophone.app.ui.projects.crsLabel
import es.topophone.core.designsystem.component.MinTouchTarget
import es.topophone.core.model.cad.CadEntityKind
import es.topophone.core.model.cad.CadIgnored
import es.topophone.core.model.cad.CadIgnoredReason
import es.topophone.core.model.cad.CadSourceCrs
import es.topophone.core.model.preferences.ThemeState
import es.topophone.core.model.project.LinearUnit
import es.topophone.core.model.project.ProjectCrs
import es.topophone.data.cad.CadImportFailure
import java.text.NumberFormat

const val CAD_LOADING_TAG = "cad_loading"
const val CAD_PROBLEM_TAG = "cad_problem"
const val CAD_SCANNING_TAG = "cad_scanning"
const val CAD_INCOMPLETE_TAG = "cad_incomplete"
const val CAD_CRS_PROJECT_TAG = "cad_crs_project"
const val CAD_CRS_OTHER_TAG = "cad_crs_other"
const val CAD_ELEVATIONS_TAG = "cad_elevations"
const val CAD_EXTENT_TAG = "cad_extent"
const val CAD_OUTSIDE_TAG = "cad_outside"
const val CAD_FAR_TAG = "cad_far"
const val CAD_FAR_CONFIRM_TAG = "cad_far_confirm"
const val CAD_DUPLICATE_TAG = "cad_duplicate"
const val CAD_DUPLICATE_CONFIRM_TAG = "cad_duplicate_confirm"
const val CAD_IMPORT_BUTTON_TAG = "cad_import_button"
const val CAD_PROGRESS_TAG = "cad_progress"
const val CAD_CANCEL_TAG = "cad_cancel"
const val CAD_FAILURE_TAG = "cad_failure"
const val CAD_RESULT_TAG = "cad_result"
const val CAD_RESULT_INCOMPLETE_TAG = "cad_result_incomplete"
const val CAD_SUMMARY_SAVE_TAG = "cad_summary_save"
const val CAD_SUMMARY_SHARE_TAG = "cad_summary_share"

fun cadSourceTag(crs: ProjectCrs) = "cad_source_${crs.name}"

fun cadUnitTag(unit: LinearUnit) = "cad_unit_${unit.name}"

/** Acciones de la pantalla de importar un DXF (S040). */
class CadImportActions(
    val onSource: (CadSourceCrs) -> Unit = {},
    val onUnit: (LinearUnit) -> Unit = {},
    val onUseElevations: (Boolean) -> Unit = {},
    val onConfirmFar: () -> Unit = {},
    val onConfirmDuplicate: () -> Unit = {},
    val onImport: () -> Unit = {},
    val onCancel: () -> Unit = {},
    val onSaveSummary: (fileName: String) -> Unit = {},
    val onShareSummary: (fileName: String) -> Unit = {},
)

/**
 * La importación de un DXF a un proyecto (S040), en una sola pantalla: el fichero (o por qué no se abre), el sistema de coordenadas,
 * la unidad y las cotas, la extensión con sus avisos, el progreso con «Cancelar» y el resumen (RF-6 a RF-10, RF-13, RF-18).
 */
@Composable
fun CadImportScreen(
    themeState: ThemeState,
    onToggleSun: () -> Unit,
    state: CadImportUiState,
    actions: CadImportActions,
    onBack: () -> Unit,
    summaryOutcome: ReportOutcome? = null,
) {
    SectionScaffold(
        title = stringResource(R.string.cad_import_title),
        themeState = themeState,
        onToggleSun = onToggleSun,
        onNavigateBack = onBack,
    ) { padding ->
        when (state.stage) {
            CadImportStage.LOADING -> Column(
                modifier = Modifier.padding(padding).padding(16.dp).testTag(CAD_LOADING_TAG),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                CircularProgressIndicator()
                Text(stringResource(R.string.cad_import_loading))
            }
            CadImportStage.PROBLEM -> Column(
                modifier = Modifier.padding(padding).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(state.fileName, style = MaterialTheme.typography.titleMedium)
                Text(
                    problemText(state),
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.testTag(CAD_PROBLEM_TAG),
                ) // RF-3 a RF-5
                Button(onClick = onBack, modifier = Modifier.heightIn(min = MinTouchTarget)) {
                    Text(stringResource(R.string.cad_import_close))
                }
            }
            CadImportStage.IMPORTING -> Column(
                modifier = Modifier.padding(padding).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(state.fileName, style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.cad_importing))
                ProgressBar(state.importBytes, state.totalBytes)
                OutlinedButton(
                    onClick = actions.onCancel,
                    modifier = Modifier.fillMaxWidth().heightIn(min = MinTouchTarget).testTag(CAD_CANCEL_TAG),
                ) { Text(stringResource(R.string.cad_cancel)) } // RF-11
            }
            CadImportStage.CONFIG -> Column(
                modifier = Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                ConfigSections(state, actions)
            }
            CadImportStage.RESULT -> Column(
                modifier = Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                ResultSection(state, actions, summaryOutcome, onBack)
            }
        }
    }
}

@Composable
private fun problemText(state: CadImportUiState): String = when (state.fileProblem) {
    CadFileProblem.EMPTY -> stringResource(R.string.cad_problem_empty)
    CadFileProblem.NOT_DXF -> stringResource(R.string.cad_problem_not_dxf)
    CadFileProblem.DWG -> state.dwgVersion?.let { stringResource(R.string.cad_problem_dwg, it) }
        ?: stringResource(R.string.cad_problem_dwg_unknown)
    CadFileProblem.BINARY_DXF -> stringResource(R.string.cad_problem_binary)
    CadFileProblem.TOO_BIG -> stringResource(R.string.cad_problem_too_big)
    CadFileProblem.NO_ELEMENTS -> stringResource(R.string.cad_problem_no_elements)
    CadFileProblem.UNREADABLE, null -> stringResource(R.string.cad_problem_unreadable)
}

@Composable
private fun ProgressBar(read: Long, total: Long?) {
    val modifier = Modifier.fillMaxWidth().testTag(CAD_PROGRESS_TAG)
    if (total != null && total > 0) {
        LinearProgressIndicator(progress = { (read.toFloat() / total).coerceIn(0f, 1f) }, modifier = modifier)
    } else {
        LinearProgressIndicator(modifier = modifier)
    }
}

@Composable
private fun ConfigSections(state: CadImportUiState, actions: CadImportActions) {
    Text(stringResource(R.string.cad_section_file), style = MaterialTheme.typography.titleSmall)
    Text(state.fileName, style = MaterialTheme.typography.titleMedium)
    if (state.scanning) {
        Text(stringResource(R.string.cad_scanning), modifier = Modifier.testTag(CAD_SCANNING_TAG))
        ProgressBar(state.bytesRead, state.totalBytes)
    } else {
        Text(pluralStringResource(R.plurals.cad_layer_count, state.elementCount, state.elementCount))
        if (!state.scanComplete) {
            Text(
                stringResource(R.string.cad_file_incomplete),
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.testTag(CAD_INCOMPLETE_TAG),
            )
        }
    }
    FailureMessage(state)
    CrsSection(state, actions)
    UnitSection(state, actions)
    CheckRow(stringResource(R.string.cad_use_elevations), state.options.useElevations, CAD_ELEVATIONS_TAG, actions.onUseElevations) // RF-8
    if (!state.scanning) {
        ExtentSection(state)
        Warnings(state, actions)
    }
    Button(
        onClick = actions.onImport,
        enabled = state.canImport,
        modifier = Modifier.fillMaxWidth().heightIn(min = MinTouchTarget).testTag(CAD_IMPORT_BUTTON_TAG),
    ) { Text(stringResource(R.string.cad_import_button)) }
}

@Composable
private fun FailureMessage(state: CadImportUiState) {
    val text = when {
        state.cancelled -> stringResource(R.string.cad_cancelled)
        state.failure == CadImportFailure.WRITE_ERROR -> stringResource(R.string.cad_failed_write)
        state.failure == CadImportFailure.READ_ERROR -> stringResource(R.string.cad_failed_read)
        state.failure == CadImportFailure.NO_ELEMENTS -> stringResource(R.string.cad_failed_none)
        else -> return
    }
    Text(
        text,
        color = MaterialTheme.colorScheme.error,
        modifier = Modifier.testTag(CAD_FAILURE_TAG).semantics {
            liveRegion =
                LiveRegionMode.Polite
        },
    )
}

/** El del proyecto u otro UTM de los que ofrece la app (S040/RF-6). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CrsSection(state: CadImportUiState, actions: CadImportActions) {
    val source = state.options.source
    Text(stringResource(R.string.cad_section_crs), style = MaterialTheme.typography.titleSmall)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Choice(
            stringResource(R.string.cad_crs_project, crsLabel(state.projectCrs)),
            CAD_CRS_PROJECT_TAG,
            source == CadSourceCrs.Project,
        ) { actions.onSource(CadSourceCrs.Project) }
        Choice(stringResource(R.string.cad_crs_other), CAD_CRS_OTHER_TAG, source is CadSourceCrs.Utm) {
            if (source !is CadSourceCrs.Utm) actions.onSource(sourceOf(state.availableSources.first()))
        }
    }
    if (source is CadSourceCrs.Utm) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            state.availableSources.forEach { crs ->
                Choice(crsLabel(crs), cadSourceTag(crs), crs.datum == source.datum && crs.utmZone == source.zone) {
                    actions.onSource(sourceOf(crs))
                }
            }
        }
    }
}

private fun sourceOf(crs: ProjectCrs): CadSourceCrs = CadSourceCrs.Utm(crs.datum, requireNotNull(crs.utmZone))

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun UnitSection(state: CadImportUiState, actions: CadImportActions) {
    Text(stringResource(R.string.cad_section_unit), style = MaterialTheme.typography.titleSmall)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        LinearUnit.entries.forEach { unit ->
            Choice(stringResource(unitLabel(unit)), cadUnitTag(unit), state.options.unit == unit) { actions.onUnit(unit) }
        }
    }
}

@StringRes
private fun unitLabel(unit: LinearUnit) = when (unit) {
    LinearUnit.METRE -> R.string.import_unit_metre
    LinearUnit.INTERNATIONAL_FOOT -> R.string.import_unit_foot
    LinearUnit.US_SURVEY_FOOT -> R.string.import_unit_usft
}

/** La extensión del dibujo en el sistema del proyecto y su distancia, o por qué no cabe (S040/RF-9). */
@Composable
private fun ExtentSection(state: CadImportUiState) {
    Text(stringResource(R.string.cad_section_extent), style = MaterialTheme.typography.titleSmall)
    val extent = state.extent
    if (state.outsideArea || extent == null) {
        Text(
            stringResource(R.string.cad_outside_area),
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.testTag(CAD_OUTSIDE_TAG),
        )
        return
    }
    val number = NumberFormat.getNumberInstance().apply {
        minimumFractionDigits = 1
        maximumFractionDigits = 1
    }
    Column(modifier = Modifier.testTag(CAD_EXTENT_TAG), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(stringResource(R.string.cad_extent_east, number.format(extent.minEasting), number.format(extent.maxEasting)))
        Text(stringResource(R.string.cad_extent_north, number.format(extent.minNorthing), number.format(extent.maxNorthing)))
        state.distanceKm?.let { Text(stringResource(R.string.cad_distance, number.format(it))) }
    }
}

/** Los avisos que piden confirmar antes de importar: un dibujo lejano y un nombre ya importado (S040/RF-10, RF-20). */
@Composable
private fun Warnings(state: CadImportUiState, actions: CadImportActions) {
    if (state.farWarning) {
        Text(stringResource(R.string.cad_far_warning), color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag(CAD_FAR_TAG))
        CheckRow(stringResource(R.string.cad_far_confirm), state.farConfirmed, CAD_FAR_CONFIRM_TAG) { if (it) actions.onConfirmFar() }
    }
    if (state.duplicateName) {
        Text(
            stringResource(R.string.cad_duplicate_warning),
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.testTag(CAD_DUPLICATE_TAG),
        )
        CheckRow(stringResource(R.string.cad_duplicate_confirm), state.duplicateConfirmed, CAD_DUPLICATE_CONFIRM_TAG) {
            if (it) actions.onConfirmDuplicate()
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

/** Lo importado, lo ignorado y las capas, con el resumen para guardar o compartir (S040/RF-18). */
@Composable
private fun ResultSection(state: CadImportUiState, actions: CadImportActions, summaryOutcome: ReportOutcome?, onClose: () -> Unit) {
    val summary = state.result?.summary ?: return
    Text(stringResource(R.string.cad_result_title), style = MaterialTheme.typography.titleMedium)
    Text(state.fileName, style = MaterialTheme.typography.titleSmall)
    val description = stringResource(R.string.cad_result_description, summary.importedTotal, summary.ignoredTotal, summary.layers)
    Column(
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.testTag(CAD_RESULT_TAG).semantics(mergeDescendants = true) {
            contentDescription = description
            liveRegion = LiveRegionMode.Polite
        },
    ) {
        StatusMark(R.drawable.ic_check, stringResource(R.string.cad_result_imported, summary.importedTotal))
        summary.imported.entries.sortedBy { it.key.ordinal }.forEach { (kind, count) ->
            val label = stringResource(kindLabel(kind))
            Text(stringResource(R.string.cad_label_count, label, count), modifier = Modifier.padding(start = 28.dp))
        }
        val ignoredIcon = if (summary.ignoredTotal > 0) R.drawable.ic_warning else R.drawable.ic_check
        val ignoredText = stringResource(R.string.cad_result_ignored, summary.ignoredTotal)
        StatusMark(ignoredIcon, ignoredText, error = summary.ignoredTotal > 0)
        summary.ignored.entries.forEach { (item, count) ->
            Text(stringResource(R.string.cad_label_count, ignoredLabel(item), count), modifier = Modifier.padding(start = 28.dp))
        }
        StatusMark(R.drawable.ic_check, stringResource(R.string.cad_result_layers, summary.layers))
    }
    if (summary.incomplete) {
        StatusMark(R.drawable.ic_warning, stringResource(R.string.cad_result_incomplete), error = true, tag = CAD_RESULT_INCOMPLETE_TAG)
    }
    OutlinedButton(
        onClick = { actions.onSaveSummary(SUMMARY_FILE) },
        modifier = Modifier.fillMaxWidth().heightIn(min = MinTouchTarget).testTag(CAD_SUMMARY_SAVE_TAG),
    ) { Text(stringResource(R.string.cad_summary_save)) }
    OutlinedButton(
        onClick = { actions.onShareSummary(SUMMARY_FILE) },
        modifier = Modifier.fillMaxWidth().heightIn(min = MinTouchTarget).testTag(CAD_SUMMARY_SHARE_TAG),
    ) { Text(stringResource(R.string.cad_summary_share)) }
    when (summaryOutcome) {
        ReportOutcome.SAVED -> Text(stringResource(R.string.cad_summary_saved))
        ReportOutcome.FAILED -> Text(stringResource(R.string.cad_summary_failed), color = MaterialTheme.colorScheme.error)
        null -> Unit
    }
    Button(onClick = onClose, modifier = Modifier.fillMaxWidth().heightIn(min = MinTouchTarget)) {
        Text(stringResource(R.string.cad_import_close))
    }
}

private const val SUMMARY_FILE = "resumen-dxf.txt"

@Composable
private fun StatusMark(drawable: Int, text: String, error: Boolean = false, tag: String? = null) {
    val color = if (error) MaterialTheme.colorScheme.error else LocalContentColor.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = if (tag != null) Modifier.testTag(tag) else Modifier,
    ) {
        Icon(painterResource(drawable), contentDescription = null, tint = color, modifier = Modifier.size(20.dp))
        Text(text, color = color)
    }
}

@StringRes
internal fun kindLabel(kind: CadEntityKind) = when (kind) {
    CadEntityKind.POINT -> R.string.cad_kind_point
    CadEntityKind.LINE -> R.string.cad_kind_line
    CadEntityKind.POLYLINE -> R.string.cad_kind_polyline
    CadEntityKind.ARC -> R.string.cad_kind_arc
    CadEntityKind.CIRCLE -> R.string.cad_kind_circle
    CadEntityKind.SPLINE -> R.string.cad_kind_spline
    CadEntityKind.BLOCK_INSERT -> R.string.cad_kind_block_insert
}

@Composable
private fun ignoredLabel(item: CadIgnored): String = ignoredText(LocalResources.current, item)

/** El texto de lo ignorado, también para el fichero del resumen (S040/RF-18). */
internal fun ignoredText(resources: Resources, item: CadIgnored): String = when (item.reason) {
    CadIgnoredReason.UNSUPPORTED_TYPE -> resources.getString(R.string.cad_ignored_unsupported, item.dxfType.orEmpty())
    CadIgnoredReason.OUTSIDE_CRS_AREA -> resources.getString(R.string.cad_ignored_outside_area)
    CadIgnoredReason.OUTSIDE_ED50_GRID -> resources.getString(R.string.cad_ignored_outside_grid)
    CadIgnoredReason.BLOCK_CYCLE -> resources.getString(R.string.cad_ignored_block_cycle)
    CadIgnoredReason.BLOCK_TOO_LARGE -> resources.getString(R.string.cad_ignored_block_large)
    CadIgnoredReason.UNDEFINED_BLOCK -> resources.getString(R.string.cad_ignored_undefined_block)
    CadIgnoredReason.INVALID_VALUE -> resources.getString(R.string.cad_ignored_invalid, item.dxfType.orEmpty())
}
