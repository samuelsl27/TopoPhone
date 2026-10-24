package es.topophone.app.ui.points

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.Lazy
import dagger.hilt.android.lifecycle.HiltViewModel
import es.topophone.app.R
import es.topophone.app.ui.SectionScaffold
import es.topophone.app.ui.projects.ConfirmDialog
import es.topophone.app.ui.projects.CoordinateRow
import es.topophone.app.ui.projects.SurveyNavigation
import es.topophone.app.ui.projects.datumName
import es.topophone.app.ui.projects.formatDateTime
import es.topophone.app.ui.projects.heightTitle
import es.topophone.app.ui.projects.unitSymbol
import es.topophone.app.ui.receiver.nameRes
import es.topophone.app.ui.receiver.tiltSummary
import es.topophone.app.ui.receiver.uiLocale
import es.topophone.core.designsystem.component.MinTouchTarget
import es.topophone.core.gnss.nmea.SolutionStatus
import es.topophone.core.model.format.AngleFormat
import es.topophone.core.model.format.DurationText
import es.topophone.core.model.format.LengthFormat
import es.topophone.core.model.point.BaseSource
import es.topophone.core.model.point.HistoryItem
import es.topophone.core.model.point.HistoryLine
import es.topophone.core.model.point.Measurement
import es.topophone.core.model.point.MeasurementMode
import es.topophone.core.model.point.Point
import es.topophone.core.model.point.PointBase
import es.topophone.core.model.point.PointChange
import es.topophone.core.model.point.PointCoordinates
import es.topophone.core.model.point.PointHistory
import es.topophone.core.model.point.PointHistoryEntry
import es.topophone.core.model.point.PointNames
import es.topophone.core.model.point.PrecisionFailure
import es.topophone.core.model.point.TiltAngles
import es.topophone.core.model.point.TiltMode
import es.topophone.core.model.preferences.ThemeState
import es.topophone.core.model.project.GeodeticDatum
import es.topophone.core.model.project.Project
import es.topophone.core.survey.PointCorrections
import es.topophone.core.survey.ProjectPosition
import es.topophone.core.survey.ProjectPositionCalculator
import es.topophone.data.point.PointRepository
import es.topophone.data.point.PointSaveResult
import es.topophone.data.project.ProjectRepository
import es.topophone.data.receiver.ReceiverTiltSettings
import java.util.UUID
import javax.inject.Inject
import kotlin.math.roundToInt
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

const val POINT_COORDINATES_TAG = "point_coordinates"
const val POINT_MEASURED_COORDINATES_TAG = "point_measured_coordinates"
const val POINT_REVERT_TAG = "point_revert"
const val POINT_BASE_TAG = "point_base"
const val POINT_MEASUREMENT_TAG = "point_measurement"
const val POINT_TILT_TAG = "point_tilt"
const val POINT_NMEA_TAG = "point_nmea"
const val NMEA_DIALOG_TAG = "nmea_dialog"
const val NMEA_CHUNK_TITLE_TAG = "nmea_chunk_title"
const val NMEA_PREVIOUS_TAG = "nmea_previous"
const val NMEA_NEXT_TAG = "nmea_next"
const val POINT_EDIT_TAG = "point_edit"
const val POINT_POLE_TAG = "point_pole"
const val POINT_DUPLICATE_TAG = "point_duplicate"
const val POINT_HISTORY_TAG = "point_history"
const val POINT_DELETE_TAG = "point_delete"
const val POINT_NOTICE_DISMISS_TAG = "point_notice_dismiss"
const val HISTORY_DIALOG_TAG = "history_dialog"

/**
 * Lo que muestra la ficha de un punto (S011/RF-5 a RF-7, RF-11): el punto, su proyecto, su historial y si una
 * corrección del jalón ha mantenido sus coordenadas editadas.
 */
data class PointDetailsUiState(
    val project: Project? = null,
    val point: Point? = null,
    val history: List<PointHistoryEntry> = emptyList(),
    val keptEdited: Boolean = false,
    /** La base del punto en el SC del proyecto, si la tiene (S015/RF-21). */
    val baseCoordinates: ProjectPosition? = null,
)

/** Acciones de la ficha del punto. */
class PointDetailsActions(
    val onEdit: () -> Unit = {},
    val onRevert: () -> Unit = {},
    val onCorrectPole: (poleHeightM: Double) -> Unit = {},
    val onDismissNotice: () -> Unit = {},
    val onDuplicate: () -> Unit = {},
    val onDelete: () -> Unit = {},
)

/** Adónde va la ficha tras una acción: a la de la copia, o fuera si el punto se ha borrado. */
sealed interface PointDetailsEvent {
    data class Open(val pointId: String) : PointDetailsEvent

    data object Close : PointDetailsEvent
}

/**
 * Un punto y su proyecto, al día si cambian (S011/RF-5), con su historial (S011/RF-11); volver a la medida,
 * corregir el jalón, duplicar y borrar (S011/RF-13, RF-14, RF-21, RF-22).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class PointDetailsViewModel @Inject constructor(
    private val points: PointRepository,
    projects: ProjectRepository,
    private val calculator: Lazy<ProjectPositionCalculator>,
) : ViewModel() {
    private val ids = MutableStateFlow<Pair<String, String>?>(null)
    private val keptEdited = MutableStateFlow(false)
    private val eventChannel = Channel<PointDetailsEvent>(Channel.BUFFERED)
    val events: Flow<PointDetailsEvent> = eventChannel.receiveAsFlow()

    val state: StateFlow<PointDetailsUiState> = ids.filterNotNull().flatMapLatest { (projectId, pointId) ->
        combine(projects.observe(projectId), points.point(pointId), points.history(pointId), keptEdited) { project, point, history, kept ->
            // S015/RF-21: con el SC actual del proyecto; si cambia, la base se calcula en el nuevo.
            val base = point?.measurement?.base
            val baseCoordinates = if (base != null && project != null) {
                calculator.get().fromGround(base.position, project.crs, project.heightSystem)
            } else {
                null
            }
            PointDetailsUiState(project, point, history, kept, baseCoordinates)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), PointDetailsUiState())

    private val corrections: PointCorrections get() = PointCorrections(calculator.get())

    fun start(projectId: String, pointId: String) {
        ids.value = projectId to pointId
    }

    /** Las coordenadas de la medida, como un cambio más del historial (S011/RF-13). */
    fun revertToMeasurement() {
        val point = state.value.point ?: return
        val reverted = corrections.revertToMeasurement(point) ?: return
        viewModelScope.launch { points.update(reverted, PointChange.REVERT) }
    }

    /** S011/RF-14: desde la antena guardada; si sus coordenadas están editadas, se mantienen y se avisa. */
    fun correctPole(poleHeightM: Double) {
        val point = state.value.point ?: return
        val project = state.value.project ?: return
        val result = corrections.withPoleHeight(point, poleHeightM, project.crs, project.heightSystem) ?: return
        viewModelScope.launch {
            if (points.update(result.point, PointChange.POLE) == PointSaveResult.Saved) keptEdited.value = result.keptEditedCoordinates
        }
    }

    fun dismissNotice() {
        keptEdited.value = false
    }

    /** El tramo [seq] de las frases NMEA del punto, cuando son demasiadas para su fila; null si no existe (S017/RF-12). */
    suspend fun nmeaChunk(seq: Int): String? = state.value.point?.id?.let { points.nmeaChunk(it, seq) }

    /** S011/RF-21: una copia manual con el siguiente nombre libre; se abre su ficha. */
    fun duplicate() {
        val point = state.value.point ?: return
        viewModelScope.launch {
            val name = PointNames.nextFree(points.lastName(point.projectId), points.names(point.projectId))
            val copy = corrections.duplicate(point, UUID.randomUUID().toString(), name, System.currentTimeMillis())
            if (points.create(copy) == PointSaveResult.Saved) eventChannel.send(PointDetailsEvent.Open(copy.id))
        }
    }

    /** S011/RF-22: la confirmación la pide la pantalla. */
    fun delete() {
        val point = state.value.point ?: return
        viewModelScope.launch {
            points.moveToTrash(listOf(point.id))
            eventChannel.send(PointDetailsEvent.Close)
        }
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}

@Composable
fun PointDetailsRoute(
    projectId: String,
    pointId: String,
    themeState: ThemeState,
    onToggleSun: () -> Unit,
    navigation: SurveyNavigation,
    viewModel: PointDetailsViewModel = hiltViewModel(),
) {
    LaunchedEffect(projectId, pointId) { viewModel.start(projectId, pointId) }
    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is PointDetailsEvent.Open -> navigation.onOpenPoint(projectId, event.pointId)
                PointDetailsEvent.Close -> navigation.onBack()
            }
        }
    }
    val state by viewModel.state.collectAsStateWithLifecycle()
    PointDetailsScreen(
        themeState,
        onToggleSun,
        state,
        PointDetailsActions(
            onEdit = { navigation.onEditPoint(projectId, pointId) }, // S011/RF-8
            onRevert = viewModel::revertToMeasurement,
            onCorrectPole = viewModel::correctPole,
            onDismissNotice = viewModel::dismissNotice,
            onDuplicate = viewModel::duplicate,
            onDelete = viewModel::delete,
        ),
        navigation.onBack,
        loadNmeaChunk = viewModel::nmeaChunk, // S017/RF-12
    )
}

/**
 * Ficha de un punto (S011/RF-6, RF-7, RF-11 a RF-14, RF-21, RF-22): acciones arriba; sus coordenadas actuales; si
 * está editado, también las de su medida con "Volver a la medida"; y, si es medido, su medida con las frases NMEA.
 */
@Composable
fun PointDetailsScreen(
    themeState: ThemeState,
    onToggleSun: () -> Unit,
    state: PointDetailsUiState,
    actions: PointDetailsActions,
    onBack: () -> Unit,
    loadNmeaChunk: suspend (Int) -> String? = { null },
) {
    SectionScaffold(
        title = state.point?.name.orEmpty(),
        themeState = themeState,
        onToggleSun = onToggleSun,
        onNavigateBack = onBack,
    ) { padding ->
        val project = state.project ?: return@SectionScaffold
        val point = state.point ?: return@SectionScaffold
        Column(
            modifier = Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // S011/RF-21
            point.copyOf?.let { Text(stringResource(R.string.point_copy_of, it), style = MaterialTheme.typography.labelLarge) }
            Actions(state, point, actions)
            if (state.keptEdited) Notice(actions.onDismissNotice) // S011/RF-14
            DetailsCard(stringResource(R.string.point_coordinates), POINT_COORDINATES_TAG) {
                if (point.edited) MarkLine(R.string.point_mark_edited)
                CoordinateRows(project, point.coordinates)
            } // S011/RF-6
            val measurement = point.measurement
            if (point.edited && measurement != null) {
                DetailsCard(stringResource(R.string.point_measured_coordinates), POINT_MEASURED_COORDINATES_TAG) {
                    CoordinateRows(project, measurement.coordinates)
                    OutlinedButton(
                        onClick = actions.onRevert,
                        modifier = Modifier.fillMaxWidth().heightIn(min = MinTouchTarget).testTag(POINT_REVERT_TAG),
                    ) { Text(stringResource(R.string.point_revert)) }
                } // S011/RF-12, RF-13
            }
            if (measurement != null) {
                MeasurementCard(project, measurement, loadNmeaChunk) // S011/RF-7
                measurement.base?.let { BaseCard(project, it, state.baseCoordinates) } // S015/RF-21
                measurement.tilt?.angles?.let { TiltCard(project, it) } // S016/RF-23
            } else {
                Text(
                    stringResource(R.string.point_created_at, formatDateTime(point.createdAtMs, uiLocale())),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

/** Jalón (solo medidos), duplicar, historial y borrar tras confirmar (S011/RF-11, RF-14, RF-21, RF-22). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Actions(state: PointDetailsUiState, point: Point, actions: PointDetailsActions) {
    var pole by remember { mutableStateOf(false) }
    var history by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ActionButton(R.string.point_edit, POINT_EDIT_TAG, actions.onEdit)
        if (point.measurement != null) ActionButton(R.string.objects_pole, POINT_POLE_TAG) { pole = true }
        ActionButton(R.string.point_duplicate, POINT_DUPLICATE_TAG, actions.onDuplicate)
        ActionButton(R.string.point_history, POINT_HISTORY_TAG) { history = true }
        ActionButton(R.string.objects_delete, POINT_DELETE_TAG) { confirmDelete = true }
    }
    if (pole) {
        PoleDialog(
            onApply = { value ->
                pole = false
                actions.onCorrectPole(value)
            },
            onDismiss = { pole = false },
        )
    }
    if (history) HistoryDialog(PointHistory.items(state.history, point)) { history = false }
    if (confirmDelete) {
        ConfirmDialog(
            message = stringResource(R.string.point_delete_confirm, point.name),
            confirm = stringResource(R.string.objects_move_to_trash),
            onConfirm = {
                confirmDelete = false
                actions.onDelete()
            },
            onDismiss = { confirmDelete = false },
        )
    }
}

@Composable
private fun ActionButton(text: Int, tag: String, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, modifier = Modifier.heightIn(min = MinTouchTarget).testTag(tag)) { Text(stringResource(text)) }
}

@Composable
private fun Notice(onDismiss: () -> Unit) {
    Surface(tonalElevation = 2.dp, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(stringResource(R.string.objects_pole_kept_edited))
            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = MinTouchTarget).testTag(POINT_NOTICE_DISMISS_TAG)) {
                Text(stringResource(R.string.objects_dismiss))
            }
        }
    }
}

/** Los cambios del punto, del más reciente al más antiguo, con su fecha en hora local (S011/RF-11; RNF-T10). */
@Composable
private fun HistoryDialog(items: List<HistoryItem>, onDismiss: () -> Unit) {
    val locale = uiLocale()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.point_history)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()).testTag(HISTORY_DIALOG_TAG),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (items.isEmpty()) Text(stringResource(R.string.point_history_empty))
                items.forEach { item ->
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            stringResource(
                                R.string.point_history_entry,
                                formatDateTime(item.changedAtMs, locale),
                                stringResource(item.change.titleRes()),
                            ),
                            style = MaterialTheme.typography.labelLarge,
                        )
                        item.lines.forEach { line -> Text(historyLine(line)) }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = MinTouchTarget)) {
                Text(stringResource(R.string.collector_close))
            }
        },
    )
}

private fun PointChange.titleRes(): Int = when (this) {
    PointChange.EDIT -> R.string.point_change_edit
    PointChange.POLE -> R.string.point_change_pole
    PointChange.REVERT -> R.string.point_change_revert
}

@Composable
private fun historyLine(line: HistoryLine): String {
    val locale = uiLocale()
    val empty = stringResource(R.string.point_history_none)
    return when (line) {
        is HistoryLine.Name -> stringResource(R.string.point_history_name, line.from, line.to)
        is HistoryLine.Description -> stringResource(
            R.string.point_history_description,
            line.from.ifEmpty {
                empty
            },
            line.to.ifEmpty { empty },
        )
        is HistoryLine.Pole -> stringResource(
            R.string.point_history_pole,
            line.fromM?.let { LengthFormat.metres(it, locale) } ?: empty,
            line.toM?.let { LengthFormat.metres(it, locale) } ?: empty,
        )
        HistoryLine.Coordinates -> stringResource(R.string.point_history_coordinates)
        HistoryLine.Reverted -> stringResource(R.string.point_history_reverted)
    }
}

@Composable
private fun DetailsCard(title: String, tag: String, content: @Composable () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp).semantics(mergeDescendants = true) {}.testTag(tag),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
private fun MarkLine(text: Int) {
    Text(stringResource(text), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.tertiary)
}

/** Este, Norte y altura en el SC del proyecto, y geográficas con altura elipsoidal, en las unidades del proyecto. */
@Composable
private fun CoordinateRows(project: Project, coordinates: PointCoordinates) {
    val locale = uiLocale()
    val unit = unitSymbol(project.linearUnit)
    fun length(valueM: Double) = "${LengthFormat.inUnit(valueM, project.linearUnit, locale)} $unit"
    val noHeight = stringResource(R.string.point_mark_no_height)
    coordinates.easting?.let { CoordinateRow(stringResource(R.string.coord_easting), length(it), "point_easting") }
    coordinates.northing?.let { CoordinateRow(stringResource(R.string.coord_northing), length(it), "point_northing") }
    CoordinateRow(heightTitle(project.heightSystem), coordinates.height?.let(::length) ?: noHeight, "point_height")
    // Las geográficas son las de los cálculos: ETRS89, o REGCAN95 en Canarias; también en un proyecto ED50.
    val datum = if (project.crs.datum == GeodeticDatum.REGCAN95) GeodeticDatum.REGCAN95 else GeodeticDatum.ETRS89
    Text(stringResource(R.string.point_geographic, datumName(datum)), style = MaterialTheme.typography.labelMedium)
    val ground = coordinates.ground
    CoordinateRow(
        stringResource(R.string.coord_latitude_symbol),
        AngleFormat.latitude(ground.latitudeDeg, project.angularUnit, locale),
        "point_lat",
    )
    CoordinateRow(
        stringResource(R.string.coord_longitude_symbol),
        AngleFormat.longitude(ground.longitudeDeg, project.angularUnit, locale),
        "point_lon",
    )
    CoordinateRow(stringResource(R.string.coord_ellipsoidal_height), ground.ellipsoidalHeightM?.let(::length) ?: noHeight, "point_h")
}

/** Jalón, antena, calidad, fecha y hora, control de precisión y frases NMEA de un punto medido (S011/RF-7). */
@Composable
private fun MeasurementCard(project: Project, measurement: Measurement, loadNmeaChunk: suspend (Int) -> String?) {
    val locale = uiLocale()
    val unit = unitSymbol(project.linearUnit)
    fun length(valueM: Double) = "${LengthFormat.inUnit(valueM, project.linearUnit, locale)} $unit"
    val quality = measurement.quality
    val unavailable = stringResource(R.string.point_history_none)
    var nmea by remember { mutableStateOf(false) }
    DetailsCard(stringResource(R.string.point_measurement), POINT_MEASUREMENT_TAG) {
        CoordinateRow(stringResource(R.string.objects_pole), LengthFormat.metres(measurement.poleHeightM, locale), "point_pole_height")
        measurement.tilt?.let { tilt ->
            CoordinateRow(
                stringResource(R.string.point_tilt_mode),
                tiltSummary(ReceiverTiltSettings(tilt.mode, tilt.verified)),
                "point_tilt_mode",
            ) // S016/RF-23
        }
        val antenna = measurement.antenna
        // S016/RF-26: con el receptor que compensa, la posición del receptor ya es la punta.
        val tip = measurement.tilt?.mode == TiltMode.RECEIVER
        CoordinateRow(
            stringResource(if (tip) R.string.point_receiver_tip_latitude else R.string.point_antenna_latitude),
            AngleFormat.latitude(antenna.latitudeDeg, project.angularUnit, locale),
            "point_antenna_lat",
        )
        CoordinateRow(
            stringResource(if (tip) R.string.point_receiver_tip_longitude else R.string.point_antenna_longitude),
            AngleFormat.longitude(antenna.longitudeDeg, project.angularUnit, locale),
            "point_antenna_lon",
        )
        CoordinateRow(
            stringResource(if (tip) R.string.point_receiver_tip_height else R.string.point_antenna_height),
            antenna.ellipsoidalHeightM?.let(::length) ?: unavailable,
            "point_antenna_h",
        )
        val solution = SolutionStatus.entries.firstOrNull { it.name == quality.solution }
        CoordinateRow(
            stringResource(R.string.point_solution),
            solution?.let {
                stringResource(it.nameRes())
            } ?: quality.solution,
            "point_solution",
        )
        CoordinateRow(
            stringResource(R.string.receiver_status_satellites),
            quality.satellites?.toString() ?: unavailable,
            "point_satellites",
        )
        CoordinateRow(
            stringResource(R.string.receiver_status_hdop),
            quality.hdop?.let { String.format(locale, "%.1f", it) } ?: unavailable,
            "point_hdop",
        )
        CoordinateRow(
            stringResource(R.string.receiver_status_correction_age),
            quality.correctionAgeS?.let { stringResource(R.string.receiver_seconds, String.format(locale, "%.1f", it)) } ?: unavailable,
            "point_correction_age",
        )
        CoordinateRow(stringResource(R.string.map_accuracy_h), quality.horizontalM?.let(::length) ?: unavailable, "point_sigma_h")
        CoordinateRow(stringResource(R.string.point_accuracy_v), quality.verticalM?.let(::length) ?: unavailable, "point_sigma_v")
        val mode = stringResource(
            if (quality.mode ==
                MeasurementMode.INSTANT
            ) {
                R.string.collector_mode_instant
            } else {
                R.string.collector_mode_averaged
            },
        )
        CoordinateRow(stringResource(R.string.point_mode), mode, "point_mode")
        CoordinateRow(
            stringResource(R.string.point_epochs),
            stringResource(R.string.point_epochs_value, quality.epochsUsed, quality.epochsDiscarded),
            "point_epochs",
        )
        // S017/RF-13, RF-14: lo que dejó el promediado, y si lo midió el modo automático.
        quality.averagedTimeS?.let {
            CoordinateRow(stringResource(R.string.point_averaged_time), DurationText.format(it.roundToInt()), "point_averaged_time")
            CoordinateRow(
                stringResource(R.string.point_deviation_h),
                quality.deviationHorizontalM?.let(::length) ?: unavailable,
                "point_deviation_h",
            )
            CoordinateRow(
                stringResource(R.string.point_deviation_v),
                quality.deviationVerticalM?.let(::length) ?: unavailable,
                "point_deviation_v",
            )
        }
        if (quality.auto == true) CoordinateRow(stringResource(R.string.point_auto), stringResource(R.string.point_auto_yes), "point_auto")
        CoordinateRow(stringResource(R.string.point_measured_at), formatDateTime(measurement.measuredToMs, locale), "point_measured_at")
        if (quality.fromReplay) Text(stringResource(R.string.point_from_replay))
        if (measurement.outOfTolerance.isNotEmpty()) {
            MarkLine(R.string.point_mark_out_of_tolerance)
            measurement.outOfTolerance.sorted().forEach { Text(stringResource(R.string.point_list_item, stringResource(it.textRes()))) }
        }
        TextButton(onClick = { nmea = true }, modifier = Modifier.heightIn(min = MinTouchTarget).testTag(POINT_NMEA_TAG)) {
            Text(stringResource(R.string.point_nmea))
        }
    }
    if (nmea) NmeaDialog(measurement.rawNmea, measurement.nmeaChunks, loadNmeaChunk) { nmea = false }
}

/** La inclinación de la medida, en la unidad angular y lineal del proyecto (S016/RF-23). */
@Composable
private fun TiltCard(project: Project, angles: TiltAngles) {
    val locale = uiLocale()
    val unit = unitSymbol(project.linearUnit)
    fun length(valueM: Double) = "${LengthFormat.inUnit(valueM, project.linearUnit, locale)} $unit"
    fun angle(valueRad: Double) = AngleFormat.direction(valueRad, project.angularUnit, locale)
    DetailsCard(stringResource(R.string.point_tilt_title), POINT_TILT_TAG) {
        CoordinateRow(stringResource(R.string.status_tilt_angle), angle(angles.tiltRad), "point_tilt_angle")
        CoordinateRow(stringResource(R.string.status_tilt_direction), angle(angles.directionRad), "point_tilt_direction")
        angles.headingRad?.let { CoordinateRow(stringResource(R.string.status_tilt_heading), angle(it), "point_tilt_heading") }
        CoordinateRow(stringResource(R.string.precision_tilt_limit), angle(angles.maxTiltRad), "point_tilt_max")
        CoordinateRow(stringResource(R.string.point_tilt_sigma_h), length(angles.horizontalAccuracyM), "point_tilt_sigma_h")
        CoordinateRow(stringResource(R.string.point_tilt_sigma_v), length(angles.verticalAccuracyM), "point_tilt_sigma_v")
    }
}

/** La base con la que se midió: geográficas tal como las dio su fuente, el SC del proyecto, la fuente y la línea base (S015/RF-21). */
@Composable
private fun BaseCard(project: Project, base: PointBase, projected: ProjectPosition?) {
    val locale = uiLocale()
    val unit = unitSymbol(project.linearUnit)
    fun length(valueM: Double) = "${LengthFormat.inUnit(valueM, project.linearUnit, locale)} $unit"
    val unavailable = stringResource(R.string.point_history_none)
    DetailsCard(stringResource(R.string.base_title), POINT_BASE_TAG) {
        val source = when (base.source) {
            BaseSource.RECEIVER -> stringResource(R.string.base_source_receiver)
            BaseSource.CORRECTIONS -> base.stationId?.let { stringResource(R.string.base_source_corrections, it) }
                ?: stringResource(R.string.base_source_corrections_no_station)
        }
        CoordinateRow(stringResource(R.string.base_source), source, "point_base_source")
        CoordinateRow(stringResource(R.string.coord_easting), projected?.easting?.let(::length) ?: unavailable, "point_base_easting")
        CoordinateRow(stringResource(R.string.coord_northing), projected?.northing?.let(::length) ?: unavailable, "point_base_northing")
        CoordinateRow(heightTitle(project.heightSystem), projected?.height?.let(::length) ?: unavailable, "point_base_height")
        CoordinateRow(
            stringResource(R.string.coord_latitude_symbol),
            AngleFormat.latitude(base.position.latitudeDeg, project.angularUnit, locale),
            "point_base_lat",
        )
        CoordinateRow(
            stringResource(R.string.coord_longitude_symbol),
            AngleFormat.longitude(base.position.longitudeDeg, project.angularUnit, locale),
            "point_base_lon",
        )
        CoordinateRow(
            stringResource(R.string.coord_ellipsoidal_height),
            base.position.ellipsoidalHeightM?.let(::length) ?: unavailable,
            "point_base_h",
        )
        base.antennaHeightM?.let {
            CoordinateRow(stringResource(R.string.base_antenna_height), LengthFormat.metres(it, locale), "point_base_antenna_height")
        }
        CoordinateRow(stringResource(R.string.baseline), length(base.baselineM), "point_baseline")
    }
}

private fun PrecisionFailure.textRes(): Int = when (this) {
    PrecisionFailure.NOT_FIX -> R.string.out_of_tolerance_not_fix
    PrecisionFailure.HORIZONTAL -> R.string.out_of_tolerance_horizontal
    PrecisionFailure.VERTICAL -> R.string.out_of_tolerance_vertical
    PrecisionFailure.NO_ACCURACY -> R.string.out_of_tolerance_no_accuracy
    PrecisionFailure.TILT -> R.string.out_of_tolerance_tilt
}

/**
 * Las frases NMEA de las épocas usadas, tal como llegaron (S011/RF-7; S010/RF-17). Un promediado largo las tiene en varios
 * tramos, que se ven de uno en uno (S017/RF-12).
 */
@Composable
private fun NmeaDialog(rawNmea: String, chunks: Int, loadChunk: suspend (Int) -> String?, onDismiss: () -> Unit) {
    var index by remember { mutableIntStateOf(0) }
    val text by produceState(rawNmea, index, chunks) { value = if (chunks == 0) rawNmea else loadChunk(index).orEmpty() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            if (chunks == 0) {
                Text(stringResource(R.string.point_nmea_title))
            } else {
                Text(stringResource(R.string.point_nmea_chunk_title, index + 1, chunks), modifier = Modifier.testTag(NMEA_CHUNK_TITLE_TAG))
            }
        },
        text = { NmeaText(text) },
        confirmButton = {
            Row {
                if (chunks > 1) {
                    TextButton(
                        onClick = { index-- },
                        enabled = index > 0,
                        modifier = Modifier.heightIn(min = MinTouchTarget).testTag(NMEA_PREVIOUS_TAG),
                    ) { Text(stringResource(R.string.point_nmea_previous)) }
                    TextButton(
                        onClick = { index++ },
                        enabled = index < chunks - 1,
                        modifier = Modifier.heightIn(min = MinTouchTarget).testTag(NMEA_NEXT_TAG),
                    ) { Text(stringResource(R.string.point_nmea_next)) }
                }
                TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = MinTouchTarget)) {
                    Text(stringResource(R.string.collector_close))
                }
            }
        },
    )
}

/** Un texto corto, de una vez; uno largo, por líneas, para no maquetar cientos de miles de caracteres juntos (S017/RF-12). */
@Composable
private fun NmeaText(text: String) {
    val style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
    if (text.length <= NMEA_SINGLE_TEXT_MAX) {
        Text(text, style = style, modifier = Modifier.verticalScroll(rememberScrollState()).testTag(NMEA_DIALOG_TAG))
    } else {
        val lines = remember(text) { text.lines() }
        LazyColumn(modifier = Modifier.heightIn(max = NMEA_LIST_MAX_HEIGHT).testTag(NMEA_DIALOG_TAG)) {
            items(lines) { line -> Text(line, style = style) }
        }
    }
}

private const val NMEA_SINGLE_TEXT_MAX = 20_000
private val NMEA_LIST_MAX_HEIGHT = 360.dp
