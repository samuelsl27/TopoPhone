package es.topophone.app.ui.receiver

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import es.topophone.app.R
import es.topophone.core.designsystem.component.MinTouchTarget
import es.topophone.receiver.session.ConnectionState

const val RECEIVER_HEADER_TAG = "receiver_header"
const val HEADER_NAME_TAG = "receiver_header_name"
const val LOW_SPACE_TAG = "receiver_low_space"

/**
 * Cabecera de la pantalla del receptor (S005/RF-6 a RF-8): el nombre del receptor o de la sesión
 * reproducida, el estado de la conexión, "Grabando" y la inclinación, los avisos y las acciones.
 */
@Composable
internal fun ReceiverHeader(state: ReceiverUiState, actions: ReceiverActions) {
    val connection = state.snapshot.connection
    val replay = state.snapshot.replay
    Card(modifier = Modifier.fillMaxWidth().testTag(RECEIVER_HEADER_TAG)) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                replay?.name ?: connection.receiverName(),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.testTag(HEADER_NAME_TAG),
            )
            Text(
                headerStateText(connection, replay != null),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.testTag(CONNECTION_STATUS_TAG),
            )
            ConnectionIndicators(state) // S003/RF-20, RF-22
            if (replay != null) {
                ReplayControls(replay, actions) // S003/RF-37 a RF-41
                BluetoothDevicesSection(state, actions) // S003/RF-43: conectar un receptor termina la reproducción
            } else {
                Warnings(state, actions)
                when (connection) {
                    is ConnectionState.Connecting, is ConnectionState.Receiving, is ConnectionState.NoData -> OutlinedButton(
                        onClick = actions.onDisconnect,
                        modifier = Modifier.heightIn(min = MinTouchTarget).testTag(DISCONNECT_TAG),
                    ) { Text(stringResource(R.string.receiver_disconnect)) } // S002/RF-10
                    is ConnectionState.Reconnecting -> ReconnectingDetails(connection, state.nowMs, actions) // S004/RF-15, RF-16
                    else -> Unit
                }
            }
        }
    }
}

/** Avisos de la conexión (S005/RF-8). */
@Composable
private fun Warnings(state: ReceiverUiState, actions: ReceiverActions) {
    if (state.snapshot.nmeaMissing) {
        Text(
            stringResource(R.string.receiver_nmea_missing),
            modifier = Modifier.testTag(NMEA_MISSING_TAG),
            color = MaterialTheme.colorScheme.error,
        )
    } // S004/RF-28
    if (state.notificationsDenied) {
        Text(stringResource(R.string.receiver_notifications_denied))
        OutlinedButton(
            onClick = actions.onEnableNotifications,
            modifier = Modifier.heightIn(min = MinTouchTarget).testTag(ENABLE_NOTIFICATIONS_TAG),
        ) { Text(stringResource(R.string.receiver_notifications_enable)) }
    } // S004/RF-6
    if (state.snapshot.recordingStoppedLowSpace) {
        Text(
            stringResource(R.string.receiver_recording_stopped_low_space),
            modifier = Modifier.testTag(LOW_SPACE_TAG),
            color = MaterialTheme.colorScheme.error,
        )
    } // S003/RF-28
}

/** El nombre ya es el título: el estado no lo repite (S005/RF-6). */
@Composable
private fun headerStateText(connection: ConnectionState, replaying: Boolean): String = when {
    replaying -> stringResource(R.string.receiver_header_replaying) // S003/RF-35
    connection is ConnectionState.Connecting -> stringResource(R.string.receiver_header_connecting)
    connection is ConnectionState.Receiving -> stringResource(R.string.receiver_header_receiving)
    connection is ConnectionState.NoData -> stringResource(R.string.receiver_header_no_data)
    connection is ConnectionState.Reconnecting -> stringResource(R.string.receiver_header_reconnecting) // S004/RF-13
    else -> connectionText(connection)
}

private fun ConnectionState.receiverName(): String = when (this) {
    is ConnectionState.Connecting -> description
    is ConnectionState.Receiving -> description
    is ConnectionState.NoData -> description
    is ConnectionState.Reconnecting -> description
    is ConnectionState.Lost -> description
    is ConnectionState.ReplayFinished -> description
    ConnectionState.Disconnected, is ConnectionState.Failed -> ""
}
