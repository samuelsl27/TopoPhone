package es.topophone.app.ui.map

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import es.topophone.app.R
import es.topophone.app.ui.projects.unitSymbol
import es.topophone.app.ui.receiver.uiLocale
import es.topophone.core.model.project.Project
import es.topophone.core.survey.PositionUnavailable

const val MAP_MENU_QUERY_TAG = "map_menu_query"
const val MAP_MENU_COORDINATES_TAG = "map_menu_coordinates"
const val MAP_MENU_REASON_TAG = "map_menu_reason"
const val MAP_MENU_BACK_TAG = "map_menu_back"
const val MAP_MENU_COPY_TAG = "map_menu_copy"
const val MAP_MENU_OUTSIDE_TAG = "map_menu_outside"

fun mapMenuLayerTag(layerId: String) = "map_menu_layer_$layerId"

fun mapMenuCoordinateTag(kind: CoordinateKind) = "map_menu_coordinate_${kind.name}"

/** Lo que el menú del mapa pide hacer; lo atiende [MapMenuViewModel] y la pantalla (S036/RF-34 a RF-41). */
class MapMenuActions(
    val onLongPress: (MapPress) -> Unit = {},
    val onQuery: () -> Unit = {},
    val onCoordinates: () -> Unit = {},
    val onBack: () -> Unit = {},
    val onClose: () -> Unit = {},
    val onCopy: (String) -> Unit = {},
)

/** El menú del mapa en una hoja que sale al mantener pulsado un punto; al cerrarla se quita la marca del punto (S036/RF-34, RF-35). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MapMenuSheet(state: MapMenuUiState, project: Project?, actions: MapMenuActions) {
    ModalBottomSheet(onDismissRequest = actions.onClose, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        MapMenuContent(state, project, actions)
    }
}

/** El contenido del menú, sin la hoja: las dos opciones, la información de las capas o las coordenadas del punto. */
@Composable
fun MapMenuContent(state: MapMenuUiState, project: Project?, actions: MapMenuActions) {
    Column(
        modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        when (state.screen) {
            MapMenuScreen.MENU -> Options(state, project, actions)
            MapMenuScreen.INFO -> LayersInfo(state, actions)
            MapMenuScreen.COORDINATES -> Coordinates(state, project, actions)
        }
    }
}

@Composable
private fun Options(state: MapMenuUiState, project: Project?, actions: MapMenuActions) {
    Text(stringResource(R.string.map_menu_title), style = MaterialTheme.typography.titleLarge)
    Button(
        onClick = actions.onQuery,
        enabled = state.canQuery,
        modifier = Modifier.fillMaxWidth().heightIn(min = MENU_ROW_DP).testTag(MAP_MENU_QUERY_TAG),
    ) {
        Text(stringResource(R.string.map_menu_query))
    }
    if (!state.canQuery) {
        Text(
            stringResource(if (state.visibleLayers == 0) R.string.map_menu_no_visible else R.string.map_menu_no_queryable),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.testTag(MAP_MENU_REASON_TAG),
        )
    }
    OutlinedButton(
        onClick = actions.onCoordinates,
        enabled = project != null,
        modifier = Modifier.fillMaxWidth().heightIn(min = MENU_ROW_DP).testTag(MAP_MENU_COORDINATES_TAG),
    ) {
        Text(stringResource(R.string.map_menu_coordinates))
    }
}

@Composable
private fun Back(onBack: () -> Unit) {
    TextButton(onClick = onBack, modifier = Modifier.heightIn(min = MENU_ROW_DP).testTag(MAP_MENU_BACK_TAG)) {
        Text(stringResource(R.string.map_menu_back))
    }
}

/** La respuesta de cada capa visible consultable, con el nombre de su capa y el de su servicio (S036/RF-36, RF-38, RF-39). */
@Composable
private fun LayersInfo(state: MapMenuUiState, actions: MapMenuActions) {
    Back(actions.onBack)
    Text(stringResource(R.string.map_menu_info_title), style = MaterialTheme.typography.titleLarge)
    state.results.forEachIndexed { index, result ->
        if (index > 0) HorizontalDivider()
        Column(
            modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}.testTag(mapMenuLayerTag(result.layerId)),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(result.layerTitle, style = MaterialTheme.typography.titleMedium)
            Text(result.serviceName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            when (val status = result.status) {
                LayerInfoStatus.Loading -> Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    Text(stringResource(R.string.map_menu_loading))
                }
                is LayerInfoStatus.Text -> Text(status.text, style = MaterialTheme.typography.bodyMedium)
                LayerInfoStatus.NoData -> Text(stringResource(R.string.map_menu_no_data), style = MaterialTheme.typography.bodyMedium)
                is LayerInfoStatus.Failed -> Text(
                    failureText(status),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun failureText(status: LayerInfoStatus.Failed): String = when (status.problem) {
    LayerInfoProblem.TIMEOUT -> stringResource(R.string.map_menu_error_timeout)
    LayerInfoProblem.NO_RESPONSE -> stringResource(R.string.map_menu_error_no_response)
    LayerInfoProblem.HTTP_ERROR -> stringResource(R.string.map_menu_error_http, status.detail.orEmpty())
    LayerInfoProblem.SERVICE_EXCEPTION ->
        status.detail?.let { stringResource(R.string.map_menu_error_service_detail, it) } ?: stringResource(R.string.map_menu_error_service)
    LayerInfoProblem.NO_ADDRESS -> stringResource(R.string.map_menu_error_address)
}

/** Este y Norte en el SC del proyecto y su latitud y longitud, con «Copiar»; fuera del ámbito del SC, solo latitud y longitud (RF-40, RF-41). */
@Composable
private fun Coordinates(state: MapMenuUiState, project: Project?, actions: MapMenuActions) {
    Back(actions.onBack)
    Text(stringResource(R.string.map_menu_coordinates_title), style = MaterialTheme.typography.titleLarge)
    val coordinates = state.coordinates
    if (coordinates == null || project == null) return
    val lines = coordinates.lines(project, unitSymbol(project.linearUnit), uiLocale())
    val labels = CoordinateKind.entries.associateWith { coordinateLabel(it) }
    if (coordinates.terrain.isTerrain) {
        Text(
            stringResource(R.string.terrain_ground),
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.testTag(MAP_MENU_TERRAIN_TAG),
        ) // S058/RF-9
    }
    lines.forEach { line ->
        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = MENU_ROW_DP).semantics(mergeDescendants = true) {}
                .testTag(mapMenuCoordinateTag(line.kind)),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(labels.getValue(line.kind), style = MaterialTheme.typography.bodyLarge)
            Text(line.value, style = MaterialTheme.typography.bodyLarge)
        }
    }
    coordinates.outOfScope?.let { reason ->
        Text(
            stringResource(
                if (reason == PositionUnavailable.OUTSIDE_ED50_GRID) R.string.project_outside_ed50 else R.string.project_outside_crs,
            ) + "\n" + stringResource(R.string.map_menu_outside_scope),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.testTag(MAP_MENU_OUTSIDE_TAG),
        )
    }
    val copyText = MapCoordinates.copyText(lines) { labels.getValue(it) }
    Button(
        onClick = { actions.onCopy(copyText) },
        modifier = Modifier.fillMaxWidth().heightIn(min = MENU_ROW_DP).testTag(MAP_MENU_COPY_TAG),
    ) {
        Text(stringResource(R.string.map_menu_copy))
    }
}

@Composable
private fun coordinateLabel(kind: CoordinateKind): String = stringResource(
    when (kind) {
        CoordinateKind.EASTING -> R.string.coord_easting
        CoordinateKind.NORTHING -> R.string.coord_northing
        CoordinateKind.LATITUDE -> R.string.coord_latitude_symbol
        CoordinateKind.LONGITUDE -> R.string.coord_longitude_symbol
    },
)

const val MAP_MENU_TERRAIN_TAG = "map_menu_terrain"

private val MENU_ROW_DP = 56.dp
