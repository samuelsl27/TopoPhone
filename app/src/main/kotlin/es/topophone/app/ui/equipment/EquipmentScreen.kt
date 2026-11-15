package es.topophone.app.ui.equipment

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import es.topophone.app.R
import es.topophone.app.ui.receiver.ReceiverCard
import es.topophone.app.ui.receiver.uiLocale
import es.topophone.core.designsystem.component.MinTouchTarget
import es.topophone.core.model.format.LengthFormat
import es.topophone.core.model.guide.EquipmentProfile

const val EQUIPMENT_SCREEN_TAG = "equipment_screen"
const val EQUIPMENT_NOT_APPLICABLE_TAG = "equipment_not_applicable"
const val EQUIPMENT_MODEL_TAG = "equipment_model"
const val EQUIPMENT_SERIAL_TAG = "equipment_serial"
const val EQUIPMENT_FIRMWARE_TAG = "equipment_firmware"
const val EQUIPMENT_SAVE_TAG = "equipment_save"
const val EQUIPMENT_USE_PROFILE_TAG = "equipment_use_profile"
const val EQUIPMENT_MESSAGE_TAG = "equipment_message"
const val EQUIPMENT_CURRENT_PROFILE_TAG = "equipment_current_profile"

fun equipmentCatalogTag(ref: String) = "equipment_catalog_$ref"

/** Lo que «Datos del receptor» pide al ViewModel (S043/RF-18, RF-21). */
class EquipmentActions(
    val onSave: (model: String, serial: String, firmware: String) -> Unit = { _, _, _ -> },
    /** Devuelve el nombre del modelo elegido, para el campo. */
    val onPick: (ref: String) -> String? = { null },
    val onUseProfile: () -> Unit = {},
    val onDismissMessage: () -> Unit = {},
)

@Composable
private fun messageText(message: EquipmentMessage): String = when (message) {
    EquipmentMessage.Saved -> stringResource(R.string.equipment_saved)
    is EquipmentMessage.ProfileUsed -> stringResource(R.string.equipment_profile_used, message.profileName)
    EquipmentMessage.Locked -> stringResource(R.string.equipment_locked)
}

/** «Datos del receptor»: el formulario de modelo, serie y firmware, el catálogo y el perfil de antena propuesto (S043/RF-18, RF-21). */
@Composable
fun EquipmentCard(state: EquipmentUiState, actions: EquipmentActions) {
    ReceiverCard(stringResource(R.string.equipment_title)) {
        if (!state.applies) {
            Text(stringResource(R.string.equipment_not_applicable), modifier = Modifier.testTag(EQUIPMENT_NOT_APPLICABLE_TAG))
            return@ReceiverCard
        }
        val saved = state.saved ?: EquipmentProfile.EMPTY
        // Lo escrito se conserva al girar la pantalla y se reinicia al cambiar de receptor o al guardar otros datos.
        var model by rememberSaveable(state.receiverKey, saved) { mutableStateOf(saved.model) }
        var serial by rememberSaveable(state.receiverKey, saved) { mutableStateOf(saved.serial) }
        var firmware by rememberSaveable(state.receiverKey, saved) { mutableStateOf(saved.firmware) }
        val changed = EquipmentProfile(model, serial, firmware).trimmed() != saved.trimmed()
        Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.testTag(EQUIPMENT_SCREEN_TAG)) {
            Text(stringResource(R.string.equipment_hint), style = MaterialTheme.typography.bodyMedium)
            OutlinedTextField(
                value = model,
                onValueChange = { model = it },
                label = { Text(stringResource(R.string.equipment_model)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                modifier = Modifier.fillMaxWidth().testTag(EQUIPMENT_MODEL_TAG),
            )
            Text(stringResource(R.string.equipment_catalog), style = MaterialTheme.typography.labelLarge)
            state.catalog.forEach { profile ->
                OutlinedButton(
                    onClick = { actions.onPick(profile.ref)?.let { model = it } },
                    modifier = Modifier.fillMaxWidth().heightIn(min = MinTouchTarget).testTag(equipmentCatalogTag(profile.ref)),
                ) { Text(profile.name) }
            }
            OutlinedTextField(
                value = serial,
                onValueChange = { serial = it },
                label = { Text(stringResource(R.string.equipment_serial)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag(EQUIPMENT_SERIAL_TAG),
            )
            OutlinedTextField(
                value = firmware,
                onValueChange = { firmware = it },
                label = { Text(stringResource(R.string.equipment_firmware)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag(EQUIPMENT_FIRMWARE_TAG),
            )
            Button(
                onClick = { actions.onSave(model, serial, firmware) },
                enabled = changed,
                modifier = Modifier.fillMaxWidth().heightIn(min = MinTouchTarget).testTag(EQUIPMENT_SAVE_TAG),
            ) { Text(stringResource(R.string.equipment_save)) }
            state.currentProfile?.let {
                Text(
                    stringResource(R.string.equipment_current_profile, it.name),
                    modifier = Modifier.testTag(EQUIPMENT_CURRENT_PROFILE_TAG),
                )
            }
            state.picked?.takeIf { it.ref != state.currentProfile?.ref }?.let { proposed ->
                OutlinedButton(
                    onClick = actions.onUseProfile,
                    modifier = Modifier.fillMaxWidth().heightIn(min = MinTouchTarget).testTag(EQUIPMENT_USE_PROFILE_TAG),
                ) {
                    val offset = LengthFormat.metres(proposed.offsetM, uiLocale())
                    Text(stringResource(R.string.equipment_use_profile, proposed.name, offset))
                }
            }
            state.message?.let {
                Text(messageText(it), color = MaterialTheme.colorScheme.primary, modifier = Modifier.testTag(EQUIPMENT_MESSAGE_TAG))
            }
        }
    }
}
