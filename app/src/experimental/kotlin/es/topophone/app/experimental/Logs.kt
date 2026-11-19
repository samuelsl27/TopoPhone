package es.topophone.app.experimental

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
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
import es.topophone.app.ui.receiver.ReceiverCard
import es.topophone.core.designsystem.component.MinTouchTarget
import es.topophone.core.model.preferences.ThemeState
import es.topophone.receiver.experimental.ReachLiveState
import es.topophone.receiver.experimental.WriteFailure
import es.topophone.receiver.experimental.WriteResult
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

const val LOGS_STATE_TAG = "experimental_logs_state"
const val LOGS_START_TAG = "experimental_logs_start"
const val LOGS_STOP_TAG = "experimental_logs_stop"
const val LOGS_NO_LIVE_TAG = "experimental_logs_no_live"
const val LOGS_CONFIRM_DIALOG_TAG = "experimental_logs_confirm_dialog"
const val LOGS_STOP_WARNING_TAG = "experimental_logs_stop_warning"
const val LOGS_CONFIRM_YES_TAG = "experimental_logs_confirm_yes"
const val LOGS_CONFIRM_NO_TAG = "experimental_logs_confirm_no"
const val LOGS_RESULT_TAG = "experimental_logs_result"

/** La ruta de «Logs del receptor» dentro del grafo del receptor (S044/RF-20). */
@Serializable
data object ExperimentalLogsDestination

enum class LogsAction { START, STOP }

/** Todo lo que enseña «Logs del receptor» (S044/RF-20, RF-21). */
data class LogsUiState(
    val source: ReachSource = ReachSource.Inactive,
    val live: ReachLiveState = ReachLiveState(),
    /** Hay receptor listo, información básica válida, directo y estado de los logs conocido: lo que hace falta para escribir. */
    val canWrite: Boolean = false,
    /** La única acción que se ofrece: parar si algún log escribe, iniciar si no; `null` si no se puede escribir. */
    val action: LogsAction? = null,
    val confirming: LogsAction? = null,
    val sending: Boolean = false,
    val result: WriteResult<Boolean>? = null,
)

class LogsActions(
    val onRequest: () -> Unit = {},
    val onConfirm: () -> Unit = {},
    val onCancel: () -> Unit = {},
    val onDismissResult: () -> Unit = {},
)

/**
 * «Logs del receptor»: muestra el estado actual de los logs por el directo, pide confirmar antes de iniciar o parar (con aviso al parar),
 * envía **una** vez y comprueba el estado resultante (S044/RF-20, RF-21, RF-22). Sin directo no se ofrece ninguna acción (plan D11).
 */
@HiltViewModel
class LogsViewModel @Inject constructor(private val session: ExperimentalSession) : ViewModel() {

    private data class Local(val confirming: LogsAction? = null, val sending: Boolean = false, val result: WriteResult<Boolean>? = null)

    private val local = MutableStateFlow(Local())

    val state: StateFlow<LogsUiState> = combine(session.source, session.info, session.live, local) { source, info, live, local ->
        val basicInfoValid = ((info as? ReachInfoState.Loaded)?.reading?.info) != null
        val canWrite = source is ReachSource.Ready && basicInfoValid && live.connected && live.logsWriting != null
        LogsUiState(
            source = source,
            live = live,
            canWrite = canWrite,
            action = if (canWrite) (if (live.logsWriting == true) LogsAction.STOP else LogsAction.START) else null,
            confirming = local.confirming,
            sending = local.sending,
            result = local.result,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, LogsUiState())

    /** La pantalla pide el directo mientras esté abierta (S044/RF-16). */
    fun onScreenOpened() = session.startLive()

    fun onScreenClosed() = session.stopLive()

    /** Pide confirmar la acción que se ofrece; no envía nada (RF-20). */
    fun onRequest() {
        val action = state.value.action ?: return
        if (!state.value.sending) local.update { it.copy(confirming = action, result = null) }
    }

    fun onCancel() = local.update { it.copy(confirming = null) }

    fun onDismissResult() = local.update { it.copy(result = null) }

    /** Envía **una** vez la acción confirmada y enseña el estado resultante (RF-20, RF-21). */
    fun onConfirm() {
        val current = state.value
        val action = local.value.confirming ?: return
        val endpoint = (current.source as? ReachSource.Ready)?.endpoint ?: return
        if (!current.canWrite || local.value.sending) return
        local.update { it.copy(sending = true) }
        viewModelScope.launch {
            val result = session.setLogs(endpoint, started = action == LogsAction.START)
            local.update { it.copy(confirming = null, sending = false, result = result) }
        }
    }
}

@Composable
fun LogsRoute(themeState: ThemeState, onToggleSun: () -> Unit, onBack: () -> Unit) {
    val viewModel = hiltViewModel<LogsViewModel>()
    val state by viewModel.state.collectAsStateWithLifecycle()
    DisposableEffect(viewModel) {
        viewModel.onScreenOpened()
        onDispose { viewModel.onScreenClosed() }
    }
    LogsScreen(
        state = state,
        actions = LogsActions(viewModel::onRequest, viewModel::onConfirm, viewModel::onCancel, viewModel::onDismissResult),
        themeState = themeState,
        onToggleSun = onToggleSun,
        onBack = onBack,
    )
}

@Composable
fun LogsScreen(state: LogsUiState, actions: LogsActions, themeState: ThemeState, onToggleSun: () -> Unit, onBack: () -> Unit) {
    SectionScaffold(
        title = stringResource(R.string.experimental_logs_title),
        themeState = themeState,
        onToggleSun = onToggleSun,
        onNavigateBack = onBack,
    ) { padding ->
        Column(
            modifier = Modifier.padding(padding).padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ReceiverCard(stringResource(R.string.experimental_logs_title)) {
                if (!state.live.connected) {
                    Text(stringResource(R.string.experimental_logs_no_live), modifier = Modifier.testTag(LOGS_NO_LIVE_TAG))
                } else {
                    Text(logsStateText(state.live.logsWriting), modifier = Modifier.testTag(LOGS_STATE_TAG))
                }
                when (state.action) {
                    LogsAction.START -> ActionButton(R.string.experimental_logs_start, LOGS_START_TAG, !state.sending, actions.onRequest)
                    LogsAction.STOP -> ActionButton(R.string.experimental_logs_stop, LOGS_STOP_TAG, !state.sending, actions.onRequest)
                    null -> Unit
                }
                state.result?.let { result ->
                    Text(
                        resultText(result),
                        color = if (result is WriteResult.Confirmed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                        modifier = Modifier
                            .heightIn(min = MinTouchTarget)
                            .clickable(onClick = actions.onDismissResult)
                            .testTag(LOGS_RESULT_TAG),
                    )
                }
            }
        }
    }
    state.confirming?.let { ConfirmDialog(it, actions) }
}

@Composable
private fun logsStateText(writing: Boolean?): String = stringResource(
    when (writing) {
        true -> R.string.experimental_logs_writing
        false -> R.string.experimental_logs_stopped
        null -> R.string.experimental_logs_unknown
    },
)

@Composable
private fun ActionButton(label: Int, tag: String, enabled: Boolean, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth().heightIn(min = MinTouchTarget).testTag(tag),
    ) { Text(stringResource(label)) }
}

@Composable
private fun resultText(result: WriteResult<Boolean>): String = stringResource(
    when (result) {
        is WriteResult.Confirmed ->
            if (result.value) R.string.experimental_logs_confirmed_writing else R.string.experimental_logs_confirmed_stopped
        is WriteResult.NotConfirmed -> when {
            result.requestFailed -> when (result.read) {
                true -> R.string.experimental_logs_send_failed_writing
                false -> R.string.experimental_logs_send_failed_stopped
                null -> R.string.experimental_logs_send_failed_unknown
            }
            else -> when (result.read) {
                true -> R.string.experimental_logs_mismatch_writing
                false -> R.string.experimental_logs_mismatch_stopped
                null -> R.string.experimental_logs_not_confirmed_unknown
            }
        }
        is WriteResult.Failed -> when (result.cause) {
            WriteFailure.NO_RESPONSE -> R.string.experimental_logs_failed_no_response
            WriteFailure.INCOMPATIBLE -> R.string.experimental_logs_failed_incompatible
            WriteFailure.INVALID_VALUE -> R.string.experimental_logs_failed_invalid
            WriteFailure.NO_LIVE -> R.string.experimental_logs_failed_no_live
        }
    },
)

/** Confirmar antes de iniciar o parar; al parar, avisa de que el log activo se cerrará y no continuará (S044/RF-20). */
@Composable
private fun ConfirmDialog(action: LogsAction, actions: LogsActions) {
    AlertDialog(
        onDismissRequest = actions.onCancel,
        title = { Text(stringResource(R.string.experimental_logs_title)) },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.semantics(mergeDescendants = true) {}.testTag(LOGS_CONFIRM_DIALOG_TAG),
            ) {
                when (action) {
                    LogsAction.START -> Text(stringResource(R.string.experimental_logs_start_question))
                    LogsAction.STOP -> {
                        Text(stringResource(R.string.experimental_logs_stop_question))
                        Text(
                            stringResource(R.string.experimental_logs_stop_warning),
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.testTag(LOGS_STOP_WARNING_TAG),
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = actions.onConfirm,
                modifier = Modifier.heightIn(min = MinTouchTarget).testTag(LOGS_CONFIRM_YES_TAG),
            ) { Text(stringResource(R.string.experimental_logs_confirm_yes)) }
        },
        dismissButton = {
            TextButton(
                onClick = actions.onCancel,
                modifier = Modifier.heightIn(min = MinTouchTarget).testTag(LOGS_CONFIRM_NO_TAG),
            ) { Text(stringResource(R.string.experimental_logs_confirm_no)) }
        },
    )
}
