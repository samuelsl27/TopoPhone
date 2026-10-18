package es.topophone.app.ui.points

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
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
import es.topophone.app.ui.projects.CoordinateRow
import es.topophone.app.ui.projects.SurveyNavigation
import es.topophone.app.ui.projects.datumName
import es.topophone.app.ui.projects.formatDateTime
import es.topophone.app.ui.projects.heightTitle
import es.topophone.app.ui.projects.unitSymbol
import es.topophone.app.ui.receiver.nameRes
import es.topophone.app.ui.receiver.uiLocale
import es.topophone.core.designsystem.component.MinTouchTarget
import es.topophone.core.gnss.nmea.SolutionStatus
import es.topophone.core.model.format.AngleFormat
import es.topophone.core.model.format.LengthFormat
import es.topophone.core.model.point.Measurement
import es.topophone.core.model.point.MeasurementMode
import es.topophone.core.model.point.Point
import es.topophone.core.model.point.PointChange
import es.topophone.core.model.point.PointCoordinates
import es.topophone.core.model.point.PrecisionFailure
import es.topophone.core.model.preferences.ThemeState
import es.topophone.core.model.project.GeodeticDatum
import es.topophone.core.model.project.Project
import es.topophone.core.survey.PointCorrections
import es.topophone.core.survey.ProjectPositionCalculator
import es.topophone.data.point.PointRepository
import es.topophone.data.project.ProjectRepository
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

const val POINT_COORDINATES_TAG = "point_coordinates"
const val POINT_MEASURED_COORDINATES_TAG = "point_measured_coordinates"
const val POINT_REVERT_TAG = "point_revert"
const val POINT_MEASUREMENT_TAG = "point_measurement"
const val POINT_NMEA_TAG = "point_nmea"
const val NMEA_DIALOG_TAG = "nmea_dialog"

/** Lo que muestra la ficha de un punto (S011/RF-5 a RF-7). */
data class PointDetailsUiState(val project: Project? = null, val point: Point? = null)

/** Acciones de la ficha del punto. */
class PointDetailsActions(val onRevert: () -> Unit = {})

/** Un punto y su proyecto, al día si cambian (S011/RF-5); volver a la medida (S011/RF-13). */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class PointDetailsViewModel @Inject constructor(
    private val points: PointRepository,
    projects: ProjectRepository,
    private val calculator: Lazy<ProjectPositionCalculator>,
) : ViewModel() {
    private val ids = MutableStateFlow<Pair<String, String>?>(null)

    val state: StateFlow<PointDetailsUiState> = ids.filterNotNull().flatMapLatest { (projectId, pointId) ->
        combine(projects.observe(projectId), points.point(pointId)) { project, point -> PointDetailsUiState(project, point) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), PointDetailsUiState())

    fun start(projectId: String, pointId: String) {
        ids.value = projectId to pointId
    }

    /** Las coordenadas de la medida, como un cambio más del historial (S011/RF-13). */
    fun revertToMeasurement() {
        val point = state.value.point ?: return
        val reverted = PointCorrections(calculator.get()).revertToMeasurement(point) ?: return
        viewModelScope.launch { points.update(reverted, PointChange.REVERT) }
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
    val state by viewModel.state.collectAsStateWithLifecycle()
    PointDetailsScreen(themeState, onToggleSun, state, PointDetailsActions(onRevert = viewModel::revertToMeasurement), navigation.onBack)
}

/**
 * Ficha de un punto (S011/RF-6, RF-7, RF-12, RF-13): sus coordenadas actuales; si está editado, también las de su
 * medida con "Volver a la medida"; y, si es medido, su medida con las frases NMEA.
 */
@Composable
fun PointDetailsScreen(
    themeState: ThemeState,
    onToggleSun: () -> Unit,
    state: PointDetailsUiState,
    actions: PointDetailsActions,
    onBack: () -> Unit,
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
                MeasurementCard(project, measurement) // S011/RF-7
            } else {
                Text(
                    stringResource(R.string.point_created_at, formatDateTime(point.createdAtMs, uiLocale())),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
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
private fun MeasurementCard(project: Project, measurement: Measurement) {
    val locale = uiLocale()
    val unit = unitSymbol(project.linearUnit)
    fun length(valueM: Double) = "${LengthFormat.inUnit(valueM, project.linearUnit, locale)} $unit"
    val quality = measurement.quality
    val unavailable = "—"
    var nmea by remember { mutableStateOf(false) }
    DetailsCard(stringResource(R.string.point_measurement), POINT_MEASUREMENT_TAG) {
        CoordinateRow(stringResource(R.string.objects_pole), LengthFormat.metres(measurement.poleHeightM, locale), "point_pole")
        val antenna = measurement.antenna
        CoordinateRow(
            stringResource(R.string.point_antenna_latitude),
            AngleFormat.latitude(antenna.latitudeDeg, project.angularUnit, locale),
            "point_antenna_lat",
        )
        CoordinateRow(
            stringResource(R.string.point_antenna_longitude),
            AngleFormat.longitude(antenna.longitudeDeg, project.angularUnit, locale),
            "point_antenna_lon",
        )
        CoordinateRow(
            stringResource(R.string.point_antenna_height),
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
            quality.hdop?.let {
                String.format(locale, "%.1f", it)
            } ?: unavailable,
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
    if (nmea) NmeaDialog(measurement.rawNmea) { nmea = false }
}

private fun PrecisionFailure.textRes(): Int = when (this) {
    PrecisionFailure.NOT_FIX -> R.string.out_of_tolerance_not_fix
    PrecisionFailure.HORIZONTAL -> R.string.out_of_tolerance_horizontal
    PrecisionFailure.VERTICAL -> R.string.out_of_tolerance_vertical
    PrecisionFailure.NO_ACCURACY -> R.string.out_of_tolerance_no_accuracy
}

/** Las frases NMEA de las épocas usadas, tal como llegaron (S011/RF-7; S010/RF-17). */
@Composable
private fun NmeaDialog(rawNmea: String, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.point_nmea_title)) },
        text = {
            Text(
                rawNmea,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                modifier = Modifier.verticalScroll(rememberScrollState()).testTag(NMEA_DIALOG_TAG),
            )
        },
        confirmButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = MinTouchTarget)) {
                Text(stringResource(R.string.collector_close))
            }
        },
    )
}
