package es.topophone.app.ui.projects

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import es.topophone.app.R
import es.topophone.app.ui.SectionScaffold
import es.topophone.core.designsystem.component.MinTouchTarget
import es.topophone.core.model.preferences.ThemeState
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
import kotlinx.coroutines.launch

const val PROJECT_VIEW_TAG = "project_view"
const val PROJECT_EDIT_TAG = "project_edit"

/** Lo que muestra la vista de proyecto (S006/RF-28 a RF-31). */
data class ProjectViewUiState(val project: Project? = null)

/** Proyecto abierto; abrirlo lo pone el primero de la lista (S006/RF-1). */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ProjectViewModel @Inject constructor(private val repository: ProjectRepository) : ViewModel() {
    private val projectId = MutableStateFlow<String?>(null)

    val state: StateFlow<ProjectViewUiState> = projectId
        .flatMapLatest { id -> if (id == null) flowOf(null) else repository.observe(id) }
        .map(::ProjectViewUiState)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), ProjectViewUiState())

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
        ) {}
    }
}
