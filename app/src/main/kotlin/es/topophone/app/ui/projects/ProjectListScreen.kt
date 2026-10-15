package es.topophone.app.ui.projects

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import es.topophone.app.ui.receiver.uiLocale
import es.topophone.core.designsystem.R as DesignR
import es.topophone.core.designsystem.component.MinTouchTarget
import es.topophone.core.model.preferences.ThemeState
import es.topophone.core.model.project.Project
import es.topophone.core.model.project.ProjectCrs
import es.topophone.core.model.project.ProjectFolder
import es.topophone.data.project.ProjectRepository
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

const val PROJECT_LIST_TAG = "project_list"
const val NEW_PROJECT_TAG = "new_project"
const val EMPTY_NEW_PROJECT_TAG = "empty_new_project"

fun projectTag(id: String) = "project_$id"

fun folderTag(id: String) = "folder_$id"

/** Lo que muestra la lista de proyectos (S006/RF-1 a RF-3). */
data class ProjectListUiState(val folders: List<ProjectFolder> = emptyList(), val projects: List<Project> = emptyList())

/** Acciones de la lista de proyectos. */
class ProjectListActions(
    val onNewProject: () -> Unit = {},
    val onOpenProject: (String) -> Unit = {},
    val onOpenFolder: (String) -> Unit = {},
)

/** Carpetas y proyectos que no están en ninguna (S006/RF-1). */
@HiltViewModel
class ProjectListViewModel @Inject constructor(repository: ProjectRepository) : ViewModel() {
    val state: StateFlow<ProjectListUiState> = combine(repository.folders(), repository.projects(folderId = null), ::ProjectListUiState)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), ProjectListUiState())

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}

/** Entrada de la lista de proyectos: une el ViewModel con la navegación de Levantamiento. */
@Composable
fun ProjectListRoute(
    themeState: ThemeState,
    onToggleSun: () -> Unit,
    navigation: SurveyNavigation,
    viewModel: ProjectListViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    ProjectListScreen(
        themeState = themeState,
        onToggleSun = onToggleSun,
        state = state,
        actions = ProjectListActions(
            onNewProject = { navigation.onNewProject(null) },
            onOpenProject = navigation.onOpenProject,
            onOpenFolder = navigation.onOpenFolder,
        ),
    )
}

/** Lista de proyectos en Levantamiento, como la app de referencia y con diseño propio (S006/RF-1 a RF-3). */
@Composable
fun ProjectListScreen(themeState: ThemeState, onToggleSun: () -> Unit, state: ProjectListUiState, actions: ProjectListActions) {
    SectionScaffold(
        title = stringResource(R.string.nav_survey),
        themeState = themeState,
        onToggleSun = onToggleSun,
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = actions.onNewProject,
                modifier = Modifier.heightIn(min = MinTouchTarget).testTag(NEW_PROJECT_TAG),
            ) { Text(stringResource(R.string.project_new)) }
        },
    ) { padding ->
        Column(
            modifier = Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(16.dp).testTag(PROJECT_LIST_TAG),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (state.folders.isEmpty() && state.projects.isEmpty()) {
                EmptyProjects(actions.onNewProject)
                return@Column
            }
            if (state.folders.isNotEmpty()) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    state.folders.forEachIndexed { index, folder ->
                        if (index > 0) HorizontalDivider()
                        FolderRow(folder) { actions.onOpenFolder(folder.id) }
                    }
                }
            }
            if (state.projects.isNotEmpty()) ProjectRows(state.projects, actions.onOpenProject)
        }
    }
}

/** S006/RF-3: aún no hay proyectos; se ofrece crear uno. */
@Composable
private fun EmptyProjects(onNewProject: () -> Unit) {
    Text(stringResource(R.string.projects_empty_title), style = MaterialTheme.typography.headlineSmall)
    Text(stringResource(R.string.projects_empty_description), style = MaterialTheme.typography.bodyLarge)
    Button(onClick = onNewProject, modifier = Modifier.heightIn(min = MinTouchTarget).testTag(EMPTY_NEW_PROJECT_TAG)) {
        Text(stringResource(R.string.project_new))
    }
}

@Composable
internal fun ProjectRows(projects: List<Project>, onOpenProject: (String) -> Unit, folderOf: (Project) -> String? = { null }) {
    Card(modifier = Modifier.fillMaxWidth()) {
        projects.forEachIndexed { index, project ->
            if (index > 0) HorizontalDivider()
            ProjectRow(project, folderOf(project)) { onOpenProject(project.id) }
        }
    }
}

/** S006/RF-2: nombre, SC y última modificación (y, al buscar, su carpeta). */
@Composable
private fun ProjectRow(project: Project, folder: String?, onClick: () -> Unit) {
    val modified = stringResource(R.string.project_modified, formatDateTime(project.modifiedAtMs, uiLocale()))
    ListItem(
        headlineContent = { Text(project.name) },
        supportingContent = {
            Column {
                Text(crsLabel(project.crs))
                Text(modified, style = MaterialTheme.typography.bodySmall)
                folder?.let { Text(stringResource(R.string.project_in_folder, it), style = MaterialTheme.typography.bodySmall) }
            }
        },
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        modifier = Modifier.heightIn(min = MinTouchTarget).clickable(onClick = onClick).testTag(projectTag(project.id)),
    )
}

@Composable
private fun FolderRow(folder: ProjectFolder, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(folder.name) },
        leadingContent = { Icon(painterResource(R.drawable.ic_folder), contentDescription = null) },
        trailingContent = { Icon(painterResource(DesignR.drawable.ds_ic_chevron_right), contentDescription = null) },
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        modifier = Modifier.heightIn(min = MinTouchTarget).clickable(onClick = onClick).testTag(folderTag(folder.id)),
    )
}

/** Nombre del SC del proyecto en la interfaz (S006/RF-2, RF-8). */
@Composable
internal fun crsLabel(crs: ProjectCrs): String = stringResource(R.string.project_crs_utm, crs.utmZone)

/** Fecha y hora locales del móvil (RNF-T10). */
internal fun formatDateTime(epochMs: Long, locale: Locale): String = Instant.ofEpochMilli(
    epochMs,
).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM).withLocale(locale))
