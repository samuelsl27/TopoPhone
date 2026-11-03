package es.topophone.app.ui.codes

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import es.topophone.app.R
import es.topophone.app.ui.SectionScaffold
import es.topophone.app.ui.projects.ConfirmDialog
import es.topophone.core.designsystem.component.MinTouchTarget
import es.topophone.core.model.preferences.ThemeState
import es.topophone.core.model.project.NameError
import es.topophone.data.codes.CodeLibraryRepository
import es.topophone.data.codes.LibraryResult
import es.topophone.data.codes.LibrarySummary
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

const val CODE_LIBRARY_NEW_TAG = "code_library_new"
const val CODE_LIBRARY_IMPORT_TAG = "code_library_import"
const val CODE_LIBRARY_NAME_TAG = "code_library_name"
const val CODE_LIBRARY_SAVE_TAG = "code_library_save"

fun codeLibraryTag(id: String) = "code_library_$id"

fun codeLibraryMenuTag(id: String) = "code_library_menu_$id"

fun codeLibraryActionTag(id: String, action: String) = "code_library_${action}_$id"

/** El diálogo de nombre abierto: nueva biblioteca o renombrar una (S023/RF-2). */
sealed interface LibraryDialog {
    data object Create : LibraryDialog

    data class Rename(val id: String, val name: String) : LibraryDialog
}

data class LibraryDialogState(val dialog: LibraryDialog, val error: NameError? = null)

data class CodeLibraryListUiState(
    val libraries: List<LibrarySummary> = emptyList(),
    val dialog: LibraryDialogState? = null,
    val confirmDelete: LibrarySummary? = null,
)

/** Lo que el usuario hace en la lista de bibliotecas (S023/RF-1 a RF-3). */
data class CodeLibraryListActions(
    val onOpen: (String) -> Unit = {},
    val onNew: () -> Unit = {},
    val onImport: () -> Unit = {},
    val onRename: (LibrarySummary) -> Unit = {},
    val onDuplicate: (String) -> Unit = {},
    val onAskDelete: (LibrarySummary) -> Unit = {},
    val onConfirmDelete: () -> Unit = {},
    val onCancelDelete: () -> Unit = {},
    val onSubmitName: (String) -> Unit = {},
    val onDismissDialog: () -> Unit = {},
)

/** La lista de bibliotecas de códigos de Ajustes (S023/RF-1 a RF-4). */
@HiltViewModel
class CodeLibraryListViewModel @Inject constructor(private val repository: CodeLibraryRepository) : ViewModel() {
    private val dialog = MutableStateFlow<LibraryDialogState?>(null)
    private val confirmDelete = MutableStateFlow<LibrarySummary?>(null)

    val state: StateFlow<CodeLibraryListUiState> = combine(repository.libraries(), dialog, confirmDelete) {
            libraries,
            dialogState,
            delete,
        ->
        CodeLibraryListUiState(libraries, dialogState, delete)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), CodeLibraryListUiState())

    fun openNew() {
        dialog.value = LibraryDialogState(LibraryDialog.Create)
    }

    fun openRename(library: LibrarySummary) {
        dialog.value = LibraryDialogState(LibraryDialog.Rename(library.id, library.name))
    }

    fun dismissDialog() {
        dialog.value = null
    }

    /** Crea o renombra según el diálogo; si el nombre no vale, el diálogo dice por qué (RF-2). */
    fun submitName(name: String) {
        val open = dialog.value?.dialog ?: return
        viewModelScope.launch {
            val result = when (open) {
                LibraryDialog.Create -> repository.create(name)
                is LibraryDialog.Rename -> repository.rename(open.id, name)
            }
            dialog.value = if (result is LibraryResult.Invalid) LibraryDialogState(open, result.error) else null
        }
    }

    /** [copyLabel] es la palabra de la interfaz para la copia (RF-2, RF-3). */
    fun duplicate(id: String, copyLabel: String) {
        viewModelScope.launch { repository.duplicate(id, copyLabel) }
    }

    fun askDelete(library: LibrarySummary) {
        confirmDelete.value = library
    }

    fun cancelDelete() {
        confirmDelete.value = null
    }

    /** RF-2: con confirmación; no modifica ningún proyecto ni punto (RF-4). */
    fun confirmDelete() {
        val library = confirmDelete.value ?: return
        confirmDelete.value = null
        viewModelScope.launch { repository.delete(library.id) }
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}

@Composable
fun CodeLibraryListRoute(
    themeState: ThemeState,
    onToggleSun: () -> Unit,
    onOpenLibrary: (String) -> Unit = {},
    onImport: (fileUri: String) -> Unit = {},
    viewModel: CodeLibraryListViewModel = hiltViewModel(),
    onBack: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val copyLabel = stringResource(R.string.codes_library_copy_label)
    val pickFile = rememberCodeImportLauncher(onImport)
    CodeLibraryListScreen(
        themeState,
        onToggleSun,
        state,
        CodeLibraryListActions(
            onOpen = onOpenLibrary,
            onNew = viewModel::openNew,
            onImport = pickFile,
            onRename = viewModel::openRename,
            onDuplicate = { viewModel.duplicate(it, copyLabel) },
            onAskDelete = viewModel::askDelete,
            onConfirmDelete = viewModel::confirmDelete,
            onCancelDelete = viewModel::cancelDelete,
            onSubmitName = viewModel::submitName,
            onDismissDialog = viewModel::dismissDialog,
        ),
        onBack,
    )
}

/** "Bibliotecas de códigos": las por defecto marcadas con un texto y las del usuario, con nombre y número de códigos (S023/RF-1). */
@Composable
fun CodeLibraryListScreen(
    themeState: ThemeState,
    onToggleSun: () -> Unit,
    state: CodeLibraryListUiState,
    actions: CodeLibraryListActions,
    onBack: () -> Unit,
) {
    SectionScaffold(
        title = stringResource(R.string.codes_libraries_title),
        themeState = themeState,
        onToggleSun = onToggleSun,
        onNavigateBack = onBack,
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(0.dp),
        ) {
            item {
                Button(
                    onClick = actions.onNew,
                    modifier = Modifier.padding(bottom = 16.dp).fillMaxWidth().heightIn(min = MinTouchTarget).testTag(CODE_LIBRARY_NEW_TAG),
                ) { Text(stringResource(R.string.codes_library_new)) }
                OutlinedButton(
                    onClick = actions.onImport,
                    modifier = Modifier.padding(
                        bottom = 16.dp,
                    ).fillMaxWidth().heightIn(min = MinTouchTarget).testTag(CODE_LIBRARY_IMPORT_TAG),
                ) { Text(stringResource(R.string.codes_import_csv)) }
            }
            items(state.libraries, key = { it.id }) { library ->
                LibraryRow(library, actions)
                HorizontalDivider()
            }
        }
    }
    state.dialog?.let { LibraryNameDialog(it, actions) }
    state.confirmDelete?.let { library ->
        ConfirmDialog(
            message = stringResource(R.string.codes_library_delete_message, library.name),
            confirm = stringResource(R.string.codes_library_delete),
            onConfirm = actions.onConfirmDelete,
            onDismiss = actions.onCancelDelete,
        )
    }
}

@Composable
private fun LibraryRow(library: LibrarySummary, actions: CodeLibraryListActions) {
    var menu by remember { mutableStateOf(false) }
    ListItem(
        headlineContent = { Text(library.name) },
        supportingContent = {
            val count = pluralStringResource(R.plurals.codes_count, library.codeCount, library.codeCount)
            // El «por defecto» va con texto, no solo con un icono (RNF-T8).
            Text(if (library.isDefault) "$count · ${stringResource(R.string.codes_library_default_tag)}" else count)
        },
        trailingContent = {
            IconButton(onClick = { menu = true }, modifier = Modifier.size(MinTouchTarget).testTag(codeLibraryMenuTag(library.id))) {
                Icon(
                    painterResource(R.drawable.ic_more_vert),
                    contentDescription = stringResource(R.string.codes_library_menu, library.name),
                )
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                if (!library.isDefault) {
                    LibraryMenuItem(R.string.codes_library_rename, codeLibraryActionTag(library.id, "rename")) {
                        menu = false
                        actions.onRename(library)
                    }
                }
                LibraryMenuItem(R.string.codes_library_duplicate, codeLibraryActionTag(library.id, "duplicate")) {
                    menu = false
                    actions.onDuplicate(library.id)
                }
                if (!library.isDefault) {
                    LibraryMenuItem(R.string.codes_library_delete, codeLibraryActionTag(library.id, "delete")) {
                        menu = false
                        actions.onAskDelete(library)
                    }
                }
            }
        },
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.heightIn(min = MinTouchTarget).clickable { actions.onOpen(library.id) }.testTag(codeLibraryTag(library.id)),
    )
}

@Composable
private fun LibraryMenuItem(text: Int, tag: String, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(stringResource(text)) },
        onClick = onClick,
        modifier = Modifier.heightIn(min = MinTouchTarget).testTag(tag),
    )
}

@Composable
private fun LibraryNameDialog(state: LibraryDialogState, actions: CodeLibraryListActions) {
    val initial = (state.dialog as? LibraryDialog.Rename)?.name.orEmpty()
    var name by rememberSaveable(state.dialog) { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = actions.onDismissDialog,
        title = {
            Text(stringResource(if (state.dialog is LibraryDialog.Rename) R.string.codes_library_rename else R.string.codes_library_new))
        },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.codes_library_name)) },
                    isError = state.error != null,
                    supportingText = state.error?.let { error -> { Text(stringResource(nameErrorText(error))) } },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag(CODE_LIBRARY_NAME_TAG),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { actions.onSubmitName(name) },
                modifier = Modifier.heightIn(min = MinTouchTarget).testTag(CODE_LIBRARY_SAVE_TAG),
            ) { Text(stringResource(R.string.project_save)) }
        },
        dismissButton = {
            TextButton(onClick = actions.onDismissDialog, modifier = Modifier.heightIn(min = MinTouchTarget)) {
                Text(stringResource(R.string.dialog_cancel))
            }
        },
    )
}

private fun nameErrorText(error: NameError): Int = when (error) {
    NameError.EMPTY -> R.string.codes_library_name_empty
    NameError.DUPLICATE -> R.string.codes_library_name_duplicate
}
