package es.topophone.app.ui.stakeout

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
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
import es.topophone.app.ui.cad.elementKindLabel
import es.topophone.app.ui.collector.AverageStatsUi
import es.topophone.app.ui.collector.CollectorActions
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
import es.topophone.core.model.format.LengthFormat
import es.topophone.core.model.point.ElevationSource
import es.topophone.core.model.point.HeadingChoice
import es.topophone.core.model.point.LineInputError
import es.topophone.core.model.point.LineStakeoutMode
import es.topophone.core.model.point.StakeMode
import es.topophone.core.model.project.LinearUnit
import es.topophone.core.survey.LineObjective
import es.topophone.core.survey.RangePosition
import es.topophone.core.survey.StakeZone

const val LINE_PANEL_TAG = "line_stakeout_panel"
const val LINE_TITLE_TAG = "line_stakeout_title"
const val LINE_CLOSE_TAG = "line_stakeout_close"
const val LINE_ELEMENT_TAG = "line_stakeout_element"
const val LINE_OFFSET_TAG = "line_stakeout_offset"
const val LINE_A_TAG = "line_stakeout_a"
const val LINE_B_TAG = "line_stakeout_b"
const val LINE_INTERVAL_TAG = "line_stakeout_interval"
const val LINE_INVERT_TAG = "line_stakeout_invert"
const val LINE_HERE_TAG = "line_stakeout_here"
const val LINE_PREVIOUS_TAG = "line_stakeout_previous"
const val LINE_NEXT_TAG = "line_stakeout_next"
const val LINE_LATERAL_TAG = "line_stakeout_lateral"
const val LINE_ERROR_TAG = "line_stakeout_error"
const val LINE_CHAINAGE_TAG = "line_stakeout_chainage"
const val LINE_REMAINING_TAG = "line_stakeout_remaining"
const val LINE_POSITION_TAG = "line_stakeout_position"
const val LINE_REIMPORT_TAG = "line_stakeout_reimport"
const val LINE_CLOSED_TAG = "line_stakeout_closed"
const val LINE_UNSUPPORTED_TAG = "line_stakeout_unsupported"
const val LINE_SERIES_EMPTY_TAG = "line_stakeout_series_empty"
const val LINE_ANNOUNCE_TAG = "line_stakeout_announce"

fun lineModeTag(mode: LineStakeoutMode) = "line_stakeout_mode_${mode.name}"

fun lineVertexTag(index: Int) = "line_stakeout_vertex_$index"

fun linePointTag(index: Int) = "line_stakeout_point_$index"

fun lineErrorTag(field: LineField) = "line_stakeout_error_${field.name}"

/** Lo que el usuario hace en el replanteo de una línea (S041/RF-2 a RF-9, RF-16 a RF-22). */
class LineStakeoutActions(
    val onClose: () -> Unit = {},
    val onMode: (LineStakeoutMode) -> Unit = {},
    val onGuideMode: (StakeMode) -> Unit = {},
    val onOffset: (String) -> Unit = {},
    val onA: (String) -> Unit = {},
    val onB: (String) -> Unit = {},
    val onInterval: (String) -> Unit = {},
    val onInvert: () -> Unit = {},
    val onHere: () -> Unit = {},
    val onSelectVertex: (Int) -> Unit = {},
    val onSelectPoint: (Int) -> Unit = {},
    val onPrevious: () -> Unit = {},
    val onNext: () -> Unit = {},
    val onHeading: (HeadingChoice) -> Unit = {},
    val onTolerance: (String) -> Boolean = { false },
    val onToleranceV: (String) -> Boolean = { false },
    val onElevation: (String) -> Boolean = { false },
    val onUseLineElevation: () -> Unit = {},
    /** Contra qué cota se replantea, con el conmutador (S042/RF-25). */
    val onElevationSource: (ElevationSource) -> Unit = {},
)

/**
 * Panel del replanteo de una línea, en el hueco del recolector y sin tapar el mapa (S041/RF-1 a RF-4): la capa, el dibujo, el tipo,
 * la longitud y la cota; los cinco modos; los campos y las listas de cada modo; la guía con el PK, el lado y lo que falta, la
 * diana y el nivel; y "Medir". La pantalla no se apaga mientras está abierto (RNF-T2).
 */
@Composable
fun LineStakeoutPanel(
    state: LineStakeoutUiState,
    actions: LineStakeoutActions,
    modifier: Modifier = Modifier,
    collector: CollectorUiState? = null,
    collectorActions: CollectorActions = CollectorActions(),
    stats: AverageStatsUi? = null,
) {
    KeepScreenOn()
    var settingsOpen by remember { mutableStateOf(false) }
    var elevationOpen by remember { mutableStateOf(false) }
    val unit = state.project?.linearUnit ?: LinearUnit.METRE
    Card(modifier = modifier.fillMaxWidth().padding(8.dp).testTag(LINE_PANEL_TAG)) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Header(state, actions)
            val element = state.element
            if (state.closed != null) {
                Text(
                    stringResource(R.string.stakeout_line_closed_drawing),
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive }.testTag(LINE_CLOSED_TAG),
                )
                return@Column
            }
            if (element == null) {
                if (state.unsupported) {
                    Text(
                        stringResource(R.string.stakeout_line_unsupported),
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.testTag(LINE_UNSUPPORTED_TAG),
                    )
                }
                return@Column
            }
            Column(
                modifier = Modifier.weight(1f, fill = false).heightIn(max = BODY_MAX_HEIGHT).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ElementInfo(element, unit)
                ModeChoice(state, actions)
                if (state.mode == LineStakeoutMode.BY_SEGMENTS && state.vertices?.needsReimport == true) {
                    Text(
                        stringResource(R.string.stakeout_line_reimport),
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.testTag(LINE_REIMPORT_TAG),
                    )
                }
                Inputs(state, actions, unit)
                Lists(state, actions, unit)
                StakeModeChoice(state.settings, state.measuring, actions.onGuideMode)
                ElevationSourceSection(
                    state.surfaceActive,
                    state.elevationSource,
                    state.canChooseSource,
                    state.surfaceRemoved,
                    state.measuring,
                    state.guidance,
                    unit,
                    actions.onElevationSource,
                ) // S042/RF-23 a RF-27
                Guidance(state.project, state.settings, state.guidance) { ui, numbers -> LineFigures(state, ui, numbers) }
                collector?.let { Messages(it, collectorActions) }
            }
            if (collector != null) {
                if (collector.measuring) {
                    Measuring(collector, unit, stats, collectorActions)
                } else {
                    MeasureButton(collector, collectorActions)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(
                        onClick = { elevationOpen = true },
                        enabled = !state.measuring,
                        modifier = Modifier.heightIn(min = MinTouchTarget).testTag(STAKEOUT_ELEVATION_TAG),
                    ) { Text(stringResource(R.string.stakeout_elevation_button)) }
                    TextButton(
                        onClick = { settingsOpen = true },
                        enabled = !state.measuring,
                        modifier = Modifier.heightIn(min = MinTouchTarget).testTag(STAKEOUT_SETTINGS_TAG),
                    ) { Text(stringResource(R.string.stakeout_settings)) }
                }
            }
        }
    }
    if (settingsOpen && !state.measuring) {
        StakeoutSettingsDialog(state.settings, unit, actions.onHeading, actions.onTolerance, { settingsOpen = false }, actions.onToleranceV)
    }
    if (elevationOpen && !state.measuring) {
        StakeoutElevationDialog(
            state.designElevationM,
            state.elevationOverridden,
            unit,
            actions.onElevation,
            actions.onUseLineElevation,
        ) { elevationOpen = false }
    }
    collector?.pending?.let { OutOfToleranceDialog(it, collectorActions.onConfirm, collectorActions.onDiscard) }
    if (collector?.paused == PauseState.LONG_PAUSE) {
        LongPauseDialog(collectorActions.onFinish, collectorActions.onCancel, collectorActions.onKeepWaiting)
    }
}

@Composable
private fun Header(state: LineStakeoutUiState, actions: LineStakeoutActions) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            stringResource(R.string.stakeout_line_title, state.element?.layerName.orEmpty()),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.weight(1f).testTag(LINE_TITLE_TAG),
        )
        TextButton(onClick = actions.onClose, modifier = Modifier.heightIn(min = MinTouchTarget).testTag(LINE_CLOSE_TAG)) {
            Text(stringResource(R.string.collector_close))
        }
    }
}

/** La capa, el dibujo, el tipo, la longitud y la cota de la línea, en la unidad del proyecto (S041/RF-4). */
@Composable
private fun ElementInfo(element: LineElementInfo, unit: LinearUnit) {
    val locale = uiLocale()
    val symbol = unitSymbol(unit)
    val height = when {
        element.minHeightM == null || element.maxHeightM == null -> stringResource(R.string.cad_element_no_elevation)
        element.minHeightM == element.maxHeightM ->
            stringResource(R.string.cad_element_elevation, LengthFormat.inUnit(element.minHeightM, unit, locale), symbol)
        else -> stringResource(
            R.string.cad_element_elevation_range,
            LengthFormat.inUnit(element.minHeightM, unit, locale),
            LengthFormat.inUnit(element.maxHeightM, unit, locale),
            symbol,
        )
    }
    Column(
        modifier = Modifier.semantics(mergeDescendants = true) {
        }.testTag(LINE_ELEMENT_TAG),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(stringResource(elementKindLabel(element.kind)), style = MaterialTheme.typography.titleSmall)
        Text(
            stringResource(R.string.cad_element_layer, element.layerName) + " · " +
                stringResource(R.string.cad_element_drawing, element.drawingName),
        )
        Text(stringResource(R.string.cad_element_length, LengthFormat.inUnit(element.lengthM, unit, locale), symbol) + " · " + height)
    }
}

/** Los cinco modos (S041/RF-3), que no cambian con una medida en marcha (RF-22). */
@Composable
private fun ModeChoice(state: LineStakeoutUiState, actions: LineStakeoutActions) {
    Row(modifier = Modifier.selectableGroup().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        LineStakeoutMode.entries.forEach { mode ->
            FilterChip(
                selected = state.mode == mode,
                onClick = { actions.onMode(mode) },
                enabled = !state.measuring,
                label = { Text(stringResource(mode.labelRes())) },
                modifier = Modifier.heightIn(min = MinTouchTarget).testTag(lineModeTag(mode)),
            )
        }
    }
}

/** Los campos de cada modo: desplazamiento, A y B con "Invertir" y "Mi posición", e intervalo (S041/RF-11, RF-16, RF-18, RF-21). */
@Composable
private fun Inputs(state: LineStakeoutUiState, actions: LineStakeoutActions, unit: LinearUnit) {
    val symbol = unitSymbol(unit)
    val offset =
        state.mode == LineStakeoutMode.ALONG || state.mode == LineStakeoutMode.SEGMENT_AB || state.mode == LineStakeoutMode.INTERVALS
    val range = state.mode == LineStakeoutMode.SEGMENT_AB || state.mode == LineStakeoutMode.INTERVALS
    if (offset) {
        NumberField(
            state.offsetText,
            R.string.stakeout_line_offset_label,
            symbol,
            state.errors[LineField.OFFSET],
            !state.measuring,
            LINE_OFFSET_TAG,
            LineField.OFFSET,
            actions.onOffset,
            helpRes = R.string.stakeout_line_offset_help,
        )
    }
    if (range) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
            Column(modifier = Modifier.weight(1f)) {
                NumberField(
                    state.aText,
                    R.string.stakeout_line_pk_a_label,
                    symbol,
                    state.errors[LineField.A],
                    !state.measuring,
                    LINE_A_TAG,
                    LineField.A,
                    actions.onA,
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                NumberField(
                    state.bText,
                    R.string.stakeout_line_pk_b_label,
                    symbol,
                    state.errors[LineField.B],
                    !state.measuring,
                    LINE_B_TAG,
                    LineField.B,
                    actions.onB,
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = actions.onInvert,
                enabled = !state.measuring,
                modifier = Modifier.heightIn(min = MinTouchTarget).testTag(LINE_INVERT_TAG),
            ) { Text(stringResource(R.string.stakeout_line_invert)) }
            OutlinedButton(
                onClick = actions.onHere,
                enabled = !state.measuring && state.guidance is GuidanceState.On,
                modifier = Modifier.heightIn(min = MinTouchTarget).testTag(LINE_HERE_TAG),
            ) { Text(stringResource(R.string.stakeout_line_here)) }
        }
    }
    if (state.mode == LineStakeoutMode.INTERVALS) {
        NumberField(
            state.intervalText,
            R.string.stakeout_line_interval_label,
            symbol,
            state.errors[LineField.INTERVAL],
            !state.measuring,
            LINE_INTERVAL_TAG,
            LineField.INTERVAL,
            actions.onInterval,
        )
    }
}

@Composable
private fun NumberField(
    value: String,
    labelRes: Int,
    symbol: String,
    error: LineInputError?,
    enabled: Boolean,
    tag: String,
    field: LineField,
    onChange: (String) -> Unit,
    helpRes: Int? = null,
) {
    var text by remember(value) { mutableStateOf(value) }
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
                { Text(stringResource(error.textRes()), modifier = Modifier.testTag(lineErrorTag(field))) }
            }
            helpRes != null -> {
                { Text(stringResource(helpRes)) }
            }
            else -> null
        },
        enabled = enabled,
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = Modifier.fillMaxWidth().heightIn(min = MinTouchTarget).testTag(tag),
    )
}

/** La lista de vértices de "Por segmentos" o de puntos de "Intervalos", con "Anterior" y "Siguiente" (S041/RF-7, RF-8, RF-20). */
@Composable
private fun Lists(state: LineStakeoutUiState, actions: LineStakeoutActions, unit: LinearUnit) {
    val locale = uiLocale()
    val symbol = unitSymbol(unit)
    when (state.mode) {
        LineStakeoutMode.BY_SEGMENTS -> {
            val vertices = state.vertices ?: return
            Text(stringResource(R.string.stakeout_line_vertices), style = MaterialTheme.typography.titleSmall)
            PreviousNext(state, actions)
            LazyColumn(modifier = Modifier.heightIn(max = LIST_MAX_HEIGHT).selectableGroup()) {
                items(vertices.size) { index ->
                    val entry = vertices.entries[index]
                    ListRow(
                        text = stringResource(
                            R.string.stakeout_line_item,
                            entry.number,
                            "${LengthFormat.inUnit(entry.chainageM, unit, locale)} $symbol",
                        ),
                        selected = index == state.vertexIndex,
                        enabled = !state.measuring,
                        tag = lineVertexTag(index),
                        onClick = { actions.onSelectVertex(index) },
                    )
                }
            }
        }
        LineStakeoutMode.INTERVALS -> {
            val series = state.series
            Text(stringResource(R.string.stakeout_line_points), style = MaterialTheme.typography.titleSmall)
            if (series == null) {
                Text(stringResource(R.string.stakeout_line_series_empty), modifier = Modifier.testTag(LINE_SERIES_EMPTY_TAG))
                return
            }
            PreviousNext(state, actions)
            LazyColumn(modifier = Modifier.heightIn(max = LIST_MAX_HEIGHT).selectableGroup()) {
                items(series.size) { index ->
                    ListRow(
                        text = stringResource(
                            R.string.stakeout_line_item,
                            index + 1,
                            "${LengthFormat.inUnit(series.chainageOf(index), unit, locale)} $symbol",
                        ),
                        selected = index == state.seriesIndex,
                        enabled = !state.measuring,
                        tag = linePointTag(index),
                        onClick = { actions.onSelectPoint(index) },
                    )
                }
            }
        }
        else -> Unit
    }
}

@Composable
private fun PreviousNext(state: LineStakeoutUiState, actions: LineStakeoutActions) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(
            onClick = actions.onPrevious,
            enabled = !state.measuring,
            modifier = Modifier.heightIn(min = MinTouchTarget).testTag(LINE_PREVIOUS_TAG),
        ) { Text(stringResource(R.string.stakeout_previous)) }
        OutlinedButton(
            onClick = actions.onNext,
            enabled = !state.measuring,
            modifier = Modifier.heightIn(min = MinTouchTarget).testTag(LINE_NEXT_TAG),
        ) { Text(stringResource(R.string.stakeout_next)) }
    }
}

@Composable
private fun ListRow(text: String, selected: Boolean, enabled: Boolean, tag: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = MinTouchTarget)
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(text, modifier = Modifier.weight(1f))
        // El elegido se dice con texto, no solo con el color de la fila (RNF-T8)
        if (selected) Text(stringResource(R.string.stakeout_line_selected), style = MaterialTheme.typography.labelLarge)
    }
}

/**
 * Lo que dice la guía de una línea bajo la distancia: el lado con su distancia o el error lateral, el PK y lo que falta, o que la
 * punta está antes del inicio o después del final (S041/RF-10, RF-11, RF-14, RF-17), y su frase para TalkBack (RNF-T8).
 */
@Composable
private fun LineFigures(state: LineStakeoutUiState, ui: GuidanceUi, numbers: Numbers) {
    val line = ui.line ?: return
    val mode = state.mode
    val lateralPhrase = lateralPhrase(line, mode, numbers, ui.guidance.distanceM)
    val chainage = numbers.length(line.chainageM)
    val phrase = stringResource(R.string.stakeout_line_a11y, lateralPhrase, chainage)
    // Se anuncia al entrar en cada zona y no con cada refresco: la frase se fija al cambiar de zona (RNF-T8)
    val announced = remember(ui.zone) { phrase }
    Column(
        modifier = Modifier.semantics(mergeDescendants = true) { contentDescription = phrase }.testTag(LINE_POSITION_TAG),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(lateralPhrase, style = MaterialTheme.typography.titleMedium, modifier = Modifier.testTag(LINE_LATERAL_TAG))
        val lateralError = line.lateralErrorM
        if (mode != LineStakeoutMode.TO_LINE && lateralError != null) {
            Text(errorPhrase(lateralError, numbers), modifier = Modifier.testTag(LINE_ERROR_TAG))
        }
        Text(stringResource(R.string.stakeout_line_chainage, chainage), modifier = Modifier.testTag(LINE_CHAINAGE_TAG))
        val remaining = if (mode == LineStakeoutMode.SEGMENT_AB) {
            stringResource(R.string.stakeout_line_from_a, numbers.length(line.alongM), numbers.length(line.remainingM))
        } else {
            stringResource(R.string.stakeout_line_remaining, numbers.length(line.remainingM))
        }
        Text(remaining, modifier = Modifier.testTag(LINE_REMAINING_TAG))
    }
    if (ui.zone != StakeZone.FAR) {
        Text(
            announced,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }.testTag(LINE_ANNOUNCE_TAG),
        )
    }
}

@Composable
private fun lateralPhrase(line: LineObjective, mode: LineStakeoutMode, numbers: Numbers, distanceM: Double): String {
    if (line.position != RangePosition.WITHIN) {
        val distance = numbers.length(distanceM)
        val ab = mode == LineStakeoutMode.SEGMENT_AB
        val resource = when {
            line.position == RangePosition.BEFORE && ab -> R.string.stakeout_line_before_a
            line.position == RangePosition.AFTER && ab -> R.string.stakeout_line_after_b
            line.position == RangePosition.BEFORE -> R.string.stakeout_line_before_start
            else -> R.string.stakeout_line_after_end
        }
        return stringResource(resource, distance)
    }
    val lateral = line.location.lateralM
    return when {
        lateral > 0.0 -> stringResource(R.string.stakeout_line_lateral_right, numbers.length(lateral))
        lateral < 0.0 -> stringResource(R.string.stakeout_line_lateral_left, numbers.length(-lateral))
        else -> stringResource(R.string.stakeout_line_lateral_on)
    }
}

@Composable
private fun errorPhrase(error: Double, numbers: Numbers): String = when {
    error > 0.0 -> stringResource(R.string.stakeout_line_error_lateral_right, numbers.length(error))
    error < 0.0 -> stringResource(R.string.stakeout_line_error_lateral_left, numbers.length(-error))
    else -> stringResource(R.string.stakeout_line_error_lateral_none)
}

internal fun LineStakeoutMode.labelRes(): Int = when (this) {
    LineStakeoutMode.BY_SEGMENTS -> R.string.stakeout_line_mode_by_segments
    LineStakeoutMode.TO_LINE -> R.string.stakeout_line_mode_to_line
    LineStakeoutMode.ALONG -> R.string.stakeout_line_mode_along
    LineStakeoutMode.SEGMENT_AB -> R.string.stakeout_line_mode_segment_ab
    LineStakeoutMode.INTERVALS -> R.string.stakeout_line_mode_intervals
}

private fun LineInputError.textRes(): Int = when (this) {
    LineInputError.NOT_A_NUMBER -> R.string.stakeout_line_error_not_a_number
    LineInputError.OUT_OF_LINE -> R.string.stakeout_line_error_out_of_line
    LineInputError.SAME_PK -> R.string.stakeout_line_error_same_pk
    LineInputError.INTERVAL_NOT_POSITIVE -> R.string.stakeout_line_error_interval
}

private val BODY_MAX_HEIGHT = 380.dp
private val LIST_MAX_HEIGHT = 168.dp
