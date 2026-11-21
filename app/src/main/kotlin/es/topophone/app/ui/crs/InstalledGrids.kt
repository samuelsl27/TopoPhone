package es.topophone.app.ui.crs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import es.topophone.app.R
import es.topophone.app.ui.SectionScaffold
import es.topophone.core.designsystem.component.MinTouchTarget
import es.topophone.core.model.preferences.ThemeState
import es.topophone.data.grids.DeleteResult
import es.topophone.data.grids.GridStore
import es.topophone.data.grids.InstalledGrid
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

const val INSTALLED_GRIDS_EMPTY_TAG = "installed_grids_empty"
const val INSTALLED_GRIDS_IN_USE_TAG = "installed_grids_in_use"
const val INSTALLED_GRID_DELETE_CONFIRM_TAG = "installed_grid_delete_confirm"

fun installedGridTag(name: String) = "installed_grid_$name"

fun installedGridDeleteTag(name: String) = "installed_grid_delete_$name"

data class InstalledGridsUiState(
    val grids: List<InstalledGrid> = emptyList(),
    val loaded: Boolean = false,
    /** Los proyectos que usan la rejilla que se intentó borrar y no se pudo (S034/RF-23). */
    val blockedBy: List<String> = emptyList(),
) {
    val totalBytes: Long get() = grids.sumOf { it.sizeBytes }
}

/** Las rejillas y geoides descargados, con su tamaño y el proyecto que los usa, y borrar los que ningún proyecto usa (S034/RF-23). */
@HiltViewModel
class InstalledGridsViewModel @Inject constructor(private val store: GridStore) : ViewModel() {
    private val mutableState = MutableStateFlow(InstalledGridsUiState())
    val state: StateFlow<InstalledGridsUiState> = mutableState.asStateFlow()

    fun refresh() {
        viewModelScope.launch { mutableState.value = InstalledGridsUiState(store.installedGrids(), loaded = true) }
    }

    fun delete(name: String) {
        viewModelScope.launch {
            val result = store.delete(name)
            val grids = store.installedGrids()
            mutableState.value =
                InstalledGridsUiState(grids, loaded = true, blockedBy = (result as? DeleteResult.InUse)?.projectNames.orEmpty())
        }
    }

    fun dismissBlocked() = mutableState.update { it.copy(blockedBy = emptyList()) }

    private inline fun MutableStateFlow<InstalledGridsUiState>.update(change: (InstalledGridsUiState) -> InstalledGridsUiState) {
        value = change(value)
    }
}

@Composable
fun InstalledGridsRoute(
    themeState: ThemeState,
    onToggleSun: () -> Unit,
    viewModel: InstalledGridsViewModel = hiltViewModel(),
    onBack: () -> Unit,
) {
    val state by viewModel.state.collectAsState()
    androidx.compose.runtime.LaunchedEffect(Unit) { viewModel.refresh() }
    InstalledGridsScreen(themeState, onToggleSun, state, viewModel::delete, viewModel::dismissBlocked, onBack)
}

/** La lista de rejillas descargadas con su tamaño y los proyectos que las usan (S034/RF-23). Zonas táctiles de 56 dp (RNF-T8). */
@Composable
fun InstalledGridsScreen(
    themeState: ThemeState,
    onToggleSun: () -> Unit,
    state: InstalledGridsUiState,
    onDelete: (String) -> Unit,
    onDismissBlocked: () -> Unit,
    onBack: () -> Unit,
) {
    var confirming by remember { mutableStateOf<InstalledGrid?>(null) }
    SectionScaffold(
        title = stringResource(R.string.installed_grids_title),
        themeState = themeState,
        onToggleSun = onToggleSun,
        onNavigateBack = onBack,
    ) { padding ->
        if (state.loaded && state.grids.isEmpty()) {
            Text(
                stringResource(R.string.installed_grids_empty),
                modifier = Modifier.padding(padding).padding(16.dp).testTag(INSTALLED_GRIDS_EMPTY_TAG),
            )
        } else {
            LazyColumn(modifier = Modifier.padding(padding).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                item {
                    Text(
                        stringResource(R.string.installed_grids_total, formatSize(state.totalBytes)),
                        style = MaterialTheme.typography.titleSmall,
                    )
                }
                items(state.grids, key = { it.entry.name }) { grid ->
                    Column(modifier = Modifier.fillMaxWidth().testTag(installedGridTag(grid.entry.name))) {
                        Text(grid.entry.verticalCrs.ifEmpty { grid.entry.name }, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            stringResource(R.string.installed_grids_line, grid.entry.name, formatSize(grid.sizeBytes)),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Text(grid.entry.license.name, style = MaterialTheme.typography.bodySmall)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (grid.inUse) {
                                Text(
                                    stringResource(R.string.installed_grids_used_by, grid.usedBy.joinToString(", ")),
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.weight(1f),
                                )
                            } else {
                                Text(
                                    stringResource(R.string.installed_grids_unused),
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.weight(1f),
                                )
                            }
                            OutlinedButton(
                                onClick = { confirming = grid },
                                enabled = !grid.inUse,
                                modifier = Modifier.heightIn(min = MinTouchTarget).testTag(installedGridDeleteTag(grid.entry.name)),
                            ) { Text(stringResource(R.string.installed_grids_delete)) }
                        }
                        HorizontalDivider()
                    }
                }
            }
        }
    }
    confirming?.let { grid ->
        AlertDialog(
            onDismissRequest = { confirming = null },
            title = { Text(stringResource(R.string.installed_grids_delete_title)) },
            text = { Text(stringResource(R.string.installed_grids_delete_text, grid.entry.name, formatSize(grid.sizeBytes))) },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDelete(grid.entry.name)
                        confirming = null
                    },
                    modifier = Modifier.heightIn(min = MinTouchTarget).testTag(INSTALLED_GRID_DELETE_CONFIRM_TAG),
                ) { Text(stringResource(R.string.installed_grids_delete)) }
            },
            dismissButton = {
                TextButton(onClick = {
                    confirming = null
                }, modifier = Modifier.heightIn(min = MinTouchTarget)) { Text(stringResource(R.string.grid_download_cancel)) }
            },
        )
    }
    if (state.blockedBy.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = onDismissBlocked,
            title = { Text(stringResource(R.string.installed_grids_delete_title)) },
            text = {
                Text(
                    stringResource(R.string.installed_grids_blocked, state.blockedBy.joinToString(", ")),
                    modifier = Modifier.testTag(INSTALLED_GRIDS_IN_USE_TAG),
                )
            },
            confirmButton = {
                TextButton(onClick = onDismissBlocked, modifier = Modifier.heightIn(min = MinTouchTarget)) {
                    Text(stringResource(R.string.grid_download_close))
                }
            },
        )
    }
}
