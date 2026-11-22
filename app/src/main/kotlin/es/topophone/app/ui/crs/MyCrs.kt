package es.topophone.app.ui.crs

import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
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
import es.topophone.app.ui.export.ExportFiles
import es.topophone.app.ui.export.shareExportIntent
import es.topophone.app.ui.export.writeToUri
import es.topophone.core.designsystem.component.MinTouchTarget
import es.topophone.core.formats.crs.CustomCrsJson
import es.topophone.core.formats.crs.CustomCrsReadResult
import es.topophone.core.formats.crs.CustomCrsRejection
import es.topophone.core.geodesy.catalog.CustomCrsDraft
import es.topophone.core.model.preferences.ThemeState
import es.topophone.core.model.project.CustomCrs
import es.topophone.data.crs.CustomCrsConflictChoice
import es.topophone.data.crs.CustomCrsImport
import es.topophone.data.crs.CustomCrsRepository
import es.topophone.data.crs.CustomCrsSave
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

const val MY_CRS_EMPTY_TAG = "my_crs_empty"
const val MY_CRS_NEW_TAG = "my_crs_new"
const val MY_CRS_RENAME_FIELD_TAG = "my_crs_rename_field"
const val MY_CRS_RENAME_CONFIRM_TAG = "my_crs_rename_confirm"
const val MY_CRS_DELETE_CONFIRM_TAG = "my_crs_delete_confirm"
const val MY_CRS_NAME_TAKEN_TAG = "my_crs_name_taken"
const val MY_CRS_IMPORT_TAG = "my_crs_import"
const val MY_CRS_IMPORT_SUMMARY_TAG = "my_crs_import_summary"
const val MY_CRS_IMPORT_ADD_TAG = "my_crs_import_add"
const val MY_CRS_CONFLICT_REPLACE_TAG = "my_crs_conflict_replace"
const val MY_CRS_CONFLICT_KEEP_TAG = "my_crs_conflict_keep"
const val MY_CRS_CONFLICT_SKIP_TAG = "my_crs_conflict_skip"
const val MY_CRS_REJECTED_TAG = "my_crs_rejected"
const val MY_CRS_DONE_TAG = "my_crs_done"
const val MY_CRS_EXPORT_SAVE_TAG = "my_crs_export_save"
const val MY_CRS_EXPORT_SHARE_TAG = "my_crs_export_share"
const val MY_CRS_EXPORT_FAILED_TAG = "my_crs_export_failed"

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
    val onImport: () -> Unit = {},
    val onExport: (CustomCrs) -> Unit = {},
    val onConfirmImport: () -> Unit = {},
    val onResolveConflict: (CustomCrsConflictChoice) -> Unit = {},
    val onDismissImport: () -> Unit = {},
    val onExportSave: (CustomCrs) -> Unit = {},
    val onExportShare: (CustomCrs) -> Unit = {},
    val onCancelExport: () -> Unit = {},
    val onDismissExportFailed: () -> Unit = {},
    val onBack: () -> Unit = {},
)

/** En qué punto está la importación de un JSON de SC (S035/RF-13 a RF-15). */
sealed interface MyCrsImport {
    data object None : MyCrsImport

    /** El SC leído, a la espera de que el usuario lo confirme (RF-13). */
    data class Confirm(val crs: CustomCrs) : MyCrsImport

    /** El nombre ya existe con otra definición: reemplazar, conservar ambos u omitir (RF-15). */
    data class Conflict(val crs: CustomCrs, val existing: CustomCrs) : MyCrsImport

    /** El fichero no se importa, con el motivo (RF-14). */
    data class Rejected(val reason: CustomCrsRejection) : MyCrsImport

    /** Terminó: el SC [name] se añadió, se reemplazó o ya estaba. */
    data class Done(val name: String, val kind: DoneKind) : MyCrsImport

    enum class DoneKind { ADDED, REPLACED, ALREADY }
}

data class MyCrsUiState(
    val items: List<CustomCrs> = emptyList(),
    val loaded: Boolean = false,
    /** El nombre al que se quiso renombrar ya lo tiene otro SC (S035/RF-10). */
    val nameTaken: Boolean = false,
    /** Se acaba de renombrar bien: el diálogo se puede cerrar. */
    val renamed: Boolean = false,
    val import: MyCrsImport = MyCrsImport.None,
    /** El SC cuyo JSON se va a guardar o compartir (S035/RF-12). */
    val exporting: CustomCrs? = null,
    val exportFailed: Boolean = false,
)

/** Mis SC: ver, duplicar, renombrar y borrar los SC personalizados (S035/RF-9, RF-10). */
@HiltViewModel
class MyCrsViewModel @Inject constructor(private val repository: CustomCrsRepository) : ViewModel() {
    private val nameTaken = MutableStateFlow(false)
    private val renamed = MutableStateFlow(false)
    private val importStage = MutableStateFlow<MyCrsImport>(MyCrsImport.None)
    private val exporting = MutableStateFlow<CustomCrs?>(null)
    private val exportFailed = MutableStateFlow(false)

    val state: StateFlow<MyCrsUiState> = combine(
        repository.observeAll(),
        combine(nameTaken, renamed) { taken, done -> taken to done },
        importStage,
        combine(exporting, exportFailed) { crs, failed -> crs to failed },
    ) { items, (taken, done), stage, (export, failed) ->
        MyCrsUiState(items, loaded = true, nameTaken = taken, renamed = done, import = stage, exporting = export, exportFailed = failed)
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

    /** Se eligió un fichero para importar: [text] es su contenido (o `null` si no se pudo leer). Se valida el contenido, no la extensión (RF-13, RF-14). */
    fun importText(text: String?) {
        importStage.value = when (val read = if (text == null) null else CustomCrsJson.read(text)) {
            null -> MyCrsImport.Rejected(CustomCrsRejection.INVALID)
            is CustomCrsReadResult.Rejected -> MyCrsImport.Rejected(read.reason)
            is CustomCrsReadResult.Ok -> MyCrsImport.Confirm(read.crs)
        }
    }

    /** El usuario confirmó el SC leído (RF-13): se añade, o se pregunta si el nombre ya existe con otra definición (RF-15). */
    fun confirmImport() {
        val stage = importStage.value as? MyCrsImport.Confirm ?: return
        viewModelScope.launch { importStage.value = outcomeOf(repository.import(stage.crs), stage.crs) }
    }

    /** El usuario decidió qué hacer con el nombre repetido (RF-15). */
    fun resolveConflict(choice: CustomCrsConflictChoice) {
        val stage = importStage.value as? MyCrsImport.Conflict ?: return
        viewModelScope.launch { importStage.value = outcomeOf(repository.import(stage.crs, choice), stage.crs) }
    }

    fun dismissImport() {
        importStage.value = MyCrsImport.None
    }

    private fun outcomeOf(result: CustomCrsImport, incoming: CustomCrs): MyCrsImport = when (result) {
        is CustomCrsImport.Added -> MyCrsImport.Done(result.crs.name, MyCrsImport.DoneKind.ADDED)
        is CustomCrsImport.Replaced -> MyCrsImport.Done(result.crs.name, MyCrsImport.DoneKind.REPLACED)
        is CustomCrsImport.Unchanged -> MyCrsImport.Done(result.existing.name, MyCrsImport.DoneKind.ALREADY)
        is CustomCrsImport.Conflict -> MyCrsImport.Conflict(incoming, result.existing)
        CustomCrsImport.Skipped -> MyCrsImport.None
    }

    /** Se pidió el JSON de [crs]: se pregunta si se guarda o se comparte (RF-12). */
    fun startExport(crs: CustomCrs) {
        exportFailed.value = false
        exporting.value = crs
    }

    fun cancelExport() {
        exporting.value = null
    }

    /** El fichero se guardó o se compartió; con [ok] falso se avisa de que no se pudo. */
    fun exportDone(ok: Boolean) {
        exporting.value = null
        exportFailed.value = !ok
    }

    fun dismissExportFailed() {
        exportFailed.value = false
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
        actions = {
            TextButton(onClick = actions.onImport, modifier = Modifier.heightIn(min = MinTouchTarget).testTag(MY_CRS_IMPORT_TAG)) {
                Text(stringResource(R.string.my_crs_import))
            }
        },
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
    ImportDialogs(state.import, actions)
    ExportDialogs(state, actions)
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
            MenuItem(R.string.my_crs_export, myCrsActionTag("export", crs.id)) {
                menu = false
                actions.onExport(crs)
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
private fun ImportDialogs(stage: MyCrsImport, actions: MyCrsActions) {
    when (stage) {
        MyCrsImport.None -> Unit
        is MyCrsImport.Confirm -> {
            val d = stage.crs.definition
            AlertDialog(
                onDismissRequest = actions.onDismissImport,
                title = { Text(stringResource(R.string.my_crs_import_title)) },
                text = {
                    Text(
                        stringResource(
                            R.string.my_crs_import_summary,
                            stage.crs.name,
                            d.datumName,
                            stringResource(methodLabelOf(if (d.projected) d.method else CustomCrsDraft.GEOGRAPHIC)),
                            d.ellipsoidSemiMajorM.toString(),
                            d.ellipsoidInverseFlattening.toString(),
                        ),
                        modifier = Modifier.testTag(MY_CRS_IMPORT_SUMMARY_TAG),
                    )
                },
                confirmButton = {
                    TextButton(onClick = actions.onConfirmImport, modifier = Modifier.testTag(MY_CRS_IMPORT_ADD_TAG)) {
                        Text(stringResource(R.string.my_crs_import_add))
                    }
                },
                dismissButton = { TextButton(onClick = actions.onDismissImport) { Text(stringResource(R.string.my_crs_cancel)) } },
            )
        }
        is MyCrsImport.Conflict -> AlertDialog(
            onDismissRequest = actions.onDismissImport,
            title = { Text(stringResource(R.string.my_crs_conflict_title)) },
            text = { Text(stringResource(R.string.my_crs_conflict_message, stage.crs.name)) },
            confirmButton = {
                Row {
                    TextButton(
                        onClick = { actions.onResolveConflict(CustomCrsConflictChoice.REPLACE) },
                        modifier = Modifier.testTag(MY_CRS_CONFLICT_REPLACE_TAG),
                    ) { Text(stringResource(R.string.my_crs_conflict_replace)) }
                    TextButton(
                        onClick = { actions.onResolveConflict(CustomCrsConflictChoice.KEEP_BOTH) },
                        modifier = Modifier.testTag(MY_CRS_CONFLICT_KEEP_TAG),
                    ) { Text(stringResource(R.string.my_crs_conflict_keep_both)) }
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { actions.onResolveConflict(CustomCrsConflictChoice.SKIP) },
                    modifier = Modifier.testTag(MY_CRS_CONFLICT_SKIP_TAG),
                ) { Text(stringResource(R.string.my_crs_conflict_skip)) }
            },
        )
        is MyCrsImport.Rejected -> AlertDialog(
            onDismissRequest = actions.onDismissImport,
            title = { Text(stringResource(R.string.my_crs_import_rejected_title)) },
            text = {
                Text(
                    stringResource(
                        when (stage.reason) {
                            CustomCrsRejection.INVALID -> R.string.my_crs_reject_invalid
                            CustomCrsRejection.UNKNOWN_VERSION -> R.string.my_crs_reject_unknown_version
                            CustomCrsRejection.UNSUPPORTED_METHOD -> R.string.my_crs_reject_unsupported_method
                            CustomCrsRejection.OUT_OF_RANGE -> R.string.my_crs_reject_out_of_range
                        },
                    ),
                    modifier = Modifier.testTag(MY_CRS_REJECTED_TAG),
                )
            },
            confirmButton = { TextButton(onClick = actions.onDismissImport) { Text(stringResource(R.string.my_crs_confirm)) } },
        )
        is MyCrsImport.Done -> AlertDialog(
            onDismissRequest = actions.onDismissImport,
            text = {
                Text(
                    stringResource(
                        when (stage.kind) {
                            MyCrsImport.DoneKind.ADDED -> R.string.my_crs_done_added
                            MyCrsImport.DoneKind.REPLACED -> R.string.my_crs_done_replaced
                            MyCrsImport.DoneKind.ALREADY -> R.string.my_crs_done_same
                        },
                        stage.name,
                    ),
                    modifier = Modifier.testTag(MY_CRS_DONE_TAG),
                )
            },
            confirmButton = { TextButton(onClick = actions.onDismissImport) { Text(stringResource(R.string.my_crs_confirm)) } },
        )
    }
}

@Composable
private fun ExportDialogs(state: MyCrsUiState, actions: MyCrsActions) {
    state.exporting?.let { crs ->
        AlertDialog(
            onDismissRequest = actions.onCancelExport,
            title = { Text(stringResource(R.string.my_crs_export_title)) },
            text = { Text(stringResource(R.string.my_crs_export_question, crs.name)) },
            confirmButton = {
                Row {
                    TextButton(onClick = { actions.onExportSave(crs) }, modifier = Modifier.testTag(MY_CRS_EXPORT_SAVE_TAG)) {
                        Text(stringResource(R.string.export_save))
                    }
                    TextButton(onClick = { actions.onExportShare(crs) }, modifier = Modifier.testTag(MY_CRS_EXPORT_SHARE_TAG)) {
                        Text(stringResource(R.string.export_share))
                    }
                }
            },
            dismissButton = { TextButton(onClick = actions.onCancelExport) { Text(stringResource(R.string.my_crs_cancel)) } },
        )
    }
    if (state.exportFailed) {
        AlertDialog(
            onDismissRequest = actions.onDismissExportFailed,
            text = { Text(stringResource(R.string.my_crs_export_failed), modifier = Modifier.testTag(MY_CRS_EXPORT_FAILED_TAG)) },
            confirmButton = { TextButton(onClick = actions.onDismissExportFailed) { Text(stringResource(R.string.my_crs_confirm)) } },
        )
    }
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
    val context = LocalContext.current
    val resolver = context.contentResolver
    val scope = rememberCoroutineScope()
    var pendingSave by remember { mutableStateOf<CustomCrs?>(null) }
    // RNF-T5: el contenido decide si es un SC; el tipo del fichero no cuenta.
    val open = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch {
                val text = withContext(Dispatchers.IO) { readText(resolver, uri) }
                viewModel.importText(text)
            }
        }
    }
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(JSON_MIME)) { uri ->
        val crs = pendingSave
        pendingSave = null
        if (uri == null || crs == null) {
            viewModel.cancelExport()
        } else {
            scope.launch {
                val bytes = CustomCrsJson.write(crs).toByteArray(Charsets.UTF_8)
                val saved = writeToUri(resolver, uri) { out ->
                    out.write(bytes)
                    true
                }
                if (!saved) runCatching { DocumentsContract.deleteDocument(resolver, uri) }
                viewModel.exportDone(saved)
            }
        }
    }
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
            onImport = { open.launch(arrayOf("*/*")) },
            onExport = viewModel::startExport,
            onConfirmImport = viewModel::confirmImport,
            onResolveConflict = viewModel::resolveConflict,
            onDismissImport = viewModel::dismissImport,
            onExportSave = { crs ->
                pendingSave = crs
                save.launch(fileNameOf(crs))
            },
            onExportShare = { crs ->
                scope.launch {
                    // Desde la caché de la app, con el FileProvider, como el CSV (S012/RF-18).
                    val file = ExportFiles.prepare(context.cacheDir, fileNameOf(crs), System.currentTimeMillis())
                    withContext(Dispatchers.IO) { file.writeText(CustomCrsJson.write(crs), Charsets.UTF_8) }
                    context.startActivity(shareExportIntent(context, file, mimeType = JSON_MIME))
                    viewModel.exportDone(true)
                }
            },
            onCancelExport = viewModel::cancelExport,
            onDismissExportFailed = viewModel::dismissExportFailed,
            onBack = onBack,
        ),
    )
}

/** Tipo de los ficheros de SC. */
const val JSON_MIME = "application/json"

/** Lo que más ocupa un JSON de SC que se acepta: un SC pesa unos cientos de bytes; un fichero enorme no es uno (RNF-T5). */
private const val MAX_JSON_BYTES = 1_000_000

/** El nombre del fichero de un SC: su nombre sin lo que no vale en un fichero, y `.json`. */
internal fun fileNameOf(crs: CustomCrs): String =
    crs.name.map { if (it.isLetterOrDigit() || it == '-' || it == '_') it else '_' }.joinToString("").trim('_').ifEmpty { "sc" } + ".json"

/** El texto del fichero, o `null` si no se puede leer o pesa demasiado. */
private fun readText(resolver: android.content.ContentResolver, uri: android.net.Uri): String? = try {
    resolver.openInputStream(uri)?.use { input ->
        val buffer = ByteArray(MAX_JSON_BYTES + 1)
        var read = 0
        while (read < buffer.size) {
            val n = input.read(buffer, read, buffer.size - read)
            if (n < 0) break
            read += n
        }
        if (read > MAX_JSON_BYTES) null else String(buffer, 0, read, Charsets.UTF_8)
    }
} catch (_: java.io.IOException) {
    null
} catch (_: SecurityException) {
    null
}
