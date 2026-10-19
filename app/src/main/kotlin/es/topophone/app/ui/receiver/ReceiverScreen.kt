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
import es.topophone.receiver.session.ReceiverSnapshot
import es.topophone.receiver.session.ReplayInfo

/** Acciones de la pantalla Receptor. */
class ReceiverActions(
    val onRequestPermission: () -> Unit = {},
    val onOpenAppSettings: () -> Unit = {},
    val onEnableBluetooth: () -> Unit = {},
    val onConnect: (String) -> Unit = {},
    val onDisconnect: () -> Unit = {},
    val onReconnect: () -> Unit = {},
    val onPoleHeightChange: (String) -> Unit = {},
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
    val onOpenStatus: () -> Unit = {},
    val onOpenCoordinates: () -> Unit = {},
    val onOpenCorrections: () -> Unit = {},
    val onOpenBluetoothSettings: () -> Unit = {},
    /** S015/RF-17. */
    val onDismissBaseChange: (Int) -> Unit = {},
    /** S015/RF-15. */
    val onDismissLongBaseline: (Int) -> Unit = {},
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
const val RECEIVER_LIST_TAG = "receiver_list"
const val RECEIVER_DASHBOARD_TAG = "receiver_dashboard"
const val TILT_TAG = "receiver_tilt"
const val ENABLE_NOTIFICATIONS_TAG = "enable_notifications"
const val ADD_RECEIVER_TAG = "add_receiver"

fun deviceTag(address: String) = "device_$address"

/** Qué muestra la raíz de Receptor (S005/RF-1, RF-5). */
enum class ReceiverHomeMode { LIST, DASHBOARD }

/**
 * La pantalla del receptor mientras hay un receptor conectado, conectándose o reconectándose, o una
 * reproducción en curso (que pasa por los mismos estados); la lista de receptores en los demás casos.
 */
fun receiverHomeMode(snapshot: ReceiverSnapshot): ReceiverHomeMode = when (snapshot.connection) {
    is ConnectionState.Connecting,
    is ConnectionState.Receiving,
    is ConnectionState.NoData,
    is ConnectionState.Reconnecting,
    -> ReceiverHomeMode.DASHBOARD
    else -> ReceiverHomeMode.LIST
}

/** Entrada de la sección Receptor: une el ViewModel con los permisos y las intenciones de Android. */
@Composable
fun ReceiverRoute(
    page: ReceiverPage,
    themeState: ThemeState,
    onToggleSun: () -> Unit,
    navigation: ReceiverNavigation = ReceiverNavigation(),
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
    // La pantalla Correcciones tiene su propio ViewModel (perfiles NTRIP, S007); la página no cambia en la misma ruta.
    val correctionsViewModel: CorrectionsViewModel? = if (page == ReceiverPage.CORRECTIONS) hiltViewModel() else null
    val corrections = correctionsViewModel?.state?.collectAsStateWithLifecycle()?.value ?: CorrectionsUiState()
    ReceiverPageScreen(
        page = page,
        themeState = themeState,
        onToggleSun = onToggleSun,
        state = state,
        onBack = navigation.onBack,
        corrections = corrections,
        correctionsActions = correctionsViewModel?.let { vm ->
            CorrectionsActions(
                onConnect = vm::connect,
                onStop = vm::stop,
                onNewProfile = navigation.onNewProfile,
                onEditProfile = navigation.onEditProfile,
                onDeleteProfile = vm::delete,
                onSubmitPassword = vm::submitPassword,
                onDismissPassword = vm::dismissPassword,
            )
        } ?: CorrectionsActions(),
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
            onPoleHeightChange = viewModel::updatePoleHeight,
            onOpenSessions = navigation.onOpenSessions,
            onOpenStatus = { navigation.onOpen(ReceiverPage.STATUS) }, // S005/RF-14
            onOpenCoordinates = { navigation.onOpen(ReceiverPage.COORDINATES) },
            onOpenCorrections = { navigation.onOpen(ReceiverPage.CORRECTIONS) },
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
            onDismissBaseChange = viewModel::dismissBaseChange,
            onDismissLongBaseline = viewModel::dismissLongBaseline,
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

/** Raíz de Receptor sin estado propio: la lista de receptores o la pantalla del receptor (S005/RF-1, RF-5). */
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
        // La raíz cambia de contenido con el estado, sin navegar (S005/RF-1, RF-5).
        val tag = when (receiverHomeMode(state.snapshot)) {
            ReceiverHomeMode.LIST -> RECEIVER_LIST_TAG
            ReceiverHomeMode.DASHBOARD -> RECEIVER_DASHBOARD_TAG
        }
        Column(
            modifier = Modifier.padding(padding).verticalScroll(scrollState).padding(16.dp).testTag(tag),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            when (receiverHomeMode(state.snapshot)) {
                ReceiverHomeMode.LIST -> ReceiverList(state, actions)
                ReceiverHomeMode.DASHBOARD -> {
                    ReceiverHeader(state, actions)
                    ReceiverMenu(state, actions)
                }
            }
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

/** Intento, cuenta atrás, Bluetooth apagado, "Reintentar ahora" y "Desconectar" (S004/RF-15, RF-16, RF-21, RF-22). */
@Composable
internal fun ReconnectingDetails(state: ConnectionState.Reconnecting, nowMs: Long, actions: ReceiverActions) {
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
internal fun ConnectionIndicators(state: ReceiverUiState) {
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
internal fun ReplayControls(replay: ReplayInfo, actions: ReceiverActions) {
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
internal fun connectionText(connection: ConnectionState): String = when (connection) {
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
