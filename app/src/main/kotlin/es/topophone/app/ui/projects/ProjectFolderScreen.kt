package es.topophone.app.ui.projects

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
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
import es.topophone.core.model.project.ProjectFolder
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

const val PROJECT_FOLDER_TAG = "project_folder"

/** Una carpeta abierta: sus proyectos y las carpetas a las que se pueden mover (S006/RF-20). */
data class ProjectFolderUiState(
    val folder: ProjectFolder? = null,
    val projects: List<Project> = emptyList(),
    val folders: List<ProjectFolder> = emptyList(),
)

/** Acciones de la pantalla de una carpeta: las de cada proyecto, como en la lista, y crear dentro. */
class ProjectFolderActions(
    val list: ProjectListActions = ProjectListActions(),
    val onNewProject: (folderId: String) -> Unit = {},
    val onBack: () -> Unit = {},
)

/** Proyectos de una carpeta, del abierto más recientemente al más antiguo (S006/RF-20). */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ProjectFolderViewModel @Inject constructor(private val repository: ProjectRepository) : ViewModel() {
    private val folderId = MutableStateFlow<String?>(null)

    val state: StateFlow<ProjectFolderUiState> = folderId.filterNotNull().flatMapLatest { id ->
        combine(repository.folders(), repository.projects(id)) { folders, projects ->
            ProjectFolderUiState(folders.firstOrNull { it.id == id }, projects, folders)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), ProjectFolderUiState())

    fun start(id: String) {
        folderId.value = id
    }

    fun duplicate(id: String, copyLabel: String) {
        viewModelScope.launch { repository.duplicate(id, copyLabel) }
    }

    fun moveToFolder(id: String, folderId: String?) {
        viewModelScope.launch { repository.moveToFolder(id, folderId) }
    }

    fun delete(id: String) {
        viewModelScope.launch { repository.moveToTrash(id) } // S006/RF-22
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}

@Composable
fun ProjectFolderRoute(
    folderId: String,
    themeState: ThemeState,
    onToggleSun: () -> Unit,
    navigation: SurveyNavigation,
    viewModel: ProjectFolderViewModel = hiltViewModel(),
) {
    LaunchedEffect(folderId) { viewModel.start(folderId) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val copyLabel = stringResource(R.string.project_copy_label)
    ProjectFolderScreen(
        themeState,
        onToggleSun,
        state,
        ProjectFolderActions(
            list = ProjectListActions(
                onOpenProject = navigation.onOpenProject,
                onEdit = navigation.onEditProject,
                onDuplicate = { viewModel.duplicate(it, copyLabel) },
                onMove = viewModel::moveToFolder,
                onDelete = viewModel::delete,
                onExport = { id -> navigation.onExport(id, false) }, // S012/RF-1
            ),
            onNewProject = navigation.onNewProject,
            onBack = navigation.onBack,
        ),
    )
}

/** Pantalla de una carpeta (S006/RF-20). */
@Composable
fun ProjectFolderScreen(themeState: ThemeState, onToggleSun: () -> Unit, state: ProjectFolderUiState, actions: ProjectFolderActions) {
    val folder = state.folder
    SectionScaffold(
        title = folder?.name.orEmpty(),
        themeState = themeState,
        onToggleSun = onToggleSun,
        onNavigateBack = actions.onBack,
        floatingActionButton = {
            if (folder != null) {
                ExtendedFloatingActionButton(
                    onClick = { actions.onNewProject(folder.id) },
                    modifier = Modifier.heightIn(min = MinTouchTarget).testTag(NEW_PROJECT_TAG),
                ) { Text(stringResource(R.string.project_new)) }
            }
        },
    ) { padding ->
        var moving by remember { mutableStateOf<Project?>(null) }
        LazyColumn(modifier = Modifier.padding(padding).testTag(PROJECT_FOLDER_TAG), contentPadding = PaddingValues(16.dp)) {
            if (state.projects.isEmpty()) {
                item { Text(stringResource(R.string.folder_empty)) }
            } else {
                projectItems(state.projects, actions.list, onMove = { moving = it })
            }
        }
        moving?.let { project ->
            MoveToFolderDialog(project, state.folders, onDismiss = { moving = null }) { folderId ->
                actions.list.onMove(project.id, folderId)
                moving = null
            }
        }
    }
}
