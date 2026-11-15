package es.topophone.app.ui.receiver

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
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
import es.topophone.core.model.receiver.WifiReceiverDraft
import es.topophone.core.model.receiver.WifiReceiverProblem
import es.topophone.core.model.receiver.WifiReceiverValidation
import es.topophone.core.model.receiver.WifiReceiverValidator
import es.topophone.core.model.receiver.WifiReceiverWarning
import es.topophone.data.receiver.WifiReceiverRepository
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

const val WIFI_NAME_TAG = "wifi_form_name"
const val WIFI_HOST_TAG = "wifi_form_host"
const val WIFI_POSITION_PORT_TAG = "wifi_form_position_port"
const val WIFI_CORRECTIONS_PORT_TAG = "wifi_form_corrections_port"
const val WIFI_SAVE_TAG = "wifi_form_save"
const val WIFI_DELETE_TAG = "wifi_form_delete"
const val WIFI_DELETE_CONFIRM_TAG = "wifi_form_delete_confirm"

/**
 * El formulario de un receptor Wi-Fi (S021/RF-2, RF-3). [draft] es lo escrito; [problems] lo que impidió guardar al intentarlo, y
 * [warnings] lo que se avisa pero deja guardar (puertos bajo 3000), en vivo.
 */
data class WifiReceiverFormUiState(
    val draft: WifiReceiverDraft = WifiReceiverDraft.initial(),
    val loaded: Boolean = false,
    val editing: Boolean = false,
    val problems: Set<WifiReceiverProblem> = emptySet(),
    val warnings: Set<WifiReceiverWarning> = emptySet(),
    val confirmingDelete: Boolean = false,
    /** Se guardó, se borró o el receptor ya no existe: la pantalla vuelve atrás. */
    val done: Boolean = false,
)

class WifiReceiverFormActions(
    val onName: (String) -> Unit = {},
    val onHost: (String) -> Unit = {},
    val onPositionPort: (String) -> Unit = {},
    val onCorrectionsPort: (String) -> Unit = {},
    val onSave: () -> Unit = {},
    val onAskDelete: () -> Unit = {},
    val onConfirmDelete: () -> Unit = {},
    val onCancelDelete: () -> Unit = {},
    val onBack: () -> Unit = {},
)

/** Alta, edición y borrado de un receptor Wi-Fi (S021/RF-2 a RF-5); la identidad no cambia al editar (RF-5). */
@HiltViewModel
class WifiReceiverFormViewModel @Inject constructor(private val repository: WifiReceiverRepository) : ViewModel() {
    private val mutableState = MutableStateFlow(WifiReceiverFormUiState())
    val state: StateFlow<WifiReceiverFormUiState> = mutableState.asStateFlow()

    private var receiverId: String? = null
    private var started = false

    fun start(id: String?) {
        if (started) return
        started = true
        receiverId = id
        if (id == null) {
            mutableState.value = loaded(WifiReceiverDraft.initial(), editing = false)
            return
        }
        viewModelScope.launch {
            val receiver = repository.byId(id)
            mutableState.value = if (receiver == null) {
                WifiReceiverFormUiState(loaded = true, done = true) // se borró entre medias: no hay nada que editar
            } else {
                loaded(
                    WifiReceiverDraft(
                        receiver.name,
                        receiver.host,
                        receiver.positionPort.toString(),
                        receiver.correctionsPort?.toString().orEmpty(),
                    ),
                    editing = true,
                )
            }
        }
    }

    private fun loaded(draft: WifiReceiverDraft, editing: Boolean) =
        WifiReceiverFormUiState(draft = draft, loaded = true, editing = editing, warnings = warningsOf(draft))

    private fun warningsOf(draft: WifiReceiverDraft): Set<WifiReceiverWarning> =
        (WifiReceiverValidator.validate(draft) as? WifiReceiverValidation.Valid)?.warnings.orEmpty()

    private fun change(draft: WifiReceiverDraft, solved: WifiReceiverProblem) =
        mutableState.update { it.copy(draft = draft, problems = it.problems - solved, warnings = warningsOf(draft)) }

    fun actions(onBack: () -> Unit) = WifiReceiverFormActions(
        onName = { text -> mutableState.update { it.copy(draft = it.draft.copy(name = text)) } },
        onHost = { text ->
            val draft = mutableState.value.draft.copy(host = text)
            change(draft, WifiReceiverProblem.HOST_INVALID)
            mutableState.update { it.copy(problems = it.problems - WifiReceiverProblem.HOST_EMPTY) }
        },
        onPositionPort = { text -> change(mutableState.value.draft.copy(positionPort = text), WifiReceiverProblem.POSITION_PORT_INVALID) },
        onCorrectionsPort = { text ->
            change(mutableState.value.draft.copy(correctionsPort = text), WifiReceiverProblem.CORRECTIONS_PORT_INVALID)
        },
        onSave = ::save,
        onAskDelete = { mutableState.update { it.copy(confirmingDelete = true) } },
        onConfirmDelete = ::delete,
        onCancelDelete = { mutableState.update { it.copy(confirmingDelete = false) } },
        onBack = onBack,
    )

    /** S021/RF-3: sin dirección válida o con un puerto que no vale no se guarda y se dice por qué. */
    fun save() {
        when (val validation = WifiReceiverValidator.validate(mutableState.value.draft)) {
            is WifiReceiverValidation.Invalid -> mutableState.update { it.copy(problems = validation.problems) }
            is WifiReceiverValidation.Valid -> viewModelScope.launch {
                val id = receiverId
                if (id == null) repository.add(validation.input) else repository.update(id, validation.input)
                mutableState.update { it.copy(done = true) }
            }
        }
    }

    /** S021/RF-4: el borrado se confirma antes. */
    fun delete() {
        val id = receiverId ?: return
        viewModelScope.launch {
            repository.delete(id)
            mutableState.update { it.copy(confirmingDelete = false, done = true) }
        }
    }
}

/** Entrada del formulario: al guardar, borrar o no existir el receptor se vuelve a la lista. */
@Composable
fun WifiReceiverFormRoute(
    receiverId: String?,
    themeState: ThemeState,
    onToggleSun: () -> Unit,
    onBack: () -> Unit,
    viewModel: WifiReceiverFormViewModel = hiltViewModel(),
) {
    LaunchedEffect(Unit) { viewModel.start(receiverId) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    WifiReceiverFormScreen(themeState, onToggleSun, state, viewModel.actions(onBack))
}

/** Formulario de un receptor Wi-Fi: nombre, dirección, puerto de posición y puerto de correcciones opcional (S021/RF-2). */
@Composable
fun WifiReceiverFormScreen(
    themeState: ThemeState,
    onToggleSun: () -> Unit,
    state: WifiReceiverFormUiState,
    actions: WifiReceiverFormActions,
) {
    LaunchedEffect(state.done) { if (state.done) actions.onBack() }
    val draft = state.draft
    val problems = state.problems
    SectionScaffold(
        title = stringResource(if (state.editing) R.string.wifi_form_edit else R.string.wifi_form_new),
        themeState = themeState,
        onToggleSun = onToggleSun,
        onNavigateBack = actions.onBack,
    ) { padding ->
        Column(
            modifier = Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            FormField(draft.name, R.string.wifi_form_name, WIFI_NAME_TAG, help = R.string.wifi_form_name_help, onChange = actions.onName)
            FormField(
                draft.host,
                R.string.wifi_form_host,
                WIFI_HOST_TAG,
                error = when {
                    WifiReceiverProblem.HOST_EMPTY in problems -> R.string.wifi_form_host_empty
                    WifiReceiverProblem.HOST_INVALID in problems -> R.string.wifi_form_host_invalid
                    else -> null
                },
                keyboardType = KeyboardType.Uri,
                onChange = actions.onHost,
            )
            FormField(
                draft.positionPort,
                R.string.wifi_form_position_port,
                WIFI_POSITION_PORT_TAG,
                error = R.string.wifi_form_position_invalid.takeIf { WifiReceiverProblem.POSITION_PORT_INVALID in problems },
                warning = R.string.wifi_form_port_reserved.takeIf { WifiReceiverWarning.RESERVED_POSITION_PORT in state.warnings },
                keyboardType = KeyboardType.Number,
                onChange = actions.onPositionPort,
            )
            FormField(
                draft.correctionsPort,
                R.string.wifi_form_corrections_port,
                WIFI_CORRECTIONS_PORT_TAG,
                error = R.string.wifi_form_corrections_invalid.takeIf { WifiReceiverProblem.CORRECTIONS_PORT_INVALID in problems },
                warning = R.string.wifi_form_port_reserved.takeIf { WifiReceiverWarning.RESERVED_CORRECTIONS_PORT in state.warnings },
                help = R.string.wifi_form_corrections_help,
                keyboardType = KeyboardType.Number,
                onChange = actions.onCorrectionsPort,
            )
            Button(onClick = actions.onSave, modifier = Modifier.heightIn(min = MinTouchTarget).testTag(WIFI_SAVE_TAG)) {
                Text(stringResource(R.string.project_save))
            }
            if (state.editing) {
                OutlinedButton(
                    onClick = actions.onAskDelete,
                    modifier = Modifier.heightIn(min = MinTouchTarget).testTag(WIFI_DELETE_TAG),
                ) { Text(stringResource(R.string.wifi_form_delete)) }
            }
        }
        if (state.confirmingDelete) DeleteDialog(draft.name.ifBlank { draft.host }, actions)
    }
}

@Composable
private fun FormField(
    value: String,
    label: Int,
    tag: String,
    error: Int? = null,
    warning: Int? = null,
    help: Int? = null,
    keyboardType: KeyboardType = KeyboardType.Text,
    onChange: (String) -> Unit,
) {
    val supporting = error ?: warning ?: help
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(stringResource(label)) },
        isError = error != null,
        supportingText = supporting?.let {
            {
                Text(
                    stringResource(it),
                    color = if (warning != null &&
                        error == null
                    ) {
                        MaterialTheme.colorScheme.tertiary
                    } else {
                        Color.Unspecified
                    },
                )
            }
        },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType, autoCorrectEnabled = false),
        modifier = Modifier.fillMaxWidth().testTag(tag),
    )
}

/** «¿Borrar este receptor?»: el borrado se confirma (S021/RF-4). */
@Composable
private fun DeleteDialog(name: String, actions: WifiReceiverFormActions) {
    AlertDialog(
        onDismissRequest = actions.onCancelDelete,
        title = { Text(stringResource(R.string.wifi_form_delete_title)) },
        text = { Text(stringResource(R.string.wifi_form_delete_text, name)) },
        confirmButton = {
            TextButton(
                onClick = actions.onConfirmDelete,
                modifier = Modifier.heightIn(min = MinTouchTarget).testTag(WIFI_DELETE_CONFIRM_TAG),
            ) {
                Text(stringResource(R.string.wifi_form_delete_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = actions.onCancelDelete, modifier = Modifier.heightIn(min = MinTouchTarget)) {
                Text(stringResource(R.string.receiver_add_cancel))
            }
        },
    )
}
