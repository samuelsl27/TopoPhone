package es.topophone.app.ui.receiver

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import es.topophone.app.R
import es.topophone.core.designsystem.component.MinTouchTarget
import es.topophone.core.gnss.nmea.ReplaySpeed
import es.topophone.core.gnss.scenario.Scenario
import es.topophone.core.gnss.scenario.ScenarioId
import es.topophone.core.gnss.scenario.Scenarios

const val VIRTUAL_DIALOG_TAG = "virtual_dialog"
const val VIRTUAL_CONNECT_TAG = "virtual_connect"
const val VIRTUAL_CANCEL_TAG = "virtual_cancel"
const val VIRTUAL_ORIGIN_PROJECT_TAG = "virtual_origin_project"
const val VIRTUAL_ORIGIN_COORDINATES_TAG = "virtual_origin_coordinates"
const val VIRTUAL_LATITUDE_TAG = "virtual_latitude"
const val VIRTUAL_LONGITUDE_TAG = "virtual_longitude"

fun virtualScenarioTag(id: ScenarioId) = "virtual_scenario_${id.name}"

fun virtualSpeedTag(speed: ReplaySpeed) = "virtual_speed_${speed.name}"

/**
 * La elección del receptor virtual (S022/RF-13, RF-14): el escenario con su descripción y su duración, dónde ocurre y a qué velocidad.
 * «Conectar» no se puede pulsar sin un origen válido.
 */
@Composable
internal fun VirtualReceiverDialog(ui: VirtualUi, actions: ReceiverActions) {
    AlertDialog(
        onDismissRequest = actions.onVirtualCancel,
        modifier = Modifier.testTag(VIRTUAL_DIALOG_TAG),
        title = { Text(stringResource(R.string.virtual_dialog_title)) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.virtual_scenario_label), style = MaterialTheme.typography.titleSmall)
                for (scenario in Scenarios.all) {
                    ScenarioChoice(scenario, selected = ui.scenario == scenario.id) { actions.onVirtualScenario(scenario.id) }
                }
                Text(stringResource(R.string.virtual_origin_label), style = MaterialTheme.typography.titleSmall)
                if (ui.projectCenter != null) {
                    Choice(
                        stringResource(R.string.virtual_origin_project, ui.projectName.orEmpty()),
                        selected = ui.originMode == VirtualOriginMode.PROJECT,
                        modifier = Modifier.testTag(VIRTUAL_ORIGIN_PROJECT_TAG),
                    ) { actions.onVirtualOriginMode(VirtualOriginMode.PROJECT) }
                }
                Choice(
                    stringResource(R.string.virtual_origin_coordinates),
                    selected = ui.originMode == VirtualOriginMode.COORDINATES,
                    modifier = Modifier.testTag(VIRTUAL_ORIGIN_COORDINATES_TAG),
                ) { actions.onVirtualOriginMode(VirtualOriginMode.COORDINATES) }
                if (ui.originMode == VirtualOriginMode.COORDINATES) {
                    OutlinedTextField(
                        value = ui.latitudeText,
                        onValueChange = actions.onVirtualLatitude,
                        label = { Text(stringResource(R.string.virtual_latitude)) },
                        isError = ui.latitudeInvalid,
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.fillMaxWidth().testTag(VIRTUAL_LATITUDE_TAG),
                    )
                    OutlinedTextField(
                        value = ui.longitudeText,
                        onValueChange = actions.onVirtualLongitude,
                        label = { Text(stringResource(R.string.virtual_longitude)) },
                        isError = ui.longitudeInvalid,
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.fillMaxWidth().testTag(VIRTUAL_LONGITUDE_TAG),
                    )
                    if (ui.latitudeInvalid || ui.longitudeInvalid) {
                        Text(stringResource(R.string.virtual_coordinates_invalid), color = MaterialTheme.colorScheme.error)
                    }
                }
                Text(stringResource(R.string.virtual_speed_label), style = MaterialTheme.typography.titleSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (speed in ReplaySpeed.entries) {
                        FilterChip(
                            selected = ui.speed == speed,
                            onClick = { actions.onVirtualSpeed(speed) },
                            label = { Text(stringResource(R.string.virtual_speed_value, speed.factor)) },
                            modifier = Modifier.heightIn(min = MinTouchTarget).testTag(virtualSpeedTag(speed)),
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = actions.onVirtualConfirm,
                enabled = ui.canContinue,
                modifier = Modifier.heightIn(min = MinTouchTarget).testTag(VIRTUAL_CONNECT_TAG),
            ) { Text(stringResource(R.string.virtual_connect)) }
        },
        dismissButton = {
            TextButton(onClick = actions.onVirtualCancel, modifier = Modifier.heightIn(min = MinTouchTarget).testTag(VIRTUAL_CANCEL_TAG)) {
                Text(stringResource(R.string.virtual_cancel))
            }
        },
    )
}

/** Un escenario: su nombre, su descripción y lo que dura (S022/RF-14). */
@Composable
private fun ScenarioChoice(scenario: Scenario, selected: Boolean, onClick: () -> Unit) {
    val minutes = scenario.durationS / SECONDS_PER_MINUTE
    val seconds = scenario.durationS % SECONDS_PER_MINUTE
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = MinTouchTarget).selectable(selected, role = Role.RadioButton, onClick = onClick)
            .testTag(virtualScenarioTag(scenario.id)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Column(modifier = Modifier.padding(start = 8.dp)) {
            Text(stringResource(scenario.id.nameRes()), style = MaterialTheme.typography.bodyLarge)
            Text(stringResource(scenario.id.descriptionRes()), style = MaterialTheme.typography.bodySmall)
            Text(stringResource(R.string.scenario_duration, minutes, seconds), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun Choice(label: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Row(
        modifier = modifier.fillMaxWidth().heightIn(min = MinTouchTarget).selectable(selected, role = Role.RadioButton, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(label, modifier = Modifier.padding(start = 8.dp))
    }
}

private const val SECONDS_PER_MINUTE = 60
