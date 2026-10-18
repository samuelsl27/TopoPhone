package es.topophone.app.ui.projects

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import es.topophone.app.R
import es.topophone.app.ui.SectionScaffold
import es.topophone.core.model.preferences.ThemeState
import es.topophone.core.model.project.DatumCheck
import es.topophone.core.model.project.Project
import es.topophone.data.project.ProjectRepository
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

const val PROJECT_INFO_TAG = "project_info"
const val NO_POINTS_TAG = "project_no_points"

/** Lo que muestran los datos del proyecto (S009/RF-5). */
data class ProjectInfoUiState(val project: Project? = null)

/** El proyecto cuyos datos se ven, al día si se edita (S009/RF-5). */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ProjectInfoViewModel @Inject constructor(repository: ProjectRepository) : ViewModel() {
    private val projectId = MutableStateFlow<String?>(null)

    val state: StateFlow<ProjectInfoUiState> = projectId
        .flatMapLatest { id -> if (id == null) flowOf(null) else repository.observe(id) }
        .map { ProjectInfoUiState(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), ProjectInfoUiState())

    fun start(id: String) {
        projectId.value = id
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}

@Composable
fun ProjectInfoRoute(
    projectId: String,
    themeState: ThemeState,
    onToggleSun: () -> Unit,
    navigation: SurveyNavigation,
    viewModel: ProjectInfoViewModel = hiltViewModel(),
) {
    LaunchedEffect(projectId) { viewModel.start(projectId) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    ProjectInfoScreen(themeState, onToggleSun, state, navigation.onBack)
}

/** Datos del proyecto, a un toque desde el mapa (S006/RF-28, RF-31; S009/RF-5). */
@Composable
fun ProjectInfoScreen(themeState: ThemeState, onToggleSun: () -> Unit, state: ProjectInfoUiState, onBack: () -> Unit) {
    SectionScaffold(
        title = stringResource(R.string.project_info_screen),
        themeState = themeState,
        onToggleSun = onToggleSun,
        onNavigateBack = onBack,
    ) { padding ->
        Column(
            modifier = Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            state.project?.let { project ->
                ProjectInfoCard(project)
                PointsCard()
            }
        }
    }
}

/** SC, altura, unidades y datum de las correcciones, con su aviso (S006/RF-11, RF-28; S008/RF-2, RF-18). */
@Composable
private fun ProjectInfoCard(project: Project) {
    ViewCard(stringResource(R.string.project_info_title)) {
        Column(
            modifier = Modifier.semantics(mergeDescendants = true) {}.testTag(PROJECT_INFO_TAG),
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

/** Hasta S010 no hay recolección: el proyecto aún no tiene puntos (S006/RF-31). */
@Composable
private fun PointsCard() {
    ViewCard(stringResource(R.string.project_points_title)) {
        Text(stringResource(R.string.project_no_points), modifier = Modifier.testTag(NO_POINTS_TAG))
    }
}

@Composable
private fun ViewCard(title: String, content: @Composable () -> Unit) {
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
