package es.topophone.app.ui.receiver

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import es.topophone.app.R
import es.topophone.app.receiver.notificationSettingsIntent
import es.topophone.app.ui.SectionScaffold
import es.topophone.core.designsystem.component.MinTouchTarget
import es.topophone.core.designsystem.theme.LocalStatusPalette
import es.topophone.core.designsystem.theme.StatusTone
import es.topophone.core.gnss.nmea.ReplaySpeed
import es.topophone.core.gnss.nmea.TiltState
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
    val onRetryNow: () -> Unit = {},
    val onAllowNotifications: () -> Unit = {},
    val onDeclineNotifications: () -> Unit = {},
    val onEnableNotifications: () -> Unit = {},
    val onAddReceiver: () -> Unit = {},
    val onOpenBluetoothSettings: () -> Unit = {},
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
const val RECONNECT_DETAIL_TAG = "reconnect_detail"
const val RETRY_NOW_TAG = "retry_now"
const val NMEA_MISSING_TAG = "nmea_missing"
const val RECORDING_TAG = "receiver_recording"
const val TILT_TAG = "receiver_tilt"
const val ENABLE_NOTIFICATIONS_TAG = "enable_notifications"
const val ADD_RECEIVER_TAG = "add_receiver"

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
    val notificationsLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { viewModel.refreshNotifications() }
    val chooserLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        viewModel.receiverChosen(result.resultCode, result.data) // S004/RF-2 a RF-4
    }
    val chooser = state.pairing.chooser
    LaunchedEffect(chooser) {
        if (chooser != null) {
            viewModel.chooserOpened()
            chooserLauncher.launch(IntentSenderRequest.Builder(chooser).build()) // S004/RF-1
        }
    }
    LifecycleResumeEffect(Unit) {
        viewModel.refreshBluetooth()
        viewModel.refreshNotifications()
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
            onRetryNow = viewModel::retryNow,
            onAllowNotifications = {
                viewModel.notificationsAnswered()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    notificationsLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            },
            onDeclineNotifications = viewModel::notificationsAnswered,
            onEnableNotifications = { context.startActivity(notificationSettingsIntent(context.packageName)) },
            onAddReceiver = viewModel::addReceiver,
            onOpenBluetoothSettings = {
                try {
                    context.startActivity(bluetoothSettingsIntent())
                } catch (_: ActivityNotFoundException) {
                    // Sin pantalla de ajustes de Bluetooth: el texto de la tarjeta sigue indicando qué hacer.
                }
            },
        ),
    )
}

/** Ajustes de Bluetooth del móvil: la vía de S002 para emparejar, sin búsqueda del sistema (S004/RF-1). */
internal fun bluetoothSettingsIntent(): Intent = Intent(Settings.ACTION_BLUETOOTH_SETTINGS)

/** Pantalla Receptor sin estado propio (S002). */
@Composable
fun ReceiverScreen(themeState: ThemeState, onToggleSun: () -> Unit, state: ReceiverUiState, actions: ReceiverActions) {
    val scrollState = rememberScrollState()
    // S003/RF-35: al empezar una reproducción se sube a la tarjeta Conexión, donde están sus controles.
    val replayName = state.snapshot.replay?.name
    var scrolledForReplay by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(replayName) {
        if (replayName != null && replayName != scrolledForReplay) {
            scrollState.animateScrollTo(0)
            scrolledForReplay = replayName
        }
    }
    SectionScaffold(title = stringResource(R.string.nav_receiver), themeState = themeState, onToggleSun = onToggleSun) { padding ->
        Column(
            modifier = Modifier.padding(padding).verticalScroll(scrollState).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            ConnectionCard(state, actions)
            StatusCard(state.snapshot)
            NtripCard(state, actions)
            CoordinatesCard(state, actions.onPoleHeightChange)
            SessionCard(state.sessionAvailable, actions.onShareSession, actions.onOpenSessions)
        }
    }
    if (state.askNotifications) NotificationsDialog(actions)
}

/** Explica para qué es el permiso de notificaciones antes de que Android lo pida (S004/RF-5). */
@Composable
private fun NotificationsDialog(actions: ReceiverActions) {
    AlertDialog(
        onDismissRequest = actions.onDeclineNotifications,
        title = { Text(stringResource(R.string.notifications_permission_title)) },
        text = { Text(stringResource(R.string.notifications_permission_rationale)) },
        confirmButton = {
            TextButton(onClick = actions.onAllowNotifications, modifier = Modifier.heightIn(min = MinTouchTarget)) {
                Text(stringResource(R.string.notifications_permission_continue))
            }
        },
        dismissButton = {
            TextButton(onClick = actions.onDeclineNotifications, modifier = Modifier.heightIn(min = MinTouchTarget)) {
                Text(stringResource(R.string.notifications_permission_not_now))
            }
        },
    )
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
            ConnectionIndicators(state)
            ReplayControls(replay, actions)
            BluetoothDevicesSection(state, actions) // S003/RF-43: conectar un receptor termina la reproducción
            return@ReceiverCard
        }
        Text(connectionText(connection), modifier = Modifier.testTag(CONNECTION_STATUS_TAG), style = MaterialTheme.typography.bodyLarge)
        ConnectionIndicators(state)
        if (connection is ConnectionState.Failed) {
            // S004/RF-27: un rechazo inmediato tiene una causa más probable que las demás.
            val causes = if (connection.failure ==
                ConnectFailure.BUSY
            ) {
                R.string.receiver_busy_cause
            } else {
                R.string.receiver_connect_failed_causes
            }
            Text(stringResource(causes))
        }
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
            is ConnectionState.Reconnecting -> ReconnectingDetails(connection, state.nowMs, actions)
            is ConnectionState.ReplayFinished -> Button(
                onClick = actions.onReplayAgain,
                modifier = Modifier.heightIn(min = MinTouchTarget).testTag(REPLAY_AGAIN_TAG),
            ) { Text(stringResource(R.string.replay_again)) } // S003/RF-40
            else -> Unit
        }
        if (!active) {
            BluetoothDevicesSection(state, actions)
        }
    }
}

/** Intento, cuenta atrás, Bluetooth apagado, "Reintentar ahora" y "Desconectar" (S004/RF-15, RF-16, RF-21, RF-22). */
@Composable
private fun ReconnectingDetails(state: ConnectionState.Reconnecting, nowMs: Long, actions: ReceiverActions) {
    val next = state.nextAttemptAtMs
    val detail = if (next == null) {
        stringResource(R.string.receiver_reconnect_trying, state.attempt)
    } else {
        val seconds = ((next - nowMs).coerceAtLeast(0) + MS_PER_S - 1) / MS_PER_S
        stringResource(R.string.receiver_reconnect_waiting, state.attempt, seconds.toInt())
    }
    Text(detail, modifier = Modifier.testTag(RECONNECT_DETAIL_TAG))
    if (state.bluetoothOff) Text(stringResource(R.string.receiver_reconnect_bluetooth_off), color = MaterialTheme.colorScheme.error)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = actions.onRetryNow, modifier = Modifier.heightIn(min = MinTouchTarget).testTag(RETRY_NOW_TAG)) {
            Text(stringResource(R.string.receiver_retry_now))
        }
        OutlinedButton(onClick = actions.onDisconnect, modifier = Modifier.heightIn(min = MinTouchTarget).testTag(DISCONNECT_TAG)) {
            Text(stringResource(R.string.receiver_disconnect))
        }
    }
}

private const val MS_PER_S = 1_000L

/** "Grabando" e inclinación, que antes iban en la franja de estado (S003/RF-20, RF-22). */
@Composable
private fun ConnectionIndicators(state: ReceiverUiState) {
    val tilt = state.snapshot.epoch?.tilt?.state
    if (!state.snapshot.recording && tilt == null) return
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        if (state.snapshot.recording) {
            Row(
                modifier = Modifier.semantics(mergeDescendants = true) {}.testTag(RECORDING_TAG),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Box(Modifier.size(10.dp).background(LocalStatusPalette.current.colorsFor(StatusTone.NEGATIVE).container, CircleShape))
                Text(stringResource(R.string.status_bar_recording))
            }
        } // S003/RF-20
        tilt?.let {
            Text(stringResource(R.string.status_bar_tilt, stringResource(tiltNameRes(it))), modifier = Modifier.testTag(TILT_TAG))
        } // S003/RF-22
    }
}

/** Nombre del estado de la inclinación de ETC (S003/RF-22). */
internal fun tiltNameRes(state: TiltState): Int = when (state) {
    TiltState.OFF -> R.string.tilt_off
    TiltState.FATAL -> R.string.tilt_fatal
    TiltState.SETUP -> R.string.tilt_setup
    TiltState.ALIGNMENT -> R.string.tilt_alignment
    TiltState.COMPENSATING -> R.string.tilt_compensating
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
    // Toda la fila, interruptor y texto, se pulsa (RNF-T8: 56 dp, con guantes).
    Row(
        modifier = Modifier
            .heightIn(min = MinTouchTarget)
            .toggleable(value = replay.loop, role = Role.Switch, onValueChange = actions.onReplayLoop)
            .testTag(REPLAY_LOOP_TAG),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Switch(checked = replay.loop, onCheckedChange = null)
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
    is ConnectionState.Reconnecting -> stringResource(R.string.receiver_state_reconnecting, connection.description) // S004/RF-13
    is ConnectionState.ReplayFinished -> stringResource(R.string.receiver_replay_finished, connection.description) // S003/RF-40
    is ConnectionState.Failed -> stringResource(
        when (connection.failure) {
            ConnectFailure.TIMEOUT -> R.string.receiver_state_failed_timeout
            ConnectFailure.BUSY -> R.string.receiver_state_failed_busy // S004/RF-27
            ConnectFailure.ERROR -> R.string.receiver_state_failed_error
        },
    )
}

@Composable
private fun BluetoothDevicesSection(state: ReceiverUiState, actions: ReceiverActions) {
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
            AddReceiver(state, actions)
        }
    }
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
