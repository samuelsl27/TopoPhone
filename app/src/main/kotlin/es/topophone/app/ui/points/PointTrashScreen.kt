package es.topophone.app.ui.points

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import es.topophone.app.R
import es.topophone.app.ui.SectionScaffold
import es.topophone.app.ui.projects.ConfirmDialog
import es.topophone.app.ui.projects.SurveyNavigation
import es.topophone.core.designsystem.component.MinTouchTarget
import es.topophone.core.model.point.PointNames
import es.topophone.core.model.preferences.ThemeState
import es.topophone.data.point.PointRepository
import es.topophone.data.point.PointSaveResult
import es.topophone.data.point.TrashedPoint
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

const val RENAME_FIELD_TAG = "rename_field"
const val RENAME_RESTORE_TAG = "rename_restore"

fun trashedPointTag(id: String) = "trashed_point_$id"

fun restorePointTag(id: String) = "restore_point_$id"

fun deletePointForeverTag(id: String) = "delete_point_forever_$id"

/** Papelera de puntos de un proyecto (S011/RF-23 a RF-26); [renameFor], el punto cuyo nombre está ocupado. */
data class PointTrashUiState(
    val loaded: Boolean = false,
    val points: List<TrashedPoint> = emptyList(),
    val renameFor: String? = null,
    val renameTaken: Boolean = false,
)

/** Acciones de la papelera de puntos. */
class PointTrashActions(
    val onRestore: (String) -> Unit = {},
    val onRestoreAs: (id: String, name: String) -> Unit = { _, _ -> },
    val onDismissRename: () -> Unit = {},
    val onDeleteForever: (String) -> Unit = {},
)

/** Puntos borrados de un proyecto, restaurar (con otro nombre si el suyo está ocupado) y borrar del todo. */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class PointTrashViewModel @Inject constructor(private val points: PointRepository) : ViewModel() {
    private val projectId = MutableStateFlow<String?>(null)
    private val rename = MutableStateFlow<Pair<String, Boolean>?>(null)

    val state: StateFlow<PointTrashUiState> = projectId.filterNotNull().flatMapLatest { id ->
        combine(points.trash(id), rename) { list, renaming ->
            PointTrashUiState(loaded = true, points = list, renameFor = renaming?.first, renameTaken = renaming?.second == true)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), PointTrashUiState())

    fun start(id: String) {
        projectId.value = id
    }

    /** S011/RF-24; si el nombre lo tiene otro punto, se pide uno nuevo (S011/RF-25). */
    fun restore(id: String) {
        viewModelScope.launch {
            if (points.restore(id) == PointSaveResult.DuplicateName) rename.value = id to false
        }
    }

    /** S011/RF-25. */
    fun restoreAs(id: String, name: String) {
        val clean = PointNames.clean(name)
        if (!PointNames.isValid(clean)) return
        viewModelScope.launch {
            rename.value = if (points.restore(id, clean) == PointSaveResult.DuplicateName) id to true else null
        }
    }

    fun dismissRename() {
        rename.value = null
    }

    /** S011/RF-26: la confirmación la pide la pantalla. */
    fun deleteForever(id: String) {
        viewModelScope.launch { points.deletePermanently(id) }
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}

@Composable
fun PointTrashRoute(
    projectId: String,
    themeState: ThemeState,
    onToggleSun: () -> Unit,
    navigation: SurveyNavigation,
    viewModel: PointTrashViewModel = hiltViewModel(),
) {
    LaunchedEffect(projectId) { viewModel.start(projectId) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    PointTrashScreen(
        themeState,
        onToggleSun,
        state,
        PointTrashActions(
            onRestore = viewModel::restore,
            onRestoreAs = viewModel::restoreAs,
            onDismissRename = viewModel::dismissRename,
            onDeleteForever = viewModel::deleteForever,
        ),
        navigation.onBack,
    )
}

/** Papelera de puntos, como la de proyectos (S006): nombre, días que quedan, restaurar y borrar del todo. */
@Composable
fun PointTrashScreen(
    themeState: ThemeState,
    onToggleSun: () -> Unit,
    state: PointTrashUiState,
    actions: PointTrashActions,
    onBack: () -> Unit,
) {
    var confirming by remember { mutableStateOf<TrashedPoint?>(null) }
    SectionScaffold(
        title = stringResource(R.string.point_trash_title),
        themeState = themeState,
        onToggleSun = onToggleSun,
        onNavigateBack = onBack,
    ) { padding ->
        Column(
            modifier = Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (state.loaded && state.points.isEmpty()) Text(stringResource(R.string.trash_empty))
            state.points.forEach { trashed ->
                val point = trashed.point
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Column(modifier = Modifier.semantics(mergeDescendants = true) {}.testTag(trashedPointTag(point.id))) {
                            Text(point.name, style = MaterialTheme.typography.titleMedium)
                            if (point.description.isNotEmpty()) Text(point.description)
                            Text(stringResource(R.string.trash_days_left, trashed.daysLeft)) // S011/RF-23
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                onClick = { actions.onRestore(point.id) },
                                modifier = Modifier.heightIn(min = MinTouchTarget).testTag(restorePointTag(point.id)),
                            ) { Text(stringResource(R.string.trash_restore)) } // S011/RF-24
                            TextButton(
                                onClick = { confirming = trashed },
                                modifier = Modifier.heightIn(min = MinTouchTarget).testTag(deletePointForeverTag(point.id)),
                            ) { Text(stringResource(R.string.trash_delete_forever)) } // S011/RF-26
                        }
                    }
                }
            }
        }
    }
    confirming?.let { trashed ->
        ConfirmDialog(
            message = stringResource(R.string.trash_delete_forever_confirm, trashed.point.name),
            confirm = stringResource(R.string.trash_delete_forever),
            onConfirm = {
                confirming = null
                actions.onDeleteForever(trashed.point.id)
            },
            onDismiss = { confirming = null },
        )
    }
    val renaming = state.renameFor?.let { id -> state.points.firstOrNull { it.point.id == id }?.point }
    if (renaming != null) RenameDialog(renaming.id, renaming.name, state.renameTaken, actions)
}

/** El nombre del punto ya lo tiene otro: se pide uno nuevo para restaurarlo (S011/RF-25). */
@Composable
private fun RenameDialog(id: String, current: String, taken: Boolean, actions: PointTrashActions) {
    var name by remember(id) { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = actions.onDismissRename,
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.point_trash_name_taken))
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.collector_name)) },
                    isError = taken,
                    supportingText = if (taken) ({ Text(stringResource(R.string.collector_name_taken)) }) else null,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag(RENAME_FIELD_TAG),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { actions.onRestoreAs(id, name) },
                enabled = PointNames.isValid(name),
                modifier = Modifier.heightIn(min = MinTouchTarget).testTag(RENAME_RESTORE_TAG),
            ) { Text(stringResource(R.string.trash_restore)) }
        },
        dismissButton = {
            TextButton(onClick = actions.onDismissRename, modifier = Modifier.heightIn(min = MinTouchTarget)) {
                Text(stringResource(R.string.dialog_cancel))
            }
        },
    )
}
