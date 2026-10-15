package es.topophone.app.ui.receiver

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import es.topophone.app.R
import es.topophone.app.ui.SectionScaffold
import es.topophone.core.designsystem.component.MinTouchTarget
import es.topophone.core.gnss.nmea.ReplaySpeed
import es.topophone.core.model.preferences.ThemeState
import es.topophone.receiver.session.ConnectFailure
import es.topophone.receiver.session.ConnectionState
import es.topophone.receiver.session.ReplayInfo

/** Acciones de la pantalla Receptor. */
class ReceiverActions(
    val onRequestPermission: () -> Unit = {},
    val onOpenAppSettings: () -> Unit = {},
    val onEnableBluetooth: () -> Unit = {},
    val onConnect: (String) -> Unit = {},
    val onDisconnect: () -> Unit = {},
    val onReconnect: () -> Unit = {},
    val onFormChange: (NtripForm) -> Unit = {},
    val onConnectNtrip: () -> Unit = {},
    val onDisconnectNtrip: () -> Unit = {},
    val onPoleHeightChange: (String) -> Unit = {},
    val onShareSession: () -> Unit = {},
    val onOpenSessions: () -> Unit = {},
    val onReplaySpeed: (ReplaySpeed) -> Unit = {},
    val onReplayLoop: (Boolean) -> Unit = {},
    val onStopReplay: () -> Unit = {},
    val onReplayAgain: () -> Unit = {},
)

const val PERMISSION_REQUEST_TAG = "bt_permission_request"
const val OPEN_SETTINGS_TAG = "bt_open_settings"
const val ENABLE_BLUETOOTH_TAG = "bt_enable"
const val CONNECTION_STATUS_TAG = "connection_status"
const val DISCONNECT_TAG = "receiver_disconnect"
const val RECONNECT_TAG = "receiver_reconnect"
const val REPLAY_SPEED_X1_TAG = "replay_speed_x1"
const val REPLAY_SPEED_X5_TAG = "replay_speed_x5"
const val REPLAY_LOOP_TAG = "replay_loop"
const val REPLAY_STOP_TAG = "replay_stop"
const val REPLAY_AGAIN_TAG = "replay_again"

fun deviceTag(address: String) = "device_$address"

/** Entrada de la sección Receptor: une el ViewModel con los permisos y las intenciones de Android. */
@Composable
fun ReceiverRoute(
    themeState: ThemeState,
    onToggleSun: () -> Unit,
    onOpenSessions: () -> Unit = {},
    viewModel: ReceiverViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { viewModel.refreshBluetooth() }
    val enableLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { viewModel.refreshBluetooth() }
    LifecycleResumeEffect(Unit) {
        viewModel.refreshBluetooth()
        onPauseOrDispose {}
    }
    ReceiverScreen(
        themeState = themeState,
        onToggleSun = onToggleSun,
        state = state,
        actions = ReceiverActions(
            onRequestPermission = {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) permissionLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
            },
            onOpenAppSettings = {
                context.startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)),
                )
            },
            onEnableBluetooth = { enableLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)) },
            onConnect = viewModel::connect,
            onDisconnect = viewModel::disconnect,
            onReconnect = viewModel::reconnect,
            onFormChange = viewModel::updateForm,
            onConnectNtrip = viewModel::connectNtrip,
            onDisconnectNtrip = viewModel::disconnectNtrip,
            onPoleHeightChange = viewModel::updatePoleHeight,
            onShareSession = { viewModel.latestSession()?.let { shareSession(context, it) } },
            onOpenSessions = onOpenSessions,
            onReplaySpeed = viewModel::setReplaySpeed,
            onReplayLoop = viewModel::setReplayLoop,
            onStopReplay = viewModel::stopReplay,
            onReplayAgain = viewModel::replayAgain,
        ),
    )
}

/** Pantalla Receptor sin estado propio (S002). */
@Composable
fun ReceiverScreen(themeState: ThemeState, onToggleSun: () -> Unit, state: ReceiverUiState, actions: ReceiverActions) {
    SectionScaffold(title = stringResource(R.string.nav_receiver), themeState = themeState, onToggleSun = onToggleSun) { padding ->
        Column(
            modifier = Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            ConnectionCard(state, actions)
            StatusCard(state.snapshot)
            NtripCard(state, actions)
            CoordinatesCard(state, actions.onPoleHeightChange)
            SessionCard(state.sessionAvailable, actions.onShareSession, actions.onOpenSessions)
        }
    }
}

@Composable
internal fun ReceiverCard(title: String, content: @Composable () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
private fun ConnectionCard(state: ReceiverUiState, actions: ReceiverActions) {
    ReceiverCard(stringResource(R.string.receiver_connection_title)) {
        val connection = state.snapshot.connection
        val replay = state.snapshot.replay
        val active =
            connection is ConnectionState.Connecting || connection is ConnectionState.Receiving || connection is ConnectionState.NoData
        if (replay != null && active) {
            Text(
                stringResource(R.string.receiver_replaying, replay.name),
                modifier = Modifier.testTag(CONNECTION_STATUS_TAG),
                style = MaterialTheme.typography.bodyLarge,
            ) // S003/RF-35
            ReplayControls(replay, actions)
            BluetoothDevicesSection(state.bluetooth, actions) // S003/RF-43: conectar un receptor termina la reproducción
            return@ReceiverCard
        }
        Text(connectionText(connection), modifier = Modifier.testTag(CONNECTION_STATUS_TAG), style = MaterialTheme.typography.bodyLarge)
        if (connection is ConnectionState.Failed) Text(stringResource(R.string.receiver_connect_failed_causes))
        if (state.snapshot.recordingStoppedLowSpace) {
            Text(stringResource(R.string.receiver_recording_stopped_low_space), color = MaterialTheme.colorScheme.error)
        } // S003/RF-28
        when (connection) {
            is ConnectionState.Connecting, is ConnectionState.Receiving, is ConnectionState.NoData -> OutlinedButton(
                onClick = actions.onDisconnect,
                modifier = Modifier.heightIn(min = MinTouchTarget).testTag(DISCONNECT_TAG),
            ) { Text(stringResource(R.string.receiver_disconnect)) }
            is ConnectionState.Lost -> Button(
                onClick = actions.onReconnect,
                modifier = Modifier.heightIn(min = MinTouchTarget).testTag(RECONNECT_TAG),
            ) { Text(stringResource(R.string.receiver_reconnect)) }
            is ConnectionState.ReplayFinished -> Button(
                onClick = actions.onReplayAgain,
                modifier = Modifier.heightIn(min = MinTouchTarget).testTag(REPLAY_AGAIN_TAG),
            ) { Text(stringResource(R.string.replay_again)) } // S003/RF-40
            else -> Unit
        }
        if (!active) {
            BluetoothDevicesSection(state.bluetooth, actions)
        }
    }
}

/** Velocidad, bucle y Detener de la reproducción en curso (S003/RF-37, S003/RF-39, S003/RF-41). */
@Composable
private fun ReplayControls(replay: ReplayInfo, actions: ReceiverActions) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        SingleChoiceSegmentedButtonRow {
            ReplaySpeed.entries.forEachIndexed { index, speed ->
                SegmentedButton(
                    selected = replay.speed == speed,
                    onClick = { actions.onReplaySpeed(speed) },
                    shape = SegmentedButtonDefaults.itemShape(index, ReplaySpeed.entries.size),
                    modifier = Modifier.heightIn(min = MinTouchTarget).testTag(
                        if (speed ==
                            ReplaySpeed.X1
                        ) {
                            REPLAY_SPEED_X1_TAG
                        } else {
                            REPLAY_SPEED_X5_TAG
                        },
                    ),
                ) { Text(stringResource(if (speed == ReplaySpeed.X1) R.string.replay_speed_x1 else R.string.replay_speed_x5)) }
            }
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Switch(
            checked = replay.loop,
            onCheckedChange = actions.onReplayLoop,
            modifier = Modifier.testTag(REPLAY_LOOP_TAG),
        )
        Text(stringResource(R.string.replay_loop))
    }
    OutlinedButton(onClick = actions.onStopReplay, modifier = Modifier.heightIn(min = MinTouchTarget).testTag(REPLAY_STOP_TAG)) {
        Text(stringResource(R.string.replay_stop))
    }
}

@Composable
private fun connectionText(connection: ConnectionState): String = when (connection) {
    ConnectionState.Disconnected -> stringResource(R.string.receiver_state_disconnected)
    is ConnectionState.Connecting -> stringResource(R.string.receiver_state_connecting, connection.description)
    is ConnectionState.Receiving -> stringResource(R.string.receiver_state_receiving, connection.description)
    is ConnectionState.NoData -> stringResource(R.string.receiver_state_no_data, connection.description)
    is ConnectionState.Lost -> stringResource(R.string.receiver_state_lost, connection.description)
    is ConnectionState.ReplayFinished -> stringResource(R.string.receiver_replay_finished, connection.description) // S003/RF-40
    is ConnectionState.Failed -> stringResource(
        if (connection.failure == ConnectFailure.TIMEOUT) R.string.receiver_state_failed_timeout else R.string.receiver_state_failed_error,
    )
}

@Composable
private fun BluetoothDevicesSection(bluetooth: BluetoothUi, actions: ReceiverActions) {
    when (bluetooth) {
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
            if (bluetooth.devices.isEmpty()) {
                Text(stringResource(R.string.receiver_no_bonded_devices))
            } else {
                Text(stringResource(R.string.receiver_choose_device), style = MaterialTheme.typography.bodyMedium)
                bluetooth.devices.forEach { device ->
                    OutlinedButton(
                        onClick = { actions.onConnect(device.address) },
                        modifier = Modifier.fillMaxWidth().heightIn(min = MinTouchTarget).testTag(deviceTag(device.address)),
                    ) {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Text(device.name, style = MaterialTheme.typography.bodyLarge)
                            Text(device.address, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }
}
