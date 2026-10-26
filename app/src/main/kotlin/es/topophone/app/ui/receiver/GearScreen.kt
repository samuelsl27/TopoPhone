package es.topophone.app.ui.receiver

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import es.topophone.app.R
import es.topophone.core.designsystem.component.MinTouchTarget
import es.topophone.core.model.format.LengthFormat
import es.topophone.core.model.point.PoleKind
import es.topophone.core.survey.AntennaProfile
import es.topophone.core.survey.GearProblem
import es.topophone.core.survey.GearProblemField
import es.topophone.core.survey.SavedPole

const val GEAR_SCREEN_TAG = "gear_screen"
const val GEAR_ANTENNA_HEIGHT_TAG = "gear_antenna_height"
const val GEAR_VERTICAL_HEIGHT_TAG = "gear_vertical_height"
const val GEAR_PROBLEM_TAG = "gear_problem"
const val GEAR_MESSAGE_TAG = "gear_message"
const val GEAR_LOCKED_TAG = "gear_locked"
const val GEAR_NEW_PROFILE_TAG = "gear_new_profile"
const val GEAR_NEW_POLE_TAG = "gear_new_pole"
const val GEAR_DIALOG_NAME_TAG = "gear_dialog_name"
const val GEAR_DIALOG_FIELD_TAG = "gear_dialog_field"
const val GEAR_DIALOG_RADIUS_TAG = "gear_dialog_radius"
const val GEAR_DIALOG_SAVE_TAG = "gear_dialog_save"
const val GEAR_DIALOG_SLANT_TAG = "gear_dialog_slant"

fun gearProfileTag(ref: String) = "gear_profile_$ref"

fun gearPoleTag(id: String) = "gear_pole_$id"

fun gearProfileEditTag(ref: String) = "gear_profile_edit_$ref"

fun gearProfileDeleteTag(ref: String) = "gear_profile_delete_$ref"

fun gearProfileCopyTag(ref: String) = "gear_profile_copy_$ref"

fun gearPoleEditTag(id: String) = "gear_pole_edit_$id"

fun gearPoleDeleteTag(id: String) = "gear_pole_delete_$id"

/** Lo que la pantalla "Antena y jalón" pide al ViewModel (S046). */
class GearActions(
    val onSelectProfile: (String) -> Unit = {},
    val onSelectPole: (String) -> Unit = {},
    val onDraftProfile: (ref: String, copyLabel: String) -> ProfileDraft? = { _, _ -> null },
    val onSaveProfile: (
        id: String?,
        name: String,
        offset: String,
        radius: String,
        onDone: (Boolean) -> Unit,
    ) -> Unit = { _, _, _, _, _ -> },
    val onDeleteProfile: (id: String) -> Unit = {},
    val onSavePole: (id: String?, name: String, kind: PoleKind, height: String, onDone: (Boolean) -> Unit) -> Unit = { _, _, _, _, _ -> },
    val onDeletePole: (id: String) -> Unit = {},
    val onDismissMessage: () -> Unit = {},
)

/** El texto del problema que impide medir con el equipo activo (S046/RF-18, RF-28). */
@Composable
internal fun gearProblemText(problem: GearProblem): String = stringResource(
    when (problem) {
        GearProblem.NO_POLE -> R.string.gear_problem_no_pole
        GearProblem.NO_RADIUS -> R.string.gear_problem_no_radius
        GearProblem.SLANT_SHORTER_THAN_RADIUS -> R.string.gear_problem_slant_short
    },
)

/** El resumen del equipo activo para el menú del receptor y para el selector (S046/RF-15). */
@Composable
internal fun gearSummary(state: GearUiState): String {
    val gear = state.gear
    val antenna = gear.antennaHeightM ?: return gear.problem?.let { gearProblemText(it) } ?: ""
    return stringResource(R.string.gear_summary, gear.pole?.name.orEmpty(), LengthFormat.metres(antenna, uiLocale()), gear.profile.name)
}

@Composable
private fun messageText(message: GearMessage): String = when (message) {
    is GearMessage.Invalid -> stringResource(
        when (message.field) {
            GearProblemField.NAME_EMPTY -> R.string.gear_msg_name_empty
            GearProblemField.NAME_TOO_LONG -> R.string.gear_msg_name_long
            GearProblemField.NAME_TAKEN -> R.string.gear_msg_name_taken
            GearProblemField.OFFSET -> R.string.gear_msg_offset
            GearProblemField.RADIUS -> R.string.gear_msg_radius
            GearProblemField.HEIGHT -> R.string.gear_msg_height
        },
    )
    is GearMessage.ProfileInUse -> stringResource(R.string.gear_msg_profile_in_use)
    GearMessage.LastPole -> stringResource(R.string.gear_msg_last_pole)
    is GearMessage.PoleActive -> stringResource(R.string.gear_msg_pole_active)
    GearMessage.Locked -> stringResource(R.string.gear_msg_locked)
    is GearMessage.ProfileChanged -> stringResource(R.string.gear_msg_profile_changed, message.slantPoleName)
}

/** Qué diálogo está abierto: un perfil o un jalón, nuevo o existente. */
private sealed interface GearDialog {
    data class Profile(val id: String?, val name: String, val offset: String, val radius: String) : GearDialog

    data class Pole(val id: String?, val name: String, val kind: PoleKind, val height: String) : GearDialog
}

/** La pantalla "Antena y jalón": el equipo activo, los perfiles de antena y los jalones guardados (S046/RF-1 a RF-19). */
@Composable
internal fun GearCard(state: GearUiState, actions: GearActions) {
    var draft by remember { mutableStateOf<GearDialog?>(null) }
    val locale = uiLocale()
    val copyLabel = stringResource(R.string.gear_copy_label)
    ReceiverCard(stringResource(R.string.gear_title)) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.testTag(GEAR_SCREEN_TAG)) {
            state.gear.antennaHeightM?.let {
                Text(
                    stringResource(R.string.gear_antenna_height, LengthFormat.metres(it, locale)),
                    modifier = Modifier.testTag(GEAR_ANTENNA_HEIGHT_TAG),
                )
            }
            state.gear.verticalPoleM?.let {
                Text(
                    stringResource(R.string.gear_vertical_height, LengthFormat.metres(it, locale)),
                    modifier = Modifier.testTag(GEAR_VERTICAL_HEIGHT_TAG),
                )
            }
            state.gear.problem?.let {
                Text(gearProblemText(it), color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag(GEAR_PROBLEM_TAG))
            }
            if (state.locked) Text(stringResource(R.string.gear_locked), modifier = Modifier.testTag(GEAR_LOCKED_TAG))
            state.message?.takeUnless { it is GearMessage.Invalid }?.let {
                Text(messageText(it), color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag(GEAR_MESSAGE_TAG))
            }
        }
    }
    ReceiverCard(stringResource(R.string.gear_profile_title)) {
        Column(modifier = Modifier.selectableGroup()) {
            state.profiles.forEach { profile ->
                ProfileRow(profile, selected = profile.ref == state.gear.profile.ref, locked = state.locked, actions = actions, onEdit = {
                    draft =
                        GearDialog.Profile(
                            profile.ref.removePrefix("own:"),
                            profile.name,
                            plainText(profile.offsetM),
                            profile.radiusM?.let(::plainText).orEmpty(),
                        )
                }, onCopy = {
                    actions.onDraftProfile(profile.ref, copyLabel)?.let {
                        draft =
                            GearDialog.Profile(null, it.name, it.offsetText, it.radiusText)
                    }
                })
            }
        }
        TextButton(
            onClick = { draft = GearDialog.Profile(null, "", "", "") },
            modifier = Modifier.heightIn(min = MinTouchTarget).testTag(GEAR_NEW_PROFILE_TAG),
        ) { Text(stringResource(R.string.gear_new_profile)) }
    }
    ReceiverCard(stringResource(R.string.gear_pole_title)) {
        Column(modifier = Modifier.selectableGroup()) {
            state.poles.forEach { pole ->
                PoleRow(pole, selected = pole.id == state.gear.pole?.id, locked = state.locked, actions = actions, onEdit = {
                    draft = GearDialog.Pole(pole.id, pole.name, pole.kind, plainText(pole.enteredM))
                })
            }
        }
        TextButton(
            onClick = { draft = GearDialog.Pole(null, "", PoleKind.VERTICAL, "") },
            modifier = Modifier.heightIn(min = MinTouchTarget).testTag(GEAR_NEW_POLE_TAG),
        ) { Text(stringResource(R.string.gear_new_pole)) }
    }
    when (val open = draft) {
        is GearDialog.Profile -> ProfileDialog(open, state.message, actions, onDismiss = {
            draft = null
            actions.onDismissMessage()
        })
        is GearDialog.Pole -> PoleDialog(open, state.message, actions, onDismiss = {
            draft = null
            actions.onDismissMessage()
        })
        null -> Unit
    }
}

private fun plainText(value: Double): String = value.toBigDecimal().toPlainString()

@Composable
private fun ProfileRow(
    profile: AntennaProfile,
    selected: Boolean,
    locked: Boolean,
    actions: GearActions,
    onEdit: () -> Unit,
    onCopy: () -> Unit,
) {
    val locale = uiLocale()
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = MinTouchTarget)
                .selectable(selected = selected, enabled = !locked, role = Role.RadioButton) { actions.onSelectProfile(profile.ref) }
                .testTag(gearProfileTag(profile.ref)),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(selected = selected, onClick = null)
            Column(modifier = Modifier.weight(1f)) {
                Text(profile.name, style = MaterialTheme.typography.bodyLarge)
                val offset = LengthFormat.metres(profile.offsetM, locale)
                Text(
                    profile.radiusM?.let { stringResource(R.string.gear_profile_detail_radius, offset, LengthFormat.metres(it, locale)) }
                        ?: stringResource(R.string.gear_profile_detail, offset),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!profile.builtIn) {
                TextButton(onClick = onEdit, modifier = Modifier.heightIn(min = MinTouchTarget).testTag(gearProfileEditTag(profile.ref))) {
                    Text(stringResource(R.string.gear_edit))
                }
                TextButton(
                    onClick = { actions.onDeleteProfile(profile.ref.removePrefix("own:")) },
                    modifier = Modifier.heightIn(min = MinTouchTarget).testTag(gearProfileDeleteTag(profile.ref)),
                ) { Text(stringResource(R.string.gear_delete)) }
            } else {
                TextButton(onClick = onCopy, modifier = Modifier.heightIn(min = MinTouchTarget).testTag(gearProfileCopyTag(profile.ref))) {
                    Text(stringResource(R.string.gear_copy_profile))
                }
            }
        }
    }
}

@Composable
private fun PoleRow(pole: SavedPole, selected: Boolean, locked: Boolean, actions: GearActions, onEdit: () -> Unit) {
    val locale = uiLocale()
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = MinTouchTarget)
                .selectable(selected = selected, enabled = !locked, role = Role.RadioButton) { actions.onSelectPole(pole.id) }
                .testTag(gearPoleTag(pole.id)),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(selected = selected, onClick = null)
            Column(modifier = Modifier.weight(1f)) {
                Text(pole.name, style = MaterialTheme.typography.bodyLarge)
                val height = LengthFormat.metres(pole.enteredM, locale)
                Text(
                    stringResource(
                        if (pole.kind ==
                            PoleKind.SLANT
                        ) {
                            R.string.gear_pole_detail_slant
                        } else {
                            R.string.gear_pole_detail_vertical
                        },
                        height,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = onEdit, modifier = Modifier.heightIn(min = MinTouchTarget).testTag(gearPoleEditTag(pole.id))) {
                Text(stringResource(R.string.gear_edit))
            }
            TextButton(onClick = {
                actions.onDeletePole(pole.id)
            }, modifier = Modifier.heightIn(min = MinTouchTarget).testTag(gearPoleDeleteTag(pole.id))) {
                Text(stringResource(R.string.gear_delete))
            }
        }
    }
}

@Composable
private fun ProfileDialog(open: GearDialog.Profile, message: GearMessage?, actions: GearActions, onDismiss: () -> Unit) {
    var name by rememberSaveable(open) { mutableStateOf(open.name) }
    var offset by rememberSaveable(open) { mutableStateOf(open.offset) }
    var radius by rememberSaveable(open) { mutableStateOf(open.radius) }
    val error = (message as? GearMessage.Invalid)?.takeIf { it.target == GearTarget.PROFILE }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (open.id == null) R.string.gear_new_profile else R.string.gear_edit_profile)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                GearField(R.string.gear_profile_name, name, GEAR_DIALOG_NAME_TAG, error?.field in NAME_FIELDS, KeyboardType.Text) {
                    name =
                        it
                }
                GearField(
                    R.string.gear_profile_offset,
                    offset,
                    GEAR_DIALOG_FIELD_TAG,
                    error?.field == GearProblemField.OFFSET,
                    KeyboardType.Decimal,
                ) {
                    offset =
                        it
                }
                GearField(
                    R.string.gear_profile_radius,
                    radius,
                    GEAR_DIALOG_RADIUS_TAG,
                    error?.field == GearProblemField.RADIUS,
                    KeyboardType.Decimal,
                ) {
                    radius =
                        it
                }
                error?.let { Text(messageText(it), color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag(GEAR_MESSAGE_TAG)) }
            }
        },
        confirmButton = {
            Button(
                onClick = { actions.onSaveProfile(open.id, name, offset, radius) { saved -> if (saved) onDismiss() } },
                modifier = Modifier.heightIn(min = MinTouchTarget).testTag(GEAR_DIALOG_SAVE_TAG),
            ) { Text(stringResource(R.string.gear_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = MinTouchTarget)) {
                Text(stringResource(R.string.gear_cancel))
            }
        },
    )
}

@Composable
private fun PoleDialog(open: GearDialog.Pole, message: GearMessage?, actions: GearActions, onDismiss: () -> Unit) {
    var name by rememberSaveable(open) { mutableStateOf(open.name) }
    var height by rememberSaveable(open) { mutableStateOf(open.height) }
    var slant by rememberSaveable(open) { mutableStateOf(open.kind == PoleKind.SLANT) }
    val error = (message as? GearMessage.Invalid)?.takeIf { it.target == GearTarget.POLE }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (open.id == null) R.string.gear_new_pole else R.string.gear_edit_pole)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                GearField(R.string.gear_pole_name, name, GEAR_DIALOG_NAME_TAG, error?.field in NAME_FIELDS, KeyboardType.Text) { name = it }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = MinTouchTarget)
                        .clickable(role = Role.Switch) { slant = !slant }
                        .testTag(GEAR_DIALOG_SLANT_TAG),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(stringResource(R.string.gear_pole_kind_slant), modifier = Modifier.weight(1f))
                    Switch(checked = slant, onCheckedChange = null)
                }
                GearField(
                    if (slant) R.string.gear_pole_height_slant else R.string.gear_pole_height_vertical,
                    height,
                    GEAR_DIALOG_FIELD_TAG,
                    error?.field == GearProblemField.HEIGHT,
                    KeyboardType.Decimal,
                ) { height = it }
                error?.let { Text(messageText(it), color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag(GEAR_MESSAGE_TAG)) }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    actions.onSavePole(open.id, name, if (slant) PoleKind.SLANT else PoleKind.VERTICAL, height) { saved ->
                        if (saved) onDismiss()
                    }
                },
                modifier = Modifier.heightIn(min = MinTouchTarget).testTag(GEAR_DIALOG_SAVE_TAG),
            ) { Text(stringResource(R.string.gear_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = MinTouchTarget)) {
                Text(stringResource(R.string.gear_cancel))
            }
        },
    )
}

private val NAME_FIELDS = setOf(GearProblemField.NAME_EMPTY, GearProblemField.NAME_TOO_LONG, GearProblemField.NAME_TAKEN)

@Composable
private fun GearField(label: Int, value: String, tag: String, isError: Boolean, keyboard: KeyboardType, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(stringResource(label)) },
        isError = isError,
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = keyboard),
        modifier = Modifier.fillMaxWidth().testTag(tag),
    )
}
