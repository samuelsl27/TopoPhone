package es.topophone.app.ui.receiver

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import es.topophone.app.R
import es.topophone.core.designsystem.component.MinTouchTarget
import es.topophone.core.model.point.TiltMode
import es.topophone.core.survey.WizardState
import es.topophone.data.receiver.ReceiverTiltSettings

const val TILT_SCREEN_TAG = "receiver_tilt_screen"
const val TILT_MODE_STATE_TAG = "tilt_mode_state"
const val TILT_VERTICAL_WARNING_TAG = "tilt_vertical_warning"
const val TILT_WIZARD_OPEN_TAG = "tilt_wizard_open"

fun tiltModeTag(mode: TiltMode) = "tilt_mode_${mode.name.lowercase()}"

/** Lo que pinta el apartado "Inclinación" (S016/RF-1, RF-3, RF-4) y el asistente, si está abierto (S016/RF-5 a RF-10). */
data class TiltUiState(val settings: ReceiverTiltSettings = ReceiverTiltSettings.NONE, val wizard: WizardState? = null) {
    /** S016/RF-4: "Jalón vertical" con un receptor que el asistente encontró compensando. */
    val verticalWithCompensatingReceiver: Boolean
        get() = settings.mode == TiltMode.VERTICAL && settings.detected == TiltMode.RECEIVER
}

class TiltActions(
    val onChoose: (TiltMode) -> Unit = {},
    val onOpenWizard: () -> Unit = {},
    val onStartTest: () -> Unit = {},
    val onConfirm: () -> Unit = {},
    val onRepeat: () -> Unit = {},
    val onCloseWizard: () -> Unit = {},
)

/** El nombre de cada modo (S016/RF-1). */
internal fun TiltMode.labelRes(): Int = when (this) {
    TiltMode.APP -> R.string.tilt_mode_app
    TiltMode.RECEIVER -> R.string.tilt_mode_receiver
    TiltMode.VERTICAL -> R.string.tilt_mode_vertical
}

private fun TiltMode.helpRes(): Int = when (this) {
    TiltMode.APP -> R.string.tilt_mode_app_help
    TiltMode.RECEIVER -> R.string.tilt_mode_receiver_help
    TiltMode.VERTICAL -> R.string.tilt_mode_vertical_help
}

/** Resumen del modo para el menú del receptor: el modo y si está comprobado, o "Sin elegir" (S016/RF-1, RF-3). */
@Composable
internal fun tiltSummary(settings: ReceiverTiltSettings): String {
    val mode = settings.mode ?: return stringResource(R.string.tilt_mode_none)
    val name = stringResource(mode.labelRes())
    return when {
        !mode.usesTilt -> name
        settings.verified -> stringResource(R.string.tilt_mode_verified, name)
        else -> stringResource(R.string.tilt_mode_unverified, name)
    }
}

/** El apartado "Inclinación": los tres modos, si el elegido está comprobado, el aviso de RF-4 y el asistente. */
@Composable
internal fun TiltCard(state: TiltUiState, actions: TiltActions) {
    val selected = state.settings.mode
    ReceiverCard(stringResource(R.string.tilt_mode_title)) {
        Column(modifier = Modifier.selectableGroup().testTag(TILT_SCREEN_TAG)) {
            TiltMode.entries.forEach { mode ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = MinTouchTarget)
                        .selectable(selected = mode == selected, role = Role.RadioButton, onClick = { actions.onChoose(mode) })
                        .testTag(tiltModeTag(mode)),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = mode == selected, onClick = null)
                    Column(modifier = Modifier.padding(start = 12.dp)) {
                        Text(stringResource(mode.labelRes()), style = MaterialTheme.typography.bodyLarge)
                        Text(stringResource(mode.helpRes()), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        val stateText = when {
            selected == null -> stringResource(R.string.tilt_mode_state_none) // S016/RF-2
            !selected.usesTilt -> null
            state.settings.verified -> stringResource(R.string.tilt_mode_state_verified) // S016/RF-10
            else -> stringResource(R.string.tilt_mode_state_unverified) // S016/RF-3
        }
        stateText?.let { Text(it, modifier = Modifier.testTag(TILT_MODE_STATE_TAG)) }
        if (state.verticalWithCompensatingReceiver) {
            Text(
                stringResource(R.string.tilt_vertical_warning),
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.testTag(TILT_VERTICAL_WARNING_TAG),
            ) // S016/RF-4
        }
        if (state.wizard == null) {
            Button(onClick = actions.onOpenWizard, modifier = Modifier.heightIn(min = MinTouchTarget).testTag(TILT_WIZARD_OPEN_TAG)) {
                Text(stringResource(R.string.tilt_wizard_open))
            }
        }
    }
    state.wizard?.let { TiltWizardCard(it, actions) }
}
