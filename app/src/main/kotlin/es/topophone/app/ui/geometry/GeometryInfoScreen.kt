package es.topophone.app.ui.geometry

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import es.topophone.app.R
import es.topophone.app.ui.SectionScaffold
import es.topophone.app.ui.projects.unitSymbol
import es.topophone.app.ui.receiver.uiLocale
import es.topophone.core.designsystem.component.MinTouchTarget
import es.topophone.core.model.format.AngleFormat
import es.topophone.core.model.format.AreaFormat
import es.topophone.core.model.format.LengthFormat
import es.topophone.core.model.format.SlopeFormat
import es.topophone.core.model.geometry.DistanceMode
import es.topophone.core.model.preferences.ThemeState
import es.topophone.core.survey.geometry.GeometryNotice
import es.topophone.core.survey.geometry.SegmentInfo

const val GEOMETRY_INFO_TAG = "geometry_info"
const val GEOMETRY_MODE_GRID_TAG = "geometry_mode_grid"
const val GEOMETRY_MODE_GROUND_TAG = "geometry_mode_ground"
const val GEOMETRY_ACTIVE_MODE_TAG = "geometry_active_mode"
const val GEOMETRY_LENGTH_2D_TAG = "geometry_length_2d"
const val GEOMETRY_LENGTH_3D_TAG = "geometry_length_3d"
const val GEOMETRY_PERIMETER_TAG = "geometry_perimeter"
const val GEOMETRY_AREA_TAG = "geometry_area"
const val GEOMETRY_COUNTS_TAG = "geometry_counts"
const val GEOMETRY_NOTICE_CROSSES_TAG = "geometry_notice_crosses"
const val GEOMETRY_NOTICE_MISSING_HEIGHT_TAG = "geometry_notice_missing_height"
const val GEOMETRY_NOTICE_LOCAL_PLANE_TAG = "geometry_notice_local_plane"
const val GEOMETRY_NOTICE_NO_FACTOR_TAG = "geometry_notice_no_factor"
const val GEOMETRY_NOTICE_FACTOR_NO_HEIGHT_TAG = "geometry_notice_factor_no_height"
const val GEOMETRY_PROBLEM_TAG = "geometry_problem"
const val GEOMETRY_EXPORT_TAG = "geometry_export"

fun geometrySegmentTag(index: Int) = "geometry_segment_$index"

private const val MISSING_TEXT = "—"
private const val LISTED_SEGMENTS = 10

private val NUMBER_WIDTH = 48.dp
private val NAME_WIDTH = 96.dp
private val VALUE_WIDTH = 120.dp
private val AZIMUTH_WIDTH = 140.dp
private val SLOPE_WIDTH = 96.dp

/** Lo que el usuario hace en la información geométrica (S026/RF-8, RF-9). */
class GeometryInfoActions(val onMode: (DistanceMode) -> Unit = {}, val onExport: () -> Unit = {})

@Composable
fun GeometryInfoRoute(
    projectId: String,
    lineId: String,
    themeState: ThemeState,
    onToggleSun: () -> Unit,
    viewModel: GeometryInfoViewModel = hiltViewModel(),
    onBack: () -> Unit,
) {
    LaunchedEffect(projectId, lineId) { viewModel.start(projectId, lineId) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    GeometryInfoScreen(themeState, onToggleSun, state, GeometryInfoActions(onMode = viewModel::setMode), onBack)
}

/**
 * La información geométrica de una línea o un polígono (S026/RF-1 a RF-3, RF-6, RF-9, RF-11, RF-12): longitudes, perímetro y área,
 * el selector de distancia y la tabla de segmentos. Solo muestra; no modifica ningún dato.
 */
@Composable
fun GeometryInfoScreen(
    themeState: ThemeState,
    onToggleSun: () -> Unit,
    state: GeometryInfoUi,
    actions: GeometryInfoActions,
    onBack: () -> Unit,
) {
    SectionScaffold(
        title = state.lineName.ifBlank { stringResource(R.string.geometry_title) },
        themeState = themeState,
        onToggleSun = onToggleSun,
        onNavigateBack = onBack,
    ) { padding ->
        if (!state.loaded) return@SectionScaffold
        val info = state.info
        val tableScroll = rememberScrollState()
        LazyColumn(
            modifier = Modifier.padding(padding).padding(horizontal = 16.dp).testTag(GEOMETRY_INFO_TAG),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item(key = "mode") { ModeSelector(state, actions) }
            if (info == null) {
                item(key = "problem") {
                    val text = when (state.problem) {
                        GeometryProblem.NO_POSITION -> R.string.geometry_problem_no_position
                        else -> R.string.geometry_problem_too_few
                    }
                    Text(stringResource(text), color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag(GEOMETRY_PROBLEM_TAG))
                }
                return@LazyColumn
            }
            item(key = "totals") { Totals(state, info) }
            item(key = "notices") { Notices(state, info) }
            item(key = "table-title") { Text(stringResource(R.string.geometry_table_title), style = MaterialTheme.typography.titleMedium) }
            item(key = "table-head") { TableHeader(state, tableScroll) }
            items(info.segments, key = { it.index }) { segment -> SegmentRow(segment, state, tableScroll) }
        }
    }
}

@Composable
private fun ModeSelector(state: GeometryInfoUi, actions: GeometryInfoActions) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            FilterChip(
                selected = state.mode == DistanceMode.GRID,
                onClick = { actions.onMode(DistanceMode.GRID) },
                label = { Text(stringResource(R.string.geometry_mode_grid)) },
                modifier = Modifier.weight(1f).heightIn(min = MinTouchTarget).testTag(GEOMETRY_MODE_GRID_TAG),
            )
            FilterChip(
                selected = state.mode == DistanceMode.GROUND,
                onClick = { actions.onMode(DistanceMode.GROUND) },
                label = { Text(stringResource(R.string.geometry_mode_ground)) },
                modifier = Modifier.weight(1f).heightIn(min = MinTouchTarget).testTag(GEOMETRY_MODE_GROUND_TAG),
            )
        }
        val active = if (state.mode == DistanceMode.GROUND) R.string.geometry_active_ground else R.string.geometry_active_grid
        Text(stringResource(active), style = MaterialTheme.typography.titleSmall, modifier = Modifier.testTag(GEOMETRY_ACTIVE_MODE_TAG))
    }
}

@Composable
private fun Totals(state: GeometryInfoUi, info: es.topophone.core.survey.geometry.GeometryInfo) {
    val locale = uiLocale()
    val unit = unitSymbol(state.linearUnit)
    fun length(valueM: Double?) = valueM?.let { "${LengthFormat.inUnit(it, state.linearUnit, locale)} $unit" } ?: MISSING_TEXT
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            TotalRow(R.string.geometry_length_2d, length(info.length2D), GEOMETRY_LENGTH_2D_TAG)
            TotalRow(R.string.geometry_length_3d, length(info.length3D), GEOMETRY_LENGTH_3D_TAG)
            info.perimeter?.let { TotalRow(R.string.geometry_perimeter, length(it), GEOMETRY_PERIMETER_TAG) }
            info.area?.let {
                val area = "${AreaFormat.inUnit(it, state.linearUnit, locale)} ${AreaFormat.unitSymbol(state.linearUnit)}"
                TotalRow(R.string.geometry_area, area, GEOMETRY_AREA_TAG)
            }
            Text(
                stringResource(R.string.geometry_counts, info.vertexCount, info.segmentCount),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.testTag(GEOMETRY_COUNTS_TAG),
            )
        }
    }
}

@Composable
private fun TotalRow(label: Int, value: String, tag: String) {
    Row(
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier.fillMaxWidth().testTag(tag).semantics(mergeDescendants = true) {
        },
    ) {
        Text(stringResource(label), style = MaterialTheme.typography.titleSmall)
        Text(value, style = MaterialTheme.typography.titleMedium.copy(fontFamily = FontFamily.Monospace))
    }
}

@Composable
private fun Notices(state: GeometryInfoUi, info: es.topophone.core.survey.geometry.GeometryInfo) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (info.crosses) Notice(stringResource(R.string.geometry_notice_crosses), GEOMETRY_NOTICE_CROSSES_TAG)
        if (info.missingHeightSegments.isNotEmpty()) {
            val listed = info.missingHeightSegments.take(LISTED_SEGMENTS).joinToString(", ") { (it + 1).toString() }
            val more = if (info.missingHeightSegments.size > LISTED_SEGMENTS) ", …" else ""
            Notice(stringResource(R.string.geometry_notice_missing_height, listed + more), GEOMETRY_NOTICE_MISSING_HEIGHT_TAG)
        }
        if (GeometryNotice.LOCAL_PLANE in info.notices || state.geographic) {
            Notice(stringResource(R.string.geometry_notice_local_plane), GEOMETRY_NOTICE_LOCAL_PLANE_TAG)
        }
        if (GeometryNotice.NO_FACTOR in
            info.notices
        ) {
            Notice(stringResource(R.string.geometry_notice_no_factor), GEOMETRY_NOTICE_NO_FACTOR_TAG)
        }
        if (GeometryNotice.FACTOR_WITHOUT_HEIGHT in info.notices) {
            Notice(stringResource(R.string.geometry_notice_factor_no_height), GEOMETRY_NOTICE_FACTOR_NO_HEIGHT_TAG)
        }
    }
}

@Composable
private fun Notice(text: String, tag: String) {
    Card(modifier = Modifier.fillMaxWidth().testTag(tag).semantics(mergeDescendants = true) {}) {
        Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(12.dp))
    }
}

@Composable
private fun TableHeader(state: GeometryInfoUi, scroll: androidx.compose.foundation.ScrollState) {
    val unit = unitSymbol(state.linearUnit)
    Row(modifier = Modifier.horizontalScroll(scroll)) {
        Cell(stringResource(R.string.geometry_col_number), NUMBER_WIDTH, header = true)
        Cell(stringResource(R.string.geometry_col_from), NAME_WIDTH, header = true)
        Cell(stringResource(R.string.geometry_col_to), NAME_WIDTH, header = true)
        Cell("${stringResource(R.string.geometry_col_chainage)} ($unit)", VALUE_WIDTH, header = true)
        Cell("${stringResource(R.string.geometry_col_length_2d)} ($unit)", VALUE_WIDTH, header = true)
        Cell("${stringResource(R.string.geometry_col_length_3d)} ($unit)", VALUE_WIDTH, header = true)
        Cell(stringResource(R.string.geometry_col_azimuth), AZIMUTH_WIDTH, header = true)
        Cell("${stringResource(R.string.geometry_col_delta)} ($unit)", VALUE_WIDTH, header = true)
        Cell(stringResource(R.string.geometry_col_slope), SLOPE_WIDTH, header = true)
    }
}

@Composable
private fun SegmentRow(segment: SegmentInfo, state: GeometryInfoUi, scroll: androidx.compose.foundation.ScrollState) {
    val locale = uiLocale()
    val from = state.names.getOrElse(segment.from) { "?" }
    val to = state.names.getOrElse(segment.to) { "?" }
    fun length(valueM: Double?) = valueM?.let { LengthFormat.inUnit(it, state.linearUnit, locale) } ?: MISSING_TEXT
    val description = stringResource(R.string.geometry_segment_description, segment.index + 1, from, to)
    Row(
        modifier = Modifier.horizontalScroll(scroll).testTag(geometrySegmentTag(segment.index)).semantics(mergeDescendants = true) {
            contentDescription = description
        },
    ) {
        Cell((segment.index + 1).toString(), NUMBER_WIDTH)
        Cell(from, NAME_WIDTH)
        Cell(to, NAME_WIDTH)
        Cell(length(segment.chainage), VALUE_WIDTH)
        Cell(length(segment.length2D), VALUE_WIDTH)
        Cell(length(segment.length3D), VALUE_WIDTH)
        Cell(AngleFormat.direction(segment.azimuthRad, state.angularUnit, locale), AZIMUTH_WIDTH)
        Cell(length(segment.deltaHeight), VALUE_WIDTH)
        Cell(segment.slopePercent?.let { SlopeFormat.percent(it, locale) } ?: MISSING_TEXT, SLOPE_WIDTH)
    }
}

@Composable
private fun Cell(text: String, width: Dp, header: Boolean = false) {
    val style = if (header) MaterialTheme.typography.labelMedium else MaterialTheme.typography.bodyMedium
    Text(
        text,
        style = style.copy(fontFamily = FontFamily.Monospace),
        modifier = Modifier.width(width).heightIn(min = if (header) 0.dp else MinTouchTarget).padding(end = 8.dp),
    )
}
