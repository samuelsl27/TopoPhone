package es.topophone.app.ui.gpkg

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
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
import es.topophone.app.ui.projects.CentralPointPicker
import es.topophone.app.ui.projects.CentralPointSection
import es.topophone.app.ui.projects.MapSlot
import es.topophone.app.ui.projects.crsLabel
import es.topophone.core.designsystem.component.MinTouchTarget
import es.topophone.core.formats.gpkg.GpkgCrs
import es.topophone.core.formats.gpkg.GpkgCrsUnit
import es.topophone.core.formats.shp.ShpEncoding
import es.topophone.core.model.cad.GpkgIgnoredReason
import es.topophone.core.model.cad.GpkgLayerKind
import es.topophone.core.model.map.MapViewport
import es.topophone.core.model.preferences.ThemeState
import es.topophone.core.model.project.CentralPoint
import es.topophone.core.model.project.ProjectCrs
import es.topophone.core.survey.CentralPointRejection
import es.topophone.core.survey.CentralPointResult
import es.topophone.data.gpkg.GpkgImportFailure
import java.text.NumberFormat

const val GPKG_LOADING_TAG = "gpkg_loading"
const val GPKG_PROBLEM_TAG = "gpkg_problem"
const val GPKG_LAYERS_TAG = "gpkg_layers"
const val GPKG_LEFT_OUT_TAG = "gpkg_left_out"
const val GPKG_NO_LAYER_TAG = "gpkg_no_layer"
const val GPKG_IMPORT_BUTTON_TAG = "gpkg_import_button"
const val GPKG_SCANNING_TAG = "gpkg_scanning"
const val GPKG_FAR_TAG = "gpkg_far"
const val GPKG_FAR_CONFIRM_TAG = "gpkg_far_confirm"
const val GPKG_ZONE_RADIUS_TAG = "gpkg_zone_radius"
const val GPKG_ZONE_ALL_TAG = "gpkg_zone_all"
const val GPKG_RADIUS_VALUE_TAG = "gpkg_radius_value"
const val GPKG_RADIUS_SLIDER_TAG = "gpkg_radius_slider"
const val GPKG_ZONE_COUNT_TAG = "gpkg_zone_count"
const val GPKG_ZONE_COUNTING_TAG = "gpkg_zone_counting"
const val GPKG_ZONE_LIMIT_TAG = "gpkg_zone_limit"
const val GPKG_NO_CENTER_TAG = "gpkg_no_center"
const val GPKG_ELEVATIONS_TAG = "gpkg_elevations"
const val GPKG_PROGRESS_TAG = "gpkg_progress"
const val GPKG_CANCEL_TAG = "gpkg_cancel"
const val GPKG_FAILURE_TAG = "gpkg_failure"
const val GPKG_RESULT_TAG = "gpkg_result"
const val GPKG_SUMMARY_SAVE_TAG = "gpkg_summary_save"
const val GPKG_SUMMARY_SHARE_TAG = "gpkg_summary_share"

fun gpkgNameFieldTag(table: String, column: String?) = "gpkg_name_${table}_${column ?: "none"}"

fun gpkgResultLayerTag(table: String) = "gpkg_result_layer_$table"

fun gpkgLayerTag(table: String) = "gpkg_layer_$table"

fun gpkgCrsTag(table: String) = "gpkg_crs_$table"

/** La etiqueta de la opción de codificación [encoding] del `.dbf` de la capa [table] (S030/RF-18). */
fun shpEncodingTag(table: String, encoding: ShpEncoding) = "shp_encoding_${table}_${encoding.name}"

/** El aviso de que el `.cpg` de la capa declara una codificación que no se conoce (S030/RF-18). */
fun shpCpgUnknownTag(table: String) = "shp_cpg_unknown_$table"

fun gpkgCrsChangeTag(table: String) = "gpkg_crs_change_$table"

fun gpkgCrsChoiceTag(table: String, crs: ProjectCrs) = "gpkg_crs_choice_${table}_${crs.name}"

fun gpkgUnitTag(table: String) = "gpkg_unit_$table"

fun gpkgExtentTag(table: String) = "gpkg_extent_$table"

fun gpkgOutsideTag(table: String) = "gpkg_outside_$table"

/** Acciones de la pantalla de importar un GeoPackage (S048). */
class GpkgImportActions(
    val onMarked: (table: String, marked: Boolean) -> Unit = { _, _ -> },
    val onCrs: (table: String, crs: ProjectCrs?) -> Unit = { _, _ -> },
    val onConfirmFar: () -> Unit = {},
    val onAllContent: (Boolean) -> Unit = {},
    val onRadius: (Double) -> Unit = {},
    /** Este y Norte, o latitud y longitud, tal como se escribieron (S006/RF-33). */
    val onCentralTyped: (String, String) -> Unit = { _, _ -> },
    val onCentralRemove: () -> Unit = {},
    val onCentralPosition: () -> Unit = {},
    val onCentralTap: (Double, Double) -> CentralPointResult = { _, _ -> CentralPointResult.Rejected(CentralPointRejection.NO_POSITION) },
    val onCentralPicked: (CentralPoint) -> Unit = {},
    val pickerStart: () -> MapViewport? = { null },
    val onNameField: (table: String, field: String?) -> Unit = { _, _ -> },
    val onUseElevations: (Boolean) -> Unit = {},
    /** Cambia la codificación con la que se lee el `.dbf` de una capa de Shapefile (S030/RF-18). */
    val onEncoding: (table: String, encoding: ShpEncoding) -> Unit = { _, _ -> },
    val onImport: () -> Unit = {},
    val onCancel: () -> Unit = {},
    val onSaveSummary: (fileName: String) -> Unit = {},
    val onShareSummary: (fileName: String) -> Unit = {},
)

/**
 * La importación de un GeoPackage a un proyecto (S048): el fichero (o por qué no se abre), la lista de sus capas con geometría para
 * marcar cuáles se importan, el sistema de coordenadas de cada una, su extensión y distancia al proyecto, y el aviso de más de 100 km
 * (RF-2 a RF-12).
 */
@Composable
fun GpkgImportScreen(
    themeState: ThemeState,
    onToggleSun: () -> Unit,
    state: GpkgImportUiState,
    actions: GpkgImportActions,
    onBack: () -> Unit,
    map: MapSlot? = null,
    summaryOutcome: ReportOutcome? = null,
) {
    // S048/RF-14: el selector de S006 a pantalla completa, para fijar el punto central sin salir de la importación.
    var picking by rememberSaveable { mutableStateOf(false) }
    if (picking && map != null) {
        CentralPointPicker(
            themeState,
            onToggleSun,
            map,
            state.mapSettings,
            start = remember { actions.pickerStart() },
            current = state.centralPoint,
            resolve = actions.onCentralTap,
            onAccept = {
                actions.onCentralPicked(it)
                picking = false
            },
            onCancel = { picking = false },
        )
        return
    }
    SectionScaffold(
        title = stringResource(if (state.format == ImportFormat.SHAPEFILE) R.string.shp_import_title else R.string.gpkg_import_title),
        themeState = themeState,
        onToggleSun = onToggleSun,
        onNavigateBack = onBack,
    ) { padding ->
        when (state.stage) {
            GpkgImportStage.LOADING -> Column(
                modifier = Modifier.padding(padding).padding(16.dp).testTag(GPKG_LOADING_TAG),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                CircularProgressIndicator()
                Text(stringResource(R.string.gpkg_import_loading))
            }
            GpkgImportStage.PROBLEM -> Column(
                modifier = Modifier.padding(padding).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(state.fileName, style = MaterialTheme.typography.titleMedium)
                Text(
                    problemText(state.problem, state.format),
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.testTag(GPKG_PROBLEM_TAG),
                ) // RF-3, RF-4
                Button(onClick = onBack, modifier = Modifier.heightIn(min = MinTouchTarget)) {
                    Text(stringResource(R.string.gpkg_import_close))
                }
            }
            GpkgImportStage.IMPORTING -> Column(
                modifier = Modifier.padding(padding).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(state.fileName, style = MaterialTheme.typography.titleMedium)
                Text(stringResource(if (state.format == ImportFormat.SHAPEFILE) R.string.shp_importing else R.string.gpkg_importing))
                ProgressBar(state.importDone, state.importTotal)
                OutlinedButton(
                    onClick = actions.onCancel,
                    modifier = Modifier.fillMaxWidth().heightIn(min = MinTouchTarget).testTag(GPKG_CANCEL_TAG),
                ) { Text(stringResource(R.string.gpkg_cancel)) } // RF-29
            }
            GpkgImportStage.RESULT -> Column(
                modifier = Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                ResultSection(state, actions, summaryOutcome, onBack)
            }
            GpkgImportStage.CONFIG -> Column(
                modifier = Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                ConfigSections(state, actions, onPick = map?.let { { picking = true } })
            }
        }
    }
}

@Composable
private fun problemText(problem: GpkgFileProblem?, format: ImportFormat): String = when (problem) {
    GpkgFileProblem.EMPTY -> stringResource(R.string.gpkg_problem_empty)
    GpkgFileProblem.NOT_GEOPACKAGE -> stringResource(R.string.gpkg_problem_not_gpkg)
    GpkgFileProblem.NOT_SHAPEFILE -> stringResource(R.string.shp_problem_not_shapefile)
    GpkgFileProblem.NO_SHP -> stringResource(R.string.shp_problem_no_shp)
    GpkgFileProblem.MIXED_SELECTION -> stringResource(R.string.shp_problem_mixed)
    GpkgFileProblem.DAMAGED ->
        stringResource(if (format == ImportFormat.SHAPEFILE) R.string.shp_problem_damaged else R.string.gpkg_problem_damaged)
    GpkgFileProblem.NO_LAYERS -> stringResource(R.string.gpkg_problem_no_layers)
    GpkgFileProblem.TOO_BIG -> stringResource(R.string.gpkg_problem_too_big)
    GpkgFileProblem.NO_SPACE -> stringResource(R.string.gpkg_problem_no_space)
    GpkgFileProblem.UNREADABLE, null -> stringResource(R.string.gpkg_problem_unreadable)
}

@Composable
private fun ConfigSections(state: GpkgImportUiState, actions: GpkgImportActions, onPick: (() -> Unit)?) {
    Text(stringResource(R.string.gpkg_section_file), style = MaterialTheme.typography.titleSmall)
    Text(state.fileName, style = MaterialTheme.typography.titleMedium)
    Text(pluralStringResource(R.plurals.gpkg_layer_count, state.layers.size, state.layers.size))
    if (state.leftOutTables.isNotEmpty()) {
        Text(
            pluralStringResource(
                if (state.format == ImportFormat.SHAPEFILE) R.plurals.shp_left_out else R.plurals.gpkg_left_out,
                state.leftOutTables.size,
                state.leftOutTables.size,
                state.leftOutTables.joinToString(", "),
            ),
            modifier = Modifier.testTag(GPKG_LEFT_OUT_TAG),
        ) // RF-6
    }
    FailureMessage(state)
    Text(stringResource(R.string.gpkg_section_layers), style = MaterialTheme.typography.titleSmall)
    if (state.scanning) {
        Text(stringResource(R.string.gpkg_scanning), modifier = Modifier.testTag(GPKG_SCANNING_TAG))
    }
    Column(modifier = Modifier.testTag(GPKG_LAYERS_TAG), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        state.layers.forEach { row -> LayerRow(row, state.scanning, state.format, actions) }
    }
    if (state.markedLayers.isEmpty()) {
        Text(
            stringResource(R.string.gpkg_no_layer_marked),
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.testTag(GPKG_NO_LAYER_TAG),
        )
    }
    CheckRow(stringResource(R.string.gpkg_use_elevations), state.useElevations, GPKG_ELEVATIONS_TAG, actions.onUseElevations) // RF-19
    ZoneSection(state, actions, onPick)
    if (state.farWarning) {
        Text(
            stringResource(R.string.gpkg_far_warning),
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.testTag(GPKG_FAR_TAG),
        ) // RF-12
        CheckRow(stringResource(R.string.gpkg_far_confirm), state.farConfirmed, GPKG_FAR_CONFIRM_TAG) { if (it) actions.onConfirmFar() }
    }
    Button(
        onClick = actions.onImport,
        enabled = state.canImport,
        modifier = Modifier.fillMaxWidth().heightIn(min = MinTouchTarget).testTag(GPKG_IMPORT_BUTTON_TAG),
    ) { Text(stringResource(R.string.gpkg_import_button)) }
}

@Composable
private fun LayerRow(row: GpkgLayerRow, scanning: Boolean, format: ImportFormat, actions: GpkgImportActions) {
    val info = row.info
    val kind = stringResource(
        when (info.kind) {
            GpkgLayerKind.POINTS -> R.string.gpkg_kind_points
            GpkgLayerKind.LINES -> R.string.gpkg_kind_lines
            GpkgLayerKind.POLYGONS -> R.string.gpkg_kind_polygons
            GpkgLayerKind.MIXED -> R.string.gpkg_kind_mixed
        },
    )
    val count = pluralStringResource(
        R.plurals.gpkg_object_count,
        info.featureCount.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
        NumberFormat.getIntegerInstance().format(info.featureCount),
    )
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = MinTouchTarget)
                .toggleable(value = row.marked, role = Role.Checkbox, onValueChange = { actions.onMarked(info.table, it) })
                .testTag(gpkgLayerTag(info.table)),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Checkbox(checked = row.marked, onCheckedChange = null)
            Column(modifier = Modifier.weight(1f)) {
                Text(info.table, style = MaterialTheme.typography.bodyLarge)
                Text(stringResource(R.string.gpkg_layer_detail, kind, count), style = MaterialTheme.typography.bodySmall)
            }
        }
        if (row.marked) {
            CrsBlock(row, format, actions)
            if (row.encoding != null) EncodingBlock(row, row.encoding, actions)
            NameFieldBlock(row, actions)
            if (!scanning) ExtentBlock(row)
        }
    }
}

/** El sistema de coordenadas de la capa, su unidad (la fija el sistema) y cómo elegir otro (S048/RF-8 a RF-10). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CrsBlock(row: GpkgLayerRow, format: ImportFormat, actions: GpkgImportActions) {
    val table = row.info.table
    val effective = row.effective
    var changing by rememberSaveable(table) { mutableStateOf(false) }
    Column(modifier = Modifier.padding(start = 40.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (effective == null) {
            Text(
                undeclaredText(row.crs, format),
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.testTag(gpkgCrsTag(table)),
            ) // RF-9
        } else {
            Text(
                stringResource(R.string.gpkg_crs_label, systemLabel(effective, row.chosen != null)),
                modifier = Modifier.testTag(gpkgCrsTag(table)),
            ) // RF-8
            Text(
                stringResource(if (effective.unit == GpkgCrsUnit.METRE) R.string.gpkg_unit_metres else R.string.gpkg_unit_degrees),
                modifier = Modifier.testTag(gpkgUnitTag(table)),
            ) // RF-10
            if (effective.wgs84 && row.chosen == null) {
                Text(stringResource(R.string.gpkg_crs_wgs84_notice), style = MaterialTheme.typography.bodySmall)
            }
            OutlinedButton(
                onClick = { changing = !changing },
                modifier = Modifier.heightIn(min = MinTouchTarget).testTag(gpkgCrsChangeTag(table)),
            ) { Text(stringResource(R.string.gpkg_crs_change)) }
        }
        if (effective == null || changing) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ProjectCrs.entries.forEach { crs ->
                    FilterChip(
                        selected = row.chosen == crs,
                        onClick = { actions.onCrs(table, if (row.chosen == crs) null else crs) },
                        label = { Text(crsLabel(crs)) },
                        modifier = Modifier.heightIn(min = MinTouchTarget).testTag(gpkgCrsChoiceTag(table, crs)),
                    )
                }
            }
        }
    }
}

@Composable
private fun undeclaredText(crs: GpkgCrs, format: ImportFormat): String = when {
    format == ImportFormat.SHAPEFILE && crs is GpkgCrs.Unavailable ->
        stringResource(R.string.shp_crs_unavailable, crs.name ?: "${crs.authority}:${crs.code}") // S030/RF-9: el nombre que trae el .prj
    format == ImportFormat.SHAPEFILE -> stringResource(R.string.shp_crs_undeclared)
    crs is GpkgCrs.Unavailable -> stringResource(R.string.gpkg_crs_unavailable, "${crs.authority}:${crs.code}")
    else -> stringResource(R.string.gpkg_crs_undeclared)
}

/** El sistema propuesto o elegido, con su nombre en la app; el 4326 se dice como WGS 84 (S048/RF-8). */
@Composable
private fun systemLabel(crs: GpkgCrs.Supported, chosen: Boolean): String {
    val project = ProjectCrs.entries.firstOrNull { it.epsg == crs.epsg }
    return when {
        crs.wgs84 && !chosen -> stringResource(R.string.gpkg_crs_wgs84)
        project != null -> crsLabel(project)
        else -> "EPSG:${crs.epsg}"
    }
}

/** La extensión de la capa en el sistema del proyecto y su distancia al centro, o por qué no cabe (S048/RF-12). */
@Composable
private fun ExtentBlock(row: GpkgLayerRow) {
    val table = row.info.table
    if (row.outsideArea) {
        Text(
            stringResource(R.string.gpkg_outside_area),
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(start = 40.dp).testTag(gpkgOutsideTag(table)),
        )
        return
    }
    val extent = row.extent ?: return
    val number = NumberFormat.getNumberInstance().apply {
        minimumFractionDigits = 1
        maximumFractionDigits = 1
    }
    Column(modifier = Modifier.padding(start = 40.dp).testTag(gpkgExtentTag(table)), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(stringResource(R.string.cad_extent_east, number.format(extent.minEasting), number.format(extent.maxEasting)))
        Text(stringResource(R.string.cad_extent_north, number.format(extent.minNorthing), number.format(extent.maxNorthing)))
        row.distanceKm?.let { Text(stringResource(R.string.cad_distance, number.format(it))) }
    }
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

/** El radio de la zona en un control continuo de 100 m a 100 km, en escala logarítmica, y su valor redondeado (S048/RF-13). */
object GpkgRadius {
    private const val MIN = 100.0
    private const val MAX = 100_000.0

    /** De la posición del control (0 a 1) al radio en metros, redondeado a 10 m bajo 1 km, 100 m bajo 10 km y 1 km desde ahí. */
    fun fromPosition(position: Float): Double {
        val raw = MIN * Math.pow(MAX / MIN, position.coerceIn(0f, 1f).toDouble())
        val step = when {
            raw < 1_000.0 -> 10.0
            raw < 10_000.0 -> 100.0
            else -> 1_000.0
        }
        return (Math.round(raw / step) * step).coerceIn(MIN, MAX)
    }

    fun toPosition(radiusM: Double): Float = (Math.log(radiusM.coerceIn(MIN, MAX) / MIN) / Math.log(MAX / MIN)).toFloat()
}

/** La zona: un radio alrededor del centro del proyecto o todo el contenido, cuántos objetos entran y el punto central (S048/RF-13 a RF-16). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ZoneSection(state: GpkgImportUiState, actions: GpkgImportActions, onPick: (() -> Unit)?) {
    Text(stringResource(R.string.gpkg_section_zone), style = MaterialTheme.typography.titleSmall)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(
            selected = !state.areaAll,
            onClick = { actions.onAllContent(false) },
            label = { Text(stringResource(R.string.gpkg_zone_radius)) },
            modifier = Modifier.heightIn(min = MinTouchTarget).testTag(GPKG_ZONE_RADIUS_TAG),
        )
        FilterChip(
            selected = state.areaAll,
            onClick = { actions.onAllContent(true) },
            label = { Text(stringResource(R.string.gpkg_zone_all)) },
            modifier = Modifier.heightIn(min = MinTouchTarget).testTag(GPKG_ZONE_ALL_TAG),
        )
    }
    if (!state.areaAll) {
        val metres = state.radiusM
        Text(
            if (metres < 1_000.0) {
                stringResource(R.string.gpkg_radius_m, NumberFormat.getIntegerInstance().format(metres))
            } else {
                stringResource(
                    R.string.gpkg_radius_km,
                    NumberFormat.getNumberInstance().apply { maximumFractionDigits = 1 }.format(
                        metres / 1_000.0,
                    ),
                )
            },
            modifier = Modifier.testTag(GPKG_RADIUS_VALUE_TAG),
        )
        Slider(
            value = GpkgRadius.toPosition(metres),
            onValueChange = { actions.onRadius(GpkgRadius.fromPosition(it)) },
            modifier = Modifier.fillMaxWidth().heightIn(min = MinTouchTarget).testTag(GPKG_RADIUS_SLIDER_TAG),
        )
        if (state.needsCenter) {
            Text(
                stringResource(R.string.gpkg_no_center),
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.testTag(GPKG_NO_CENTER_TAG),
            ) // RF-14
        }
        CentralPointSection(
            crs = state.projectCrs,
            linearUnit = state.linearUnit,
            angularUnit = state.angularUnit,
            point = state.centralPoint,
            error = state.centralError,
            onTyped = actions.onCentralTyped,
            onRemove = actions.onCentralRemove,
            onPosition = actions.onCentralPosition,
            onPick = onPick,
        )
    }
    ZoneCount(state)
}

@Composable
private fun ZoneCount(state: GpkgImportUiState) {
    if (state.needsCenter) return
    when {
        state.zone.counting -> Text(stringResource(R.string.gpkg_zone_counting), modifier = Modifier.testTag(GPKG_ZONE_COUNTING_TAG))
        state.zone.exceeded -> Text(
            stringResource(R.string.gpkg_zone_limit),
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.testTag(GPKG_ZONE_LIMIT_TAG).semantics { liveRegion = LiveRegionMode.Polite },
        ) // RF-16
        else -> Text(
            stringResource(R.string.gpkg_zone_count, NumberFormat.getIntegerInstance().format(state.zone.objects)),
            modifier = Modifier.testTag(GPKG_ZONE_COUNT_TAG).semantics { liveRegion = LiveRegionMode.Polite },
        )
    }
}

@Composable
private fun ProgressBar(done: Long, total: Long) {
    val modifier = Modifier.fillMaxWidth().testTag(GPKG_PROGRESS_TAG)
    if (total > 0) {
        LinearProgressIndicator(progress = { (done.toFloat() / total).coerceIn(0f, 1f) }, modifier = modifier)
    } else {
        LinearProgressIndicator(modifier = modifier)
    }
}

/** La codificación con la que se lee el `.dbf` de la capa, UTF-8 o Windows-1252, y el aviso de un `.cpg` desconocido (S030/RF-18). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EncodingBlock(row: GpkgLayerRow, encoding: ShpEncoding, actions: GpkgImportActions) {
    val table = row.info.table
    Column(modifier = Modifier.padding(start = 40.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(R.string.shp_encoding_label), style = MaterialTheme.typography.labelLarge)
        if (row.cpgUnknown) {
            Text(
                stringResource(R.string.shp_encoding_unknown),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.testTag(shpCpgUnknownTag(table)),
            )
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ShpEncoding.entries.forEach { option ->
                FilterChip(
                    selected = encoding == option,
                    onClick = { actions.onEncoding(table, option) },
                    label = { Text(option.label) },
                    modifier = Modifier.heightIn(min = MinTouchTarget).testTag(shpEncodingTag(table, option)),
                )
            }
        }
    }
}

/** El campo con el que se nombra cada objeto de la capa, entre sus columnas, o ninguno (S048/RF-21, RF-22). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun NameFieldBlock(row: GpkgLayerRow, actions: GpkgImportActions) {
    val table = row.info.table
    Column(modifier = Modifier.padding(start = 40.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(R.string.gpkg_name_field), style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = row.nameField == null,
                onClick = { actions.onNameField(table, null) },
                label = { Text(stringResource(R.string.gpkg_name_none)) },
                modifier = Modifier.heightIn(min = MinTouchTarget).testTag(gpkgNameFieldTag(table, null)),
            )
            row.info.columns.forEach { column ->
                FilterChip(
                    selected = row.nameField == column.name,
                    onClick = { actions.onNameField(table, column.name) },
                    label = { Text(column.name) },
                    modifier = Modifier.heightIn(min = MinTouchTarget).testTag(gpkgNameFieldTag(table, column.name)),
                )
            }
        }
    }
}

@Composable
private fun FailureMessage(state: GpkgImportUiState) {
    val text = when {
        state.cancelled -> stringResource(R.string.gpkg_cancelled)
        state.failure == GpkgImportFailure.WRITE_ERROR -> stringResource(R.string.gpkg_failed_write)
        state.failure == GpkgImportFailure.READ_ERROR -> stringResource(R.string.gpkg_failed_read)
        state.failure == GpkgImportFailure.NO_OBJECTS -> stringResource(R.string.gpkg_failed_none)
        state.failure == GpkgImportFailure.TOO_MANY_OBJECTS -> stringResource(R.string.gpkg_failed_too_many)
        else -> return
    }
    Text(
        text,
        color = MaterialTheme.colorScheme.error,
        modifier = Modifier.testTag(GPKG_FAILURE_TAG).semantics { liveRegion = LiveRegionMode.Polite },
    ) // RF-30
}

/** Lo importado, fuera de la zona e ignorado por capa, con el resumen para guardar o compartir (S048/RF-25). */
@Composable
private fun ResultSection(state: GpkgImportUiState, actions: GpkgImportActions, summaryOutcome: ReportOutcome?, onClose: () -> Unit) {
    val summary = state.result?.summary ?: return
    Text(stringResource(R.string.gpkg_result_title), style = MaterialTheme.typography.titleMedium)
    Text(state.fileName, style = MaterialTheme.typography.titleSmall)
    val description = stringResource(R.string.gpkg_result_description, summary.importedTotal, summary.ignoredTotal, summary.layers.size)
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.testTag(GPKG_RESULT_TAG).semantics(mergeDescendants = true) {
            contentDescription = description
            liveRegion = LiveRegionMode.Polite
        },
    ) {
        Text(stringResource(R.string.gpkg_result_imported, summary.importedTotal), style = MaterialTheme.typography.titleSmall)
        summary.layers.forEach { layer ->
            Column(modifier = Modifier.testTag(gpkgResultLayerTag(layer.table)), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(layer.table, style = MaterialTheme.typography.bodyLarge)
                Text(
                    stringResource(R.string.gpkg_label_count, stringResource(R.string.gpkg_summary_imported), layer.imported),
                    Modifier.padding(start = 16.dp),
                )
                Text(
                    stringResource(R.string.gpkg_label_count, stringResource(R.string.gpkg_summary_outside), layer.outsideArea),
                    Modifier.padding(start = 16.dp),
                )
                Text(
                    stringResource(R.string.gpkg_label_count, stringResource(R.string.gpkg_summary_ignored), layer.ignoredTotal),
                    Modifier.padding(start = 16.dp),
                    color = if (layer.ignoredTotal > 0) MaterialTheme.colorScheme.error else LocalContentColor.current,
                )
                layer.ignored.entries.sortedBy { it.key.ordinal }.forEach { (reason, count) ->
                    Text(
                        stringResource(R.string.gpkg_label_count, stringResource(reasonLabel(reason)), count),
                        Modifier.padding(start = 32.dp),
                    )
                }
                if (layer.omittedAttributes > 0) {
                    Text(
                        stringResource(
                            R.string.gpkg_label_count,
                            stringResource(R.string.gpkg_summary_omitted_short),
                            layer.omittedAttributes,
                        ),
                        Modifier.padding(start = 16.dp),
                    )
                }
                if (layer.withoutAttributes > 0) { // S030/RF-20
                    Text(
                        stringResource(
                            R.string.gpkg_label_count,
                            stringResource(R.string.shp_summary_without_attributes),
                            layer.withoutAttributes,
                        ),
                        Modifier.padding(start = 16.dp),
                    )
                }
            }
        }
        if (summary.leftOutTables.isNotEmpty()) {
            val leftOut = if (state.format == ImportFormat.SHAPEFILE) R.string.shp_result_left_out else R.string.gpkg_result_left_out
            Text(stringResource(leftOut, summary.leftOutTables.joinToString(", ")))
        }
        if (summary.wgs84Layers.isNotEmpty()) {
            Text(stringResource(R.string.gpkg_result_wgs84, summary.wgs84Layers.joinToString(", ")))
        }
    }
    OutlinedButton(
        onClick = { actions.onSaveSummary(summaryFile(state.format)) },
        modifier = Modifier.fillMaxWidth().heightIn(min = MinTouchTarget).testTag(GPKG_SUMMARY_SAVE_TAG),
    ) { Text(stringResource(R.string.gpkg_summary_save)) }
    OutlinedButton(
        onClick = { actions.onShareSummary(summaryFile(state.format)) },
        modifier = Modifier.fillMaxWidth().heightIn(min = MinTouchTarget).testTag(GPKG_SUMMARY_SHARE_TAG),
    ) { Text(stringResource(R.string.gpkg_summary_share)) }
    when (summaryOutcome) {
        ReportOutcome.SAVED -> Text(stringResource(R.string.gpkg_summary_saved))
        ReportOutcome.FAILED -> Text(stringResource(R.string.gpkg_summary_failed), color = MaterialTheme.colorScheme.error)
        null -> Unit
    }
    Button(onClick = onClose, modifier = Modifier.fillMaxWidth().heightIn(min = MinTouchTarget)) {
        Text(stringResource(R.string.gpkg_import_close))
    }
}

private fun summaryFile(format: ImportFormat) = if (format == ImportFormat.SHAPEFILE) "resumen-shapefile.txt" else "resumen-geopackage.txt"

/** El texto del motivo de ignorado de un objeto, de `strings.xml` (S048/RF-11, RF-18). */
internal fun reasonLabel(reason: GpkgIgnoredReason): Int = when (reason) {
    GpkgIgnoredReason.EMPTY_GEOMETRY -> R.string.gpkg_reason_empty
    GpkgIgnoredReason.CURVE_GEOMETRY -> R.string.gpkg_reason_curve
    GpkgIgnoredReason.INVALID_GEOMETRY -> R.string.gpkg_reason_invalid
    GpkgIgnoredReason.OUTSIDE_CRS_AREA -> R.string.gpkg_reason_outside_crs
    GpkgIgnoredReason.OUTSIDE_ED50_GRID -> R.string.gpkg_reason_outside_grid
    GpkgIgnoredReason.DELETED_RECORD -> R.string.gpkg_reason_deleted
    GpkgIgnoredReason.MULTIPATCH -> R.string.gpkg_reason_multipatch
    GpkgIgnoredReason.NETWORK_LINK -> R.string.gpkg_reason_network_link
    GpkgIgnoredReason.OVERLAY -> R.string.gpkg_reason_overlay
    GpkgIgnoredReason.MODEL -> R.string.gpkg_reason_model
    GpkgIgnoredReason.TRACK -> R.string.gpkg_reason_track
    GpkgIgnoredReason.TOUR -> R.string.gpkg_reason_tour
}
