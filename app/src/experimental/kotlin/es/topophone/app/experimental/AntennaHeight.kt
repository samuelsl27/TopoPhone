package es.topophone.app.experimental

import androidx.compose.foundation.clickable
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
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
import es.topophone.app.receiver.CurrentGear
import es.topophone.app.receiver.CurrentTilt
import es.topophone.app.ui.SectionScaffold
import es.topophone.app.ui.receiver.ReceiverCard
import es.topophone.app.ui.receiver.uiLocale
import es.topophone.core.designsystem.component.MinTouchTarget
import es.topophone.core.model.format.LengthFormat
import es.topophone.core.model.point.TiltMode
import es.topophone.core.model.preferences.ThemeState
import es.topophone.receiver.experimental.AntennaHeightProposal
import es.topophone.receiver.experimental.ReachPart
import es.topophone.receiver.experimental.WriteFailure
import es.topophone.receiver.experimental.WriteResult
import java.math.BigDecimal
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

const val ANTENNA_CURRENT_TAG = "experimental_antenna_current"
const val ANTENNA_PROPOSAL_TAG = "experimental_antenna_proposal"
const val ANTENNA_DOUBLE_COUNT_TAG = "experimental_antenna_double_count"
const val ANTENNA_FIELD_TAG = "experimental_antenna_field"
const val ANTENNA_SEND_TAG = "experimental_antenna_send"
const val ANTENNA_INVALID_TAG = "experimental_antenna_invalid"
const val ANTENNA_BLOCKED_TAG = "experimental_antenna_blocked"
const val ANTENNA_CONFIRM_DIALOG_TAG = "experimental_antenna_confirm_dialog"
const val ANTENNA_CONFIRM_YES_TAG = "experimental_antenna_confirm_yes"
const val ANTENNA_CONFIRM_NO_TAG = "experimental_antenna_confirm_no"
const val ANTENNA_RESULT_TAG = "experimental_antenna_result"

/** La ruta de «Enviar altura de antena» dentro del grafo del receptor (S044/RF-18). */
@Serializable
data object ExperimentalAntennaDestination

/**
 * Lo que decide la propuesta: el modo de inclinación del receptor (`null` si aún no se eligió: la app resta la altura, ADR-7) y la altura
 * introducida de su jalón activo (S016, S046).
 */
data class AntennaInputs(val mode: TiltMode?, val poleHeightM: Double?)

/** De dónde salen [AntennaInputs]; en producción, `CurrentTilt` y `CurrentGear`; en los tests, un doble. */
interface AntennaContext {
    val state: StateFlow<AntennaInputs>
}

/** El modo y el jalón activo del receptor en uso (S044/RF-18; plan D10). */
class CurrentAntennaContext(currentTilt: CurrentTilt, currentGear: CurrentGear, scope: kotlinx.coroutines.CoroutineScope) : AntennaContext {
    override val state: StateFlow<AntennaInputs> =
        combine(currentTilt.state.map { it.settings.mode }, currentGear.state.map { it.pole?.enteredM }) { mode, pole ->
            AntennaInputs(mode, pole)
        }.distinctUntilChanged().stateIn(scope, SharingStarted.Eagerly, AntennaInputs(null, null))
}

/** Todo lo que enseña «Enviar altura de antena» (S044/RF-18, RF-19, RF-21). */
data class AntennaUiState(
    val source: ReachSource = ReachSource.Inactive,
    /** La altura que el receptor dice tener ahora (en metros), o `null` si no se ha leído. */
    val currentM: Double? = null,
    /** Hay receptor listo y su información básica es válida: sin eso no se escribe nada (RF-15). */
    val canSend: Boolean = false,
    val proposal: AntennaHeightProposal = AntennaHeightProposal(0.0, warnDoubleCount = false),
    /** Lo que hay en el campo: lo escrito, o la propuesta mientras no se toque. */
    val text: String = "",
    /** [text] ya interpretado, o `null` si no es una altura válida. */
    val valueM: Double? = null,
    val confirming: Boolean = false,
    val sending: Boolean = false,
    val result: WriteResult<Double>? = null,
)

class AntennaActions(
    val onTextChange: (String) -> Unit = {},
    val onSend: () -> Unit = {},
    val onConfirm: () -> Unit = {},
    val onCancel: () -> Unit = {},
    val onDismissResult: () -> Unit = {},
)

/**
 * «Enviar altura de antena»: propone el valor según el modo, deja editarlo, pide confirmar con el valor actual y el nuevo, envía **una**
 * vez, vuelve a leer y enseña el resultado (S044/RF-18, RF-19, RF-21, RF-22). Nunca reintenta solo.
 */
@HiltViewModel
class AntennaHeightViewModel @Inject constructor(private val session: ExperimentalSession, antenna: AntennaContext) : ViewModel() {

    private data class Local(
        val edited: String? = null,
        val confirming: Boolean = false,
        val sending: Boolean = false,
        val result: WriteResult<Double>? = null,
    )

    private val local = MutableStateFlow(Local())

    val state: StateFlow<AntennaUiState> = combine(session.source, session.info, antenna.state, local) { source, info, inputs, local ->
        val reading = (info as? ReachInfoState.Loaded)?.reading
        val proposal = AntennaHeightProposal.of(inputs.mode ?: TiltMode.APP, inputs.poleHeightM)
        val text = local.edited ?: format(proposal.valueM)
        AntennaUiState(
            source = source,
            currentM = ((reading?.device as? ReachPart.Ok)?.value)?.antennaHeightM,
            canSend = source is ReachSource.Ready && reading?.info != null,
            proposal = proposal,
            text = text,
            valueM = parse(text),
            confirming = local.confirming,
            sending = local.sending,
            result = local.result,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, AntennaUiState())

    fun onTextChange(text: String) = local.update { it.copy(edited = text, result = null) }

    /** Pide confirmar; no envía nada (RF-18). */
    fun onSend() {
        val current = state.value
        if (current.canSend && current.valueM != null && !current.sending) local.update { it.copy(confirming = true) }
    }

    fun onCancel() = local.update { it.copy(confirming = false) }

    fun onDismissResult() = local.update { it.copy(result = null) }

    /** Envía el valor confirmado **una** vez y vuelve a leer la información del receptor para enseñar lo que quedó (RF-19, RF-21). */
    fun onConfirm() {
        val current = state.value
        val endpoint = (current.source as? ReachSource.Ready)?.endpoint ?: return
        val value = current.valueM ?: return
        if (!current.canSend || !local.value.confirming || local.value.sending) return
        local.update { it.copy(sending = true) }
        viewModelScope.launch {
            val result = session.setAntennaHeight(endpoint, value)
            session.refresh()
            local.update { it.copy(confirming = false, sending = false, result = result) }
        }
    }

    private companion object {
        /** Lo escrito como lo dice el usuario: coma o punto decimal; solo un número finito de 0 en adelante. */
        fun parse(text: String): Double? = text.trim().replace(',', '.').toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0.0 }

        fun format(value: Double): String = BigDecimal.valueOf(value).stripTrailingZeros().toPlainString()
    }
}

@Composable
fun AntennaHeightRoute(themeState: ThemeState, onToggleSun: () -> Unit, onBack: () -> Unit) {
    val viewModel = hiltViewModel<AntennaHeightViewModel>()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val actions = AntennaActions(
        onTextChange = viewModel::onTextChange,
        onSend = viewModel::onSend,
        onConfirm = viewModel::onConfirm,
        onCancel = viewModel::onCancel,
        onDismissResult = viewModel::onDismissResult,
    )
    AntennaHeightScreen(state, actions, themeState, onToggleSun, onBack)
}

@Composable
fun AntennaHeightScreen(
    state: AntennaUiState,
    actions: AntennaActions,
    themeState: ThemeState,
    onToggleSun: () -> Unit,
    onBack: () -> Unit,
) {
    SectionScaffold(
        title = stringResource(R.string.experimental_antenna_title),
        themeState = themeState,
        onToggleSun = onToggleSun,
        onNavigateBack = onBack,
    ) { padding ->
        Column(
            modifier = Modifier.padding(padding).padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ReceiverCard(stringResource(R.string.experimental_antenna_title)) {
                val locale = uiLocale()
                Text(
                    state.currentM?.let { stringResource(R.string.experimental_antenna_current, LengthFormat.metres(it, locale)) }
                        ?: stringResource(R.string.experimental_antenna_current_unknown),
                    modifier = Modifier.testTag(ANTENNA_CURRENT_TAG),
                )
                Text(
                    stringResource(R.string.experimental_antenna_proposal, LengthFormat.metres(state.proposal.valueM, locale)),
                    modifier = Modifier.testTag(ANTENNA_PROPOSAL_TAG),
                )
                if (state.proposal.warnDoubleCount) {
                    Text(
                        stringResource(R.string.experimental_antenna_double_count),
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.testTag(ANTENNA_DOUBLE_COUNT_TAG),
                    )
                } else {
                    Text(stringResource(R.string.experimental_antenna_proposal_receiver), style = MaterialTheme.typography.bodyMedium)
                }
                OutlinedTextField(
                    value = state.text,
                    onValueChange = actions.onTextChange,
                    label = { Text(stringResource(R.string.experimental_antenna_field)) },
                    singleLine = true,
                    isError = state.valueM == null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth().testTag(ANTENNA_FIELD_TAG),
                )
                if (state.valueM == null) {
                    Text(
                        stringResource(R.string.experimental_antenna_invalid),
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.testTag(ANTENNA_INVALID_TAG),
                    )
                }
                if (!state.canSend) {
                    Text(stringResource(R.string.experimental_antenna_blocked), modifier = Modifier.testTag(ANTENNA_BLOCKED_TAG))
                }
                Button(
                    onClick = actions.onSend,
                    enabled = state.canSend && state.valueM != null && !state.sending,
                    modifier = Modifier.fillMaxWidth().heightIn(min = MinTouchTarget).testTag(ANTENNA_SEND_TAG),
                ) { Text(stringResource(R.string.experimental_antenna_send)) }
                state.result?.let { result ->
                    Text(
                        resultText(result),
                        color = if (result is WriteResult.Confirmed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                        modifier = Modifier.heightIn(
                            min = MinTouchTarget,
                        ).clickable(onClick = actions.onDismissResult).testTag(ANTENNA_RESULT_TAG),
                    )
                }
            }
        }
    }
    if (state.confirming) ConfirmDialog(state, actions)
}

@Composable
private fun resultText(result: WriteResult<Double>): String {
    val locale = uiLocale()
    return when (result) {
        is WriteResult.Confirmed -> stringResource(R.string.experimental_antenna_confirmed, LengthFormat.metres(result.value, locale))
        is WriteResult.NotConfirmed -> when {
            result.read == null -> stringResource(R.string.experimental_antenna_unknown)
            result.requestFailed -> stringResource(R.string.experimental_antenna_send_failed, LengthFormat.metres(result.read!!, locale))
            else -> stringResource(R.string.experimental_antenna_mismatch, LengthFormat.metres(result.read!!, locale))
        }
        is WriteResult.Failed -> stringResource(
            when (result.cause) {
                WriteFailure.NO_RESPONSE -> R.string.experimental_antenna_failed_no_response
                WriteFailure.INCOMPATIBLE -> R.string.experimental_antenna_failed_incompatible
                WriteFailure.INVALID_VALUE -> R.string.experimental_antenna_failed_invalid
                WriteFailure.NO_LIVE -> R.string.experimental_antenna_failed_no_live
            },
        )
    }
}

@Composable
private fun ConfirmDialog(state: AntennaUiState, actions: AntennaActions) {
    val locale = uiLocale()
    val current = state.currentM?.let { LengthFormat.metres(it, locale) } ?: stringResource(R.string.experimental_antenna_current_unknown)
    val new = state.valueM?.let { LengthFormat.metres(it, locale) }.orEmpty()
    AlertDialog(
        onDismissRequest = actions.onCancel,
        title = { Text(stringResource(R.string.experimental_antenna_confirm_title)) },
        text = {
            Text(
                stringResource(R.string.experimental_antenna_confirm_body, current, new),
                modifier = Modifier.testTag(ANTENNA_CONFIRM_DIALOG_TAG),
            )
        },
        confirmButton = {
            TextButton(
                onClick = actions.onConfirm,
                modifier = Modifier.heightIn(min = MinTouchTarget).testTag(ANTENNA_CONFIRM_YES_TAG),
            ) { Text(stringResource(R.string.experimental_antenna_confirm_yes)) }
        },
        dismissButton = {
            TextButton(
                onClick = actions.onCancel,
                modifier = Modifier.heightIn(min = MinTouchTarget).testTag(ANTENNA_CONFIRM_NO_TAG),
            ) { Text(stringResource(R.string.experimental_antenna_confirm_no)) }
        },
    )
}
