package es.topophone.app.ui.slope

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import es.topophone.app.R
import es.topophone.app.ui.SectionScaffold
import es.topophone.app.ui.projects.unitSymbol
import es.topophone.app.ui.receiver.uiLocale
import es.topophone.core.designsystem.component.MinTouchTarget
import es.topophone.core.model.alignment.ChainageFormat
import es.topophone.core.model.alignment.slope.SlopeKind
import es.topophone.core.model.alignment.slope.SlopeSide
import es.topophone.core.model.format.LengthFormat
import es.topophone.core.model.preferences.ThemeState
import es.topophone.core.survey.SlopeEdgeResult
import es.topophone.core.survey.TerrainSource

const val SLOPE_POINT_CHAINAGE_TAG = "slope_point_chainage"
const val SLOPE_POINT_OUTSIDE_TAG = "slope_point_outside"
const val SLOPE_POINT_STATUS_TAG = "slope_point_status"
const val SLOPE_POINT_RESULT_TAG = "slope_point_result"
const val SLOPE_POINT_KIND_TAG = "slope_point_kind"
const val SLOPE_POINT_EDGE_TAG = "slope_point_edge"
const val SLOPE_POINT_HEIGHT_TAG = "slope_point_height"
const val SLOPE_POINT_EASTING_TAG = "slope_point_easting"
const val SLOPE_POINT_NORTHING_TAG = "slope_point_northing"
const val SLOPE_POINT_ELEVATION_TAG = "slope_point_elevation"
const val SLOPE_POINT_SOURCE_TAG = "slope_point_source"

fun slopePointSideTag(side: SlopeSide) = "slope_point_side_${side.name}"

/** Acciones de la pantalla del punto de talud teórico (S051). */
class SlopePointActions(val onChainage: (String) -> Unit = {}, val onSide: (SlopeSide) -> Unit = {})

/**
 * El punto de talud teórico de un PK y un lado (S051/RF-5 a RF-8): el borde, si es cabeza (desmonte) o pie (terraplén), la altura de
 * talud y las coordenadas del punto. Sin terreno lo dice y deja el seguimiento en vivo; el lado se dice con la palabra (RNF-T8).
 */
@Composable
fun SlopePointScreen(
    themeState: ThemeState,
    onToggleSun: () -> Unit,
    state: SlopePointUiState,
    actions: SlopePointActions,
    onBack: () -> Unit,
) {
    SectionScaffold(
        title = stringResource(R.string.slope_point_title),
        themeState = themeState,
        onToggleSun = onToggleSun,
        onNavigateBack = onBack,
    ) { padding ->
        Column(
            modifier = Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (state.loaded) Form(state, actions)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Form(state: SlopePointUiState, actions: SlopePointActions) {
    val locale = uiLocale()
    val symbol = unitSymbol(state.unit)
    Text(state.alignmentName, style = MaterialTheme.typography.titleMedium)
    Text(
        stringResource(
            R.string.alignment_point_axis,
            LengthFormat.metres(state.endChainageM - state.startChainageM, locale, decimals = 1),
            ChainageFormat.format(state.startChainageM, state.unit, locale),
            ChainageFormat.format(state.endChainageM, state.unit, locale),
        ),
    )
    OutlinedTextField(
        value = state.chainageText,
        onValueChange = actions.onChainage,
        label = { Text(stringResource(R.string.alignment_point_chainage)) },
        isError = state.chainageInvalid,
        supportingText = {
            Text(
                if (state.chainageInvalid) {
                    stringResource(R.string.alignment_point_invalid_chainage)
                } else {
                    stringResource(R.string.alignment_point_chainage_help, symbol)
                },
            )
        },
        singleLine = true,
        modifier = Modifier.fillMaxWidth().testTag(SLOPE_POINT_CHAINAGE_TAG),
    )
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        SlopeSide.entries.forEach { side ->
            FilterChip(
                selected = state.side == side,
                onClick = { actions.onSide(side) },
                label = { Text(sideLabel(side)) },
                modifier = Modifier.heightIn(min = MinTouchTarget).testTag(slopePointSideTag(side)),
            )
        }
    }
    if (state.outside) {
        Text(
            stringResource(
                R.string.alignment_point_outside,
                ChainageFormat.format(state.startChainageM, state.unit, locale),
                ChainageFormat.format(state.endChainageM, state.unit, locale),
            ),
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.testTag(SLOPE_POINT_OUTSIDE_TAG).semantics { liveRegion = LiveRegionMode.Polite },
        )
    }
    when (val outcome = state.outcome) {
        null -> Unit
        is SlopePointOutcome.Unavailable -> Status(unavailableText(outcome.reason))
        is SlopePointOutcome.NoTerrain -> {
            Edge(outcome.edge, state, symbol)
            Status(stringResource(R.string.slope_point_no_terrain))
        }
        is SlopePointOutcome.NoIntersection -> {
            Edge(outcome.edge, state, symbol)
            Status(stringResource(R.string.slope_point_no_intersection))
        }
        is SlopePointOutcome.AtEdge -> {
            Edge(outcome.edge, state, symbol)
            Status(stringResource(R.string.slope_point_at_edge), error = false)
        }
        is SlopePointOutcome.Found -> Result(outcome, state, symbol)
    }
}

@Composable
private fun Status(text: String, error: Boolean = true) {
    Text(
        text,
        color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.testTag(SLOPE_POINT_STATUS_TAG).semantics { liveRegion = LiveRegionMode.Polite },
    )
}

@Composable
internal fun unavailableText(reason: SlopeEdgeResult): String = when (reason) {
    SlopeEdgeResult.NoRule -> stringResource(R.string.slope_point_status_no_rule)
    SlopeEdgeResult.NoSection -> stringResource(R.string.slope_point_status_no_section)
    is SlopeEdgeResult.NoEdge -> stringResource(R.string.slope_point_status_no_edge, reason.code)
    SlopeEdgeResult.NoHeight -> stringResource(R.string.slope_point_status_no_height)
    SlopeEdgeResult.OutsideAxis -> stringResource(R.string.slope_point_status_outside)
    is SlopeEdgeResult.Edge -> ""
}

@Composable
private fun Edge(edge: SlopeEdgeResult.Edge, state: SlopePointUiState, symbol: String) {
    val locale = uiLocale()
    Text(
        stringResource(
            R.string.slope_point_edge,
            LengthFormat.inUnit(edge.offsetM, state.unit, locale),
            symbol,
            LengthFormat.inUnit(edge.heightM, state.unit, locale),
        ),
        modifier = Modifier.testTag(SLOPE_POINT_EDGE_TAG),
    )
}

@Composable
private fun Result(found: SlopePointOutcome.Found, state: SlopePointUiState, symbol: String) {
    val locale = uiLocale()
    val point = found.point
    val cut = point.kind == SlopeKind.CUT
    Edge(found.edge, state, symbol)
    Card(modifier = Modifier.fillMaxWidth().testTag(SLOPE_POINT_RESULT_TAG)) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                stringResource(
                    if (cut) R.string.slope_point_kind_cut else R.string.slope_point_kind_fill,
                    ratioLabel(found.edge.rule.ratioOf(point.kind)),
                ),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.testTag(SLOPE_POINT_KIND_TAG).semantics { liveRegion = LiveRegionMode.Polite },
            )
            Text(
                stringResource(
                    R.string.slope_point_height,
                    LengthFormat.inUnit(point.slopeHeightM, state.unit, locale),
                    symbol,
                ),
                modifier = Modifier.testTag(SLOPE_POINT_HEIGHT_TAG),
            )
            val mono = MaterialTheme.typography.titleMedium.copy(fontFamily = FontFamily.Monospace)
            Text(
                stringResource(R.string.alignment_point_easting, LengthFormat.inUnit(point.point.easting, state.unit, locale), symbol),
                style = mono,
                modifier = Modifier.testTag(SLOPE_POINT_EASTING_TAG),
            )
            Text(
                stringResource(R.string.alignment_point_northing, LengthFormat.inUnit(point.point.northing, state.unit, locale), symbol),
                style = mono,
                modifier = Modifier.testTag(SLOPE_POINT_NORTHING_TAG),
            )
            Text(
                stringResource(R.string.alignment_point_elevation, LengthFormat.inUnit(point.heightM, state.unit, locale), symbol),
                style = mono,
                modifier = Modifier.testTag(SLOPE_POINT_ELEVATION_TAG),
            )
            found.source?.let {
                Text(
                    stringResource(
                        if (it ==
                            TerrainSource.PROFILE
                        ) {
                            R.string.slope_point_source_profile
                        } else {
                            R.string.slope_point_source_surface
                        },
                    ),
                    modifier = Modifier.testTag(SLOPE_POINT_SOURCE_TAG),
                )
            }
        }
    }
}
