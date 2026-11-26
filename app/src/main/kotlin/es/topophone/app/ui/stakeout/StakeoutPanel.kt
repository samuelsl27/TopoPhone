package es.topophone.app.ui.stakeout

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
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
import es.topophone.app.ui.collector.AdmittedBadge
import es.topophone.app.ui.collector.AverageStatsUi
import es.topophone.app.ui.collector.CollectorActions
import es.topophone.app.ui.collector.CollectorUiState
import es.topophone.app.ui.projects.TerrainMark
import es.topophone.app.ui.projects.rememberTerrainView
import es.topophone.app.ui.projects.unitSymbol
import es.topophone.app.ui.receiver.nameRes
import es.topophone.app.ui.receiver.uiLocale
import es.topophone.core.designsystem.component.MinTouchTarget
import es.topophone.core.model.format.AngleFormat
import es.topophone.core.model.format.LengthFormat
import es.topophone.core.model.point.AdmittedSolutions
import es.topophone.core.model.point.ElevationSource
import es.topophone.core.model.point.HeadingChoice
import es.topophone.core.model.point.PointCoordinates
import es.topophone.core.model.point.StakeMode
import es.topophone.core.model.point.StakeoutRef
import es.topophone.core.model.point.StakeoutSettings
import es.topophone.core.model.project.AngularUnit
import es.topophone.core.model.project.LinearUnit
import es.topophone.core.model.project.Project
import es.topophone.core.survey.HeadingResult
import es.topophone.core.survey.HeadingUnavailable
import es.topophone.core.survey.StakeZone
import es.topophone.core.survey.TerrainView
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
const val STAKEOUT_TERRAIN_TAG = "stakeout_terrain"
const val STAKEOUT_NORTH_TAG = "stakeout_north"
const val STAKEOUT_EAST_TAG = "stakeout_east"
const val STAKEOUT_AHEAD_TAG = "stakeout_ahead"
const val STAKEOUT_RIGHT_TAG = "stakeout_right"
const val STAKEOUT_HEADING_TAG = "stakeout_heading"
const val STAKEOUT_STATUS_TAG = "stakeout_status"
const val STAKEOUT_RESULT_TAG = "stakeout_result"
const val STAKEOUT_RESULT_LEVEL_TAG = "stakeout_result_level"
const val STAKEOUT_RESULT_WITHIN_TAG = "stakeout_result_within"
const val STAKEOUT_RESULT_LINE_TAG = "stakeout_result_line"

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
    /** La tolerancia vertical (S019/RF-10) y la cota de diseño: escribir una y volver a la del punto (S019/RF-2, RF-3, RF-4). */
    val onToleranceV: (String) -> Boolean = { false },
    val onElevation: (String) -> Boolean = { false },
    val onUseDesignElevation: () -> Unit = {},
    /** Contra qué cota se replantea, con el conmutador (S042/RF-25). */
    val onElevationSource: (ElevationSource) -> Unit = {},
)

/**
 * El replanteo de un punto como hoja de campo (S060/RF-23): la placa de guiado con la flecha, la distancia y los desplazamientos, la
 * fila de ajustes (tolerancias, cota y modo), «Anterior», «Medir» y «Siguiente»; expandida, el punto de diseño, la cota de la superficie,
 * la diana, el nivel, el rumbo y los ajustes del rumbo (S018/RF-1 a RF-8; S019). La pantalla no se apaga mientras está abierta (RNF-T2).
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
    var settingsOpen by remember { mutableStateOf(false) }
    var elevationOpen by remember { mutableStateOf(false) }
    val target = state.target
    val unit = state.project?.linearUnit ?: LinearUnit.METRE
    val locale = uiLocale()
    val symbol = unitSymbol(unit)
    StakeoutSheet(
        title = stringResource(R.string.stakeout_title, target?.name.orEmpty()),
        onClose = actions.onClose,
        live = collector?.live,
        collector = collector,
        collectorActions = collectorActions,
        navigation = StakeNavigation(
            state.hasPrevious && !state.measuring,
            state.hasNext && !state.measuring,
            actions.onPrevious,
            actions.onNext,
        ),
        modifier = modifier,
        unit = unit,
        tag = STAKEOUT_PANEL_TAG,
        closeTag = STAKEOUT_CLOSE_TAG,
        titleTag = STAKEOUT_NAME_TAG,
        stats = stats,
        plate = {
            if (target == null) {
                if (state.notFound) Text(stringResource(R.string.stakeout_not_found), color = MaterialTheme.colorScheme.error)
            } else {
                GuidancePlate(state.project, state.settings, state.guidance, extra = { ui, _ -> HeadingNote(state, ui) })
            }
        },
        pills = {
            if (target != null) {
                StakeoutPills(
                    unit = unit,
                    settings = state.settings,
                    enabled = !state.measuring,
                    showToleranceV = state.designElevationM != null,
                    elevationText = state.designElevationM?.let { "${LengthFormat.inUnit(it, unit, locale)} $symbol" },
                    onTolerance = actions.onTolerance,
                    onToleranceV = actions.onToleranceV,
                    onMode = actions.onMode,
                    onElevation = { elevationOpen = true },
                )
            }
        },
        result = collector?.savedStake?.let { ref -> @Composable { ResultLine(ref) } },
        expandedExtra = {
            if (target != null) {
                Design(state, target)
                // Lo que se desvió el último punto, con todas sus cifras (S018/RF-28, RF-29).
                collector?.savedStake?.let { Result(collector.savedName, it, state) }
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
                (state.guidance as? GuidanceState.On)?.let { ExpandedGuidance(state, it.ui) }
                OutlinedButton(
                    onClick = { settingsOpen = true },
                    enabled = !state.measuring,
                    modifier = Modifier.heightIn(min = MinTouchTarget).testTag(STAKEOUT_SETTINGS_TAG),
                ) { Text(stringResource(R.string.stakeout_settings)) }
            }
        },
    )
    if (settingsOpen && !state.measuring) {
        StakeoutSettingsDialog(
            state.settings,
            unit,
            actions.onHeading,
            actions.onTolerance,
            onDismiss = { settingsOpen = false },
            onToleranceV = actions.onToleranceV,
        )
    }
    if (elevationOpen && !state.measuring) {
        StakeoutElevationDialog(
            state.designElevationM,
            state.elevationOverridden,
            unit,
            actions.onElevation,
            actions.onUseDesignElevation,
        ) { elevationOpen = false }
    }
}

/** Una línea con si el último punto replanteado cumple la tolerancia, en la fila extra de la hoja plegada (S018/RF-29; S060/RF-22). */
@Composable
internal fun ResultLine(ref: StakeoutRef) {
    Text(
        ref.withinText(),
        color = when (ref.withinTolerance) {
            true -> MaterialTheme.colorScheme.primary
            false -> MaterialTheme.colorScheme.onSurface
            null -> MaterialTheme.colorScheme.onSurface
        },
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.testTag(STAKEOUT_RESULT_LINE_TAG),
    )
}

/** Sin rumbo por dirección, dice por qué en la placa para que se vea por qué no hay flecha (S018/RF-11). */
@Composable
private fun HeadingNote(state: StakeoutUiState, ui: GuidanceUi) {
    if (state.settings.mode == StakeMode.BY_DIRECTION && ui.guidance.aheadM == null) {
        Text(
            headingText(ui.heading, state.settings.heading, state.project?.angularUnit ?: AngularUnit.DECIMAL_DEGREES),
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

/** En la hoja expandida: la diana, el nivel vertical, el estado y el rumbo (S018/RF-9 a RF-19; S019/RF-6 a RF-8). */
@Composable
private fun ExpandedGuidance(state: StakeoutUiState, ui: GuidanceUi) {
    val unit = state.project?.linearUnit ?: LinearUnit.METRE
    val numbers = Numbers(unit, unitSymbol(unit), uiLocale())
    val angular = state.project?.angularUnit ?: AngularUnit.DECIMAL_DEGREES
    val byDirection = state.settings.mode == StakeMode.BY_DIRECTION
    val hasDiana = ui.zone != StakeZone.FAR // S018/RF-15 a RF-17
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        if (hasDiana) {
            val headingUp = (ui.heading as? HeadingResult.Available)?.rad?.takeIf { byDirection }
            StakeoutDiana(ui, state.settings.validToleranceM, headingUp, Modifier.size(DIANA_SIZE))
        }
        // S019/RF-7: el nivel vertical a cualquier distancia del punto.
        ui.level?.let { StakeoutLevelView(it, numbers, Modifier.width(LEVEL_WIDTH).height(DIANA_SIZE)) }
    }
    // El rumbo de la flecha; el nivel (Desmonte, Terraplén) ya está en la placa (S060/RF-24).
    if (state.settings.mode == StakeMode.BY_DIRECTION) {
        Text(headingText(ui.heading, state.settings.heading, angular), modifier = Modifier.testTag(STAKEOUT_HEADING_TAG))
    }
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

/** Lo que se desvió el punto replanteado respecto al diseño, medido menos diseño (S018/RF-28, RF-29). */
@Composable
private fun Result(savedName: String?, ref: StakeoutRef, state: StakeoutUiState) {
    val unit = state.project?.linearUnit ?: LinearUnit.METRE
    val numbers = Numbers(unit, unitSymbol(unit), uiLocale())
    // S058/RF-9: con el proyecto en terreno, lo que se desvió el punto se da en terreno.
    val terrain = state.project?.let { rememberTerrainView(it) } ?: TerrainView.GRID
    val text = ref.dHeightM?.let {
        stringResource(
            R.string.stakeout_result_height,
            savedName.orEmpty(),
            numbers.signed(terrain.distance(ref.dNorthM)),
            numbers.signed(terrain.distance(ref.dEastM)),
            numbers.length(terrain.distance(ref.dHorizontalM)),
            numbers.signed(it),
        )
    } ?: stringResource(
        R.string.stakeout_result,
        savedName.orEmpty(),
        numbers.signed(terrain.distance(ref.dNorthM)),
        numbers.signed(terrain.distance(ref.dEastM)),
        numbers.length(terrain.distance(ref.dHorizontalM)),
    )
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.testTag(STAKEOUT_RESULT_TAG))
        // S019/RF-15: la cota de diseño usada, el desmonte o terraplén y si cumple, con texto además de color.
        ref.designElevationM?.let { elevation ->
            val level = ref.levelText(numbers::length)?.let { " · $it" }.orEmpty()
            Text(
                stringResource(R.string.stakeout_result_elevation, numbers.length(elevation)) + level,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.testTag(STAKEOUT_RESULT_LEVEL_TAG),
            )
        }
        Text(
            ref.withinText(),
            color = when (ref.withinTolerance) {
                true -> MaterialTheme.colorScheme.primary
                false -> MaterialTheme.colorScheme.error
                null -> MaterialTheme.colorScheme.onSurface
            },
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.testTag(STAKEOUT_RESULT_WITHIN_TAG),
        )
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
    // S019/RF-2: con una cota escrita, el panel lo dice (la del punto de arriba ya no es la que se replantea).
    val written = state.designElevationM.takeIf { state.elevationOverridden }
    if (written != null) {
        Text(
            stringResource(R.string.stakeout_elevation_written, "${LengthFormat.inUnit(written, unit, locale)} $symbol"),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.testTag(STAKEOUT_ELEVATION_TEXT_TAG),
        )
    }
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
private fun ModeChoice(state: StakeoutUiState, actions: StakeoutActions) = StakeModeChoice(state.settings, state.measuring, actions.onMode)

/** Los dos modos de guía, también en el replanteo de una línea (S041/RF-12). */
@Composable
internal fun StakeModeChoice(settings: StakeoutSettings, measuring: Boolean, onMode: (StakeMode) -> Unit) {
    Row(modifier = Modifier.selectableGroup(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        StakeMode.entries.forEach { mode ->
            FilterChip(
                selected = settings.mode == mode,
                onClick = { onMode(mode) },
                enabled = !measuring, // S018/RF-30
                label = { Text(stringResource(mode.textRes())) },
                modifier = Modifier.heightIn(min = MinTouchTarget).testTag(stakeModeTag(mode)),
            )
        }
    }
}

/**
 * La guía con la distancia, la flecha, la diana, el nivel y el estado de la solución (S018/RF-6 a RF-19, S019/RF-6 a RF-8). [extra]
 * pone lo suyo bajo la distancia: en el replanteo de una línea, el PK, el lado y lo que falta (S041/RF-10 a RF-14).
 */
@Composable
internal fun Guidance(
    project: Project?,
    settings: StakeoutSettings,
    guidance: GuidanceState,
    admitted: AdmittedSolutions = AdmittedSolutions.FIX_ONLY,
    extra: (@Composable (GuidanceUi, Numbers) -> Unit)? = null,
) {
    val unit = project?.linearUnit ?: LinearUnit.METRE
    val angular = project?.angularUnit ?: AngularUnit.DECIMAL_DEGREES
    AdmittedBadge(admitted) // S047/RF-14
    when (guidance) {
        is GuidanceState.Off -> Text(
            stringResource(guidance.reason.textRes()),
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }.testTag(STAKEOUT_OFF_TAG),
        )
        is GuidanceState.On -> {
            val ui = guidance.ui
            val numbers = Numbers(unit, unitSymbol(unit), uiLocale())
            project?.let { TerrainMark(rememberTerrainView(it), STAKEOUT_TERRAIN_TAG) } // S058/RF-9
            Text(
                stringResource(R.string.stakeout_distance, numbers.length(ui.guidance.distanceM)),
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.testTag(STAKEOUT_DISTANCE_TAG),
            )
            extra?.invoke(ui, numbers)
            val byDirection = settings.mode == StakeMode.BY_DIRECTION
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
                    StakeoutDiana(ui, settings.validToleranceM, headingUp, Modifier.size(DIANA_SIZE))
                }
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    GuidanceFigures(settings, ui, numbers)
                }
                // S019/RF-7: el nivel vertical al lado de la diana o de la flecha, a cualquier distancia del punto.
                ui.level?.let { StakeoutLevelView(it, numbers, Modifier.width(LEVEL_WIDTH).height(DIANA_SIZE)) }
            }
            GuidanceNotes(settings, ui, numbers, angular)
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
private fun GuidanceFigures(settings: StakeoutSettings, ui: GuidanceUi, numbers: Numbers) {
    if (ui.zone == StakeZone.INSIDE) {
        Text(
            stringResource(R.string.stakeout_inside),
            color = MaterialTheme.colorScheme.primary,
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }.testTag(STAKEOUT_INSIDE_TAG),
        ) // S018/RF-19: con texto, no solo con color (RNF-T8)
    }
    if (settings.mode == StakeMode.BY_DISTANCE) {
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
private fun GuidanceNotes(settings: StakeoutSettings, ui: GuidanceUi, numbers: Numbers, angular: AngularUnit) {
    if (settings.mode == StakeMode.BY_DIRECTION) {
        Text(headingText(ui.heading, settings.heading, angular), modifier = Modifier.testTag(STAKEOUT_HEADING_TAG))
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

internal fun StakeMode.textRes(): Int = when (this) {
    StakeMode.BY_DIRECTION -> R.string.stakeout_mode_direction
    StakeMode.BY_DISTANCE -> R.string.stakeout_mode_distance
}

internal fun GuidanceOff.textRes(): Int = when (this) {
    GuidanceOff.NO_POSITION -> R.string.stakeout_off_no_position
    GuidanceOff.NO_DATA -> R.string.stakeout_off_no_data
    GuidanceOff.SOLUTION_NOT_ADMITTED -> R.string.stakeout_off_not_admitted
    GuidanceOff.TILT_NOT_READY -> R.string.stakeout_off_tilt
}

/** S018 y RNF-T2: la pantalla no se apaga con el replanteo abierto. */
@Composable
internal fun KeepScreenOn() {
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
