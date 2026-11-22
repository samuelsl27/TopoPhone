package es.topophone.app.ui.crs

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import es.topophone.app.R
import es.topophone.app.ui.SectionScaffold
import es.topophone.core.designsystem.component.MinTouchTarget
import es.topophone.core.model.preferences.ThemeState
import es.topophone.core.model.project.CustomCrs
import es.topophone.data.crs.CustomCrsRepository
import es.topophone.data.crs.CustomCrsSave
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

const val MY_CRS_EMPTY_TAG = "my_crs_empty"
const val MY_CRS_NEW_TAG = "my_crs_new"
const val MY_CRS_RENAME_FIELD_TAG = "my_crs_rename_field"
const val MY_CRS_RENAME_CONFIRM_TAG = "my_crs_rename_confirm"
const val MY_CRS_DELETE_CONFIRM_TAG = "my_crs_delete_confirm"
const val MY_CRS_NAME_TAKEN_TAG = "my_crs_name_taken"

fun myCrsTag(id: String) = "my_crs_$id"

fun myCrsMenuTag(id: String) = "my_crs_menu_$id"

fun myCrsActionTag(action: String, id: String) = "my_crs_${action}_$id"

/** Lo que hace la pantalla de Mis SC con un SC de la lista (S035/RF-10). */
class MyCrsActions(
    val onNew: () -> Unit = {},
    val onEdit: (CustomCrs) -> Unit = {},
    val onDuplicate: (CustomCrs) -> Unit = {},
    val onRename: (CustomCrs, String) -> Unit = { _, _ -> },
    val onDelete: (CustomCrs) -> Unit = {},
    val onDismissNameTaken: () -> Unit = {},
    val onBack: () -> Unit = {},
)

data class MyCrsUiState(
    val items: List<CustomCrs> = emptyList(),
    val loaded: Boolean = false,
    /** El nombre al que se quiso renombrar ya lo tiene otro SC (S035/RF-10). */
    val nameTaken: Boolean = false,
    /** Se acaba de renombrar bien: el diálogo se puede cerrar. */
    val renamed: Boolean = false,
)

/** Mis SC: ver, duplicar, renombrar y borrar los SC personalizados (S035/RF-9, RF-10). */
@HiltViewModel
class MyCrsViewModel @Inject constructor(private val repository: CustomCrsRepository) : ViewModel() {
    private val nameTaken = MutableStateFlow(false)
    private val renamed = MutableStateFlow(false)

    val state: StateFlow<MyCrsUiState> = combine(repository.observeAll(), nameTaken, renamed) { items, taken, done ->
        MyCrsUiState(items, loaded = true, nameTaken = taken, renamed = done)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), MyCrsUiState())

    fun rename(crs: CustomCrs, name: String) {
        viewModelScope.launch {
            val result = repository.rename(crs.id, name)
            nameTaken.update { result == CustomCrsSave.NameTaken }
            renamed.update { result is CustomCrsSave.Saved }
        }
    }

    fun duplicate(crs: CustomCrs) {
        viewModelScope.launch { repository.duplicate(crs.id) }
    }

    fun delete(crs: CustomCrs) {
        viewModelScope.launch { repository.delete(crs.id) }
    }

    fun dismissNameTaken() {
        nameTaken.value = false
        renamed.value = false
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}

/** La lista de Mis SC con sus acciones (S035/RF-9, RF-10). Zonas táctiles de 56 dp (RNF-T8). */
@Composable
fun MyCrsScreen(themeState: ThemeState, onToggleSun: () -> Unit, state: MyCrsUiState, actions: MyCrsActions) {
    var renaming by rememberSaveable { mutableStateOf<String?>(null) }
    var deleting by rememberSaveable { mutableStateOf<String?>(null) }
    SectionScaffold(
        title = stringResource(R.string.my_crs_title),
        themeState = themeState,
        onToggleSun = onToggleSun,
        onNavigateBack = actions.onBack,
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = actions.onNew, modifier = Modifier.testTag(MY_CRS_NEW_TAG)) {
                Text(stringResource(R.string.my_crs_new))
            }
        },
    ) { padding ->
        if (state.loaded && state.items.isEmpty()) {
            Text(stringResource(R.string.my_crs_empty), modifier = Modifier.padding(padding).padding(16.dp).testTag(MY_CRS_EMPTY_TAG))
        } else {
            LazyColumn(modifier = Modifier.padding(padding).fillMaxSize().padding(horizontal = 16.dp)) {
                items(state.items, key = { it.id }) { crs ->
                    MyCrsRow(crs, actions, onRename = { renaming = crs.id }, onDelete = { deleting = crs.id })
                }
            }
        }
    }
    LaunchedEffect(state.renamed) {
        if (state.renamed) {
            renaming = null
            actions.onDismissNameTaken()
        }
    }
    state.items.firstOrNull { it.id == renaming }?.let { crs ->
        RenameDialog(
            crs,
            state.nameTaken,
            actions,
            onClose = {
                renaming = null
                actions.onDismissNameTaken()
            },
        )
    }
    state.items.firstOrNull { it.id == deleting }?.let { crs ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(stringResource(R.string.my_crs_delete_title)) },
            text = { Text(stringResource(R.string.my_crs_delete_message, crs.name)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        deleting = null
                        actions.onDelete(crs)
                    },
                    modifier = Modifier.testTag(MY_CRS_DELETE_CONFIRM_TAG),
                ) { Text(stringResource(R.string.my_crs_delete)) }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.my_crs_cancel)) } },
        )
    }
}

@Composable
private fun MyCrsRow(crs: CustomCrs, actions: MyCrsActions, onRename: () -> Unit, onDelete: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    val moreLabel = stringResource(R.string.my_crs_more, crs.name)
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = MinTouchTarget).clickable { actions.onEdit(crs) }.testTag(myCrsTag(crs.id)),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.weight(1f).padding(vertical = 8.dp)) {
            Text(crs.name, style = MaterialTheme.typography.bodyLarge)
            Text(stringResource(R.string.crs_mine_detail, crs.definition.datumName), style = MaterialTheme.typography.bodySmall)
        }
        TextButton(
            onClick = { menu = true },
            modifier = Modifier.heightIn(min = MinTouchTarget).testTag(myCrsMenuTag(crs.id)).semantics { contentDescription = moreLabel },
        ) { Text(stringResource(R.string.my_crs_menu_symbol)) }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            MenuItem(R.string.my_crs_edit, myCrsActionTag("edit", crs.id)) {
                menu = false
                actions.onEdit(crs)
            }
            MenuItem(R.string.my_crs_duplicate, myCrsActionTag("duplicate", crs.id)) {
                menu = false
                actions.onDuplicate(crs)
            }
            MenuItem(R.string.my_crs_rename, myCrsActionTag("rename", crs.id)) {
                menu = false
                onRename()
            }
            MenuItem(R.string.my_crs_delete, myCrsActionTag("delete", crs.id)) {
                menu = false
                onDelete()
            }
        }
    }
    HorizontalDivider()
}

@Composable
private fun MenuItem(label: Int, tag: String, onClick: () -> Unit) {
    DropdownMenuItem(text = {
        Text(stringResource(label))
    }, onClick = onClick, modifier = Modifier.heightIn(min = MinTouchTarget).testTag(tag))
}

@Composable
private fun RenameDialog(crs: CustomCrs, nameTaken: Boolean, actions: MyCrsActions, onClose: () -> Unit) {
    var name by rememberSaveable(crs.id) { mutableStateOf(crs.name) }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(stringResource(R.string.my_crs_rename_title)) },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = {
                        name = it
                        actions.onDismissNameTaken()
                    },
                    singleLine = true,
                    isError = nameTaken,
                    modifier = Modifier.fillMaxWidth().testTag(MY_CRS_RENAME_FIELD_TAG),
                )
                if (nameTaken) {
                    Text(
                        stringResource(R.string.custom_name_taken),
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.testTag(MY_CRS_NAME_TAKEN_TAG),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { actions.onRename(crs, name) }, modifier = Modifier.testTag(MY_CRS_RENAME_CONFIRM_TAG)) {
                Text(stringResource(R.string.my_crs_confirm))
            }
        },
        dismissButton = { TextButton(onClick = onClose) { Text(stringResource(R.string.my_crs_cancel)) } },
    )
}

/** Mis SC en Ajustes: la lista y, al crear o editar, el editor en su lugar (S035/RF-9, RF-10). */
@Composable
fun MyCrsRoute(themeState: ThemeState, onToggleSun: () -> Unit, viewModel: MyCrsViewModel = hiltViewModel(), onBack: () -> Unit) {
    val state by viewModel.state.collectAsState()
    // Vacío es la lista, «new» un SC nuevo y otro texto el identificador del SC que se edita.
    var mode by rememberSaveable { mutableStateOf("") }
    if (mode.isNotEmpty()) {
        CustomCrsEditorRoute(
            themeState,
            onToggleSun,
            CustomCrsEditorRequest(editing = state.items.firstOrNull { it.id == mode }),
            onSaved = { mode = "" },
            onBack = { mode = "" },
        )
        return
    }
    MyCrsScreen(
        themeState,
        onToggleSun,
        state,
        MyCrsActions(
            onNew = { mode = "new" },
            onEdit = { mode = it.id },
            onDuplicate = viewModel::duplicate,
            onRename = viewModel::rename,
            onDelete = viewModel::delete,
            onDismissNameTaken = viewModel::dismissNameTaken,
            onBack = onBack,
        ),
    )
}
