package es.topophone.app.ui.projects

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import es.topophone.app.ui.receiver.uiLocale
import es.topophone.core.designsystem.component.MinTouchTarget
import es.topophone.core.model.preferences.ThemeState
import es.topophone.core.model.project.CrsChangeKind
import es.topophone.core.model.project.LibraryCrs
import es.topophone.core.model.project.ProjectCrs
import es.topophone.core.model.project.ProjectCrsChange
import es.topophone.data.crs.CrsChangeResult
import es.topophone.data.crs.ProjectCrsHistoryRepository
import es.topophone.data.project.ProjectRepository
import java.text.DateFormat
import java.util.Date
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

const val CRS_HISTORY_OPEN_TAG = "crs_history_open"
const val CRS_HISTORY_EMPTY_TAG = "crs_history_empty"
const val CRS_HISTORY_UNDO_TAG = "crs_history_undo"
const val CRS_HISTORY_MESSAGE_TAG = "crs_history_message"

fun crsHistoryRowTag(id: String) = "crs_history_row_$id"

/** Lo que muestra el historial de cambios de SC de un proyecto (S035/RF-20, RF-21). */
data class ProjectCrsHistoryUiState(
    /** Los cambios, del más reciente al más antiguo. */
    val changes: List<ProjectCrsChange> = emptyList(),
    /** ¿Se puede deshacer el más reciente? Solo si el SC del proyecto sigue siendo el de ese cambio (S035/RF-21). */
    val canUndo: Boolean = false,
    /** El SC al que se acaba de volver, para decirlo (S035/RF-21). */
    val undoneTo: ProjectCrs? = null,
    val cannotUndo: Boolean = false,
)

/** El historial de cambios de SC de un proyecto y «Volver al SC anterior» (S035/RF-20, RF-21). */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ProjectCrsHistoryViewModel @Inject constructor(
    private val history: ProjectCrsHistoryRepository,
    private val projects: ProjectRepository,
) : ViewModel() {
    private val projectId = MutableStateFlow<String?>(null)
    private val undone = MutableStateFlow<ProjectCrs?>(null)
    private val cannotUndo = MutableStateFlow(false)

    val state: StateFlow<ProjectCrsHistoryUiState> = projectId.flatMapLatest { id ->
        if (id == null) {
            flowOf(ProjectCrsHistoryUiState())
        } else {
            combine(history.observe(id), projects.observe(id), undone, cannotUndo) { changes, project, to, failed ->
                val last = changes.firstOrNull()
                val canUndo = last != null && project != null && last.to == project.crs && last.toHeightSystem == project.heightSystem
                ProjectCrsHistoryUiState(changes, canUndo, to, failed)
            }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), ProjectCrsHistoryUiState())

    fun start(id: String) {
        projectId.value = id
    }

    /** «Volver al SC anterior»: el cambio más reciente se deshace y queda anotado como un cambio más (S035/RF-21). */
    fun undo() {
        val id = projectId.value ?: return
        viewModelScope.launch {
            when (val result = history.undo(id)) {
                is CrsChangeResult.Applied -> {
                    cannotUndo.value = false
                    undone.value = result.change.to
                }
                else -> {
                    undone.value = null
                    cannotUndo.value = true
                }
            }
        }
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}

/** El nombre de un SC para el historial: el de la biblioteca o personalizado por su nombre y los de España como en el resto de la app. */
@Composable
internal fun projectCrsName(crs: ProjectCrs): String = if (crs is LibraryCrs) crs.definition.name else crsLabel(crs)

@Composable
fun ProjectCrsHistoryRoute(
    projectId: String,
    themeState: ThemeState,
    onToggleSun: () -> Unit,
    onBack: () -> Unit,
    viewModel: ProjectCrsHistoryViewModel = hiltViewModel(),
) {
    LaunchedEffect(projectId) { viewModel.start(projectId) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    ProjectCrsHistoryScreen(themeState, onToggleSun, state, viewModel::undo, onBack)
}

/** El historial del SC del proyecto: fecha, SC anterior y nuevo, y «Volver al SC anterior» en el último cambio (S035/RF-20, RF-21). */
@Composable
fun ProjectCrsHistoryScreen(
    themeState: ThemeState,
    onToggleSun: () -> Unit,
    state: ProjectCrsHistoryUiState,
    onUndo: () -> Unit,
    onBack: () -> Unit,
) {
    SectionScaffold(
        title = stringResource(R.string.crs_history_title),
        themeState = themeState,
        onToggleSun = onToggleSun,
        onNavigateBack = onBack,
    ) { padding ->
        Column(
            modifier = Modifier.padding(padding).fillMaxSize().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            state.undoneTo?.let { to ->
                Text(
                    stringResource(R.string.crs_history_undone, projectCrsName(to)),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.testTag(CRS_HISTORY_MESSAGE_TAG),
                )
            }
            if (state.cannotUndo) {
                Text(
                    stringResource(R.string.crs_history_cannot_undo),
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.testTag(CRS_HISTORY_MESSAGE_TAG),
                )
            }
            if (state.changes.isEmpty()) {
                Text(
                    stringResource(R.string.crs_history_empty),
                    modifier = Modifier.padding(vertical = 8.dp).testTag(CRS_HISTORY_EMPTY_TAG),
                )
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(state.changes, key = { it.id }) { change ->
                        ChangeRow(change, undo = state.canUndo && change.id == state.changes.first().id, onUndo = onUndo)
                    }
                }
            }
        }
    }
}

@Composable
private fun ChangeRow(change: ProjectCrsChange, undo: Boolean, onUndo: () -> Unit) {
    val format = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, uiLocale())
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp).testTag(crsHistoryRowTag(change.id))) {
        Text(format.format(Date(change.atMs)), style = MaterialTheme.typography.labelMedium)
        val from = projectCrsName(change.from)
        val to = projectCrsName(change.to)
        Text(
            stringResource(if (change.kind == CrsChangeKind.UNDO) R.string.crs_history_undo_row else R.string.crs_history_change, from, to),
            style = MaterialTheme.typography.bodyLarge,
        )
        if (change.fromHeightSystem != change.toHeightSystem) {
            Text(
                stringResource(R.string.crs_history_heights, heightLabel(change.fromHeightSystem), heightLabel(change.toHeightSystem)),
                style = MaterialTheme.typography.bodySmall,
            )
        }
        if (undo) {
            OutlinedButton(onClick = onUndo, modifier = Modifier.heightIn(min = MinTouchTarget).testTag(CRS_HISTORY_UNDO_TAG)) {
                Text(stringResource(R.string.crs_history_undo))
            }
        }
    }
    HorizontalDivider()
}
