package es.topophone.app.ui.receiver

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import es.topophone.app.R
import es.topophone.core.designsystem.component.MinTouchTarget
import es.topophone.core.ntrip.NtripFailure
import es.topophone.receiver.session.NtripState
import java.text.NumberFormat

const val NTRIP_HOST_TAG = "ntrip_host"
const val NTRIP_PORT_TAG = "ntrip_port"
const val NTRIP_MOUNTPOINT_TAG = "ntrip_mountpoint"
const val NTRIP_USERNAME_TAG = "ntrip_username"
const val NTRIP_PASSWORD_TAG = "ntrip_password"
const val NTRIP_CONNECT_TAG = "ntrip_connect"
const val NTRIP_DISCONNECT_TAG = "ntrip_disconnect"
const val NTRIP_STATUS_TAG = "ntrip_status"
const val NTRIP_BYTES_TAG = "ntrip_bytes"
const val NTRIP_LAST_CORRECTION_TAG = "ntrip_last_correction"

/** Tarjeta NTRIP: datos del caster, conexión, errores y estadísticas (S002/RF-18 a RF-28). */
@Composable
internal fun NtripCard(state: ReceiverUiState, actions: ReceiverActions) {
    ReceiverCard(stringResource(R.string.receiver_ntrip_title)) {
        val ntrip = state.snapshot.ntrip
        val active = ntrip is NtripState.Connecting || ntrip is NtripState.Streaming || ntrip == NtripState.Paused
        NtripForm(state.form, enabled = !active, onChange = actions.onFormChange)
        if (state.formInvalid) Text(stringResource(R.string.ntrip_form_invalid), color = MaterialTheme.colorScheme.error)
        Text(ntripStatusText(ntrip), modifier = Modifier.testTag(NTRIP_STATUS_TAG), style = MaterialTheme.typography.bodyLarge)
        if (ntrip == NtripState.Stopped(NtripFailure.UNREACHABLE) && !state.internetAvailable) {
            Text(stringResource(R.string.ntrip_no_internet)) // S002/RF-23
        }
        if (ntrip is NtripState.Streaming) StreamingStats(ntrip, state.nowMs)
        if (active) {
            OutlinedButton(
                onClick = actions.onDisconnectNtrip,
                modifier = Modifier.heightIn(min = MinTouchTarget).testTag(NTRIP_DISCONNECT_TAG),
            ) { Text(stringResource(R.string.ntrip_disconnect)) }
        } else {
            val lost = ntrip == NtripState.Stopped(NtripFailure.NO_DATA) || ntrip == NtripState.Stopped(NtripFailure.CONNECTION_LOST)
            Button(
                onClick = actions.onConnectNtrip,
                modifier = Modifier.heightIn(min = MinTouchTarget).testTag(NTRIP_CONNECT_TAG),
            ) { Text(stringResource(if (lost) R.string.ntrip_reconnect else R.string.ntrip_connect)) } // S002/RF-19, S002/RF-24
        }
    }
}

@Composable
private fun NtripForm(form: NtripForm, enabled: Boolean, onChange: (NtripForm) -> Unit) {
    FormField(form.host, R.string.ntrip_host, NTRIP_HOST_TAG, enabled, KeyboardType.Uri) { onChange(form.copy(host = it)) }
    FormField(form.port, R.string.ntrip_port, NTRIP_PORT_TAG, enabled, KeyboardType.Number) { onChange(form.copy(port = it)) }
    FormField(form.mountpoint, R.string.ntrip_mountpoint, NTRIP_MOUNTPOINT_TAG, enabled, KeyboardType.Ascii) {
        onChange(form.copy(mountpoint = it))
    }
    FormField(form.username, R.string.ntrip_username, NTRIP_USERNAME_TAG, enabled, KeyboardType.Ascii) {
        onChange(form.copy(username = it))
    }
    FormField(form.password, R.string.ntrip_password, NTRIP_PASSWORD_TAG, enabled, KeyboardType.Password, ImeAction.Done) {
        onChange(form.copy(password = it))
    }
    // S002/RF-27: la contraseña solo vive en memoria.
    Text(stringResource(R.string.ntrip_password_not_saved), style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun FormField(
    value: String,
    label: Int,
    tag: String,
    enabled: Boolean,
    keyboardType: KeyboardType,
    imeAction: ImeAction = ImeAction.Next,
    onChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(stringResource(label)) },
        enabled = enabled,
        singleLine = true,
        visualTransformation = if (keyboardType == KeyboardType.Password) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = imeAction, autoCorrectEnabled = false),
        modifier = Modifier.fillMaxWidth().testTag(tag),
    )
}

@Composable
private fun StreamingStats(ntrip: NtripState.Streaming, nowMs: Long) {
    val bytes = NumberFormat.getIntegerInstance(uiLocale()).format(ntrip.bytesReceived)
    Text(stringResource(R.string.ntrip_bytes, bytes), modifier = Modifier.testTag(NTRIP_BYTES_TAG)) // S002/RF-25
    val last = ntrip.lastCorrectionAtMs
    val since = if (last == null) {
        stringResource(R.string.ntrip_waiting_first)
    } else {
        stringResource(R.string.ntrip_last_correction, ((nowMs - last).coerceAtLeast(0) / MS_PER_S).toInt())
    }
    Text(since, modifier = Modifier.testTag(NTRIP_LAST_CORRECTION_TAG))
}

/** Estado del NTRIP en texto, también para el resumen de Correcciones (S005/RF-12). */
@Composable
internal fun ntripStatusText(ntrip: NtripState): String = stringResource(
    when (ntrip) {
        NtripState.Idle -> R.string.ntrip_state_idle
        NtripState.Connecting -> R.string.ntrip_state_connecting
        is NtripState.Streaming -> R.string.ntrip_state_streaming
        NtripState.ReceiverNotConnected -> R.string.ntrip_receiver_not_connected // S002/RF-28
        NtripState.UnavailableInReplay -> R.string.ntrip_unavailable_in_replay // S003/RF-44
        NtripState.Paused -> R.string.ntrip_paused // S004/RF-18
        is NtripState.Stopped -> when (ntrip.failure) {
            NtripFailure.UNAUTHORIZED -> R.string.ntrip_error_unauthorized // S002/RF-21
            NtripFailure.MOUNTPOINT_NOT_FOUND -> R.string.ntrip_error_mountpoint // S002/RF-22
            NtripFailure.UNREACHABLE -> R.string.ntrip_error_unreachable // S002/RF-23
            NtripFailure.NO_DATA -> R.string.ntrip_error_no_data // S002/RF-24
            NtripFailure.CONNECTION_LOST -> R.string.ntrip_error_connection_lost // S002/RF-24
            NtripFailure.PROTOCOL -> R.string.ntrip_error_protocol
        }
    },
)

private const val MS_PER_S = 1_000L
