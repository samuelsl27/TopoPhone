package es.topophone.app.ui.receiver

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import es.topophone.app.R
import es.topophone.app.ui.SectionScaffold
import es.topophone.app.ui.projects.ConfirmDialog
import es.topophone.core.designsystem.component.MinTouchTarget
import es.topophone.core.gnss.rtcm.RtcmKind
import es.topophone.core.gnss.rtcm.RtcmMessages
import es.topophone.core.gnss.rtcm.RtcmStats
import es.topophone.core.gnss.rtcm.RtcmSystem
import es.topophone.core.model.ntrip.NtripProfile
import es.topophone.core.model.preferences.ThemeState
import es.topophone.core.ntrip.NtripFailure
import es.topophone.receiver.session.NtripState
import java.text.NumberFormat

const val CORRECTIONS_STATUS_TAG = "corrections_status"
const val CORRECTIONS_ACTIVE_PROFILE_TAG = "corrections_active_profile"
const val CORRECTIONS_STOP_TAG = "corrections_stop"
const val NO_PROFILES_TAG = "corrections_no_profiles"
const val NEW_PROFILE_TAG = "corrections_new_profile"
const val PASSWORD_PROMPT_FIELD_TAG = "corrections_password_field"
const val PASSWORD_PROMPT_CONNECT_TAG = "corrections_password_connect"
const val NTRIP_BYTES_TAG = "ntrip_bytes"
const val NTRIP_LAST_CORRECTION_TAG = "ntrip_last_correction"
const val RTCM_CARD_TAG = "rtcm_card"
const val RTCM_ERRORS_TAG = "rtcm_errors"

fun rtcmTag(type: Int) = "rtcm_$type"

fun profileTag(id: String) = "ntrip_profile_$id"

fun profileMenuTag(id: String) = "ntrip_profile_menu_$id"

/** Perfiles NTRIP, el perfil en uso y el que necesita que se escriba otra vez su contraseña (S007/RF-1, RF-6). */
data class CorrectionsUiState(
    val profiles: List<NtripProfile> = emptyList(),
    val activeProfileId: String? = null,
    val passwordNeededFor: String? = null,
)

class CorrectionsActions(
    val onConnect: (String) -> Unit = {},
    val onStop: () -> Unit = {},
    val onNewProfile: () -> Unit = {},
    val onEditProfile: (String) -> Unit = {},
    val onDeleteProfile: (String) -> Unit = {},
    val onSubmitPassword: (id: String, password: String) -> Unit = { _, _ -> },
    val onDismissPassword: () -> Unit = {},
)

/**
 * Pantalla Correcciones (S005/RF-14): estado de la conexión NTRIP y lista de perfiles, como la app de
 * referencia con diseño propio. Pulsar un perfil conecta con él (S007/RF-22).
 */
@Composable
fun CorrectionsScreen(
    themeState: ThemeState,
    onToggleSun: () -> Unit,
    onBack: () -> Unit,
    receiver: ReceiverUiState,
    state: CorrectionsUiState,
    actions: CorrectionsActions,
) {
    SectionScaffold(
        title = stringResource(R.string.receiver_menu_corrections),
        themeState = themeState,
        onToggleSun = onToggleSun,
        onNavigateBack = onBack,
        modifier = Modifier.testTag(RECEIVER_CORRECTIONS_SCREEN_TAG),
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = actions.onNewProfile,
                modifier = Modifier.heightIn(min = MinTouchTarget).testTag(NEW_PROFILE_TAG),
            ) { Text(stringResource(R.string.corrections_new_profile)) }
        },
    ) { padding ->
        Column(
            modifier = Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (receiverHomeMode(receiver.snapshot) == ReceiverHomeMode.LIST) {
                Text(
                    stringResource(R.string.receiver_no_receiver_connected),
                    modifier = Modifier.testTag(NO_RECEIVER_TAG),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.error,
                )
            } // S005/RF-16
            StatusCard(receiver, state, actions.onStop)
            (receiver.snapshot.ntrip as? NtripState.Streaming)?.let { RtcmCard(it.rtcm) } // S007/RF-32, RF-33
            Text(stringResource(R.string.corrections_profiles_title), style = MaterialTheme.typography.titleMedium)
            if (state.profiles.isEmpty()) {
                Text(stringResource(R.string.corrections_no_profiles), modifier = Modifier.testTag(NO_PROFILES_TAG)) // S007/RF-1
            } else {
                Card(modifier = Modifier.fillMaxWidth()) {
                    state.profiles.forEachIndexed { index, profile ->
                        if (index > 0) HorizontalDivider()
                        ProfileRow(profile, actions)
                    }
                }
            }
        }
        state.passwordNeededFor?.let { PasswordPrompt(it, actions) }
    }
}

/** Estado del NTRIP con el perfil en uso; mientras está abierto, "Detener" (S007/RF-25). */
@Composable
private fun StatusCard(receiver: ReceiverUiState, state: CorrectionsUiState, onStop: () -> Unit) {
    val ntrip = receiver.snapshot.ntrip
    ReceiverCard(stringResource(R.string.corrections_status_title)) {
        state.profiles.firstOrNull { it.id == state.activeProfileId }?.let { profile ->
            Text(
                stringResource(R.string.corrections_active_profile, profile.draft.name),
                modifier = Modifier.testTag(CORRECTIONS_ACTIVE_PROFILE_TAG),
            )
        }
        Text(ntripStatusText(ntrip), modifier = Modifier.testTag(CORRECTIONS_STATUS_TAG), style = MaterialTheme.typography.bodyLarge)
        if (ntrip.unreachable() && !receiver.internetAvailable) Text(stringResource(R.string.ntrip_no_internet)) // S002/RF-23
        if (ntrip is NtripState.Streaming) StreamingStats(ntrip, receiver.nowMs)
        if (ntrip.open()) {
            OutlinedButton(onClick = onStop, modifier = Modifier.heightIn(min = MinTouchTarget).testTag(CORRECTIONS_STOP_TAG)) {
                Text(stringResource(R.string.corrections_stop))
            }
        }
    }
}

/** Mensajes RTCM 3 recibidos por tipo y los que tenían el CRC erróneo (S007/RF-32, RF-33). */
@Composable
private fun RtcmCard(rtcm: RtcmStats) {
    val numbers = NumberFormat.getIntegerInstance(uiLocale())
    Box(modifier = Modifier.testTag(RTCM_CARD_TAG)) {
        ReceiverCard(stringResource(R.string.rtcm_title)) {
            rtcm.counts.toSortedMap().forEach { (type, count) ->
                val name = rtcmName(RtcmMessages.kind(type))
                Column(modifier = Modifier.semantics(mergeDescendants = true) {}.testTag(rtcmTag(type))) {
                    Text(if (name == null) type.toString() else stringResource(R.string.rtcm_type_named, type, name))
                    Text(stringResource(R.string.rtcm_count, numbers.format(count)), style = MaterialTheme.typography.bodySmall)
                }
            }
            Text(stringResource(R.string.rtcm_crc_errors, rtcm.crcErrors), modifier = Modifier.testTag(RTCM_ERRORS_TAG))
        }
    }
}

/** Nombre de un tipo de mensaje RTCM conocido, o `null` (S007/RF-32). */
@Composable
private fun rtcmName(kind: RtcmKind): String? = when (kind) {
    is RtcmKind.Msm -> stringResource(R.string.rtcm_msm, kind.level, systemName(kind.system))
    is RtcmKind.LegacyObservables -> stringResource(R.string.rtcm_legacy_observables, systemName(kind.system))
    RtcmKind.ReferenceStation -> stringResource(R.string.rtcm_reference_station)
    RtcmKind.AntennaDescriptor -> stringResource(R.string.rtcm_antenna)
    RtcmKind.ReceiverAntennaDescriptor -> stringResource(R.string.rtcm_receiver_antenna)
    is RtcmKind.Ephemeris -> stringResource(R.string.rtcm_ephemeris, systemName(kind.system))
    RtcmKind.GlonassBiases -> stringResource(R.string.rtcm_glonass_biases)
    RtcmKind.Unknown -> null
}

@Composable
private fun systemName(system: RtcmSystem): String = stringResource(
    when (system) {
        RtcmSystem.GPS -> R.string.gnss_gps
        RtcmSystem.GLONASS -> R.string.gnss_glonass
        RtcmSystem.GALILEO -> R.string.gnss_galileo
        RtcmSystem.SBAS -> R.string.gnss_sbas
        RtcmSystem.QZSS -> R.string.gnss_qzss
        RtcmSystem.BEIDOU -> R.string.gnss_beidou
        RtcmSystem.NAVIC -> R.string.gnss_navic
    },
)

/** Nombre, servidor:puerto y punto de montaje (S007/RF-1); pulsarlo conecta (S007/RF-22). */
@Composable
private fun ProfileRow(profile: NtripProfile, actions: CorrectionsActions) {
    val draft = profile.draft
    val mountpoint = draft.mountpoint.ifEmpty { stringResource(R.string.corrections_no_mountpoint) }
    ListItem(
        headlineContent = { Text(draft.name) },
        supportingContent = { Text(stringResource(R.string.corrections_profile_summary, draft.host, draft.port, mountpoint)) },
        trailingContent = { ProfileMenu(profile, actions) },
        modifier = Modifier
            .heightIn(min = MinTouchTarget)
            .clickable { actions.onConnect(profile.id) }
            .testTag(profileTag(profile.id)),
    )
}

/** "⋮" de un perfil: editar y borrar con confirmación (S007/RF-7). */
@Composable
private fun ProfileMenu(profile: NtripProfile, actions: CorrectionsActions) {
    var open by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    IconButton(onClick = { open = true }, modifier = Modifier.size(MinTouchTarget).testTag(profileMenuTag(profile.id))) {
        Icon(
            painterResource(R.drawable.ic_more_vert),
            contentDescription = stringResource(R.string.corrections_profile_menu, profile.draft.name),
        )
    }
    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
        DropdownMenuItem(
            text = { Text(stringResource(R.string.corrections_edit)) },
            onClick = {
                open = false
                actions.onEditProfile(profile.id)
            },
            modifier = Modifier.heightIn(min = MinTouchTarget),
        )
        DropdownMenuItem(
            text = { Text(stringResource(R.string.corrections_delete)) },
            onClick = {
                open = false
                confirmDelete = true
            },
            modifier = Modifier.heightIn(min = MinTouchTarget),
        )
    }
    if (confirmDelete) {
        ConfirmDialog(
            message = stringResource(R.string.corrections_delete_confirm, profile.draft.name),
            confirm = stringResource(R.string.corrections_delete),
            onConfirm = {
                confirmDelete = false
                actions.onDeleteProfile(profile.id)
            },
            onDismiss = { confirmDelete = false },
        )
    }
}

/** La contraseña guardada no se puede leer: se escribe otra vez y se conecta (S007/RF-6). */
@Composable
private fun PasswordPrompt(profileId: String, actions: CorrectionsActions) {
    var password by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = actions.onDismissPassword,
        title = { Text(stringResource(R.string.corrections_password_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.corrections_password_lost))
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text(stringResource(R.string.corrections_password_title)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth().testTag(PASSWORD_PROMPT_FIELD_TAG),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { actions.onSubmitPassword(profileId, password) },
                modifier = Modifier.heightIn(min = MinTouchTarget).testTag(PASSWORD_PROMPT_CONNECT_TAG),
            ) { Text(stringResource(R.string.corrections_connect)) }
        },
        dismissButton = {
            TextButton(onClick = actions.onDismissPassword, modifier = Modifier.heightIn(min = MinTouchTarget)) {
                Text(stringResource(R.string.dialog_cancel))
            }
        },
    )
}

/** Bytes recibidos y segundos desde la última corrección (S002/RF-25). */
@Composable
internal fun StreamingStats(ntrip: NtripState.Streaming, nowMs: Long) {
    val bytes = NumberFormat.getIntegerInstance(uiLocale()).format(ntrip.bytesReceived)
    Text(stringResource(R.string.ntrip_bytes, bytes), modifier = Modifier.testTag(NTRIP_BYTES_TAG))
    val last = ntrip.lastCorrectionAtMs
    val since = if (last == null) {
        stringResource(R.string.ntrip_waiting_first)
    } else {
        stringResource(R.string.ntrip_last_correction, ((nowMs - last).coerceAtLeast(0) / MS_PER_S).toInt())
    }
    Text(since, modifier = Modifier.testTag(NTRIP_LAST_CORRECTION_TAG))
}

/** La conexión NTRIP está abierta: conectando, recibiendo, reintentando o en pausa. */
internal fun NtripState.open(): Boolean =
    this is NtripState.Connecting || this is NtripState.Streaming || this is NtripState.Reconnecting || this == NtripState.Paused

/** No se puede contactar con el caster, se esté reintentando o no (S002/RF-23). */
internal fun NtripState.unreachable(): Boolean =
    this == NtripState.Stopped(NtripFailure.UNREACHABLE) || (this as? NtripState.Reconnecting)?.failure == NtripFailure.UNREACHABLE

/** Estado del NTRIP en texto, también para el resumen de Correcciones (S005/RF-12). */
@Composable
internal fun ntripStatusText(ntrip: NtripState): String = if (ntrip is NtripState.Reconnecting) {
    stringResource(R.string.ntrip_reconnecting, ntrip.attempt) // S007/RF-25
} else {
    ntripStatusResource(ntrip)
}

@Composable
private fun ntripStatusResource(ntrip: NtripState): String = stringResource(
    when (ntrip) {
        is NtripState.Reconnecting -> R.string.ntrip_state_connecting
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
            NtripFailure.TLS_INVALID -> R.string.ntrip_error_tls // S007/RF-28
        }
    },
)

private const val MS_PER_S = 1_000L
