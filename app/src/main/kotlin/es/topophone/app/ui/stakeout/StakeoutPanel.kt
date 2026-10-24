package es.topophone.app.ui.stakeout

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import es.topophone.app.R
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
import es.topophone.app.ui.receiver.nameRes
import es.topophone.app.ui.receiver.uiLocale
import es.topophone.core.designsystem.component.MinTouchTarget
import es.topophone.core.model.format.AngleFormat
import es.topophone.core.model.format.LengthFormat
import es.topophone.core.model.point.HeadingChoice
import es.topophone.core.model.point.PointCoordinates
import es.topophone.core.model.point.StakeMode
import es.topophone.core.model.point.StakeoutRef
import es.topophone.core.model.project.AngularUnit
import es.topophone.core.model.project.LinearUnit
import es.topophone.core.survey.HeadingResult
import es.topophone.core.survey.HeadingUnavailable
import es.topophone.core.survey.StakeZone
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

const val STAKEOUT_PANEL_TAG = "stakeout_panel"
const val STAKEOUT_NAME_TAG = "stakeout_name"
const val STAKEOUT_DESCRIPTION_TAG = "stakeout_description"
const val STAKEOUT_DESIGN_TAG = "stakeout_design"
const val STAKEOUT_CLOSE_TAG = "stakeout_close"
const val STAKEOUT_PREVIOUS_TAG = "stakeout_previous"
const val STAKEOUT_NEXT_TAG = "stakeout_next"
const val STAKEOUT_OFF_TAG = "stakeout_off"
const val STAKEOUT_DISTANCE_TAG = "stakeout_distance"
const val STAKEOUT_NORTH_TAG = "stakeout_north"
const val STAKEOUT_EAST_TAG = "stakeout_east"
const val STAKEOUT_AHEAD_TAG = "stakeout_ahead"
const val STAKEOUT_RIGHT_TAG = "stakeout_right"
const val STAKEOUT_HEADING_TAG = "stakeout_heading"
const val STAKEOUT_STATUS_TAG = "stakeout_status"
const val STAKEOUT_RESULT_TAG = "stakeout_result"

fun stakeModeTag(mode: StakeMode) = "stakeout_mode_${mode.name}"

/** Lo que el usuario hace en el replanteo (S018/RF-1 a RF-8). */
class StakeoutActions(
    val onClose: () -> Unit = {},
    val onPrevious: () -> Unit = {},
    val onNext: () -> Unit = {},
    val onMode: (StakeMode) -> Unit = {},
    /** El origen del rumbo y la tolerancia, en el diálogo de ajustes; la tolerancia da false si no vale (S018/RF-8, RF-18). */
    val onHeading: (HeadingChoice) -> Unit = {},
    val onTolerance: (String) -> Boolean = { false },
)

/**
 * Panel del replanteo, en el hueco del recolector y sin tapar el mapa (S018/RF-1): el punto de diseño con "Anterior" y
 * "Siguiente", los dos modos de guía y, según el modo, la distancia con las diferencias de Norte y Este o con adelante y
 * derecha, el rumbo, la diferencia de cota, el estado de la solución o por qué la guía está apagada. La pantalla no se apaga
 * mientras está abierto (RNF-T2).
 */
@Composable
fun StakeoutPanel(
    state: StakeoutUiState,
    actions: StakeoutActions,
    modifier: Modifier = Modifier,
    collector: CollectorUiState? = null,
    collectorActions: CollectorActions = CollectorActions(),
    stats: AverageStatsUi? = null,
) {
    KeepScreenOn()
    var settingsOpen by remember { mutableStateOf(false) }
    val target = state.target
    Card(modifier = modifier.fillMaxWidth().padding(8.dp).testTag(STAKEOUT_PANEL_TAG)) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Header(state, actions)
            if (target == null) {
                if (state.notFound) Text(stringResource(R.string.stakeout_not_found), color = MaterialTheme.colorScheme.error)
                return@Column
            }
            Column(
                modifier = Modifier.weight(1f, fill = false).heightIn(max = BODY_MAX_HEIGHT).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Design(state, target)
                // Lo que se desvió el último punto, arriba: es lo que se mira al medir y no debe quedar bajo el pliegue.
                collector?.savedStake?.let { Result(collector.savedName, it, state) }
                ModeChoice(state, actions)
                Guidance(state)
                collector?.let { Messages(it, collectorActions) } // los motivos por los que no se puede medir, avisos y el punto guardado
            }
            if (collector != null) {
                val unit = state.project?.linearUnit ?: LinearUnit.METRE
                if (collector.measuring) {
                    Measuring(collector, unit, stats, collectorActions) // S018/RF-26, RF-30
                } else {
                    MeasureButton(collector, collectorActions) // S018/RF-26
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
        StakeoutSettingsDialog(
            state.settings,
            state.project?.linearUnit ?: LinearUnit.METRE,
            actions.onHeading,
            actions.onTolerance,
        ) { settingsOpen = false }
    }
    collector?.pending?.let { OutOfToleranceDialog(it, collectorActions.onConfirm, collectorActions.onDiscard) } // S018/RF-26
    if (collector?.paused == PauseState.LONG_PAUSE) {
        LongPauseDialog(collectorActions.onFinish, collectorActions.onCancel, collectorActions.onKeepWaiting)
    }
}

/** Lo que se desvió el punto replanteado respecto al diseño, medido menos diseño (S018/RF-28, RF-29). */
@Composable
private fun Result(savedName: String?, ref: StakeoutRef, state: StakeoutUiState) {
    val unit = state.project?.linearUnit ?: LinearUnit.METRE
    val numbers = Numbers(unit, unitSymbol(unit), uiLocale())
    val text = ref.dHeightM?.let {
        stringResource(
            R.string.stakeout_result_height,
            savedName.orEmpty(),
            numbers.signed(ref.dNorthM),
            numbers.signed(ref.dEastM),
            numbers.length(ref.dHorizontalM),
            numbers.signed(it),
        )
    } ?: stringResource(
        R.string.stakeout_result,
        savedName.orEmpty(),
        numbers.signed(ref.dNorthM),
        numbers.signed(ref.dEastM),
        numbers.length(ref.dHorizontalM),
    )
    Text(text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.testTag(STAKEOUT_RESULT_TAG))
}

@Composable
private fun Header(state: StakeoutUiState, actions: StakeoutActions) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            stringResource(R.string.stakeout_title, state.target?.name.orEmpty()),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.weight(1f).testTag(STAKEOUT_NAME_TAG),
        )
        TextButton(
            onClick = actions.onPrevious,
            enabled = state.hasPrevious && !state.measuring,
            modifier = Modifier.heightIn(min = MinTouchTarget).testTag(STAKEOUT_PREVIOUS_TAG),
        ) { Text(stringResource(R.string.stakeout_previous)) }
        TextButton(
            onClick = actions.onNext,
            enabled = state.hasNext && !state.measuring,
            modifier = Modifier.heightIn(min = MinTouchTarget).testTag(STAKEOUT_NEXT_TAG),
        ) { Text(stringResource(R.string.stakeout_next)) }
        TextButton(onClick = actions.onClose, modifier = Modifier.heightIn(min = MinTouchTarget).testTag(STAKEOUT_CLOSE_TAG)) {
            Text(stringResource(R.string.collector_close))
        }
    }
}

/** El nombre, la descripción y las coordenadas del punto de diseño, con su altura si la tiene (S018/RF-4). */
@Composable
private fun Design(state: StakeoutUiState, target: StakeTarget) {
    val unit = state.project?.linearUnit ?: LinearUnit.METRE
    val locale = uiLocale()
    val symbol = unitSymbol(unit)
    if (target.description.isNotEmpty()) {
        Text(target.description, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag(STAKEOUT_DESCRIPTION_TAG))
    }
    Text(
        designText(target.coordinates, unit, symbol, locale),
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.semantics(mergeDescendants = true) {}.testTag(STAKEOUT_DESIGN_TAG),
    )
}

@Composable
private fun designText(coordinates: PointCoordinates, unit: LinearUnit, symbol: String, locale: Locale): String {
    fun length(value: Double) = "${LengthFormat.inUnit(value, unit, locale)} $symbol"
    val east = coordinates.easting
    val north = coordinates.northing
    val parts = buildList {
        if (east != null && north != null) {
            add(stringResource(R.string.stakeout_design_east, length(east)))
            add(stringResource(R.string.stakeout_design_north, length(north)))
        }
        coordinates.height?.let { add(stringResource(R.string.stakeout_design_height, length(it))) }
    }
    return parts.joinToString(" · ")
}

/** "Por dirección" o "Por distancia" (S018/RF-5). */
@Composable
private fun ModeChoice(state: StakeoutUiState, actions: StakeoutActions) {
    Row(modifier = Modifier.selectableGroup(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        StakeMode.entries.forEach { mode ->
            FilterChip(
                selected = state.settings.mode == mode,
                onClick = { actions.onMode(mode) },
                enabled = !state.measuring, // S018/RF-30
                label = { Text(stringResource(mode.textRes())) },
                modifier = Modifier.heightIn(min = MinTouchTarget).testTag(stakeModeTag(mode)),
            )
        }
    }
}

@Composable
private fun Guidance(state: StakeoutUiState) {
    val unit = state.project?.linearUnit ?: LinearUnit.METRE
    val angular = state.project?.angularUnit ?: AngularUnit.DECIMAL_DEGREES
    when (val guidance = state.guidance) {
        is GuidanceState.Off -> Text(
            stringResource(guidance.reason.textRes()),
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }.testTag(STAKEOUT_OFF_TAG),
        )
        is GuidanceState.On -> {
            val ui = guidance.ui
            val numbers = Numbers(unit, unitSymbol(unit), uiLocale())
            Text(
                stringResource(R.string.stakeout_distance, numbers.length(ui.guidance.distanceM)),
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.testTag(STAKEOUT_DISTANCE_TAG),
            )
            val byDirection = state.settings.mode == StakeMode.BY_DIRECTION
            val ahead = ui.guidance.aheadM
            val right = ui.guidance.rightM
            val hasArrow = byDirection && ahead != null && right != null // S018/RF-7
            val hasDiana = ui.zone != StakeZone.FAR // S018/RF-15 a RF-17
            // Los dibujos y las cifras juntos, para que la diana y la cota no queden bajo el pliegue del panel.
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (hasArrow && ahead != null && right != null) {
                    StakeoutArrow(ahead, right, Modifier.size(if (hasDiana) ARROW_BESIDE_DIANA else ARROW_ALONE))
                }
                if (hasDiana) {
                    // En la zona de ayuda, la diana; con el rumbo del usuario arriba si hay flecha.
                    val headingUp = (ui.heading as? HeadingResult.Available)?.rad?.takeIf { byDirection }
                    StakeoutDiana(ui, state.settings.validToleranceM, headingUp, Modifier.size(DIANA_SIZE))
                }
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    GuidanceFigures(state, ui, numbers)
                }
                // S019/RF-7: el nivel vertical al lado de la diana o de la flecha, a cualquier distancia del punto.
                ui.level?.let { StakeoutLevelView(it, numbers, Modifier.width(LEVEL_WIDTH).height(DIANA_SIZE)) }
            }
            GuidanceNotes(state, ui, numbers, angular)
            Text(
                stringResource(
                    R.string.stakeout_status,
                    stringResource(ui.solution.nameRes()),
                    ui.horizontalAccuracyM?.let { numbers.length(it) } ?: stringResource(R.string.collector_no_accuracy),
                ),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.semantics(mergeDescendants = true) {}.testTag(STAKEOUT_STATUS_TAG),
            )
        }
    }
}

/**
 * Lo que dice la guía con cifras, al lado de la flecha y la diana: la llegada y las diferencias (S018/RF-6, RF-7, RF-11, RF-19).
 */
@Composable
private fun GuidanceFigures(state: StakeoutUiState, ui: GuidanceUi, numbers: Numbers) {
    if (ui.zone == StakeZone.INSIDE) {
        Text(
            stringResource(R.string.stakeout_inside),
            color = MaterialTheme.colorScheme.primary,
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }.testTag(STAKEOUT_INSIDE_TAG),
        ) // S018/RF-19: con texto, no solo con color (RNF-T8)
    }
    if (state.settings.mode == StakeMode.BY_DISTANCE) {
        Text(
            stringResource(R.string.stakeout_north, numbers.signed(ui.guidance.dNorthM)),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.testTag(STAKEOUT_NORTH_TAG),
        )
        Text(
            stringResource(R.string.stakeout_east, numbers.signed(ui.guidance.dEastM)),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.testTag(STAKEOUT_EAST_TAG),
        )
    } else {
        ui.guidance.aheadM?.let { ahead ->
            Text(
                stringResource(if (ahead >= 0.0) R.string.stakeout_ahead else R.string.stakeout_back, numbers.length(abs(ahead))),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.testTag(STAKEOUT_AHEAD_TAG),
            )
        }
        ui.guidance.rightM?.let { right ->
            Text(
                stringResource(if (right >= 0.0) R.string.stakeout_right else R.string.stakeout_left, numbers.length(abs(right))),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.testTag(STAKEOUT_RIGHT_TAG),
            )
        }
        if (ui.guidance.aheadM == null) {
            // S018/RF-11: sin rumbo, la distancia y las diferencias de Norte y Este siguen a la vista.
            val north = stringResource(R.string.stakeout_north, numbers.signed(ui.guidance.dNorthM))
            val east = stringResource(R.string.stakeout_east, numbers.signed(ui.guidance.dEastM))
            Text(north, modifier = Modifier.testTag(STAKEOUT_NORTH_TAG))
            Text(east, modifier = Modifier.testTag(STAKEOUT_EAST_TAG))
        }
    }
}

/** El rumbo de la flecha y la diferencia de cota, a todo el ancho: su texto es más largo que el de las cifras (S018/RF-9 a RF-12). */
@Composable
private fun GuidanceNotes(state: StakeoutUiState, ui: GuidanceUi, numbers: Numbers, angular: AngularUnit) {
    if (state.settings.mode == StakeMode.BY_DIRECTION) {
        Text(headingText(ui.heading, state.settings.heading, angular), modifier = Modifier.testTag(STAKEOUT_HEADING_TAG))
    }
    // S019/RF-6, RF-8: "Desmonte", "Terraplén" o "En cota"; sin nivel, por qué falta (RF-5). Sustituye al dato de S018/RF-12.
    ui.level?.let { StakeoutLevelReading(it, numbers) }
    ui.levelUnavailable?.let { StakeoutLevelUnavailable(it) }
}

/** Las cifras de la guía en la unidad del proyecto (RNF-T9). */
internal class Numbers(private val unit: LinearUnit, private val symbol: String, private val locale: Locale) {
    fun length(valueM: Double) = "${LengthFormat.inUnit(valueM, unit, locale)} $symbol"

    /** Con "+" delante si es positiva: hacia el Norte o el Este, o la punta por encima del diseño (S018/RF-6, RF-12). */
    fun signed(valueM: Double): String {
        val text = length(valueM)
        return if (valueM > 0.0 && !text.startsWith("-")) "+$text" else text
    }
}

@Composable
private fun headingText(heading: HeadingResult, choice: HeadingChoice, angular: AngularUnit): String {
    val locale = uiLocale()
    return when (heading) {
        is HeadingResult.Available -> {
            val value = AngleFormat.direction(heading.rad, angular, locale)
            when {
                heading.ageS > 0.0 -> stringResource(R.string.stakeout_heading_last, heading.ageS.roundToInt(), value)
                choice == HeadingChoice.IMU -> stringResource(R.string.stakeout_heading_imu, value)
                else -> stringResource(R.string.stakeout_heading_walking, value)
            }
        }
        is HeadingResult.Unavailable -> stringResource(
            when (heading.reason) {
                HeadingUnavailable.NOT_MOVING -> R.string.stakeout_heading_not_moving
                HeadingUnavailable.NO_IMU -> R.string.stakeout_heading_no_imu
                HeadingUnavailable.IMU_NOT_READY -> R.string.stakeout_heading_imu_not_ready
            },
        )
    }
}

private fun StakeMode.textRes(): Int = when (this) {
    StakeMode.BY_DIRECTION -> R.string.stakeout_mode_direction
    StakeMode.BY_DISTANCE -> R.string.stakeout_mode_distance
}

private fun GuidanceOff.textRes(): Int = when (this) {
    GuidanceOff.NO_POSITION -> R.string.stakeout_off_no_position
    GuidanceOff.NO_DATA -> R.string.stakeout_off_no_data
    GuidanceOff.NO_FIX -> R.string.stakeout_off_no_fix
    GuidanceOff.TILT_NOT_READY -> R.string.stakeout_off_tilt
}

/** S018 y RNF-T2: la pantalla no se apaga con el replanteo abierto. */
@Composable
private fun KeepScreenOn() {
    val view = LocalView.current
    DisposableEffect(view) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }
}

/** El cuerpo no pasa de esto, para que el panel deje ver el mapa; si no cabe, se desplaza. */
private val BODY_MAX_HEIGHT = 380.dp
private val ARROW_ALONE = 96.dp
private val ARROW_BESIDE_DIANA = 72.dp
private val DIANA_SIZE = 130.dp
private val LEVEL_WIDTH = 44.dp
