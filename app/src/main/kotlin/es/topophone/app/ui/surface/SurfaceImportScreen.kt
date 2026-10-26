package es.topophone.app.ui.surface

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
import androidx.compose.foundation.selection.selectable
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
import androidx.compose.material3.RadioButton
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
import es.topophone.app.ui.projects.crsLabel
import es.topophone.core.designsystem.component.MinTouchTarget
import es.topophone.core.model.cad.CadSourceCrs
import es.topophone.core.model.preferences.ThemeState
import es.topophone.core.model.project.LinearUnit
import es.topophone.core.model.project.ProjectCrs
import es.topophone.data.surface.SurfaceImportFailure
import java.text.NumberFormat

const val SURFACE_LOADING_TAG = "surface_loading"
const val SURFACE_PROBLEM_TAG = "surface_problem"
const val SURFACE_SCANNING_TAG = "surface_scanning"
const val SURFACE_CRS_PROJECT_TAG = "surface_crs_project"
const val SURFACE_CRS_OTHER_TAG = "surface_crs_other"
const val SURFACE_EXTENT_TAG = "surface_extent"
const val SURFACE_OUTSIDE_TAG = "surface_outside"
const val SURFACE_FAR_TAG = "surface_far"
const val SURFACE_FAR_CONFIRM_TAG = "surface_far_confirm"
const val SURFACE_DUPLICATE_TAG = "surface_duplicate"
const val SURFACE_DUPLICATE_CONFIRM_TAG = "surface_duplicate_confirm"
const val SURFACE_IMPORT_BUTTON_TAG = "surface_import_button"
const val SURFACE_PROGRESS_TAG = "surface_progress"
const val SURFACE_CANCEL_TAG = "surface_cancel"
const val SURFACE_FAILURE_TAG = "surface_failure"
const val SURFACE_RESULT_TAG = "surface_result"

fun surfaceSourceTag(crs: ProjectCrs) = "surface_source_${crs.name}"

fun surfaceUnitTag(unit: LinearUnit) = "surface_unit_${unit.name}"

fun surfaceChoiceTag(index: Int) = "surface_choice_$index"

/** Acciones de la pantalla de importar un LandXML (S042). */
class SurfaceImportActions(
    val onSelect: (Int) -> Unit = {},
    val onSource: (CadSourceCrs) -> Unit = {},
    val onUnit: (LinearUnit) -> Unit = {},
    val onConfirmFar: () -> Unit = {},
    val onConfirmDuplicate: () -> Unit = {},
    val onImport: () -> Unit = {},
    val onCancel: () -> Unit = {},
)

/**
 * La importación de un LandXML a un proyecto (S042), en una sola pantalla: el fichero (o por qué no se abre), la superficie que se
 * elige si hay varias, el sistema de coordenadas y la unidad, la extensión con sus avisos, el progreso con «Cancelar» y el resumen
 * (RF-3 a RF-10, RF-13, RF-15).
 */
@Composable
fun SurfaceImportScreen(
    themeState: ThemeState,
    onToggleSun: () -> Unit,
    state: SurfaceImportUiState,
    actions: SurfaceImportActions,
    onBack: () -> Unit,
) {
    SectionScaffold(
        title = stringResource(R.string.surface_import_title),
        themeState = themeState,
        onToggleSun = onToggleSun,
        onNavigateBack = onBack,
    ) { padding ->
        when (state.stage) {
            SurfaceImportStage.LOADING -> Column(
                modifier = Modifier.padding(padding).padding(16.dp).testTag(SURFACE_LOADING_TAG),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                CircularProgressIndicator()
                Text(stringResource(R.string.surface_import_loading))
            }

            SurfaceImportStage.PROBLEM -> Column(
                modifier = Modifier.padding(padding).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(state.fileName, style = MaterialTheme.typography.titleMedium)
                Text(problemText(state), color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag(SURFACE_PROBLEM_TAG))
                Button(onClick = onBack, modifier = Modifier.heightIn(min = MinTouchTarget)) {
                    Text(stringResource(R.string.surface_import_close))
                }
            }

            SurfaceImportStage.IMPORTING -> Column(
                modifier = Modifier.padding(padding).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(state.fileName, style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.surface_importing))
                ProgressBar(state.importBytes, state.totalBytes)
                OutlinedButton(
                    onClick = actions.onCancel,
                    modifier = Modifier.fillMaxWidth().heightIn(min = MinTouchTarget).testTag(SURFACE_CANCEL_TAG),
                ) { Text(stringResource(R.string.surface_cancel)) } // RF-10
            }

            SurfaceImportStage.CONFIG -> Column(
                modifier = Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                ConfigSections(state, actions)
            }

            SurfaceImportStage.RESULT -> Column(
                modifier = Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                ResultSection(state, onBack)
            }
        }
    }
}

@Composable
private fun problemText(state: SurfaceImportUiState): String = when (state.fileProblem) {
    SurfaceFileProblem.EMPTY -> stringResource(R.string.surface_problem_empty)
    SurfaceFileProblem.TOO_BIG -> stringResource(R.string.surface_problem_too_big)
    SurfaceFileProblem.NOT_XML -> stringResource(R.string.surface_problem_not_xml)
    SurfaceFileProblem.NOT_LANDXML -> stringResource(R.string.surface_problem_not_landxml)
    SurfaceFileProblem.UNSUPPORTED_VERSION -> stringResource(R.string.surface_problem_version, state.version.orEmpty())
    SurfaceFileProblem.NO_SURFACE -> stringResource(R.string.surface_problem_no_surface)
    SurfaceFileProblem.UNREADABLE, null -> stringResource(R.string.surface_problem_unreadable)
}

@Composable
private fun ProgressBar(read: Long, total: Long?) {
    val modifier = Modifier.fillMaxWidth().testTag(SURFACE_PROGRESS_TAG)
    if (total != null && total > 0) {
        LinearProgressIndicator(progress = { (read.toFloat() / total).coerceIn(0f, 1f) }, modifier = modifier)
    } else {
        LinearProgressIndicator(modifier = modifier)
    }
}

@Composable
private fun ConfigSections(state: SurfaceImportUiState, actions: SurfaceImportActions) {
    Text(stringResource(R.string.surface_section_file), style = MaterialTheme.typography.titleSmall)
    Text(state.fileName, style = MaterialTheme.typography.titleMedium)
    if (state.scanning) {
        Text(stringResource(R.string.surface_scanning), modifier = Modifier.testTag(SURFACE_SCANNING_TAG))
        ProgressBar(state.bytesRead, state.totalBytes)
    } else if (state.surfaces.size > 1) {
        SurfaceChoices(state, actions) // RF-5
    }
    FailureMessage(state)
    CrsSection(state, actions)
    UnitSection(state, actions)
    if (!state.scanning) {
        ExtentSection(state)
        Warnings(state, actions)
    }
    Button(
        onClick = actions.onImport,
        enabled = state.canImport,
        modifier = Modifier.fillMaxWidth().heightIn(min = MinTouchTarget).testTag(SURFACE_IMPORT_BUTTON_TAG),
    ) { Text(stringResource(R.string.surface_import_button)) }
}

/** Las superficies del fichero, de las que se elige una (S042/RF-5). */
@Composable
private fun SurfaceChoices(state: SurfaceImportUiState, actions: SurfaceImportActions) {
    Text(stringResource(R.string.surface_section_surface), style = MaterialTheme.typography.titleSmall)
    state.surfaces.forEachIndexed { index, surface ->
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = MinTouchTarget)
                .selectable(selected = state.selected == index, role = Role.RadioButton, onClick = { actions.onSelect(index) })
                .testTag(surfaceChoiceTag(index)),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(selected = state.selected == index, onClick = null)
            Text(
                stringResource(R.string.surface_choice, surface.name, surface.pointCount, surface.visibleFaceCount),
                modifier = Modifier.padding(start = 12.dp),
            )
        }
    }
}

@Composable
private fun FailureMessage(state: SurfaceImportUiState) {
    val text = when {
        state.cancelled -> stringResource(R.string.surface_cancelled)
        else -> when (state.failure) {
            SurfaceImportFailure.WRITE_ERROR -> stringResource(R.string.surface_failed_write)
            SurfaceImportFailure.NO_SURFACE -> stringResource(R.string.surface_failed_none)
            SurfaceImportFailure.OUTSIDE_CRS_AREA -> stringResource(R.string.surface_failed_outside)
            SurfaceImportFailure.OUTSIDE_ED50_GRID -> stringResource(R.string.surface_failed_grid)
            null -> return
            else -> stringResource(R.string.surface_failed_read)
        }
    }
    Text(
        text,
        color = MaterialTheme.colorScheme.error,
        modifier = Modifier.testTag(SURFACE_FAILURE_TAG).semantics { liveRegion = LiveRegionMode.Polite },
    )
}

/** El del proyecto u otro UTM de los que ofrece la app (S042/RF-6). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CrsSection(state: SurfaceImportUiState, actions: SurfaceImportActions) {
    val source = state.source
    Text(stringResource(R.string.surface_section_crs), style = MaterialTheme.typography.titleSmall)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Choice(
            stringResource(R.string.surface_crs_project, crsLabel(state.projectCrs)),
            SURFACE_CRS_PROJECT_TAG,
            source == CadSourceCrs.Project,
        ) { actions.onSource(CadSourceCrs.Project) }
        Choice(stringResource(R.string.surface_crs_other), SURFACE_CRS_OTHER_TAG, source is CadSourceCrs.Utm) {
            if (source !is CadSourceCrs.Utm) actions.onSource(sourceOf(state.availableSources.first()))
        }
    }
    if (source is CadSourceCrs.Utm) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            state.availableSources.forEach { crs ->
                Choice(crsLabel(crs), surfaceSourceTag(crs), crs.datum == source.datum && crs.utmZone == source.zone) {
                    actions.onSource(sourceOf(crs))
                }
            }
        }
    }
}

private fun sourceOf(crs: ProjectCrs): CadSourceCrs = CadSourceCrs.Utm(crs.datum, requireNotNull(crs.utmZone))

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun UnitSection(state: SurfaceImportUiState, actions: SurfaceImportActions) {
    Text(stringResource(R.string.surface_section_unit), style = MaterialTheme.typography.titleSmall)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        LinearUnit.entries.forEach { unit ->
            Choice(stringResource(unitLabel(unit)), surfaceUnitTag(unit), state.unit == unit) { actions.onUnit(unit) }
        }
    }
}

@StringRes
private fun unitLabel(unit: LinearUnit) = when (unit) {
    LinearUnit.METRE -> R.string.import_unit_metre
    LinearUnit.INTERNATIONAL_FOOT -> R.string.import_unit_foot
    LinearUnit.US_SURVEY_FOOT -> R.string.import_unit_usft
}

/** La extensión de la superficie en el sistema del proyecto, su cota y su distancia, o por qué no cabe (S042/RF-8). */
@Composable
private fun ExtentSection(state: SurfaceImportUiState) {
    Text(stringResource(R.string.surface_section_extent), style = MaterialTheme.typography.titleSmall)
    val extent = state.extent
    if (state.outsideArea || extent == null) {
        Text(
            stringResource(R.string.surface_outside_area),
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.testTag(SURFACE_OUTSIDE_TAG),
        )
        return
    }
    val number = NumberFormat.getNumberInstance().apply {
        minimumFractionDigits = 1
        maximumFractionDigits = 1
    }
    Column(modifier = Modifier.testTag(SURFACE_EXTENT_TAG), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(stringResource(R.string.surface_extent_east, number.format(extent.minEasting), number.format(extent.maxEasting)))
        Text(stringResource(R.string.surface_extent_north, number.format(extent.minNorthing), number.format(extent.maxNorthing)))
        state.chosen?.let { chosen ->
            val metres = state.unit.metres
            Text(
                stringResource(
                    R.string.surface_extent_height,
                    number.format(chosen.minHeight * metres),
                    number.format(
                        chosen.maxHeight * metres,
                    ),
                ),
            )
        }
        state.distanceKm?.let { Text(stringResource(R.string.surface_distance, number.format(it))) }
    }
}

/** Los avisos que piden confirmar antes de importar: una superficie lejana y un nombre de fichero ya importado (S042/RF-9, RF-15). */
@Composable
private fun Warnings(state: SurfaceImportUiState, actions: SurfaceImportActions) {
    if (state.farWarning) {
        Text(
            stringResource(R.string.surface_far_warning),
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.testTag(SURFACE_FAR_TAG),
        )
        CheckRow(stringResource(R.string.surface_far_confirm), state.farConfirmed, SURFACE_FAR_CONFIRM_TAG) {
            if (it) actions.onConfirmFar()
        }
    }
    if (state.duplicateName) {
        Text(
            stringResource(R.string.surface_duplicate_warning),
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.testTag(SURFACE_DUPLICATE_TAG),
        )
        CheckRow(stringResource(R.string.surface_duplicate_confirm), state.duplicateConfirmed, SURFACE_DUPLICATE_CONFIRM_TAG) {
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

/** Los recuentos de la superficie importada y si quedó activa (S042/RF-13, RF-17). */
@Composable
private fun ResultSection(state: SurfaceImportUiState, onClose: () -> Unit) {
    val outcome = state.result ?: return
    val summary = outcome.summary.summary
    val number = NumberFormat.getNumberInstance().apply {
        minimumFractionDigits = 1
        maximumFractionDigits = 1
    }
    Text(stringResource(R.string.surface_result_title), style = MaterialTheme.typography.titleMedium)
    Text(outcome.summary.name, style = MaterialTheme.typography.titleSmall)
    val description = stringResource(R.string.surface_result_description, summary.vertexCount, summary.triangleCount, summary.holeCount)
    Column(
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.testTag(SURFACE_RESULT_TAG).semantics(mergeDescendants = true) {
            contentDescription = description
            liveRegion = LiveRegionMode.Polite
        },
    ) {
        StatusMark(R.drawable.ic_check, stringResource(R.string.surface_result_points, summary.vertexCount))
        StatusMark(R.drawable.ic_check, stringResource(R.string.surface_result_triangles, summary.triangleCount))
        StatusMark(R.drawable.ic_check, stringResource(R.string.surface_result_holes, summary.holeCount))
        Text(
            stringResource(
                R.string.surface_extent_east,
                number.format(summary.extent.minEasting),
                number.format(summary.extent.maxEasting),
            ),
        )
        Text(
            stringResource(
                R.string.surface_extent_north,
                number.format(summary.extent.minNorthing),
                number.format(summary.extent.maxNorthing),
            ),
        )
        Text(stringResource(R.string.surface_result_heights, number.format(summary.minElevationM), number.format(summary.maxElevationM)))
        if (outcome.summary.droppedFaces > 0) {
            StatusMark(R.drawable.ic_warning, stringResource(R.string.surface_result_dropped, outcome.summary.droppedFaces), error = true)
        }
        if (outcome.summary.activated) {
            StatusMark(R.drawable.ic_check, stringResource(R.string.surface_result_active))
        } else {
            Text(stringResource(R.string.surface_result_inactive))
        }
    }
    Button(onClick = onClose, modifier = Modifier.fillMaxWidth().heightIn(min = MinTouchTarget)) {
        Text(stringResource(R.string.surface_import_close))
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
