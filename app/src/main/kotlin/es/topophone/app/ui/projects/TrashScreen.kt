package es.topophone.app.ui.projects

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import es.topophone.core.designsystem.component.MinTouchTarget
import es.topophone.core.model.preferences.ThemeState
import es.topophone.data.point.PointRepository
import es.topophone.data.project.ProjectRepository
import es.topophone.data.project.TrashedProject
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

const val TRASH_TAG = "trash"

fun trashedTag(id: String) = "trashed_$id"

fun restoreTag(id: String) = "restore_$id"

fun deleteForeverTag(id: String) = "delete_forever_$id"

/** Proyectos de la papelera (S006/RF-23). */
data class TrashUiState(val projects: List<TrashedProject> = emptyList())

class TrashActions(val onRestore: (String) -> Unit = {}, val onDeleteForever: (String) -> Unit = {}, val onBack: () -> Unit = {})

/** La papelera: restaurar o borrar definitivamente (S006/RF-23 a RF-26). */
@HiltViewModel
class TrashViewModel @Inject constructor(private val repository: ProjectRepository) : ViewModel() {
    val state: StateFlow<TrashUiState> = repository.trash().map(::TrashUiState)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), TrashUiState())

    /** [restoredLabel] es la palabra de la interfaz si su nombre está ocupado (S006/RF-25). */
    fun restore(id: String, restoredLabel: String) {
        viewModelScope.launch { repository.restore(id, restoredLabel) }
    }

    fun deleteForever(id: String) {
        viewModelScope.launch { repository.deleteForever(id) }
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}

/** Purga las papeleras de proyectos y de puntos al abrir la app, una sola vez por proceso (S006/RF-27, S011/RF-26). */
@Singleton
class TrashPurge @Inject constructor(private val repository: ProjectRepository, private val points: PointRepository) {
    private val done = AtomicBoolean(false)

    /** Devuelve si ha purgado en esta llamada. */
    suspend fun runOnce(): Boolean {
        if (!done.compareAndSet(false, true)) return false
        repository.purgeTrash()
        points.purgeTrash()
        return true
    }
}

@Composable
fun TrashRoute(themeState: ThemeState, onToggleSun: () -> Unit, navigation: SurveyNavigation, viewModel: TrashViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val restoredLabel = stringResource(R.string.project_restored_label)
    TrashScreen(
        themeState,
        onToggleSun,
        state,
        TrashActions(onRestore = {
            viewModel.restore(it, restoredLabel)
        }, onDeleteForever = viewModel::deleteForever, onBack = navigation.onBack),
    )
}

/** Papelera de proyectos (S006/RF-23 a RF-26). */
@Composable
fun TrashScreen(themeState: ThemeState, onToggleSun: () -> Unit, state: TrashUiState, actions: TrashActions) {
    var confirming by remember { mutableStateOf<TrashedProject?>(null) }
    SectionScaffold(
        title = stringResource(R.string.trash_title),
        themeState = themeState,
        onToggleSun = onToggleSun,
        onNavigateBack = actions.onBack,
    ) { padding ->
        Column(
            modifier = Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(16.dp).testTag(TRASH_TAG),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (state.projects.isEmpty()) Text(stringResource(R.string.trash_empty))
            state.projects.forEach { trashed ->
                val id = trashed.project.id
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Column(modifier = Modifier.semantics(mergeDescendants = true) {}.testTag(trashedTag(id))) {
                            Text(trashed.project.name, style = MaterialTheme.typography.titleMedium)
                            Text(stringResource(R.string.trash_days_left, trashed.daysLeft)) // S006/RF-23
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = {
                                actions.onRestore(id)
                            }, modifier = Modifier.heightIn(min = MinTouchTarget).testTag(restoreTag(id))) {
                                Text(stringResource(R.string.trash_restore))
                            } // S006/RF-24
                            TextButton(onClick = {
                                confirming = trashed
                            }, modifier = Modifier.heightIn(min = MinTouchTarget).testTag(deleteForeverTag(id))) {
                                Text(stringResource(R.string.trash_delete_forever))
                            } // S006/RF-26
                        }
                    }
                }
            }
        }
    }
    confirming?.let { trashed ->
        ConfirmDialog(
            message = stringResource(R.string.trash_delete_forever_confirm, trashed.project.name),
            confirm = stringResource(R.string.trash_delete_forever),
            onConfirm = {
                confirming = null
                actions.onDeleteForever(trashed.project.id)
            },
            onDismiss = { confirming = null },
        )
    }
}
