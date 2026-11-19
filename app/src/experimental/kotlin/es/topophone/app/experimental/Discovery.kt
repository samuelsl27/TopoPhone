package es.topophone.app.experimental

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
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
import es.topophone.app.receiver.ACCESS_LOCAL_NETWORK
import es.topophone.app.receiver.LocalNetworkPermission
import es.topophone.app.receiver.localNetworkSettingsIntent
import es.topophone.app.ui.SectionScaffold
import es.topophone.app.ui.receiver.ReceiverCard
import es.topophone.core.designsystem.component.MinTouchTarget
import es.topophone.core.model.preferences.ThemeState
import es.topophone.core.model.receiver.WifiReceiverDefaults
import es.topophone.core.model.receiver.WifiReceiverDraft
import es.topophone.core.model.receiver.WifiReceiverValidation
import es.topophone.core.model.receiver.WifiReceiverValidator
import es.topophone.data.receiver.WifiReceiverRepository
import es.topophone.receiver.experimental.DiscoveryPhase
import es.topophone.receiver.experimental.ReachDiscovery
import es.topophone.receiver.experimental.ServiceBrowser
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

const val EXPERIMENTAL_SEARCH_TAG = "experimental_search"
const val DISCOVERY_SEARCHING_TAG = "experimental_discovery_searching"
const val DISCOVERY_EMPTY_TAG = "experimental_discovery_empty"
const val DISCOVERY_CAUSES_TAG = "experimental_discovery_causes"
const val DISCOVERY_MANUAL_TAG = "experimental_discovery_manual"
const val DISCOVERY_RETRY_TAG = "experimental_discovery_retry"
const val DISCOVERY_PERMISSION_TAG = "experimental_discovery_permission"
const val DISCOVERY_PERMISSION_BUTTON_TAG = "experimental_discovery_permission_button"
const val DISCOVERY_DENIED_TAG = "experimental_discovery_denied"
const val DISCOVERY_SETTINGS_TAG = "experimental_discovery_settings"
const val DISCOVERY_ADDED_MESSAGE_TAG = "experimental_discovery_added_message"
const val ADD_NAME_TAG = "experimental_add_name"
const val ADD_HOST_TAG = "experimental_add_host"
const val ADD_PORT_TAG = "experimental_add_port"
const val ADD_CONFIRM_TAG = "experimental_add_confirm"
const val ADD_CANCEL_TAG = "experimental_add_cancel"
const val ADD_INVALID_TAG = "experimental_add_invalid"

fun discoveryRowTag(host: String) = "experimental_discovery_row_$host"

fun discoveryAddTag(host: String) = "experimental_discovery_add_$host"

fun discoveryAddedTag(host: String) = "experimental_discovery_added_$host"

/** La ruta de «Buscar receptores» dentro del grafo del receptor (S044/RF-7). */
@Serializable
data object ExperimentalDiscoveryDestination

/** Un receptor visto en la red; [alreadyAdded] si ya hay uno dado de alta con esa dirección (S044/RF-8). */
data class FoundRow(val name: String, val host: String, val alreadyAdded: Boolean)

/** Lo escrito en el formulario de alta; [invalid] si lo último que se intentó guardar no pasó la comprobación de S021 (RF-8). */
data class AddDraft(val name: String, val host: String, val port: String, val invalid: Boolean = false)

enum class DiscoveryPermission { Granted, NeedsRequest, Denied }

data class DiscoveryUiState(
    val phase: DiscoveryPhase = DiscoveryPhase.Idle,
    val found: List<FoundRow> = emptyList(),
    val permission: DiscoveryPermission = DiscoveryPermission.Granted,
    val draft: AddDraft? = null,
    /** El nombre del receptor recién dado de alta, para avisar (RF-8). */
    val addedName: String? = null,
)

class DiscoveryActions(
    val onAdd: (FoundRow) -> Unit = {},
    val onDraftChange: (AddDraft) -> Unit = {},
    val onConfirmAdd: () -> Unit = {},
    val onCancelAdd: () -> Unit = {},
    val onRetry: () -> Unit = {},
    val onRequestPermission: () -> Unit = {},
    val onOpenSettings: () -> Unit = {},
)

/**
 * «Buscar receptores»: busca 10 s en la red con permiso de red local, enseña lo que responde, marca los ya dados de alta y propone el alta
 * con el nombre, la dirección y el puerto editables; **nada se guarda sin confirmar** (S044/RF-7 a RF-10; plan D8).
 */
@HiltViewModel
class DiscoveryViewModel @Inject constructor(
    browser: ServiceBrowser,
    private val wifi: WifiReceiverRepository,
    private val localNetwork: LocalNetworkPermission,
) : ViewModel() {

    private data class Local(
        val permission: DiscoveryPermission = DiscoveryPermission.Granted,
        val draft: AddDraft? = null,
        val addedName: String? = null,
    )

    private val discovery = ReachDiscovery(browser, viewModelScope)
    private val local = MutableStateFlow(Local())

    val state: StateFlow<DiscoveryUiState> = combine(discovery.phase, discovery.found, wifi.receivers(), local) {
            phase,
            found,
            receivers,
            local,
        ->
        val known = receivers.map { it.host }.toSet()
        DiscoveryUiState(
            phase = phase,
            found = found.map { FoundRow(it.name, it.host, it.host in known) },
            permission = local.permission,
            draft = local.draft,
            addedName = local.addedName,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, DiscoveryUiState())

    /** Al abrir la pantalla o al reintentar: pide el permiso si falta y, con él, empieza la búsqueda (RF-7). */
    fun onScreenOpened() {
        if (localNetwork.required && !localNetwork.isGranted()) {
            local.update { it.copy(permission = DiscoveryPermission.NeedsRequest) }
        } else {
            local.update { it.copy(permission = DiscoveryPermission.Granted, addedName = null) }
            discovery.start()
        }
    }

    fun onRetry() = onScreenOpened()

    fun onPermissionAnswered(granted: Boolean) {
        if (granted && localNetwork.isGranted()) {
            local.update { it.copy(permission = DiscoveryPermission.Granted) }
            discovery.start()
        } else {
            local.update { it.copy(permission = DiscoveryPermission.Denied) }
        }
    }

    fun onScreenClosed() = discovery.stop()

    override fun onCleared() = discovery.stop()

    /** Propone el alta con el nombre y la dirección que anuncia y el puerto 9001; no guarda nada (RF-8). */
    fun onAdd(row: FoundRow) =
        local.update { it.copy(draft = AddDraft(row.name, row.host, WifiReceiverDefaults.POSITION_PORT.toString()), addedName = null) }

    fun onDraftChange(draft: AddDraft) = local.update { it.copy(draft = draft.copy(invalid = false)) }

    fun onCancelAdd() = local.update { it.copy(draft = null) }

    /** Comprueba el formulario con las mismas reglas que S021 y solo entonces lo da de alta (RF-8). */
    fun onConfirmAdd() {
        val draft = local.value.draft ?: return
        when (val result = WifiReceiverValidator.validate(WifiReceiverDraft(draft.name, draft.host, draft.port, ""))) {
            is WifiReceiverValidation.Invalid -> local.update { it.copy(draft = draft.copy(invalid = true)) }
            is WifiReceiverValidation.Valid -> viewModelScope.launch {
                wifi.add(result.input)
                local.update { it.copy(draft = null, addedName = result.input.name) }
            }
        }
    }
}

@Composable
fun DiscoveryRoute(themeState: ThemeState, onToggleSun: () -> Unit, onBack: () -> Unit) {
    val viewModel = hiltViewModel<DiscoveryViewModel>()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val permissionLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { viewModel.onPermissionAnswered(it) }
    DisposableEffect(viewModel) {
        viewModel.onScreenOpened()
        onDispose { viewModel.onScreenClosed() }
    }
    DiscoveryScreen(
        state = state,
        actions = DiscoveryActions(
            onAdd = viewModel::onAdd,
            onDraftChange = viewModel::onDraftChange,
            onConfirmAdd = viewModel::onConfirmAdd,
            onCancelAdd = viewModel::onCancelAdd,
            onRetry = viewModel::onRetry,
            onRequestPermission = { permissionLauncher.launch(ACCESS_LOCAL_NETWORK) },
            onOpenSettings = {
                context.startActivity(localNetworkSettingsIntent(context.packageName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            },
        ),
        themeState = themeState,
        onToggleSun = onToggleSun,
        onBack = onBack,
    )
    // Android pide el permiso al llegar el estado, no antes: así el diálogo del sistema aparece con la pantalla ya visible.
    LaunchedEffect(state.permission) {
        if (state.permission == DiscoveryPermission.NeedsRequest) permissionLauncher.launch(ACCESS_LOCAL_NETWORK)
    }
}

@Composable
fun DiscoveryScreen(
    state: DiscoveryUiState,
    actions: DiscoveryActions,
    themeState: ThemeState,
    onToggleSun: () -> Unit,
    onBack: () -> Unit,
) {
    SectionScaffold(
        title = stringResource(R.string.experimental_discovery_title),
        themeState = themeState,
        onToggleSun = onToggleSun,
        onNavigateBack = onBack,
    ) { padding ->
        Column(
            modifier = Modifier.padding(padding).padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when (state.permission) {
                DiscoveryPermission.NeedsRequest -> PermissionNeeded(actions)
                DiscoveryPermission.Denied -> PermissionDenied(actions)
                DiscoveryPermission.Granted -> Results(state, actions)
            }
        }
    }
    state.draft?.let { AddDialog(it, actions) }
}

@Composable
private fun PermissionNeeded(actions: DiscoveryActions) {
    ReceiverCard(stringResource(R.string.experimental_discovery_title)) {
        Text(stringResource(R.string.experimental_discovery_permission), modifier = Modifier.testTag(DISCOVERY_PERMISSION_TAG))
        Button(
            onClick = actions.onRequestPermission,
            modifier = Modifier.fillMaxWidth().heightIn(min = MinTouchTarget).testTag(DISCOVERY_PERMISSION_BUTTON_TAG),
        ) { Text(stringResource(R.string.experimental_discovery_permission_allow)) }
    }
}

@Composable
private fun PermissionDenied(actions: DiscoveryActions) {
    ReceiverCard(stringResource(R.string.experimental_discovery_title)) {
        Text(stringResource(R.string.experimental_discovery_denied), modifier = Modifier.testTag(DISCOVERY_DENIED_TAG))
        OutlinedButton(
            onClick = actions.onOpenSettings,
            modifier = Modifier.fillMaxWidth().heightIn(min = MinTouchTarget).testTag(DISCOVERY_SETTINGS_TAG),
        ) { Text(stringResource(R.string.experimental_discovery_settings)) }
        RetryButton(actions)
    }
}

@Composable
private fun Results(state: DiscoveryUiState, actions: DiscoveryActions) {
    ReceiverCard(stringResource(R.string.experimental_discovery_title)) {
        if (state.phase == DiscoveryPhase.Searching) {
            Text(stringResource(R.string.experimental_discovery_searching), modifier = Modifier.testTag(DISCOVERY_SEARCHING_TAG))
        }
        state.found.forEach { row -> FoundRowView(row, actions) }
        state.addedName?.let {
            Text(
                stringResource(R.string.experimental_discovery_added, it),
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.testTag(DISCOVERY_ADDED_MESSAGE_TAG),
            )
        }
        if (state.phase == DiscoveryPhase.Empty) {
            Text(stringResource(R.string.experimental_discovery_empty), modifier = Modifier.testTag(DISCOVERY_EMPTY_TAG))
            Column(modifier = Modifier.semantics(mergeDescendants = true) {}.testTag(DISCOVERY_CAUSES_TAG)) {
                Text(stringResource(R.string.experimental_discovery_causes), style = MaterialTheme.typography.titleSmall)
                Text(stringResource(R.string.experimental_discovery_cause_network))
                Text(stringResource(R.string.experimental_discovery_cause_blocked))
                Text(stringResource(R.string.experimental_discovery_cause_off))
            }
            Text(stringResource(R.string.experimental_discovery_manual), modifier = Modifier.testTag(DISCOVERY_MANUAL_TAG))
        }
        if (state.phase == DiscoveryPhase.Empty || state.phase == DiscoveryPhase.Done) RetryButton(actions)
    }
}

@Composable
private fun FoundRowView(row: FoundRow, actions: DiscoveryActions) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            stringResource(R.string.experimental_discovery_row, row.name, row.host),
            modifier = Modifier.weight(1f).testTag(discoveryRowTag(row.host)),
        )
        if (row.alreadyAdded) {
            Text(stringResource(R.string.experimental_discovery_already_added), modifier = Modifier.testTag(discoveryAddedTag(row.host)))
        } else {
            OutlinedButton(
                onClick = { actions.onAdd(row) },
                modifier = Modifier.heightIn(min = MinTouchTarget).testTag(discoveryAddTag(row.host)),
            ) { Text(stringResource(R.string.experimental_discovery_add)) }
        }
    }
}

@Composable
private fun RetryButton(actions: DiscoveryActions) {
    OutlinedButton(
        onClick = actions.onRetry,
        modifier = Modifier.fillMaxWidth().heightIn(min = MinTouchTarget).testTag(DISCOVERY_RETRY_TAG),
    ) { Text(stringResource(R.string.experimental_discovery_retry)) }
}

/** El alta con nombre, dirección y puerto editables; solo «Añadir» guarda (S044/RF-8). */
@Composable
private fun AddDialog(draft: AddDraft, actions: DiscoveryActions) {
    AlertDialog(
        onDismissRequest = actions.onCancelAdd,
        title = { Text(stringResource(R.string.experimental_discovery_add_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = draft.name,
                    onValueChange = { actions.onDraftChange(draft.copy(name = it)) },
                    label = { Text(stringResource(R.string.experimental_discovery_field_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag(ADD_NAME_TAG),
                )
                OutlinedTextField(
                    value = draft.host,
                    onValueChange = { actions.onDraftChange(draft.copy(host = it)) },
                    label = { Text(stringResource(R.string.experimental_discovery_field_host)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag(ADD_HOST_TAG),
                )
                OutlinedTextField(
                    value = draft.port,
                    onValueChange = { actions.onDraftChange(draft.copy(port = it)) },
                    label = { Text(stringResource(R.string.experimental_discovery_field_port)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag(ADD_PORT_TAG),
                )
                if (draft.invalid) {
                    Text(
                        stringResource(R.string.experimental_discovery_add_invalid),
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.testTag(ADD_INVALID_TAG),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = actions.onConfirmAdd,
                modifier = Modifier.heightIn(min = MinTouchTarget).testTag(ADD_CONFIRM_TAG),
            ) { Text(stringResource(R.string.experimental_discovery_add_confirm)) }
        },
        dismissButton = {
            TextButton(
                onClick = actions.onCancelAdd,
                modifier = Modifier.heightIn(min = MinTouchTarget).testTag(ADD_CANCEL_TAG),
            ) { Text(stringResource(R.string.experimental_discovery_add_cancel)) }
        },
    )
}
