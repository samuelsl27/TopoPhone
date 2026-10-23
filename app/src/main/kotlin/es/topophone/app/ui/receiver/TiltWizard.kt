package es.topophone.app.ui.receiver

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import es.topophone.app.R
import es.topophone.core.designsystem.component.MinTouchTarget
import es.topophone.core.model.format.LengthFormat
import es.topophone.core.model.point.TiltMode
import es.topophone.core.survey.WizardInconclusive
import es.topophone.core.survey.WizardMissing
import es.topophone.core.survey.WizardState

const val TILT_WIZARD_TAG = "tilt_wizard"
const val TILT_WIZARD_STEP_TAG = "tilt_wizard_step"
const val TILT_WIZARD_MISSING_TAG = "tilt_wizard_missing"
const val TILT_WIZARD_START_TAG = "tilt_wizard_start"
const val TILT_WIZARD_CONFIRM_TAG = "tilt_wizard_confirm"
const val TILT_WIZARD_REPEAT_TAG = "tilt_wizard_repeat"
const val TILT_WIZARD_CLOSE_TAG = "tilt_wizard_close"

/** El asistente del modo de inclinación: qué falta, cada paso de la prueba y su resultado (S016/RF-5 a RF-10). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun TiltWizardCard(wizard: WizardState, actions: TiltActions) {
    ReceiverCard(stringResource(R.string.tilt_wizard_open)) {
        Text(stringResource(R.string.tilt_wizard_intro), style = MaterialTheme.typography.bodySmall)
        // TalkBack lee el paso en que se está cuando cambia (RNF-T8).
        val stepModifier = Modifier.testTag(TILT_WIZARD_STEP_TAG).semantics { liveRegion = LiveRegionMode.Polite }
        when (wizard) {
            is WizardState.Ready -> {
                wizard.missing.forEach { missing ->
                    Text(
                        stringResource(missing.textRes()),
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.testTag(TILT_WIZARD_MISSING_TAG),
                    ) // S016/RF-5
                }
                WizardButton(
                    R.string.tilt_wizard_start,
                    TILT_WIZARD_START_TAG,
                    enabled = wizard.missing.isEmpty(),
                    onClick = actions.onStartTest,
                )
            }
            is WizardState.Reference -> {
                Text(stringResource(R.string.tilt_wizard_reference), modifier = stepModifier) // S016/RF-6
                Progress(wizard.progressS)
            }
            is WizardState.Tilt -> {
                val step = if (wizard.tiltMore) R.string.tilt_wizard_tilt_more else R.string.tilt_wizard_tilt
                Text(stringResource(step), modifier = stepModifier) // S016/RF-6
                val degrees = String.format(uiLocale(), "%.0f°", Math.toDegrees(wizard.tiltRad))
                Text(stringResource(R.string.tilt_wizard_current_tilt, degrees))
            }
            is WizardState.Hold -> {
                Text(stringResource(R.string.tilt_wizard_hold), modifier = stepModifier)
                Progress(wizard.progressS)
            }
            is WizardState.Proposed -> {
                val proposal = when (wizard.mode) {
                    TiltMode.RECEIVER -> R.string.tilt_wizard_proposed_receiver
                    else -> R.string.tilt_wizard_proposed_app
                }
                Text(stringResource(proposal), modifier = stepModifier) // S016/RF-7, RF-8
                Text(
                    stringResource(
                        R.string.tilt_wizard_movement,
                        LengthFormat.metres(wizard.movedM, uiLocale()),
                        LengthFormat.metres(wizard.expectedM, uiLocale()),
                    ),
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    WizardButton(R.string.tilt_wizard_confirm, TILT_WIZARD_CONFIRM_TAG, onClick = actions.onConfirm) // S016/RF-10
                    WizardButton(R.string.tilt_wizard_repeat, TILT_WIZARD_REPEAT_TAG, outlined = true, onClick = actions.onRepeat)
                }
            }
            is WizardState.Inconclusive -> {
                Text(
                    stringResource(R.string.tilt_wizard_inconclusive, stringResource(wizard.reason.textRes())),
                    color = MaterialTheme.colorScheme.error,
                    modifier = stepModifier,
                ) // S016/RF-9
                WizardButton(R.string.tilt_wizard_repeat, TILT_WIZARD_REPEAT_TAG, onClick = actions.onRepeat)
            }
        }
        WizardButton(R.string.tilt_wizard_close, TILT_WIZARD_CLOSE_TAG, outlined = true, onClick = actions.onCloseWizard)
    }
}

@Composable
private fun Progress(progressS: Double) {
    LinearProgressIndicator(progress = { progressS.toFloat().coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
}

@Composable
private fun WizardButton(text: Int, tag: String, enabled: Boolean = true, outlined: Boolean = false, onClick: () -> Unit) {
    val modifier = Modifier.heightIn(min = MinTouchTarget).testTag(tag)
    if (outlined) {
        OutlinedButton(onClick = onClick, enabled = enabled, modifier = modifier) { Text(stringResource(text)) }
    } else {
        Button(onClick = onClick, enabled = enabled, modifier = modifier) { Text(stringResource(text)) }
    }
}

private fun WizardMissing.textRes(): Int = when (this) {
    WizardMissing.FIX -> R.string.tilt_wizard_missing_fix
    WizardMissing.TILT -> R.string.tilt_wizard_missing_tilt
}

private fun WizardInconclusive.textRes(): Int = when (this) {
    WizardInconclusive.MISMATCH -> R.string.tilt_wizard_reason_mismatch
    WizardInconclusive.LOST -> R.string.tilt_wizard_reason_lost
    WizardInconclusive.TIMEOUT -> R.string.tilt_wizard_reason_timeout
}
