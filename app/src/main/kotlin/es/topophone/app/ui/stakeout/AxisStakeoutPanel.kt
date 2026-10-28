package es.topophone.app.ui.stakeout

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
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
import es.topophone.app.ui.collector.AverageStatsUi
import es.topophone.app.ui.collector.CollectorActions
import es.topophone.app.ui.collector.CollectorGearSelector
import es.topophone.app.ui.collector.CollectorUiState
import es.topophone.app.ui.collector.LongPauseDialog
import es.topophone.app.ui.collector.MeasureButton
import es.topophone.app.ui.collector.Measuring
import es.topophone.app.ui.collector.Messages
import es.topophone.app.ui.collector.OutOfToleranceDialog
import es.topophone.app.ui.collector.PauseState
import es.topophone.app.ui.projects.unitSymbol
import es.topophone.app.ui.receiver.uiLocale
import es.topophone.core.designsystem.component.MinTouchTarget
import es.topophone.core.model.alignment.ChainageFormat
import es.topophone.core.model.alignment.OffsetFormat
import es.topophone.core.model.alignment.OffsetSide
import es.topophone.core.model.point.HeadingChoice
import es.topophone.core.model.point.StakeMode
import es.topophone.core.model.project.LinearUnit
import es.topophone.core.survey.AcrossMove
import es.topophone.core.survey.AxisGuidanceValue
import es.topophone.core.survey.AxisInputError
import es.topophone.core.survey.StakeZone
import kotlin.math.abs

const val AXIS_PANEL_TAG = "axis_stakeout_panel"
const val AXIS_TITLE_TAG = "axis_stakeout_title"
const val AXIS_CLOSE_TAG = "axis_stakeout_close"
const val AXIS_LIMITS_TAG = "axis_stakeout_limits"
const val AXIS_START_TAG = "axis_stakeout_start"
const val AXIS_END_TAG = "axis_stakeout_end"
const val AXIS_INTERVAL_TAG = "axis_stakeout_interval"
const val AXIS_SINGULAR_TAG = "axis_stakeout_singular"
const val AXIS_OFFSET_TAG = "axis_stakeout_offset"
const val AXIS_THICKNESS_TAG = "axis_stakeout_thickness"
const val AXIS_OPEN_TAG = "axis_stakeout_open"
const val AXIS_PK_TAG = "axis_stakeout_pk"
const val AXIS_DESIGN_TAG = "axis_stakeout_design"
const val AXIS_ELEVATION_TAG = "axis_stakeout_elevation"
const val AXIS_NO_PROFILE_TAG = "axis_stakeout_no_profile"
const val AXIS_POSITION_TAG = "axis_stakeout_position"
const val AXIS_PREVIOUS_TAG = "axis_stakeout_previous"
const val AXIS_NEXT_TAG = "axis_stakeout_next"
const val AXIS_FIGURES_TAG = "axis_stakeout_figures"
const val AXIS_ALONG_TAG = "axis_stakeout_along"
const val AXIS_ACROSS_TAG = "axis_stakeout_across"
const val AXIS_NO_PROJECTION_TAG = "axis_stakeout_no_projection"
const val AXIS_OUTSIDE_TAG = "axis_stakeout_outside"
const val AXIS_CLOSED_TAG = "axis_stakeout_closed"
const val AXIS_ANNOUNCE_TAG = "axis_stakeout_announce"

fun axisChoiceTag(id: String) = "axis_stakeout_choice_$id"

fun axisErrorTag(field: AxisField) = "axis_stakeout_error_${field.name}"

/** Lo que el usuario hace en el replanteo por PK y desplazamiento (S054/RF-1 a RF-5, RF-11). */
class AxisStakeoutActions(
    val onClose: () -> Unit = {},
    val onStart: () -> Unit = {},
    val onAlignment: (String) -> Unit = {},
    val onStartText: (String) -> Unit = {},
    val onEndText: (String) -> Unit = {},
    val onIntervalText: (String) -> Unit = {},
    val onSingular: (Boolean) -> Unit = {},
    val onChainage: (String) -> Unit = {},
    val onOffset: (String) -> Unit = {},
    val onThickness: (String) -> Unit = {},
    val onPrevious: () -> Unit = {},
    val onNext: () -> Unit = {},
    val onGuideMode: (StakeMode) -> Unit = {},
    val onHeading: (HeadingChoice) -> Unit = {},
    val onTolerance: (String) -> Boolean = { false },
    val onToleranceV: (String) -> Boolean = { false },
)

/**
 * Panel del replanteo por PK y desplazamiento, en el hueco del recolector y sin tapar el mapa (S054/RF-1 a RF-9, RF-11): primero los
 * campos de partida con el PK inicial y final del eje; con el replanteo abierto, el punto de diseño con su cota, los campos que se
 * cambian a mano, la guía con lo que hay que moverse a lo largo y a través del eje, y "Medir". La pantalla no se apaga mientras está
 * abierto (RNF-T2).
 */
@Composable
fun AxisStakeoutPanel(
    state: AxisStakeoutUiState,
    actions: AxisStakeoutActions,
    modifier: Modifier = Modifier,
    collector: CollectorUiState? = null,
    collectorActions: CollectorActions = CollectorActions(),
    stats: AverageStatsUi? = null,
) {
    KeepScreenOn()
    var settingsOpen by remember { mutableStateOf(false) }
    val unit = state.project?.linearUnit ?: LinearUnit.METRE
    Card(modifier = modifier.fillMaxWidth().padding(8.dp).testTag(AXIS_PANEL_TAG)) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Header(state, actions)
            if (state.closed != null) {
                Text(
                    stringResource(R.string.axis_stakeout_closed),
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive }.testTag(AXIS_CLOSED_TAG),
                )
                return@Column
            }
            if (!state.loaded) return@Column
            Column(
                modifier = Modifier.weight(1f, fill = false).heightIn(max = BODY_MAX_HEIGHT).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                AlignmentChoice(state, actions)
                if (state.started) Started(state, actions, unit, collector, collectorActions) else Setup(state, actions, unit)
            }
            if (state.started && collector != null) {
                if (collector.measuring) {
                    Measuring(collector, unit, stats, collectorActions)
                } else {
                    MeasureButton(collector, collectorActions)
                }
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
    collector?.pending?.let { OutOfToleranceDialog(it, collectorActions.onConfirm, collectorActions.onDiscard) }
    if (collector?.paused == PauseState.LONG_PAUSE) {
        LongPauseDialog(collectorActions.onFinish, collectorActions.onCancel, collectorActions.onKeepWaiting)
    }
}

@Composable
private fun Header(state: AxisStakeoutUiState, actions: AxisStakeoutActions) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            stringResource(R.string.axis_stakeout_title, state.alignmentName),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.weight(1f).testTag(AXIS_TITLE_TAG),
        )
        TextButton(onClick = actions.onClose, modifier = Modifier.heightIn(min = MinTouchTarget).testTag(AXIS_CLOSE_TAG)) {
            Text(stringResource(R.string.collector_close))
        }
    }
}

/** Los ejes del proyecto, para cambiar de eje sin salir del replanteo (S054/RF-11). */
@Composable
private fun AlignmentChoice(state: AxisStakeoutUiState, actions: AxisStakeoutActions) {
    if (state.choices.size < 2) return
    Text(stringResource(R.string.axis_stakeout_choose), style = MaterialTheme.typography.titleSmall)
    Row(modifier = Modifier.selectableGroup().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        state.choices.forEach { choice ->
            FilterChip(
                selected = choice.id == state.alignmentId,
                onClick = { actions.onAlignment(choice.id) },
                enabled = !state.measuring,
                label = { Text(choice.name) },
                modifier = Modifier.heightIn(min = MinTouchTarget).testTag(axisChoiceTag(choice.id)),
            )
        }
    }
}

/** Los campos de partida: PK inicial, PK final, intervalo, puntos singulares, desplazamiento y espesor (S054/RF-1, RF-9). */
@Composable
private fun Setup(state: AxisStakeoutUiState, actions: AxisStakeoutActions, unit: LinearUnit) {
    val symbol = unitSymbol(unit)
    val locale = uiLocale()
    Text(
        stringResource(
            R.string.axis_stakeout_limits,
            ChainageFormat.format(state.startChainageM, unit, locale),
            ChainageFormat.format(state.endChainageM, unit, locale),
        ),
        modifier = Modifier.testTag(AXIS_LIMITS_TAG),
    )
    AxisNumberField(
        state.startText,
        R.string.axis_stakeout_start_label,
        symbol,
        state,
        AxisField.START,
        AXIS_START_TAG,
        actions.onStartText,
        unit,
    )
    AxisNumberField(state.endText, R.string.axis_stakeout_end_label, symbol, state, AxisField.END, AXIS_END_TAG, actions.onEndText, unit)
    AxisNumberField(
        state.intervalText,
        R.string.axis_stakeout_interval_label,
        symbol,
        state,
        AxisField.INTERVAL,
        AXIS_INTERVAL_TAG,
        actions.onIntervalText,
        unit,
    )
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = MinTouchTarget)
            .toggleable(value = state.singular, role = Role.Checkbox, onValueChange = actions.onSingular)
            .testTag(AXIS_SINGULAR_TAG),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Checkbox(checked = state.singular, onCheckedChange = null)
        Column {
            Text(stringResource(R.string.axis_stakeout_singular))
            Text(stringResource(R.string.axis_stakeout_singular_help), style = MaterialTheme.typography.bodySmall)
        }
    }
    AxisNumberField(
        state.offsetText,
        R.string.axis_stakeout_offset_label,
        symbol,
        state,
        AxisField.OFFSET,
        AXIS_OFFSET_TAG,
        actions.onOffset,
        unit,
        helpRes = R.string.axis_stakeout_offset_help,
    )
    AxisNumberField(
        state.thicknessText,
        R.string.axis_stakeout_thickness_label,
        symbol,
        state,
        AxisField.THICKNESS,
        AXIS_THICKNESS_TAG,
        actions.onThickness,
        unit,
        helpRes = R.string.axis_stakeout_thickness_help,
    )
    Button(onClick = actions.onStart, modifier = Modifier.fillMaxWidth().heightIn(min = MinTouchTarget).testTag(AXIS_OPEN_TAG)) {
        Text(stringResource(R.string.axis_stakeout_open))
    }
}

@Composable
private fun AxisNumberField(
    value: String,
    labelRes: Int,
    symbol: String,
    state: AxisStakeoutUiState,
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
                        modifier = Modifier.testTag(axisErrorTag(field)),
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

/** Con el replanteo abierto: el punto de diseño, los campos a mano, los PK de la serie y la guía (S054/RF-3 a RF-9). */
@Composable
private fun Started(
    state: AxisStakeoutUiState,
    actions: AxisStakeoutActions,
    unit: LinearUnit,
    collector: CollectorUiState?,
    collectorActions: CollectorActions,
) {
    val symbol = unitSymbol(unit)
    val locale = uiLocale()
    val target = state.target
    val numbers = Numbers(unit, symbol, locale)
    AxisNumberField(
        state.chainageText,
        R.string.axis_stakeout_pk_label,
        symbol,
        state,
        AxisField.CHAINAGE,
        AXIS_PK_TAG,
        actions.onChainage,
        unit,
    )
    AxisNumberField(
        state.offsetText,
        R.string.axis_stakeout_offset_label,
        symbol,
        state,
        AxisField.OFFSET,
        AXIS_OFFSET_TAG,
        actions.onOffset,
        unit,
    )
    AxisNumberField(
        state.thicknessText,
        R.string.axis_stakeout_thickness_label,
        symbol,
        state,
        AxisField.THICKNESS,
        AXIS_THICKNESS_TAG,
        actions.onThickness,
        unit,
    )
    val series = state.series
    if (series != null && series.size > 1) {
        Text(
            stringResource(R.string.axis_stakeout_position, state.seriesIndex + 1, series.size),
            modifier = Modifier.testTag(AXIS_POSITION_TAG),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = actions.onPrevious,
                enabled = !state.measuring && state.seriesIndex > 0,
                modifier = Modifier.heightIn(min = MinTouchTarget).testTag(AXIS_PREVIOUS_TAG),
            ) { Text(stringResource(R.string.stakeout_previous)) }
            OutlinedButton(
                onClick = actions.onNext,
                enabled = !state.measuring && state.seriesIndex < series.size - 1,
                modifier = Modifier.heightIn(min = MinTouchTarget).testTag(AXIS_NEXT_TAG),
            ) { Text(stringResource(R.string.stakeout_next)) }
        }
    }
    if (target == null) {
        Text(
            stringResource(
                R.string.axis_stakeout_error_out_of_axis,
                ChainageFormat.format(state.startChainageM, unit, locale),
                ChainageFormat.format(state.endChainageM, unit, locale),
            ),
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }.testTag(AXIS_OUTSIDE_TAG),
        )
        return
    }
    val side = sidePhrase(target.offsetM, unit, symbol)
    Text(
        stringResource(R.string.axis_stakeout_design, ChainageFormat.format(target.chainageM, unit, locale), side),
        style = MaterialTheme.typography.titleSmall,
        modifier = Modifier.testTag(AXIS_DESIGN_TAG),
    )
    val elevation = target.elevationM
    if (elevation != null) {
        Text(stringResource(R.string.axis_stakeout_elevation, numbers.length(elevation)), modifier = Modifier.testTag(AXIS_ELEVATION_TAG))
    } else if (!state.hasProfile) {
        Text(stringResource(R.string.axis_stakeout_no_profile), modifier = Modifier.testTag(AXIS_NO_PROFILE_TAG))
    }
    StakeModeChoice(state.settings, state.measuring, actions.onGuideMode)
    // S054/RF-8: sin proyección sobre el eje solo se muestran el Norte y el Este, que son las cifras de "Por distancia".
    val settings = if (state.noProjection) state.settings.copy(mode = StakeMode.BY_DISTANCE) else state.settings
    Guidance(state.project, settings, state.guidance, state.admitted) { ui, _ -> AxisFigures(state, ui, unit) }
    collector?.let { CollectorGearSelector(it, collectorActions) } // S046/RF-23
    collector?.let { Messages(it, collectorActions) }
}

/** "a 3,500 m a la derecha del eje", con la palabra y no solo el signo (S054/RNF-T8). */
@Composable
private fun sidePhrase(offsetM: Double, unit: LinearUnit, symbol: String): String {
    val magnitude = "${OffsetFormat.magnitude(offsetM, unit, uiLocale())} $symbol"
    return when (OffsetFormat.side(offsetM)) {
        OffsetSide.LEFT -> stringResource(R.string.axis_stakeout_side_left, magnitude)
        OffsetSide.RIGHT -> stringResource(R.string.axis_stakeout_side_right, magnitude)
        OffsetSide.ON_AXIS -> stringResource(R.string.axis_stakeout_side_on)
    }
}

/**
 * Lo que dice la guía bajo la distancia: lo que hay que moverse adelante o atrás en PK y hacia dentro o fuera del eje, o que la punta
 * no se proyecta sobre el eje (S054/RF-6, RF-8), con su frase para TalkBack (RNF-T8).
 */
@Composable
private fun AxisFigures(state: AxisStakeoutUiState, ui: GuidanceUi, unit: LinearUnit) {
    if (state.noProjection) {
        Text(
            stringResource(R.string.axis_stakeout_no_projection),
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.testTag(AXIS_NO_PROJECTION_TAG),
        )
        return
    }
    val guidance = state.axisGuidance ?: return
    val numbers = Numbers(unit, unitSymbol(unit), uiLocale())
    val along = alongPhrase(guidance, numbers)
    val across = acrossPhrase(guidance, numbers)
    val phrase = stringResource(R.string.axis_stakeout_a11y, along, across)
    // Se anuncia al entrar en cada zona y no con cada refresco: la frase se fija al cambiar de zona (RNF-T8)
    val announced = remember(ui.zone) { phrase }
    Column(
        modifier = Modifier.semantics(mergeDescendants = true) { contentDescription = phrase }.testTag(AXIS_FIGURES_TAG),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(along, style = MaterialTheme.typography.titleMedium, modifier = Modifier.testTag(AXIS_ALONG_TAG))
        Text(across, style = MaterialTheme.typography.titleMedium, modifier = Modifier.testTag(AXIS_ACROSS_TAG))
    }
    if (ui.zone != StakeZone.FAR) {
        Text(
            announced,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }.testTag(AXIS_ANNOUNCE_TAG),
        )
    }
}

/** Menos de medio milímetro es estar en el PK o en el desplazamiento, la resolución con la que se escriben. */
private const val AT_M = 0.0005

@Composable
private fun alongPhrase(guidance: AxisGuidanceValue, numbers: Numbers): String = when {
    guidance.alongM >= AT_M -> stringResource(R.string.axis_stakeout_along_ahead, numbers.length(guidance.alongM))
    guidance.alongM <= -AT_M -> stringResource(R.string.axis_stakeout_along_back, numbers.length(-guidance.alongM))
    else -> stringResource(R.string.axis_stakeout_along_none)
}

@Composable
private fun acrossPhrase(guidance: AxisGuidanceValue, numbers: Numbers): String {
    val distance = numbers.length(abs(guidance.acrossM))
    return when {
        abs(guidance.acrossM) < AT_M -> stringResource(R.string.axis_stakeout_across_none)
        guidance.across == AcrossMove.INWARD -> stringResource(R.string.axis_stakeout_across_inward, distance)
        guidance.across == AcrossMove.OUTWARD -> stringResource(R.string.axis_stakeout_across_outward, distance)
        guidance.across == AcrossMove.LEFT -> stringResource(R.string.axis_stakeout_across_left, distance)
        guidance.across == AcrossMove.RIGHT -> stringResource(R.string.axis_stakeout_across_right, distance)
        else -> stringResource(R.string.axis_stakeout_across_none)
    }
}

private val BODY_MAX_HEIGHT = 380.dp
