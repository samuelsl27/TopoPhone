package es.topophone.app.ui.projects

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.ZeroCornerSize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
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
import es.topophone.app.ui.backup.BackupNotice
import es.topophone.app.ui.backup.BackupNoticeViewModel
import es.topophone.app.ui.backup.rememberPackageHost
import es.topophone.app.ui.receiver.uiLocale
import es.topophone.core.designsystem.component.MinTouchTarget
import es.topophone.core.model.preferences.ThemeState
import es.topophone.core.model.project.GeodeticDatum
import es.topophone.core.model.project.NameError
import es.topophone.core.model.project.Project
import es.topophone.core.model.project.ProjectCrs
import es.topophone.core.model.project.ProjectFolder
import es.topophone.data.project.ProjectRepository
import es.topophone.data.project.ProjectSearchResult
import es.topophone.data.project.SaveResult
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

const val PROJECT_LIST_TAG = "project_list"
const val BACKUP_NOTICE_TAG = "backup_notice"
const val BACKUP_CHOOSE_FOLDER_TAG = "backup_notice_choose"
const val BACKUP_NOTICE_DISMISS_TAG = "backup_notice_dismiss"
const val BACKUP_REVIEW_TAG = "backup_notice_review"
const val NEW_PROJECT_TAG = "new_project"
const val EMPTY_NEW_PROJECT_TAG = "empty_new_project"
const val PROJECT_SEARCH_TAG = "project_search"
const val LIST_MENU_TAG = "project_list_menu"
const val FOLDER_NAME_TAG = "folder_name"
const val FOLDER_SAVE_TAG = "folder_save"
const val CONFIRM_TAG = "dialog_confirm"

fun projectTag(id: String) = "project_$id"

fun folderTag(id: String) = "folder_$id"

fun projectMenuTag(id: String) = "project_menu_$id"

fun folderMenuTag(id: String) = "folder_menu_$id"

/** Diálogo del nombre de una carpeta: crearla o renombrarla (S006/RF-17, RF-18). */
sealed interface FolderDialog {
    data object Create : FolderDialog

    data class Rename(val id: String, val name: String) : FolderDialog
}

data class FolderDialogState(val dialog: FolderDialog, val error: NameError? = null)

/** Destino de "Mover a carpeta"; [ROOT_TARGET] es fuera de las carpetas. */
fun moveTargetTag(folderId: String?) = "move_target_${folderId ?: ROOT_TARGET}"

private const val ROOT_TARGET = "root"

/** Lo que muestra la lista de proyectos (S006/RF-1 a RF-4); [results] solo mientras se busca. */
data class ProjectListUiState(
    val folders: List<ProjectFolder> = emptyList(),
    val projects: List<Project> = emptyList(),
    val query: String = "",
    val results: List<ProjectSearchResult>? = null,
    val folderDialog: FolderDialogState? = null,
)

/** Acciones de la lista de proyectos. */
class ProjectListActions(
    val onNewProject: () -> Unit = {},
    val onOpenProject: (String) -> Unit = {},
    val onOpenFolder: (String) -> Unit = {},
    val onQueryChange: (String) -> Unit = {},
    val onEdit: (String) -> Unit = {},
    val onDuplicate: (String) -> Unit = {},
    val onMove: (projectId: String, folderId: String?) -> Unit = { _, _ -> },
    val onNewFolder: () -> Unit = {},
    val onOpenTrash: () -> Unit = {},
    val onRenameFolder: (ProjectFolder) -> Unit = {},
    val onDeleteFolder: (String) -> Unit = {},
    val onSubmitFolderName: (String) -> Unit = {},
    val onDismissFolderDialog: () -> Unit = {},
    val onDelete: (String) -> Unit = {},
    val onExport: (String) -> Unit = {},
    /** El informe de replanteo del proyecto (S019/RF-17). */
    val onStakeoutReport: (String) -> Unit = {},
    /** S013/RF-24. */
    val onExportPackage: (Project) -> Unit = {},
    /** S013/RF-25: abre el selector de ficheros para elegir el paquete. */
    val onImportPackage: () -> Unit = {},
    /** S013/RF-22: abre el selector de carpetas para las copias automáticas. */
    val onChooseBackupFolder: () -> Unit = {},
    /** S013/RF-23. */
    val onDismissBackupNotice: () -> Unit = {},
    /** S013/RF-21: lleva a "Copias de seguridad". */
    val onReviewBackups: () -> Unit = {},
)

/** Carpetas y proyectos que no están en ninguna (S006/RF-1) y la búsqueda en todas (S006/RF-4). */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ProjectListViewModel @Inject constructor(private val repository: ProjectRepository) : ViewModel() {
    private val query = MutableStateFlow("")

    private val results: Flow<List<ProjectSearchResult>?> = query.flatMapLatest { text ->
        if (text.isBlank()) flowOf(null) else repository.search(text)
    }

    private val folderDialog = MutableStateFlow<FolderDialogState?>(null)

    val state: StateFlow<ProjectListUiState> =
        combine(repository.folders(), repository.projects(folderId = null), query, results, folderDialog) {
                folders,
                projects,
                text,
                found,
                dialog,
            ->
            ProjectListUiState(folders, projects, text, found, dialog)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), ProjectListUiState())

    fun search(text: String) {
        query.value = text
    }

    /** [copyLabel] es la palabra de la interfaz para la copia (S006/RF-16). */
    fun duplicate(id: String, copyLabel: String) {
        viewModelScope.launch { repository.duplicate(id, copyLabel) }
    }

    fun moveToFolder(id: String, folderId: String?) {
        viewModelScope.launch { repository.moveToFolder(id, folderId) } // S006/RF-19
    }

    fun openNewFolder() {
        folderDialog.value = FolderDialogState(FolderDialog.Create)
    }

    fun openRenameFolder(folder: ProjectFolder) {
        folderDialog.value = FolderDialogState(FolderDialog.Rename(folder.id, folder.name))
    }

    fun dismissFolderDialog() {
        folderDialog.value = null
    }

    /** Crea o renombra según el diálogo abierto; si el nombre no vale, el diálogo dice por qué (S006/RF-17, RF-18). */
    fun submitFolderName(name: String) {
        val dialog = folderDialog.value?.dialog ?: return
        viewModelScope.launch {
            val result = when (dialog) {
                FolderDialog.Create -> repository.createFolder(name)
                is FolderDialog.Rename -> repository.renameFolder(dialog.id, name)
            }
            folderDialog.value = when (result) {
                is SaveResult.Invalid -> FolderDialogState(dialog, result.error)
                is SaveResult.Saved -> null
            }
        }
    }

    fun deleteFolder(id: String) {
        viewModelScope.launch { repository.deleteFolder(id) } // S006/RF-21
    }

    fun delete(id: String) {
        viewModelScope.launch { repository.moveToTrash(id) } // S006/RF-22
    }

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
    noticeViewModel: BackupNoticeViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val notice by noticeViewModel.notice.collectAsStateWithLifecycle()
    val copyLabel = stringResource(R.string.project_copy_label)
    val packages = rememberPackageHost()
    val chooseFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) noticeViewModel.folderChosen(uri.toString()) // S013/RF-22
    }
    ProjectListScreen(
        themeState = themeState,
        onToggleSun = onToggleSun,
        state = state,
        actions = ProjectListActions(
            onNewProject = { navigation.onNewProject(null) },
            onOpenProject = navigation.onOpenProject,
            onOpenFolder = navigation.onOpenFolder,
            onQueryChange = viewModel::search,
            onEdit = navigation.onEditProject,
            onDuplicate = { viewModel.duplicate(it, copyLabel) },
            onMove = viewModel::moveToFolder,
            onOpenTrash = navigation.onOpenTrash,
            onNewFolder = viewModel::openNewFolder,
            onRenameFolder = viewModel::openRenameFolder,
            onDeleteFolder = viewModel::deleteFolder,
            onSubmitFolderName = viewModel::submitFolderName,
            onDismissFolderDialog = viewModel::dismissFolderDialog,
            onDelete = viewModel::delete,
            onExport = { id -> navigation.onExport(id, false) }, // S012/RF-1
            onStakeoutReport = { id -> navigation.onStakeoutReport(id, false) }, // S019/RF-17
            onExportPackage = packages.export,
            onImportPackage = packages.import,
            onChooseBackupFolder = { chooseFolder.launch(null) },
            onDismissBackupNotice = noticeViewModel::dismiss,
            onReviewBackups = navigation.onOpenBackups,
        ),
        backupNotice = notice,
    )
}

/** Lista de proyectos en Levantamiento, como la app de referencia y con diseño propio (S006/RF-1 a RF-3). */
@Composable
fun ProjectListScreen(
    themeState: ThemeState,
    onToggleSun: () -> Unit,
    state: ProjectListUiState,
    actions: ProjectListActions,
    backupNotice: BackupNotice = BackupNotice.NONE,
) {
    SectionScaffold(
        title = stringResource(R.string.nav_survey),
        themeState = themeState,
        onToggleSun = onToggleSun,
        actions = { ListMenu(actions) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = actions.onNewProject,
                modifier = Modifier.heightIn(min = MinTouchTarget).testTag(NEW_PROJECT_TAG),
            ) { Text(stringResource(R.string.project_new)) }
        },
    ) { padding ->
        var moving by remember { mutableStateOf<Project?>(null) }
        // Lista perezosa: con cientos de proyectos solo se pintan los que se ven (RNF de la spec).
        LazyColumn(modifier = Modifier.padding(padding).testTag(PROJECT_LIST_TAG), contentPadding = PaddingValues(16.dp)) {
            if (backupNotice != BackupNotice.NONE) item { BackupNoticeCard(backupNotice, actions) }
            if (state.folders.isEmpty() && state.projects.isEmpty() && state.query.isEmpty()) {
                item { EmptyProjects(actions.onNewProject) }
                return@LazyColumn
            }
            item {
                OutlinedTextField(
                    value = state.query,
                    onValueChange = actions.onQueryChange,
                    label = { Text(stringResource(R.string.projects_search)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp).testTag(PROJECT_SEARCH_TAG),
                ) // S006/RF-4
            }
            val results = state.results
            if (results != null) {
                if (results.isEmpty()) {
                    item { Text(stringResource(R.string.projects_search_empty, state.query)) }
                } else {
                    val folders = results.associate { it.project.id to it.folder?.name }
                    projectItems(results.map { it.project }, actions, folderOf = { folders[it.id] }, onMove = { moving = it })
                }
                return@LazyColumn
            }
            itemsIndexed(state.folders, key = { _, folder -> "folder-${folder.id}" }) { index, folder ->
                Column(Modifier.clip(groupShape(index, state.folders.size))) {
                    if (index > 0) HorizontalDivider()
                    FolderRow(folder, actions)
                }
            }
            if (state.folders.isNotEmpty() && state.projects.isNotEmpty()) item { Spacer(Modifier.height(12.dp)) }
            projectItems(state.projects, actions, onMove = { moving = it })
        }
        moving?.let { project ->
            MoveToFolderDialog(project, state.folders, onDismiss = { moving = null }) { folderId ->
                actions.onMove(project.id, folderId)
                moving = null
            }
        }
        state.folderDialog?.let { FolderNameDialog(it, actions) }
    }
}

/** Nombre de una carpeta nueva o renombrada, con el motivo si no vale (S006/RF-17, RF-18). */
@Composable
private fun FolderNameDialog(state: FolderDialogState, actions: ProjectListActions) {
    val initial = (state.dialog as? FolderDialog.Rename)?.name.orEmpty()
    var name by rememberSaveable(state.dialog) { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = actions.onDismissFolderDialog,
        title = { Text(stringResource(if (state.dialog is FolderDialog.Rename) R.string.folder_rename else R.string.folder_new)) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(R.string.folder_name)) },
                isError = state.error != null,
                supportingText = state.error?.let { error -> { Text(stringResource(folderErrorText(error))) } },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag(FOLDER_NAME_TAG),
            )
        },
        confirmButton = {
            TextButton(onClick = {
                actions.onSubmitFolderName(name)
            }, modifier = Modifier.heightIn(min = MinTouchTarget).testTag(FOLDER_SAVE_TAG)) {
                Text(stringResource(R.string.project_save))
            }
        },
        dismissButton = {
            TextButton(onClick = actions.onDismissFolderDialog, modifier = Modifier.heightIn(min = MinTouchTarget)) {
                Text(stringResource(R.string.dialog_cancel))
            }
        },
    )
}

private fun folderErrorText(error: NameError): Int = when (error) {
    NameError.EMPTY -> R.string.folder_name_empty
    NameError.DUPLICATE -> R.string.folder_name_duplicate
}

/** S013/RF-21, RF-22: copias automáticas sin carpeta, o la última fallida. */
@Composable
private fun BackupNoticeCard(notice: BackupNotice, actions: ProjectListActions) {
    Surface(
        tonalElevation = 2.dp,
        shape = MaterialTheme.shapes.medium,
        color = if (notice == BackupNotice.FAILED) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp).testTag(BACKUP_NOTICE_TAG),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            val text = if (notice == BackupNotice.FAILED) R.string.backup_auto_failed else R.string.backup_notice_no_folder
            Text(stringResource(text), style = MaterialTheme.typography.bodyLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (notice == BackupNotice.FAILED) {
                    TextButton(
                        onClick = actions.onReviewBackups,
                        modifier = Modifier.heightIn(min = MinTouchTarget).testTag(BACKUP_REVIEW_TAG),
                    ) {
                        Text(stringResource(R.string.backup_review))
                    }
                } else {
                    TextButton(
                        onClick = actions.onChooseBackupFolder,
                        modifier = Modifier.heightIn(min = MinTouchTarget).testTag(BACKUP_CHOOSE_FOLDER_TAG),
                    ) { Text(stringResource(R.string.backup_choose_folder)) }
                    TextButton(
                        onClick = actions.onDismissBackupNotice,
                        modifier = Modifier.heightIn(min = MinTouchTarget).testTag(BACKUP_NOTICE_DISMISS_TAG),
                    ) { Text(stringResource(R.string.backup_notice_later)) }
                }
            }
        }
    }
}

/** Diálogo de confirmación con su botón destructivo (S006/RF-21, RF-22, RF-26). */
@Composable
internal fun ConfirmDialog(message: String, confirm: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        text = { Text(message) },
        confirmButton = {
            TextButton(onClick = onConfirm, modifier = Modifier.heightIn(min = MinTouchTarget).testTag(CONFIRM_TAG)) { Text(confirm) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = MinTouchTarget)) {
                Text(stringResource(R.string.dialog_cancel))
            }
        },
    )
}

/** S006/RF-3: aún no hay proyectos; se ofrece crear uno. */
@Composable
private fun EmptyProjects(onNewProject: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.projects_empty_title), style = MaterialTheme.typography.headlineSmall)
        Text(stringResource(R.string.projects_empty_description), style = MaterialTheme.typography.bodyLarge)
        Button(onClick = onNewProject, modifier = Modifier.heightIn(min = MinTouchTarget).testTag(EMPTY_NEW_PROJECT_TAG)) {
            Text(stringResource(R.string.project_new))
        }
    }
}

/** Filas de proyectos de una lista perezosa, cada una con su menú; [onMove] abre "Mover a carpeta". */
internal fun LazyListScope.projectItems(
    projects: List<Project>,
    actions: ProjectListActions,
    folderOf: (Project) -> String? = { null },
    onMove: (Project) -> Unit,
) {
    itemsIndexed(projects, key = { _, project -> "project-${project.id}" }) { index, project ->
        Column(Modifier.clip(groupShape(index, projects.size))) {
            if (index > 0) HorizontalDivider()
            ProjectRow(project, folderOf(project), onClick = { actions.onOpenProject(project.id) }) {
                ProjectMenu(project, actions, onMove = { onMove(project) })
            }
        }
    }
}

/** Esquinas de la fila [index] de un grupo de [count]: las filas seguidas se ven como una sola tarjeta. */
@Composable
private fun groupShape(index: Int, count: Int): Shape {
    val card = MaterialTheme.shapes.medium
    val first = index == 0
    val last = index == count - 1
    return card.copy(
        topStart = if (first) card.topStart else ZeroCornerSize,
        topEnd = if (first) card.topEnd else ZeroCornerSize,
        bottomEnd = if (last) card.bottomEnd else ZeroCornerSize,
        bottomStart = if (last) card.bottomStart else ZeroCornerSize,
    )
}

/** Menú "⋮" de cada proyecto: editar, duplicar, mover a carpeta y borrar (S006/RF-14, RF-16, RF-19, RF-22). */
@Composable
private fun ProjectMenu(project: Project, actions: ProjectListActions, onMove: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    IconButton(onClick = { open = true }, modifier = Modifier.size(MinTouchTarget).testTag(projectMenuTag(project.id))) {
        Icon(painterResource(R.drawable.ic_more_vert), contentDescription = stringResource(R.string.project_menu, project.name))
    }
    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
        MenuItem(R.string.project_edit) {
            open = false
            actions.onEdit(project.id)
        }
        MenuItem(R.string.project_duplicate) {
            open = false
            actions.onDuplicate(project.id)
        }
        MenuItem(R.string.project_move) {
            open = false
            onMove()
        }
        MenuItem(R.string.export_csv) {
            open = false
            actions.onExport(project.id)
        } // S012/RF-1
        MenuItem(R.string.stakeout_report_title) {
            open = false
            actions.onStakeoutReport(project.id)
        } // S019/RF-17
        MenuItem(R.string.package_export) {
            open = false
            actions.onExportPackage(project)
        } // S013/RF-24
        MenuItem(R.string.project_delete) {
            open = false
            confirmDelete = true
        }
    }
    if (confirmDelete) {
        ConfirmDialog(
            message = stringResource(R.string.project_delete_confirm, project.name),
            confirm = stringResource(R.string.project_delete),
            onConfirm = {
                confirmDelete = false
                actions.onDelete(project.id)
            },
            onDismiss = { confirmDelete = false },
        )
    }
}

/** Menú "⋮" de la lista: nueva carpeta y papelera (S006/RF-17, RF-23). */
@Composable
private fun ListMenu(actions: ProjectListActions) {
    var open by remember { mutableStateOf(false) }
    IconButton(onClick = { open = true }, modifier = Modifier.size(MinTouchTarget).testTag(LIST_MENU_TAG)) {
        Icon(painterResource(R.drawable.ic_more_vert), contentDescription = stringResource(R.string.projects_menu))
    }
    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
        MenuItem(R.string.folder_new) {
            open = false
            actions.onNewFolder()
        }
        MenuItem(R.string.trash_title) {
            open = false
            actions.onOpenTrash()
        }
        MenuItem(R.string.package_import) {
            open = false
            actions.onImportPackage()
        } // S013/RF-25
    }
}

@Composable
private fun MenuItem(text: Int, onClick: () -> Unit) {
    DropdownMenuItem(text = { Text(stringResource(text)) }, onClick = onClick, modifier = Modifier.heightIn(min = MinTouchTarget))
}

/** S006/RF-19: a una carpeta o fuera de todas. */
@Composable
internal fun MoveToFolderDialog(project: Project, folders: List<ProjectFolder>, onDismiss: () -> Unit, onMove: (String?) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.project_move_title, project.name)) },
        text = {
            Column {
                MoveTarget(stringResource(R.string.project_no_folder), moveTargetTag(null)) { onMove(null) }
                folders.forEach { folder -> MoveTarget(folder.name, moveTargetTag(folder.id)) { onMove(folder.id) } }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = MinTouchTarget)) {
                Text(stringResource(R.string.dialog_cancel))
            }
        },
    )
}

@Composable
private fun MoveTarget(label: String, tag: String, onClick: () -> Unit) {
    Text(
        label,
        modifier = Modifier.fillMaxWidth().heightIn(
            min = MinTouchTarget,
        ).clickable(onClick = onClick).padding(vertical = 16.dp).testTag(tag),
    )
}

/** S006/RF-2: nombre, SC y última modificación (y, al buscar, su carpeta). */
@Composable
private fun ProjectRow(project: Project, folder: String?, onClick: () -> Unit, menu: @Composable () -> Unit) {
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
        trailingContent = menu,
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        modifier = Modifier.heightIn(min = MinTouchTarget).clickable(onClick = onClick).testTag(projectTag(project.id)),
    )
}

/** Una carpeta: se abre al pulsarla; su menú permite renombrarla y borrarla (S006/RF-18, RF-20, RF-21). */
@Composable
private fun FolderRow(folder: ProjectFolder, actions: ProjectListActions) {
    var menu by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    ListItem(
        headlineContent = { Text(folder.name) },
        leadingContent = { Icon(painterResource(R.drawable.ic_folder), contentDescription = null) },
        trailingContent = {
            IconButton(onClick = { menu = true }, modifier = Modifier.size(MinTouchTarget).testTag(folderMenuTag(folder.id))) {
                Icon(painterResource(R.drawable.ic_more_vert), contentDescription = stringResource(R.string.project_menu, folder.name))
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                MenuItem(R.string.folder_rename) {
                    menu = false
                    actions.onRenameFolder(folder)
                }
                MenuItem(R.string.folder_delete) {
                    menu = false
                    confirmDelete = true
                }
            }
        },
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        modifier = Modifier.heightIn(min = MinTouchTarget).clickable { actions.onOpenFolder(folder.id) }.testTag(folderTag(folder.id)),
    )
    if (confirmDelete) {
        ConfirmDialog(
            message = stringResource(R.string.folder_delete_confirm, folder.name),
            confirm = stringResource(R.string.folder_delete),
            onConfirm = {
                confirmDelete = false
                actions.onDeleteFolder(folder.id)
            },
            onDismiss = { confirmDelete = false },
        )
    }
}

/** Nombre del SC del proyecto en la interfaz: "ETRS89 / UTM huso 30", "REGCAN95 / geográficas" (S006/RF-2, S008/RF-1). */
@Composable
internal fun crsLabel(crs: ProjectCrs): String {
    val datum = datumName(crs.datum)
    val zone = crs.utmZone
    return if (zone !=
        null
    ) {
        stringResource(R.string.project_crs_utm, datum, zone)
    } else {
        stringResource(R.string.project_crs_geographic, datum)
    }
}

@Composable
internal fun datumName(datum: GeodeticDatum): String = stringResource(
    when (datum) {
        GeodeticDatum.ETRS89 -> R.string.crs_datum_etrs89
        GeodeticDatum.REGCAN95 -> R.string.crs_datum_regcan95
        GeodeticDatum.ED50 -> R.string.crs_datum_ed50
    },
)

/** Fecha y hora locales del móvil (RNF-T10). */
internal fun formatDateTime(epochMs: Long, locale: Locale): String = Instant.ofEpochMilli(
    epochMs,
).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM).withLocale(locale))
