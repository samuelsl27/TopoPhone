package es.topophone.app.ui.raster

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
import es.topophone.core.formats.raster.RasterCrsDeclaration
import es.topophone.core.geodesy.catalog.CrsCatalog
import es.topophone.core.model.cad.CadSourceCrs
import es.topophone.core.model.preferences.ThemeState
import es.topophone.core.model.project.HeightSystem
import es.topophone.core.model.project.LibraryCrs
import es.topophone.core.model.project.LinearUnit
import es.topophone.core.model.project.ProjectCrs
import es.topophone.core.survey.HeightConversion
import es.topophone.data.surface.RasterImportFailure
import java.text.NumberFormat

const val RASTER_LOADING_TAG = "raster_loading"
const val RASTER_PROBLEM_TAG = "raster_problem"
const val RASTER_SCANNING_TAG = "raster_scanning"
const val RASTER_BANDS_TAG = "raster_bands"
const val RASTER_CRS_NOTE_TAG = "raster_crs_note"
const val RASTER_CRS_PROJECT_TAG = "raster_crs_project"
const val RASTER_CRS_OTHER_TAG = "raster_crs_other"
const val RASTER_HEIGHT_NOTE_TAG = "raster_height_note"
const val RASTER_DATA_TAG = "raster_data"
const val RASTER_EXTENT_TAG = "raster_extent"
const val RASTER_OUTSIDE_TAG = "raster_outside"
const val RASTER_FAR_TAG = "raster_far"
const val RASTER_FAR_CONFIRM_TAG = "raster_far_confirm"
const val RASTER_DUPLICATE_TAG = "raster_duplicate"
const val RASTER_DUPLICATE_CONFIRM_TAG = "raster_duplicate_confirm"
const val RASTER_IMPORT_BUTTON_TAG = "raster_import_button"
const val RASTER_PROGRESS_TAG = "raster_progress"
const val RASTER_CANCEL_TAG = "raster_cancel"
const val RASTER_FAILURE_TAG = "raster_failure"
const val RASTER_RESULT_TAG = "raster_result"

fun rasterSourceTag(crs: ProjectCrs) = "raster_source_${crs.name}"

fun rasterUnitTag(unit: LinearUnit) = "raster_unit_${unit.name}"

const val RASTER_HEIGHT_REASON_TAG = "raster_height_reason"

fun rasterHeightTag(system: HeightSystem) = "raster_height_${system.name}"

/** Acciones de la pantalla de importar un ráster de cotas (S045). */
class RasterImportActions(
    val onSource: (CadSourceCrs) -> Unit = {},
    val onChooseLibrary: (LibraryCrs) -> Unit = {},
    val onConfirmLibrary: (Boolean) -> Unit = {},
    val onRefreshLibrary: () -> Unit = {},
    val onUnit: (LinearUnit) -> Unit = {},
    val onHeightSystem: (HeightSystem) -> Unit = {},
    val onConfirmFar: () -> Unit = {},
    val onConfirmDuplicate: () -> Unit = {},
    val onImport: () -> Unit = {},
    val onCancel: () -> Unit = {},
)

/**
 * La importación de un ráster de cotas a un proyecto (S045), en una sola pantalla: el fichero (o por qué no se abre), el sistema de
 * coordenadas, la unidad y el sistema de alturas, los datos del ráster con su extensión y sus avisos, el progreso con «Cancelar» y el
 * resumen (RF-3 a RF-16).
 */
@Composable
fun RasterImportScreen(
    themeState: ThemeState,
    onToggleSun: () -> Unit,
    state: RasterImportUiState,
    actions: RasterImportActions,
    onBack: () -> Unit,
    libraryHost: CrsLibraryHost = HiltCrsLibraryHost,
) {
    SectionScaffold(
        title = stringResource(R.string.raster_import_title),
        themeState = themeState,
        onToggleSun = onToggleSun,
        onNavigateBack = onBack,
    ) { padding ->
        when (state.stage) {
            RasterImportStage.LOADING -> Column(
                modifier = Modifier.padding(padding).padding(16.dp).testTag(RASTER_LOADING_TAG),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                CircularProgressIndicator()
                Text(stringResource(R.string.raster_import_loading))
            }

            RasterImportStage.PROBLEM -> Column(
                modifier = Modifier.padding(padding).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(state.fileName, style = MaterialTheme.typography.titleMedium)
                Text(problemText(state.problem), color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag(RASTER_PROBLEM_TAG))
                Button(onClick = onBack, modifier = Modifier.heightIn(min = MinTouchTarget)) {
                    Text(stringResource(R.string.raster_import_close))
                }
            }

            RasterImportStage.IMPORTING -> Column(
                modifier = Modifier.padding(padding).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                val number = NumberFormat.getIntegerInstance(uiLocale())
                Text(state.fileName, style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.raster_importing))
                ProgressBar(state.importRows, state.importTotalRows.takeIf { it > 0 })
                Text(stringResource(R.string.raster_progress, number.format(state.importRows), number.format(state.importTotalRows)))
                OutlinedButton(
                    onClick = actions.onCancel,
                    modifier = Modifier.fillMaxWidth().heightIn(min = MinTouchTarget).testTag(RASTER_CANCEL_TAG),
                ) { Text(stringResource(R.string.raster_cancel)) } // RF-13
            }

            RasterImportStage.CONFIG -> Column(
                modifier = Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                ConfigSections(state, actions, themeState, onToggleSun, libraryHost)
            }

            RasterImportStage.RESULT -> Column(
                modifier = Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                ResultSection(state, onBack)
            }
        }
    }
}

@Composable
private fun problemText(problem: RasterImportFailure?): String = when (problem) {
    RasterImportFailure.EMPTY -> stringResource(R.string.raster_problem_empty)
    RasterImportFailure.NOT_A_RASTER -> stringResource(R.string.raster_problem_not_raster)
    RasterImportFailure.NOT_GEOREFERENCED -> stringResource(R.string.raster_problem_not_georeferenced)
    RasterImportFailure.BIGTIFF -> stringResource(R.string.raster_problem_bigtiff)
    RasterImportFailure.UNSUPPORTED_COMPRESSION -> stringResource(R.string.raster_problem_compression)
    RasterImportFailure.ROTATED -> stringResource(R.string.raster_problem_rotated)
    RasterImportFailure.COLOUR -> stringResource(R.string.raster_problem_colour)
    RasterImportFailure.UNSUPPORTED_DATA_TYPE -> stringResource(R.string.raster_problem_data_type)
    RasterImportFailure.TOO_LARGE -> stringResource(R.string.raster_problem_too_large)
    RasterImportFailure.TOO_WIDE -> stringResource(R.string.raster_problem_too_wide)
    RasterImportFailure.NO_DATA_CELLS -> stringResource(R.string.raster_problem_no_data)
    RasterImportFailure.WRITE_ERROR -> stringResource(R.string.raster_problem_write)
    RasterImportFailure.OUTSIDE_CRS_AREA -> stringResource(R.string.raster_failed_outside)
    RasterImportFailure.OUTSIDE_ED50_GRID -> stringResource(R.string.raster_failed_grid)
    RasterImportFailure.READ_ERROR, null -> stringResource(R.string.raster_problem_unreadable)
}

@Composable
private fun ProgressBar(done: Long, total: Long?) {
    val modifier = Modifier.fillMaxWidth().testTag(RASTER_PROGRESS_TAG)
    if (total != null && total > 0) {
        LinearProgressIndicator(progress = { (done.toFloat() / total).coerceIn(0f, 1f) }, modifier = modifier)
    } else {
        LinearProgressIndicator(modifier = modifier)
    }
}

@Composable
private fun ConfigSections(
    state: RasterImportUiState,
    actions: RasterImportActions,
    themeState: ThemeState,
    onToggleSun: () -> Unit,
    libraryHost: CrsLibraryHost,
) {
    Text(stringResource(R.string.raster_section_file), style = MaterialTheme.typography.titleSmall)
    Text(state.fileName, style = MaterialTheme.typography.titleMedium)
    if (state.scanning) {
        val copying = state.header == null
        Text(
            stringResource(if (copying) R.string.raster_copying else R.string.raster_measuring),
            modifier = Modifier.testTag(RASTER_SCANNING_TAG),
        )
        if (copying) {
            ProgressBar(state.bytesCopied, state.totalBytes)
        } else {
            ProgressBar(state.measuredRows, state.header?.rows?.toLong())
        }
    }
    state.header?.takeIf { it.bandCount > 1 }?.let {
        Text(stringResource(R.string.raster_bands, it.bandCount), modifier = Modifier.testTag(RASTER_BANDS_TAG)) // RF-6
    }
    FailureMessage(state)
    if (state.header != null) {
        CrsSection(state, actions, themeState, onToggleSun, libraryHost)
        UnitSection(state, actions)
        HeightSection(state, actions, libraryHost)
        if (!state.scanning) {
            DataSection(state)
            ExtentSection(state)
            Warnings(state, actions)
        }
    }
    Button(
        onClick = actions.onImport,
        enabled = state.canImport,
        modifier = Modifier.fillMaxWidth().heightIn(min = MinTouchTarget).testTag(RASTER_IMPORT_BUTTON_TAG),
    ) { Text(stringResource(R.string.raster_import_button)) }
}

@Composable
private fun FailureMessage(state: RasterImportUiState) {
    val text = when {
        state.cancelled -> stringResource(R.string.raster_cancelled)
        else -> when (state.failure) {
            RasterImportFailure.WRITE_ERROR -> stringResource(R.string.raster_failed_write)
            RasterImportFailure.NO_DATA_CELLS -> stringResource(R.string.raster_failed_no_data)
            RasterImportFailure.OUTSIDE_CRS_AREA -> stringResource(R.string.raster_failed_outside)
            RasterImportFailure.OUTSIDE_ED50_GRID -> stringResource(R.string.raster_failed_grid)
            null -> return
            else -> stringResource(R.string.raster_failed_read)
        }
    }
    Text(
        text,
        color = MaterialTheme.colorScheme.error,
        modifier = Modifier.testTag(RASTER_FAILURE_TAG).semantics { liveRegion = LiveRegionMode.Polite },
    )
}

/** Lo que declara el fichero, y el del proyecto u otro de los que ofrece la app (S045/RF-8). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CrsSection(
    state: RasterImportUiState,
    actions: RasterImportActions,
    themeState: ThemeState,
    onToggleSun: () -> Unit,
    libraryHost: CrsLibraryHost,
) {
    val source = state.source
    Text(stringResource(R.string.raster_section_crs), style = MaterialTheme.typography.titleSmall)
    val note = when (val declaration = state.declaration) {
        is RasterCrsDeclaration.Supported -> {
            val label = ProjectCrs.entries.firstOrNull { it.epsg == declaration.epsg }?.let { crsLabel(it) }
            if (declaration.wgs84 || label == null) {
                stringResource(R.string.raster_crs_wgs84)
            } else {
                stringResource(R.string.raster_crs_declared, label)
            }
        }
        is RasterCrsDeclaration.Library -> stringResource(R.string.raster_crs_library, declaration.name, declaration.epsg)
        is RasterCrsDeclaration.Unavailable -> stringResource(R.string.raster_crs_unavailable, declaration.epsg)
        RasterCrsDeclaration.Undeclared -> stringResource(R.string.raster_crs_undeclared)
    }
    Text(note, modifier = Modifier.testTag(RASTER_CRS_NOTE_TAG))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Choice(
            stringResource(R.string.raster_crs_project, crsLabel(state.projectCrs)),
            RASTER_CRS_PROJECT_TAG,
            source == CadSourceCrs.Project,
        ) { actions.onSource(CadSourceCrs.Project) }
        Choice(
            stringResource(R.string.raster_crs_other),
            RASTER_CRS_OTHER_TAG,
            source is CadSourceCrs.Utm || source is CadSourceCrs.Geographic,
        ) {
            if (source == CadSourceCrs.Project || source is CadSourceCrs.Library) actions.onSource(sourceOf(state.availableSources.first()))
        }
    }
    // El SC de origen puede ser cualquiera de la biblioteca, con su cambio de datum y su aviso de precisión (S057/RF-1, RF-4, RF-8 a RF-10).
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
    if (source is CadSourceCrs.Utm || source is CadSourceCrs.Geographic) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            state.availableSources.forEach { crs ->
                Choice(crsLabel(crs), rasterSourceTag(crs), isSelected(source, crs)) { actions.onSource(sourceOf(crs)) }
            }
        }
    }
}

private fun sourceOf(crs: ProjectCrs): CadSourceCrs =
    crs.utmZone?.let { CadSourceCrs.Utm(crs.datum, it) } ?: CadSourceCrs.Geographic(crs.datum)

private fun isSelected(source: CadSourceCrs, crs: ProjectCrs): Boolean = when (source) {
    is CadSourceCrs.Utm -> crs.datum == source.datum && crs.utmZone == source.zone
    is CadSourceCrs.Geographic -> crs.datum == source.datum && crs.utmZone == null
    CadSourceCrs.Project, is CadSourceCrs.Library -> false
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun UnitSection(state: RasterImportUiState, actions: RasterImportActions) {
    Text(stringResource(R.string.raster_section_unit), style = MaterialTheme.typography.titleSmall)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        LinearUnit.entries.forEach { unit ->
            Choice(stringResource(unitLabel(unit)), rasterUnitTag(unit), state.unit == unit) { actions.onUnit(unit) }
        }
    }
}

@StringRes
private fun unitLabel(unit: LinearUnit) = when (unit) {
    LinearUnit.METRE -> R.string.import_unit_metre
    LinearUnit.INTERNATIONAL_FOOT -> R.string.import_unit_foot
    LinearUnit.US_SURVEY_FOOT -> R.string.import_unit_usft
}

/** Altitud ortométrica o altura elipsoidal del ráster, y qué pasa si no es la del proyecto (S045/RF-10). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HeightSection(state: RasterImportUiState, actions: RasterImportActions, libraryHost: CrsLibraryHost) {
    Text(stringResource(R.string.raster_section_height), style = MaterialTheme.typography.titleSmall)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Choice(
            stringResource(R.string.raster_height_orthometric),
            rasterHeightTag(HeightSystem.ORTHOMETRIC),
            state.heightSystem == HeightSystem.ORTHOMETRIC,
        ) { actions.onHeightSystem(HeightSystem.ORTHOMETRIC) }
        Choice(
            stringResource(R.string.raster_height_ellipsoidal),
            rasterHeightTag(HeightSystem.ELLIPSOIDAL),
            state.heightSystem == HeightSystem.ELLIPSOIDAL,
        ) { actions.onHeightSystem(HeightSystem.ELLIPSOIDAL) }
    }
    if (state.heightSystem != state.projectHeightSystem) {
        val noGeoid = !state.scanning && state.geoidShiftM == null && !state.outsideArea
        Text(
            stringResource(if (noGeoid) R.string.raster_height_no_geoid else R.string.raster_height_convert),
            color = if (noGeoid) MaterialTheme.colorScheme.error else LocalContentColor.current,
            modifier = Modifier.testTag(RASTER_HEIGHT_NOTE_TAG),
        )
        // Un proyecto de la biblioteca sin geoide, o con el geoide sin instalar: la geometría entra y las cotas no se convierten (S057/RF-20).
        when (val conversion = state.heightConversion) {
            HeightConversion.NoProjectGeoid ->
                Text(stringResource(R.string.raster_height_no_project_geoid), modifier = Modifier.testTag(RASTER_HEIGHT_REASON_TAG))
            is HeightConversion.MissingGeoid -> {
                Text(
                    stringResource(R.string.raster_height_missing_geoid, conversion.gridName),
                    modifier = Modifier.testTag(RASTER_HEIGHT_REASON_TAG),
                )
                libraryHost.GridDownloads(listOfNotNull(CrsCatalog.bundled.grids[conversion.gridName]), actions.onRefreshLibrary)
            }
            HeightConversion.Same, HeightConversion.Available -> Unit
        }
    }
}

/** El tamaño de celda, el número de celdas con el porcentaje sin dato y las cotas (S045/RF-11). */
@Composable
private fun DataSection(state: RasterImportUiState) {
    val header = state.header ?: return
    val integer = NumberFormat.getIntegerInstance(uiLocale())
    val decimals = NumberFormat.getNumberInstance(uiLocale()).apply {
        minimumFractionDigits = 1
        maximumFractionDigits = 1
    }
    Text(stringResource(R.string.raster_section_data), style = MaterialTheme.typography.titleSmall)
    Column(modifier = Modifier.testTag(RASTER_DATA_TAG), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        val cell = if (header.geographic) {
            "${NumberFormat.getNumberInstance(uiLocale()).apply { maximumFractionDigits = 6 }.format(header.stepX)}°"
        } else {
            "${decimals.format(header.stepX)} m"
        }
        Text(stringResource(R.string.raster_info_grid, header.columns, header.rows, cell))
        Text(
            stringResource(
                R.string.raster_info_cells,
                integer.format(header.cellCount),
                decimals.format(state.noDataPercent ?: 0.0),
            ),
        )
        val min = state.minElevationM
        val max = state.maxElevationM
        if (min != null && max != null) {
            Text(stringResource(R.string.raster_info_heights, decimals.format(min), decimals.format(max)))
        } else {
            Text(stringResource(R.string.raster_info_heights_unknown), color = MaterialTheme.colorScheme.error)
        }
    }
}

/** La extensión del ráster en el sistema del proyecto y su distancia, o por qué no cabe (S045/RF-11). */
@Composable
private fun ExtentSection(state: RasterImportUiState) {
    Text(stringResource(R.string.raster_section_extent), style = MaterialTheme.typography.titleSmall)
    val extent = state.extent
    if (state.outsideArea || extent == null) {
        Text(
            stringResource(R.string.raster_outside_area),
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.testTag(RASTER_OUTSIDE_TAG),
        )
        return
    }
    val number = NumberFormat.getNumberInstance(uiLocale()).apply {
        minimumFractionDigits = 1
        maximumFractionDigits = 1
    }
    Column(modifier = Modifier.testTag(RASTER_EXTENT_TAG), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(stringResource(R.string.raster_extent_east, number.format(extent.minEasting), number.format(extent.maxEasting)))
        Text(stringResource(R.string.raster_extent_north, number.format(extent.minNorthing), number.format(extent.maxNorthing)))
        state.distanceKm?.let { Text(stringResource(R.string.raster_distance, number.format(it))) }
    }
}

/** Los avisos que piden confirmar antes de importar: un ráster lejano y un nombre de fichero ya importado (S045/RF-12, RF-15). */
@Composable
private fun Warnings(state: RasterImportUiState, actions: RasterImportActions) {
    if (state.farWarning) {
        Text(
            stringResource(R.string.raster_far_warning),
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.testTag(RASTER_FAR_TAG),
        )
        CheckRow(stringResource(R.string.raster_far_confirm), state.farConfirmed, RASTER_FAR_CONFIRM_TAG) {
            if (it) actions.onConfirmFar()
        }
    }
    if (state.duplicateName) {
        Text(
            stringResource(R.string.raster_duplicate_warning),
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.testTag(RASTER_DUPLICATE_TAG),
        )
        CheckRow(stringResource(R.string.raster_duplicate_confirm), state.duplicateConfirmed, RASTER_DUPLICATE_CONFIRM_TAG) {
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

/** Los recuentos del ráster importado, su extensión y si quedó activo (S045/RF-16, RF-17). */
@Composable
private fun ResultSection(state: RasterImportUiState, onClose: () -> Unit) {
    val outcome = state.result ?: return
    val summary = outcome.summary.summary
    val integer = NumberFormat.getIntegerInstance(uiLocale())
    val number = NumberFormat.getNumberInstance(uiLocale()).apply {
        minimumFractionDigits = 1
        maximumFractionDigits = 1
    }
    val cell = if (state.header?.geographic == true) {
        "${NumberFormat.getNumberInstance(uiLocale()).apply { maximumFractionDigits = 6 }.format(outcome.summary.stepX)}°"
    } else {
        "${number.format(outcome.summary.stepX)} m"
    }
    Text(stringResource(R.string.raster_result_title), style = MaterialTheme.typography.titleMedium)
    Text(outcome.summary.name, style = MaterialTheme.typography.titleSmall)
    val description = stringResource(
        R.string.raster_result_description,
        integer.format(summary.dataCells),
        integer.format(summary.noDataCells),
        cell,
    )
    Column(
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.testTag(RASTER_RESULT_TAG).semantics(mergeDescendants = true) {
            contentDescription = description
            liveRegion = LiveRegionMode.Polite
        },
    ) {
        StatusMark(R.drawable.ic_check, stringResource(R.string.raster_result_data, integer.format(summary.dataCells)))
        StatusMark(R.drawable.ic_check, stringResource(R.string.raster_result_nodata, integer.format(summary.noDataCells)))
        Text(stringResource(R.string.raster_result_cell, cell))
        Text(
            stringResource(R.string.raster_extent_east, number.format(summary.extent.minEasting), number.format(summary.extent.maxEasting)),
        )
        Text(
            stringResource(
                R.string.raster_extent_north,
                number.format(summary.extent.minNorthing),
                number.format(summary.extent.maxNorthing),
            ),
        )
        Text(stringResource(R.string.raster_result_heights, number.format(summary.minElevationM), number.format(summary.maxElevationM)))
        if (outcome.summary.activated) {
            StatusMark(R.drawable.ic_check, stringResource(R.string.raster_result_active))
        } else {
            Text(stringResource(R.string.raster_result_inactive))
        }
    }
    Button(onClick = onClose, modifier = Modifier.fillMaxWidth().heightIn(min = MinTouchTarget)) {
        Text(stringResource(R.string.raster_import_close))
    }
}

@Composable
private fun StatusMark(drawable: Int, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(painterResource(drawable), contentDescription = null, modifier = Modifier.size(20.dp))
        Text(text)
    }
}
