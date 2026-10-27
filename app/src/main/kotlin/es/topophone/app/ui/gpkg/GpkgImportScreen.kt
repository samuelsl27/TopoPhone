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
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import es.topophone.app.R
import es.topophone.app.ui.SectionScaffold
import es.topophone.app.ui.projects.CentralPointPicker
import es.topophone.app.ui.projects.CentralPointSection
import es.topophone.app.ui.projects.MapSlot
import es.topophone.app.ui.projects.crsLabel
import es.topophone.core.designsystem.component.MinTouchTarget
import es.topophone.core.formats.gpkg.GpkgCrs
import es.topophone.core.formats.gpkg.GpkgCrsUnit
import es.topophone.core.model.cad.GpkgLayerKind
import es.topophone.core.model.map.MapViewport
import es.topophone.core.model.preferences.ThemeState
import es.topophone.core.model.project.CentralPoint
import es.topophone.core.model.project.ProjectCrs
import es.topophone.core.survey.CentralPointRejection
import es.topophone.core.survey.CentralPointResult
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

fun gpkgLayerTag(table: String) = "gpkg_layer_$table"

fun gpkgCrsTag(table: String) = "gpkg_crs_$table"

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
    val onImport: () -> Unit = {},
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
        title = stringResource(R.string.gpkg_import_title),
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
                    problemText(state.problem),
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.testTag(GPKG_PROBLEM_TAG),
                ) // RF-3, RF-4
                Button(onClick = onBack, modifier = Modifier.heightIn(min = MinTouchTarget)) {
                    Text(stringResource(R.string.gpkg_import_close))
                }
            }
            else -> Column(
                modifier = Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                ConfigSections(state, actions, onPick = map?.let { { picking = true } })
            }
        }
    }
}

@Composable
private fun problemText(problem: GpkgFileProblem?): String = when (problem) {
    GpkgFileProblem.EMPTY -> stringResource(R.string.gpkg_problem_empty)
    GpkgFileProblem.NOT_GEOPACKAGE -> stringResource(R.string.gpkg_problem_not_gpkg)
    GpkgFileProblem.DAMAGED -> stringResource(R.string.gpkg_problem_damaged)
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
                R.plurals.gpkg_left_out,
                state.leftOutTables.size,
                state.leftOutTables.size,
                state.leftOutTables.joinToString(", "),
            ),
            modifier = Modifier.testTag(GPKG_LEFT_OUT_TAG),
        ) // RF-6
    }
    Text(stringResource(R.string.gpkg_section_layers), style = MaterialTheme.typography.titleSmall)
    if (state.scanning) {
        Text(stringResource(R.string.gpkg_scanning), modifier = Modifier.testTag(GPKG_SCANNING_TAG))
    }
    Column(modifier = Modifier.testTag(GPKG_LAYERS_TAG), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        state.layers.forEach { row -> LayerRow(row, state.scanning, actions) }
    }
    if (state.markedLayers.isEmpty()) {
        Text(
            stringResource(R.string.gpkg_no_layer_marked),
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.testTag(GPKG_NO_LAYER_TAG),
        )
    }
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
private fun LayerRow(row: GpkgLayerRow, scanning: Boolean, actions: GpkgImportActions) {
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
            CrsBlock(row, actions)
            if (!scanning) ExtentBlock(row)
        }
    }
}

/** El sistema de coordenadas de la capa, su unidad (la fija el sistema) y cómo elegir otro (S048/RF-8 a RF-10). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CrsBlock(row: GpkgLayerRow, actions: GpkgImportActions) {
    val table = row.info.table
    val effective = row.effective
    var changing by rememberSaveable(table) { mutableStateOf(false) }
    Column(modifier = Modifier.padding(start = 40.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (effective == null) {
            Text(
                undeclaredText(row.crs),
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
private fun undeclaredText(crs: GpkgCrs): String = when (crs) {
    is GpkgCrs.Unavailable -> stringResource(R.string.gpkg_crs_unavailable, "${crs.authority}:${crs.code}")
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
