package es.topophone.app.ui.receiver

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import es.topophone.app.R
import es.topophone.core.designsystem.R as DesignR
import es.topophone.core.designsystem.component.MinTouchTarget
import es.topophone.receiver.session.ConnectFailure
import es.topophone.receiver.session.ConnectionState

const val LIST_SESSIONS_TAG = "list_sessions"

/**
 * Lista de receptores, sin conexión (S005/RF-1 a RF-4): el resultado de la última conexión, los
 * emparejados con el último usado señalado, "Añadir receptor" y el acceso a las sesiones grabadas.
 */
@Composable
internal fun ReceiverList(state: ReceiverUiState, actions: ReceiverActions) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        LastConnection(state, actions)
        BluetoothDevicesSection(state, actions)
        Card(modifier = Modifier.fillMaxWidth()) {
            ListRow(stringResource(R.string.receiver_menu_sessions), actions.onOpenSessions, Modifier.testTag(LIST_SESSIONS_TAG))
        }
    }
}

/** Estado de la última conexión y, si falló o se perdió, sus causas y su acción (S005/RF-4). */
@Composable
private fun LastConnection(state: ReceiverUiState, actions: ReceiverActions) {
    val connection = state.snapshot.connection
    Text(connectionText(connection), modifier = Modifier.testTag(CONNECTION_STATUS_TAG), style = MaterialTheme.typography.bodyLarge)
    when (connection) {
        is ConnectionState.Failed -> {
            // S004/RF-27: un rechazo inmediato tiene una causa más probable que las demás.
            val busy = connection.failure == ConnectFailure.BUSY
            Text(stringResource(if (busy) R.string.receiver_busy_cause else R.string.receiver_connect_failed_causes))
        } // S002/RF-7
        is ConnectionState.Lost -> Button(
            onClick = actions.onReconnect,
            modifier = Modifier.heightIn(min = MinTouchTarget).testTag(RECONNECT_TAG),
        ) { Text(stringResource(R.string.receiver_reconnect)) } // S002/RF-9, S004/RF-20
        is ConnectionState.ReplayFinished -> Button(
            onClick = actions.onReplayAgain,
            modifier = Modifier.heightIn(min = MinTouchTarget).testTag(REPLAY_AGAIN_TAG),
        ) { Text(stringResource(R.string.replay_again)) } // S003/RF-40
        else -> Unit
    }
}

/** Bluetooth del móvil y receptores emparejados (S002/RF-1 a RF-4, S004/RF-1, S005/RF-2, RF-3). */
@Composable
internal fun BluetoothDevicesSection(state: ReceiverUiState, actions: ReceiverActions) {
    when (val bluetooth = state.bluetooth) {
        BluetoothUi.Unsupported -> Text(stringResource(R.string.receiver_bluetooth_unsupported))
        BluetoothUi.PermissionNeeded -> {
            Text(stringResource(R.string.receiver_bluetooth_permission_rationale))
            Button(
                onClick = actions.onRequestPermission,
                modifier = Modifier.heightIn(min = MinTouchTarget).testTag(PERMISSION_REQUEST_TAG),
            ) {
                Text(stringResource(R.string.receiver_bluetooth_permission_request))
            }
            Text(stringResource(R.string.receiver_bluetooth_permission_settings_help), style = MaterialTheme.typography.bodySmall)
            OutlinedButton(
                onClick = actions.onOpenAppSettings,
                modifier = Modifier.heightIn(min = MinTouchTarget).testTag(OPEN_SETTINGS_TAG),
            ) {
                Text(stringResource(R.string.receiver_open_app_settings))
            }
        }
        BluetoothUi.Disabled -> {
            Text(stringResource(R.string.receiver_bluetooth_disabled))
            Button(onClick = actions.onEnableBluetooth, modifier = Modifier.heightIn(min = MinTouchTarget).testTag(ENABLE_BLUETOOTH_TAG)) {
                Text(stringResource(R.string.receiver_bluetooth_enable))
            }
        }
        is BluetoothUi.Ready -> {
            Text(stringResource(R.string.receiver_paired_title), style = MaterialTheme.typography.titleSmall)
            if (bluetooth.devices.isEmpty()) {
                Text(stringResource(R.string.receiver_no_bonded_devices))
            } else {
                Card(modifier = Modifier.fillMaxWidth()) {
                    bluetooth.devices.forEachIndexed { index, device ->
                        if (index > 0) HorizontalDivider()
                        val lastUsed = device.address == state.lastReceiverAddress
                        DeviceRow(device.name, device.address, lastUsed) { actions.onConnect(device.address) }
                    }
                }
            }
            AddReceiver(state, actions)
        }
    }
}

/** Un receptor emparejado: nombre, dirección y, si es el último usado, su marca (S005/RF-3). */
@Composable
private fun DeviceRow(name: String, address: String, lastUsed: Boolean, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(name) },
        supportingContent = { Text(address) },
        trailingContent = if (lastUsed) {
            { Text(stringResource(R.string.receiver_last_used), color = MaterialTheme.colorScheme.primary) }
        } else {
            null
        },
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        modifier = Modifier.heightIn(min = MinTouchTarget).clickable(onClick = onClick).testTag(deviceTag(address)),
    )
}

/** Fila que abre otra pantalla, con su flecha (S005/RF-2). */
@Composable
internal fun ListRow(title: String, onClick: () -> Unit, modifier: Modifier = Modifier, summary: (@Composable () -> Unit)? = null) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = summary,
        trailingContent = { Icon(painterResource(DesignR.drawable.ds_ic_chevron_right), contentDescription = null) },
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        modifier = modifier.heightIn(min = MinTouchTarget).clickable(onClick = onClick),
    )
}

/** "Añadir receptor" y sus avisos, debajo de la lista de emparejados, que no desaparece (S004/RF-1, RF-4). */
@Composable
private fun AddReceiver(state: ReceiverUiState, actions: ReceiverActions) {
    state.pairing.message?.let { message ->
        val text = when (message) {
            PairingMessage.SEARCH_FAILED -> R.string.receiver_pairing_search_failed
            PairingMessage.SEARCH_CANCELLED -> R.string.receiver_pairing_search_cancelled
            PairingMessage.PAIRING_FAILED -> R.string.receiver_pairing_failed
        }
        Text(stringResource(text), color = MaterialTheme.colorScheme.error)
    }
    Button(
        onClick = if (state.pairingSupported) actions.onAddReceiver else actions.onOpenBluetoothSettings,
        modifier = Modifier.heightIn(min = MinTouchTarget).testTag(ADD_RECEIVER_TAG),
    ) { Text(stringResource(R.string.receiver_add)) }
}
