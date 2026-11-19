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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import es.topophone.app.R
import es.topophone.app.ui.SectionScaffold
import es.topophone.app.ui.receiver.ReceiverCard
import es.topophone.app.ui.receiver.uiLocale
import es.topophone.core.designsystem.component.MinTouchTarget
import es.topophone.core.model.format.LengthFormat
import es.topophone.core.model.guide.EquipmentProfile
import es.topophone.core.model.preferences.ThemeState
import es.topophone.receiver.experimental.ReachInfoReading
import es.topophone.receiver.experimental.ReachPart
import kotlinx.serialization.Serializable

const val PANEL_NOT_APPLICABLE_TAG = "experimental_not_applicable"
const val PANEL_INACTIVE_TAG = "experimental_inactive"
const val PANEL_ADDRESS_FIELD_TAG = "experimental_address_field"
const val PANEL_ADDRESS_SAVE_TAG = "experimental_address_save"
const val PANEL_ADDRESS_ERROR_TAG = "experimental_address_error"
const val PANEL_LOADING_TAG = "experimental_loading"
const val PANEL_MODEL_TAG = "experimental_model"
const val PANEL_SERIAL_TAG = "experimental_serial"
const val PANEL_FIRMWARE_TAG = "experimental_firmware"
const val PANEL_ROLE_TAG = "experimental_role"
const val PANEL_ANTENNA_TAG = "experimental_antenna"
const val PANEL_IDENTITY_INCOMPATIBLE_TAG = "experimental_identity_incompatible"
const val PANEL_DEVICE_INCOMPATIBLE_TAG = "experimental_device_incompatible"
const val PANEL_RETRY_TAG = "experimental_retry"
const val PANEL_FILL_PROFILE_TAG = "experimental_fill_profile"
const val PANEL_PROFILE_DIALOG_TAG = "experimental_profile_dialog"
const val PANEL_PROFILE_CONFIRM_TAG = "experimental_profile_confirm"
const val PANEL_PROFILE_CANCEL_TAG = "experimental_profile_cancel"
const val PANEL_PROFILE_SAVED_TAG = "experimental_profile_saved"

/** La ruta del panel del módulo dentro del grafo del receptor (S044/RF-6). */
@Serializable
data object ExperimentalPanelDestination

/** Lo que el panel pide al ViewModel. */
class PanelActions(
    val onRefresh: () -> Unit = {},
    val onSaveAddress: (String) -> Unit = {},
    val onFillProfile: () -> Unit = {},
    val onConfirmProfile: () -> Unit = {},
    val onCancelProfile: () -> Unit = {},
    val onDismissSaved: () -> Unit = {},
)

@Composable
fun ExperimentalPanelRoute(themeState: ThemeState, onToggleSun: () -> Unit, onBack: () -> Unit) {
    val viewModel = hiltViewModel<ExperimentalPanelViewModel>()
    val state by viewModel.state.collectAsStateWithLifecycle()
    ExperimentalPanelScreen(
        state = state,
        actions = PanelActions(
            onRefresh = viewModel::onRefresh,
            onSaveAddress = viewModel::onSaveAddress,
            onFillProfile = viewModel::onFillProfile,
            onConfirmProfile = viewModel::onConfirmProfile,
            onCancelProfile = viewModel::onCancelProfile,
            onDismissSaved = viewModel::onDismissSaved,
        ),
        themeState = themeState,
        onToggleSun = onToggleSun,
        onBack = onBack,
    )
}

/** El panel del módulo experimental: la información leída del receptor y el perfil de equipo (S044/RF-6, RF-11, RF-12, RF-15). */
@Composable
fun ExperimentalPanelScreen(
    state: PanelUiState,
    actions: PanelActions,
    themeState: ThemeState,
    onToggleSun: () -> Unit,
    onBack: () -> Unit,
) {
    SectionScaffold(
        title = stringResource(R.string.experimental_panel_title),
        themeState = themeState,
        onToggleSun = onToggleSun,
        onNavigateBack = onBack,
    ) { padding ->
        Column(
            modifier = Modifier.padding(padding).padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when (val source = state.source) {
                ReachSource.Inactive -> Message(R.string.experimental_inactive, PANEL_INACTIVE_TAG)
                ReachSource.NotApplicable -> Message(R.string.experimental_not_applicable, PANEL_NOT_APPLICABLE_TAG)
                is ReachSource.NeedsAddress -> AddressCard(source.key, state.addressRejected, actions.onSaveAddress)
                is ReachSource.Ready -> InfoCard(state, actions)
            }
        }
    }
    state.profileProposal?.let { ProfileDialog(it, actions) }
}

@Composable
private fun Message(text: Int, tag: String) {
    Text(stringResource(text), modifier = Modifier.testTag(tag))
}

@Composable
private fun AddressCard(key: String, rejected: Boolean, onSave: (String) -> Unit) {
    ReceiverCard(stringResource(R.string.experimental_address_title)) {
        var address by rememberSaveable(key) { mutableStateOf("") }
        Text(stringResource(R.string.experimental_address_hint), style = MaterialTheme.typography.bodyMedium)
        OutlinedTextField(
            value = address,
            onValueChange = { address = it },
            label = { Text(stringResource(R.string.experimental_address_label)) },
            singleLine = true,
            isError = rejected,
            modifier = Modifier.fillMaxWidth().testTag(PANEL_ADDRESS_FIELD_TAG),
        )
        if (rejected) {
            Text(
                stringResource(R.string.experimental_address_rejected),
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.testTag(PANEL_ADDRESS_ERROR_TAG),
            )
        }
        Button(
            onClick = { onSave(address) },
            modifier = Modifier.fillMaxWidth().heightIn(min = MinTouchTarget).testTag(PANEL_ADDRESS_SAVE_TAG),
        ) { Text(stringResource(R.string.experimental_address_save)) }
    }
}

@Composable
private fun InfoCard(state: PanelUiState, actions: PanelActions) {
    ReceiverCard(stringResource(R.string.experimental_info_title)) {
        when (val info = state.info) {
            ReachInfoState.Idle, ReachInfoState.Loading -> Message(R.string.experimental_loading, PANEL_LOADING_TAG)
            is ReachInfoState.Loaded -> {
                Readings(info.reading)
                OutlinedButton(
                    onClick = actions.onRefresh,
                    modifier = Modifier.fillMaxWidth().heightIn(min = MinTouchTarget).testTag(PANEL_RETRY_TAG),
                ) { Text(stringResource(R.string.experimental_reload)) }
                OutlinedButton(
                    onClick = actions.onFillProfile,
                    enabled = info.reading.identity is ReachPart.Ok,
                    modifier = Modifier.fillMaxWidth().heightIn(min = MinTouchTarget).testTag(PANEL_FILL_PROFILE_TAG),
                ) { Text(stringResource(R.string.experimental_fill_profile)) }
            }
        }
        if (state.profileSaved) {
            Text(
                stringResource(R.string.experimental_profile_saved),
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.heightIn(
                    min = MinTouchTarget,
                ).clickable(onClick = actions.onDismissSaved).testTag(PANEL_PROFILE_SAVED_TAG),
            )
        }
    }
}

/** Cada parte con su valor, o «Receptor no compatible» solo en la parte que no se entendió (S044/RF-15). */
@Composable
private fun Readings(reading: ReachInfoReading) {
    when (val identity = reading.identity) {
        is ReachPart.Ok -> {
            InfoRow(R.string.experimental_label_model, identity.value.model, PANEL_MODEL_TAG)
            InfoRow(R.string.experimental_label_serial, identity.value.serial, PANEL_SERIAL_TAG)
            InfoRow(R.string.experimental_label_firmware, identity.value.firmware, PANEL_FIRMWARE_TAG)
        }
        is ReachPart.Incompatible -> Incompatible(PANEL_IDENTITY_INCOMPATIBLE_TAG)
    }
    when (val device = reading.device) {
        is ReachPart.Ok -> {
            device.value.role?.let { InfoRow(R.string.experimental_label_role, it, PANEL_ROLE_TAG) }
            InfoRow(R.string.experimental_label_antenna, LengthFormat.metres(device.value.antennaHeightM, uiLocale()), PANEL_ANTENNA_TAG)
        }
        is ReachPart.Incompatible -> Incompatible(PANEL_DEVICE_INCOMPATIBLE_TAG)
    }
}

@Composable
private fun InfoRow(label: Int, value: String, tag: String) {
    Text(stringResource(R.string.experimental_info_row, stringResource(label), value), modifier = Modifier.testTag(tag))
}

@Composable
private fun Incompatible(tag: String) {
    Text(stringResource(R.string.experimental_incompatible), color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag(tag))
}

@Composable
private fun ProfileDialog(proposal: EquipmentProfile, actions: PanelActions) {
    AlertDialog(
        onDismissRequest = actions.onCancelProfile,
        title = { Text(stringResource(R.string.experimental_profile_dialog_title)) },
        text = {
            Text(
                stringResource(R.string.experimental_profile_dialog_body, proposal.model, proposal.serial, proposal.firmware),
                modifier = Modifier.testTag(PANEL_PROFILE_DIALOG_TAG),
            )
        },
        confirmButton = {
            TextButton(
                onClick = actions.onConfirmProfile,
                modifier = Modifier.heightIn(min = MinTouchTarget).testTag(PANEL_PROFILE_CONFIRM_TAG),
            ) { Text(stringResource(R.string.experimental_profile_save)) }
        },
        dismissButton = {
            TextButton(
                onClick = actions.onCancelProfile,
                modifier = Modifier.heightIn(min = MinTouchTarget).testTag(PANEL_PROFILE_CANCEL_TAG),
            ) { Text(stringResource(R.string.experimental_profile_cancel)) }
        },
    )
}
