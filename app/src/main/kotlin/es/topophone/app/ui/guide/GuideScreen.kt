package es.topophone.app.ui.guide

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import es.topophone.app.R
import es.topophone.app.ui.receiver.ReceiverCard
import es.topophone.core.designsystem.component.MinTouchTarget
import es.topophone.core.model.guide.ConfigList
import es.topophone.core.model.guide.ConfigListId
import es.topophone.core.model.guide.ConfigStep

const val GUIDE_SCREEN_TAG = "guide_screen"
const val GUIDE_NOT_APPLICABLE_TAG = "guide_not_applicable"
const val GUIDE_BACK_TAG = "guide_back"
const val GUIDE_RESET_TAG = "guide_reset"
const val GUIDE_RESET_CONFIRM_TAG = "guide_reset_confirm"
const val GUIDE_FIRMWARE_NOTE_TAG = "guide_firmware_note"

fun guideListTag(id: ConfigListId) = "guide_list_${id.name.lowercase()}"

fun guideCountsTag(id: ConfigListId) = "guide_counts_${id.name.lowercase()}"

fun guideStepTag(stepId: String) = "guide_step_$stepId"

/** Lo que la Guía pide al ViewModel (S043/RF-3). */
class GuideActions(
    val onSetDone: (list: ConfigListId, stepId: String, done: Boolean) -> Unit = { _, _, _ -> },
    val onReset: (list: ConfigListId) -> Unit = {},
)

@Composable
private fun countsText(state: GuideUiState, list: ConfigList): String {
    val counts = state.counts(list)
    return stringResource(R.string.guide_counts, counts.done, counts.total, counts.checked, counts.checkable)
}

/** La Guía: las cuatro listas con sus contadores y, al abrir una, sus pasos para marcar (S043/RF-1 a RF-4). */
@Composable
fun GuideCard(state: GuideUiState, actions: GuideActions) {
    ReceiverCard(stringResource(R.string.guide_title)) {
        if (!state.applies) {
            Text(stringResource(R.string.guide_not_applicable), modifier = Modifier.testTag(GUIDE_NOT_APPLICABLE_TAG))
            return@ReceiverCard
        }
        var open by rememberSaveable(state.receiverKey) { mutableStateOf<String?>(null) }
        val list = state.lists.firstOrNull { it.id.name == open }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.testTag(GUIDE_SCREEN_TAG)) {
            if (list == null) {
                Text(stringResource(R.string.guide_lists_intro), style = MaterialTheme.typography.bodyMedium)
                state.lists.forEach { item -> ListRow(state, item, onOpen = { open = item.id.name }) }
            } else {
                OpenList(state, list, actions, onBack = { open = null })
            }
        }
    }
}

@Composable
private fun ListRow(state: GuideUiState, list: ConfigList, onOpen: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = MinTouchTarget)
            .clickable(role = Role.Button, onClick = onOpen)
            .testTag(guideListTag(list.id)),
    ) {
        Text(stringResource(GuideTexts.listTitle(list.id)), style = MaterialTheme.typography.titleSmall)
        Text(stringResource(GuideTexts.listSummary(list.id)), style = MaterialTheme.typography.bodyMedium)
        Text(countsText(state, list), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag(guideCountsTag(list.id)))
    }
}

@Composable
private fun OpenList(state: GuideUiState, list: ConfigList, actions: GuideActions, onBack: () -> Unit) {
    var confirmReset by rememberSaveable(list.id.name) { mutableStateOf(false) }
    TextButton(onClick = onBack, modifier = Modifier.heightIn(min = MinTouchTarget).testTag(GUIDE_BACK_TAG)) {
        Text(stringResource(R.string.guide_back))
    }
    Text(stringResource(GuideTexts.listTitle(list.id)), style = MaterialTheme.typography.titleMedium)
    Text(countsText(state, list), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag(guideCountsTag(list.id)))
    Text(
        stringResource(GuideTexts.firmwareNote()),
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.testTag(GUIDE_FIRMWARE_NOTE_TAG),
    )
    list.steps.forEach { step -> StepRow(step, done = step.id in state.done[list.id].orEmpty(), list = list.id, actions = actions) }
    OutlinedButton(
        onClick = { confirmReset = true },
        modifier = Modifier.fillMaxWidth().heightIn(min = MinTouchTarget).testTag(GUIDE_RESET_TAG),
    ) { Text(stringResource(R.string.guide_reset)) }
    if (confirmReset) {
        val title = stringResource(GuideTexts.listTitle(list.id))
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text(stringResource(R.string.guide_reset_title)) },
            text = { Text(stringResource(R.string.guide_reset_message, title)) },
            confirmButton = {
                Button(
                    onClick = {
                        actions.onReset(list.id)
                        confirmReset = false
                    },
                    modifier = Modifier.testTag(GUIDE_RESET_CONFIRM_TAG),
                ) { Text(stringResource(R.string.guide_reset_confirm)) }
            },
            dismissButton = { TextButton(onClick = { confirmReset = false }) { Text(stringResource(R.string.guide_cancel)) } },
        )
    }
}

@Composable
private fun StepRow(step: ConfigStep, done: Boolean, list: ConfigListId, actions: GuideActions) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = MinTouchTarget)
            .toggleable(value = done, role = Role.Checkbox) { actions.onSetDone(list, step.id, it) }
            .testTag(guideStepTag(step.id)),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Checkbox(checked = done, onCheckedChange = null)
        Column(modifier = Modifier.weight(1f)) {
            Text(stringResource(GuideTexts.stepTitle(step.id)), style = MaterialTheme.typography.bodyLarge)
            Text(stringResource(GuideTexts.stepDetail(step.id)), style = MaterialTheme.typography.bodyMedium)
            Text(
                stringResource(if (step.checkable) R.string.guide_step_checkable else R.string.guide_step_not_checkable),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}
