package es.topophone.app.ui.slope

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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import es.topophone.app.R
import es.topophone.app.ui.projects.unitSymbol
import es.topophone.app.ui.receiver.uiLocale
import es.topophone.app.ui.stakeout.AxisField
import es.topophone.app.ui.stakeout.Guidance
import es.topophone.app.ui.stakeout.KeepScreenOn
import es.topophone.app.ui.stakeout.Numbers
import es.topophone.app.ui.stakeout.STAKEOUT_SETTINGS_TAG
import es.topophone.app.ui.stakeout.StakeModeChoice
import es.topophone.app.ui.stakeout.StakeoutSettingsDialog
import es.topophone.core.designsystem.component.MinTouchTarget
import es.topophone.core.model.alignment.ChainageFormat
import es.topophone.core.model.alignment.slope.SlopeKind
import es.topophone.core.model.alignment.slope.SlopeSide
import es.topophone.core.model.format.LengthFormat
import es.topophone.core.model.point.HeadingChoice
import es.topophone.core.model.point.StakeMode
import es.topophone.core.model.project.LinearUnit
import es.topophone.core.survey.AxisInputError
import es.topophone.core.survey.SlopeLiveValue
import kotlin.math.abs

const val SLOPE_STAKE_PANEL_TAG = "slope_stake_panel"
const val SLOPE_STAKE_TITLE_TAG = "slope_stake_title"
const val SLOPE_STAKE_CLOSE_TAG = "slope_stake_close"
const val SLOPE_STAKE_LIMITS_TAG = "slope_stake_limits"
const val SLOPE_STAKE_START_TAG = "slope_stake_start"
const val SLOPE_STAKE_END_TAG = "slope_stake_end"
const val SLOPE_STAKE_INTERVAL_TAG = "slope_stake_interval"
const val SLOPE_STAKE_SINGULAR_TAG = "slope_stake_singular"
const val SLOPE_STAKE_STAKE_OFFSET_TAG = "slope_stake_stake_offset"
const val SLOPE_STAKE_OPEN_TAG = "slope_stake_open"
const val SLOPE_STAKE_POSITION_TAG = "slope_stake_position"
const val SLOPE_STAKE_PREVIOUS_TAG = "slope_stake_previous"
const val SLOPE_STAKE_NEXT_TAG = "slope_stake_next"
const val SLOPE_STAKE_DESIGN_TAG = "slope_stake_design"
const val SLOPE_STAKE_KIND_TAG = "slope_stake_kind"
const val SLOPE_STAKE_HEIGHT_TAG = "slope_stake_height"
const val SLOPE_STAKE_STATUS_TAG = "slope_stake_status"
const val SLOPE_STAKE_LIVE_TAG = "slope_stake_live"
const val SLOPE_STAKE_DZ_TAG = "slope_stake_dz"
const val SLOPE_STAKE_MOVE_TAG = "slope_stake_move"
const val SLOPE_STAKE_CHAINAGE_TAG = "slope_stake_chainage"
const val SLOPE_STAKE_NO_PROJECTION_TAG = "slope_stake_no_projection"

fun slopeStakeSideTag(side: SlopeSide) = "slope_stake_side_${side.name}"

fun slopeStakeErrorTag(field: AxisField) = "slope_stake_error_${field.name}"

/** Lo que el usuario hace en el replanteo de taludes (S051/RF-9, RF-14). */
class SlopeStakeActions(
    val onClose: () -> Unit = {},
    val onStart: () -> Unit = {},
    val onStartText: (String) -> Unit = {},
    val onEndText: (String) -> Unit = {},
    val onIntervalText: (String) -> Unit = {},
    val onSingular: (Boolean) -> Unit = {},
    val onSide: (SlopeSide) -> Unit = {},
    val onStakeOffset: (String) -> Unit = {},
    val onPrevious: () -> Unit = {},
    val onNext: () -> Unit = {},
    val onGuideMode: (StakeMode) -> Unit = {},
    val onHeading: (HeadingChoice) -> Unit = {},
    val onTolerance: (String) -> Boolean = { false },
    val onToleranceV: (String) -> Boolean = { false },
)

/**
 * Panel del replanteo de taludes, en el hueco del recolector y sin tapar el mapa (S051/RF-9 a RF-12, RF-14, RF-17): primero los campos de
 * partida con los lados; con el replanteo abierto, el PK y el lado con la palabra, si es cabeza o pie, el alzado con la punta, la
 * diferencia de cota, lo que hay que moverse hacia dentro o fuera del eje y el error en PK. La pantalla no se apaga mientras está
 * abierto (RNF-T2).
 */
@Composable
fun SlopeStakePanel(state: SlopeStakeUiState, actions: SlopeStakeActions, modifier: Modifier = Modifier) {
    KeepScreenOn()
    var settingsOpen by remember { mutableStateOf(false) }
    val unit = state.project?.linearUnit ?: LinearUnit.METRE
    Card(modifier = modifier.fillMaxWidth().padding(8.dp).testTag(SLOPE_STAKE_PANEL_TAG)) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.slope_stake_title, state.alignmentName),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f).testTag(SLOPE_STAKE_TITLE_TAG),
                )
                TextButton(onClick = actions.onClose, modifier = Modifier.heightIn(min = MinTouchTarget).testTag(SLOPE_STAKE_CLOSE_TAG)) {
                    Text(stringResource(R.string.collector_close))
                }
            }
            if (!state.loaded) return@Column
            Column(
                modifier = Modifier.weight(1f, fill = false).heightIn(max = BODY_MAX_HEIGHT).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (state.started) Started(state, actions, unit) else Setup(state, actions, unit)
            }
            if (state.started) {
                TextButton(
                    onClick = { settingsOpen = true },
                    enabled = !state.measuring,
                    modifier = Modifier.heightIn(min = MinTouchTarget).testTag(STAKEOUT_SETTINGS_TAG),
                ) { Text(stringResource(R.string.stakeout_settings)) }
            }
        }
    }
    if (settingsOpen && !state.measuring) {
        StakeoutSettingsDialog(state.settings, unit, actions.onHeading, actions.onTolerance, { settingsOpen = false }, actions.onToleranceV)
    }
}

/** Los campos de partida: PK inicial, PK final, intervalo, puntos singulares, lados y desplazamiento de estaca (S051/RF-9, RF-14). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Setup(state: SlopeStakeUiState, actions: SlopeStakeActions, unit: LinearUnit) {
    val symbol = unitSymbol(unit)
    val locale = uiLocale()
    Text(
        stringResource(
            R.string.axis_stakeout_limits,
            ChainageFormat.format(state.startChainageM, unit, locale),
            ChainageFormat.format(state.endChainageM, unit, locale),
        ),
        modifier = Modifier.testTag(SLOPE_STAKE_LIMITS_TAG),
    )
    NumberField(
        state.startText,
        R.string.axis_stakeout_start_label,
        symbol,
        state,
        AxisField.START,
        SLOPE_STAKE_START_TAG,
        actions.onStartText,
        unit,
    )
    NumberField(state.endText, R.string.axis_stakeout_end_label, symbol, state, AxisField.END, SLOPE_STAKE_END_TAG, actions.onEndText, unit)
    NumberField(
        state.intervalText,
        R.string.axis_stakeout_interval_label,
        symbol,
        state,
        AxisField.INTERVAL,
        SLOPE_STAKE_INTERVAL_TAG,
        actions.onIntervalText,
        unit,
    )
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = MinTouchTarget)
            .toggleable(value = state.singular, role = Role.Checkbox, onValueChange = actions.onSingular)
            .testTag(SLOPE_STAKE_SINGULAR_TAG),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Checkbox(checked = state.singular, onCheckedChange = null)
        Column {
            Text(stringResource(R.string.axis_stakeout_singular))
            Text(stringResource(R.string.axis_stakeout_singular_help), style = MaterialTheme.typography.bodySmall)
        }
    }
    Text(stringResource(R.string.slope_stake_sides), style = MaterialTheme.typography.titleSmall)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        SlopeSide.entries.forEach { side ->
            FilterChip(
                selected = side in state.sides,
                onClick = { actions.onSide(side) },
                label = { Text(sideLabel(side)) },
                modifier = Modifier.heightIn(min = MinTouchTarget).testTag(slopeStakeSideTag(side)),
            )
        }
    }
    StakeOffsetField(state, actions, unit)
    Button(onClick = actions.onStart, modifier = Modifier.fillMaxWidth().heightIn(min = MinTouchTarget).testTag(SLOPE_STAKE_OPEN_TAG)) {
        Text(stringResource(R.string.axis_stakeout_open))
    }
}

@Composable
private fun StakeOffsetField(state: SlopeStakeUiState, actions: SlopeStakeActions, unit: LinearUnit) {
    NumberField(
        state.stakeOffsetText,
        R.string.slope_stake_stake_offset,
        unitSymbol(unit),
        state,
        AxisField.OFFSET,
        SLOPE_STAKE_STAKE_OFFSET_TAG,
        actions.onStakeOffset,
        unit,
        helpRes = R.string.slope_stake_stake_offset_help,
    )
}

@Composable
private fun NumberField(
    value: String,
    labelRes: Int,
    symbol: String,
    state: SlopeStakeUiState,
    field: AxisField,
    tag: String,
    onChange: (String) -> Unit,
    unit: LinearUnit,
    helpRes: Int? = null,
) {
    var text by remember(value) { mutableStateOf(value) }
    val error = state.errors[field]
    val locale = uiLocale()
    OutlinedTextField(
        value = text,
        onValueChange = {
            text = it
            onChange(it)
        },
        label = { Text(stringResource(labelRes, symbol)) },
        isError = error != null,
        supportingText = when {
            error != null -> {
                {
                    Text(
                        errorText(
                            error,
                            ChainageFormat.format(state.startChainageM, unit, locale),
                            ChainageFormat.format(state.endChainageM, unit, locale),
                        ),
                        modifier = Modifier.testTag(slopeStakeErrorTag(field)),
                    )
                }
            }
            helpRes != null -> {
                { Text(stringResource(helpRes)) }
            }
            else -> null
        },
        enabled = !state.measuring,
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = Modifier.fillMaxWidth().heightIn(min = MinTouchTarget).testTag(tag),
    )
}

@Composable
private fun errorText(error: AxisInputError, start: String, end: String): String = when (error) {
    AxisInputError.NOT_A_NUMBER -> stringResource(R.string.axis_stakeout_error_not_a_number)
    AxisInputError.OUT_OF_AXIS -> stringResource(R.string.axis_stakeout_error_out_of_axis, start, end)
    AxisInputError.INTERVAL_NOT_POSITIVE -> stringResource(R.string.axis_stakeout_error_interval)
}

/** Con el replanteo abierto: el lugar de la serie, el punto de talud, el desplazamiento de estaca, la guía y el alzado en vivo (S051/RF-9 a RF-12). */
@Composable
private fun Started(state: SlopeStakeUiState, actions: SlopeStakeActions, unit: LinearUnit) {
    val locale = uiLocale()
    val symbol = unitSymbol(unit)
    val numbers = Numbers(unit, symbol, locale)
    if (state.count > 1) {
        Text(
            stringResource(R.string.axis_stakeout_position, state.position, state.count),
            modifier = Modifier.testTag(SLOPE_STAKE_POSITION_TAG),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = actions.onPrevious,
                enabled = !state.measuring && state.position > 1,
                modifier = Modifier.heightIn(min = MinTouchTarget).testTag(SLOPE_STAKE_PREVIOUS_TAG),
            ) { Text(stringResource(R.string.stakeout_previous)) }
            OutlinedButton(
                onClick = actions.onNext,
                enabled = !state.measuring && state.position < state.count,
                modifier = Modifier.heightIn(min = MinTouchTarget).testTag(SLOPE_STAKE_NEXT_TAG),
            ) { Text(stringResource(R.string.stakeout_next)) }
        }
    }
    state.item?.let { item ->
        Text(
            stringResource(R.string.slope_stake_design, ChainageFormat.format(item.chainageM, unit, locale), sideLabel(item.side)),
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.testTag(SLOPE_STAKE_DESIGN_TAG),
        )
    }
    Outcome(state, unit, symbol)
    StakeOffsetField(state, actions, unit)
    StakeModeChoice(state.settings, state.measuring, actions.onGuideMode)
    // S051/RF-17: sin proyección sobre el eje solo se muestran el Norte y el Este, que son las cifras de "Por distancia".
    val settings = if (state.noProjection) state.settings.copy(mode = StakeMode.BY_DISTANCE) else state.settings
    Guidance(state.project, settings, state.guidance, state.admitted)
    Live(state, numbers)
}

/** El punto de talud teórico del lugar de ahora, o por qué no lo hay (S051/RF-5 a RF-8). */
@Composable
private fun Outcome(state: SlopeStakeUiState, unit: LinearUnit, symbol: String) {
    when (val outcome = state.outcome) {
        null -> Unit
        is SlopePointOutcome.Found -> {
            val kind = outcome.point.kind
            Text(
                stringResource(
                    if (kind == SlopeKind.CUT) R.string.slope_point_kind_cut else R.string.slope_point_kind_fill,
                    ratioLabel(outcome.edge.rule.ratioOf(kind)),
                ),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }.testTag(SLOPE_STAKE_KIND_TAG),
            )
            Text(
                stringResource(R.string.slope_point_height, LengthFormat.inUnit(outcome.point.slopeHeightM, unit, uiLocale()), symbol),
                modifier = Modifier.testTag(SLOPE_STAKE_HEIGHT_TAG),
            )
        }
        is SlopePointOutcome.Unavailable -> Status(unavailableText(outcome.reason))
        is SlopePointOutcome.NoTerrain -> Status(stringResource(R.string.slope_point_no_terrain))
        is SlopePointOutcome.NoIntersection -> Status(stringResource(R.string.slope_point_no_intersection))
        is SlopePointOutcome.AtEdge -> Status(stringResource(R.string.slope_point_at_edge))
    }
}

@Composable
private fun Status(text: String) {
    Text(
        text,
        color = MaterialTheme.colorScheme.error,
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }.testTag(SLOPE_STAKE_STATUS_TAG),
    )
}

/**
 * Lo que dice la punta frente al talud de diseño: el alzado, la diferencia de cota, lo que hay que moverse y el error en PK, con una sola
 * frase para TalkBack (S051/RF-10 a RF-12, RF-17, RNF-T8).
 */
@Composable
private fun Live(state: SlopeStakeUiState, numbers: Numbers) {
    if (state.noProjection) {
        Text(
            stringResource(R.string.axis_stakeout_no_projection),
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.testTag(SLOPE_STAKE_NO_PROJECTION_TAG),
        )
        return
    }
    val live = state.live ?: return
    val dz = dzPhrase(live, numbers)
    val move = movePhrase(live, numbers)
    val chainage = chainagePhrase(live, numbers)
    val phrase = stringResource(R.string.slope_stake_a11y, dz, move, chainage)
    SlopeElevation.of(state)?.let { SlopeElevationView(it, phrase) }
    Column(
        modifier = Modifier.semantics(mergeDescendants = true) { contentDescription = phrase }.testTag(SLOPE_STAKE_LIVE_TAG),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(dz, style = MaterialTheme.typography.titleMedium, modifier = Modifier.testTag(SLOPE_STAKE_DZ_TAG))
        Text(move, style = MaterialTheme.typography.titleMedium, modifier = Modifier.testTag(SLOPE_STAKE_MOVE_TAG))
        Text(chainage, style = MaterialTheme.typography.titleMedium, modifier = Modifier.testTag(SLOPE_STAKE_CHAINAGE_TAG))
    }
}

/** Menos de medio milímetro es estar en la cota o en el PK, la resolución con la que se escriben. */
private const val AT_M = 0.0005

@Composable
private fun dzPhrase(live: SlopeLiveValue, numbers: Numbers): String = when {
    live.dHeightM >= AT_M -> stringResource(R.string.slope_stake_dz_above, numbers.length(live.dHeightM))
    live.dHeightM <= -AT_M -> stringResource(R.string.slope_stake_dz_below, numbers.length(-live.dHeightM))
    else -> stringResource(R.string.slope_stake_dz_none)
}

@Composable
private fun movePhrase(live: SlopeLiveValue, numbers: Numbers): String = when {
    live.moveM >= AT_M -> stringResource(R.string.slope_stake_move_outward, numbers.length(live.moveM))
    live.moveM <= -AT_M -> stringResource(R.string.slope_stake_move_inward, numbers.length(abs(live.moveM)))
    else -> stringResource(R.string.slope_stake_move_none)
}

@Composable
private fun chainagePhrase(live: SlopeLiveValue, numbers: Numbers): String = when {
    live.dChainageM >= AT_M -> stringResource(R.string.slope_stake_pk_ahead, numbers.length(live.dChainageM))
    live.dChainageM <= -AT_M -> stringResource(R.string.slope_stake_pk_back, numbers.length(abs(live.dChainageM)))
    else -> stringResource(R.string.slope_stake_pk_none)
}

private val BODY_MAX_HEIGHT = 380.dp
