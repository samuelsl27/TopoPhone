package es.topophone.app.ui.projects

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
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
import es.topophone.app.ui.receiver.ReceiverViewModel
import es.topophone.app.ui.receiver.uiLocale
import es.topophone.core.designsystem.component.MinTouchTarget
import es.topophone.core.model.format.AngleFormat
import es.topophone.core.model.format.FactorFormat
import es.topophone.core.model.format.LengthFormat
import es.topophone.core.model.preferences.ThemeState
import es.topophone.core.model.project.CrsArea
import es.topophone.core.model.project.DatumCheck
import es.topophone.core.model.project.HeightSystem
import es.topophone.core.model.project.LinearUnit
import es.topophone.core.model.project.Project
import es.topophone.core.survey.PositionUnavailable
import es.topophone.core.survey.ProjectPosition
import es.topophone.core.survey.ProjectPositionCalculator
import es.topophone.data.project.ProjectRepository
import es.topophone.data.receiver.ReceiverPreferences
import es.topophone.receiver.session.ConnectionState
import es.topophone.receiver.session.ReceiverSession
import javax.inject.Inject
import javax.inject.Named
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

const val PROJECT_VIEW_TAG = "project_view"
const val PROJECT_EDIT_TAG = "project_edit"
const val PROJECT_INFO_TAG = "project_info"
const val NO_POINTS_TAG = "project_no_points"
const val POSITION_EASTING_TAG = "position_easting"
const val POSITION_NORTHING_TAG = "position_northing"
const val POSITION_HEIGHT_TAG = "position_height"
const val POSITION_LATITUDE_TAG = "position_latitude"
const val POSITION_LONGITUDE_TAG = "position_longitude"
const val SCALE_FACTOR_TAG = "position_scale_factor"
const val CONVERGENCE_TAG = "position_convergence"
const val COMBINED_FACTOR_TAG = "position_combined_factor"
const val OTHER_ZONE_TAG = "position_other_zone"

/** Lo que muestra la vista de proyecto (S006/RF-28 a RF-31); [position] null sin posición del receptor. */
data class ProjectViewUiState(val project: Project? = null, val position: ProjectPosition? = null)

/**
 * Proyecto abierto, que pasa a ser el primero de la lista (S006/RF-1), y la posición del receptor en
 * su SC con la altura del jalón de Receptor (S006/RF-29, RF-30), a 5 Hz como máximo (RNF-T13).
 */
@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
@HiltViewModel
class ProjectViewModel @Inject constructor(
    private val repository: ProjectRepository,
    session: ReceiverSession,
    preferences: ReceiverPreferences,
    calculator: Lazy<ProjectPositionCalculator>,
    @param:Named(ReceiverViewModel.UI_SAMPLE_MS) samplePeriodMs: Long,
) : ViewModel() {
    private val projectId = MutableStateFlow<String?>(null)

    private val project = projectId.flatMapLatest { id -> if (id == null) flowOf(null) else repository.observe(id) }

    /** Solo una GGA reciente: sin datos o sin receptor no se muestra una posición antigua. */
    private val fixes = (if (samplePeriodMs > 0) session.state.sample(samplePeriodMs) else session.state)
        .map { if (it.connection is ConnectionState.Receiving) it.gga else null }
        .distinctUntilChanged()

    val state: StateFlow<ProjectViewUiState> = combine(project, fixes, preferences.poleHeightM) { project, gga, pole ->
        // Con el SC del proyecto: si se cambia, la posición se recalcula en el nuevo (S006/RF-15).
        val position = if (project != null && gga != null) calculator.get().compute(gga, pole, project.crs, project.heightSystem) else null
        ProjectViewUiState(project, position)
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), ProjectViewUiState())

    fun start(id: String) {
        if (projectId.value != null) return
        projectId.value = id
        viewModelScope.launch { repository.markOpened(id) }
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}

@Composable
fun ProjectViewRoute(
    projectId: String,
    themeState: ThemeState,
    onToggleSun: () -> Unit,
    navigation: SurveyNavigation,
    viewModel: ProjectViewModel = hiltViewModel(),
) {
    LaunchedEffect(projectId) { viewModel.start(projectId) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    ProjectViewScreen(themeState, onToggleSun, state, navigation.onBack, navigation.onEditProject)
}

/** Datos del proyecto: SC, altura, unidades y datum de las correcciones, con su aviso (S006/RF-11, RF-28; S008/RF-2). */
@Composable
private fun ProjectInfoCard(project: Project) {
    ViewCard(stringResource(R.string.project_info_title)) {
        Column(
            modifier = Modifier.semantics(mergeDescendants = true) {
            }.testTag(PROJECT_INFO_TAG),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            LabeledValue(stringResource(R.string.project_crs), crsLabel(project.crs))
            LabeledValue(stringResource(R.string.project_height), heightLabel(project.heightSystem))
            LabeledValue(stringResource(R.string.project_linear_unit), linearUnitLabel(project.linearUnit))
            LabeledValue(stringResource(R.string.project_angular_unit), angularUnitLabel(project.angularUnit))
            LabeledValue(stringResource(R.string.project_corrections_datum), datumLabel(project.correctionsDatum))
        }
        if (DatumCheck.mismatch(project.crs, project.correctionsDatum)) DatumWarning(project.crs, project.correctionsDatum)
    }
}

/** Posición actual del punto del suelo en el SC y las unidades del proyecto (S006/RF-29, RF-30; S008/RF-8). */
@Composable
private fun PositionCard(project: Project, position: ProjectPosition?) {
    ViewCard(stringResource(R.string.project_position_title)) {
        when {
            position == null -> Text(stringResource(R.string.project_no_position))
            PositionUnavailable.OUTSIDE_CRS_AREA in position.unavailable ->
                Text(stringResource(R.string.project_outside_crs), color = MaterialTheme.colorScheme.error)
            PositionUnavailable.OUTSIDE_ED50_GRID in position.unavailable ->
                Text(stringResource(R.string.project_outside_ed50), color = MaterialTheme.colorScheme.error)
            else -> PositionValues(project, position)
        }
    }
}

/**
 * Este y Norte en el huso del proyecto, con aviso si el punto cae en otro (S008/RF-12), o latitud y longitud
 * (S008/RF-6); la altura del proyecto (S008/RF-5); y en UTM, los factores y la convergencia (S008/RF-13 a RF-16).
 */
@Composable
private fun PositionValues(project: Project, position: ProjectPosition) {
    val unit = unitSymbol(project.linearUnit)
    val locale = uiLocale()
    fun length(valueM: Double) = "${LengthFormat.inUnit(valueM, project.linearUnit, locale)} $unit"
    val zone = project.crs.utmZone
    val positionZone = position.positionZone
    if (zone != null && positionZone != null && positionZone != zone) {
        Text(
            stringResource(R.string.project_other_zone, positionZone, zone),
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.testTag(OTHER_ZONE_TAG),
        )
    }
    position.easting?.let { CoordinateRow(stringResource(R.string.coord_easting), length(it), POSITION_EASTING_TAG) }
    position.northing?.let { CoordinateRow(stringResource(R.string.coord_northing), length(it), POSITION_NORTHING_TAG) }
    position.latitudeDeg?.let {
        CoordinateRow(
            stringResource(R.string.coord_latitude_symbol),
            AngleFormat.latitude(it, project.angularUnit, locale),
            POSITION_LATITUDE_TAG,
        )
    }
    position.longitudeDeg?.let {
        CoordinateRow(
            stringResource(R.string.coord_longitude_symbol),
            AngleFormat.longitude(it, project.angularUnit, locale),
            POSITION_LONGITUDE_TAG,
        )
    }
    CoordinateRow(
        heightTitle(project.heightSystem),
        position.height?.let(::length) ?: heightUnavailable(project, position),
        POSITION_HEIGHT_TAG,
    )
    if (zone == null) return // S008/RF-16: en geográficas no hay factores
    position.scaleFactor?.let {
        CoordinateRow(stringResource(R.string.project_scale_factor), FactorFormat.factor(it, locale), SCALE_FACTOR_TAG)
    }
    position.convergenceRad?.let {
        CoordinateRow(
            stringResource(R.string.project_convergence),
            AngleFormat.convergence(it, project.angularUnit, locale),
            CONVERGENCE_TAG,
        )
    }
    position.combinedFactor?.let {
        CoordinateRow(stringResource(R.string.project_combined_factor), FactorFormat.factor(it, locale), COMBINED_FACTOR_TAG)
    }
}

@Composable
private fun heightTitle(height: HeightSystem): String = stringResource(
    when (height) {
        HeightSystem.ORTHOMETRIC -> R.string.coord_orthometric_height
        HeightSystem.ELLIPSOIDAL -> R.string.coord_ellipsoidal_height
    },
)

/** Por qué falta la altura: sin altura en la GGA, o fuera del geoide del ámbito del SC (S006/RF-30, S008/RF-4). */
@Composable
private fun heightUnavailable(project: Project, position: ProjectPosition): String = stringResource(
    when {
        PositionUnavailable.OUTSIDE_GEOID !in position.unavailable -> R.string.unavailable_no_height
        project.crs.area == CrsArea.CANARIAS -> R.string.unavailable_outside_geoid_canarias
        else -> R.string.unavailable_outside_geoid
    },
)

@Composable
private fun CoordinateRow(label: String, value: String, tag: String) {
    Column(modifier = Modifier.semantics(mergeDescendants = true) {}.testTag(tag)) {
        Text(label, style = MaterialTheme.typography.labelMedium)
        Text(value, style = MaterialTheme.typography.titleLarge.copy(fontFamily = FontFamily.Monospace)) // RNF-T8
    }
}

@Composable
private fun unitSymbol(unit: LinearUnit): String = stringResource(
    when (unit) {
        LinearUnit.METRE -> R.string.unit_symbol_metre
        LinearUnit.INTERNATIONAL_FOOT -> R.string.unit_symbol_foot
        LinearUnit.US_SURVEY_FOOT -> R.string.unit_symbol_us_survey_foot
    },
)

/** Hasta S010 no hay recolección: el proyecto aún no tiene puntos (S006/RF-31). */
@Composable
private fun PointsCard() {
    ViewCard(stringResource(R.string.project_points_title)) {
        Text(stringResource(R.string.project_no_points), modifier = Modifier.testTag(NO_POINTS_TAG))
    }
}

@Composable
internal fun ViewCard(title: String, content: @Composable () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
private fun LabeledValue(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium)
        Text(value, style = MaterialTheme.typography.bodyLarge)
    }
}

/** Vista de proyecto (S006/RF-28 a RF-31). */
@Composable
fun ProjectViewScreen(
    themeState: ThemeState,
    onToggleSun: () -> Unit,
    state: ProjectViewUiState,
    onBack: () -> Unit,
    onEdit: (String) -> Unit = {},
) {
    val project = state.project
    SectionScaffold(
        title = project?.name.orEmpty(),
        themeState = themeState,
        onToggleSun = onToggleSun,
        onNavigateBack = onBack,
        actions = {
            if (project != null) {
                IconButton(onClick = { onEdit(project.id) }, modifier = Modifier.size(MinTouchTarget).testTag(PROJECT_EDIT_TAG)) {
                    Icon(painterResource(R.drawable.ic_edit), contentDescription = stringResource(R.string.project_edit))
                }
            } // S006/RF-14
        },
    ) { padding ->
        Column(
            modifier = Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(16.dp).testTag(PROJECT_VIEW_TAG),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (project != null) {
                ProjectInfoCard(project)
                PositionCard(project, state.position)
                PointsCard()
            }
        }
    }
}
