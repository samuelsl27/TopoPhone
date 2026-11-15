package es.topophone.app.ui.receiver

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import es.topophone.app.R
import es.topophone.core.designsystem.component.MinTouchTarget
import es.topophone.core.gnss.nmea.ReplaySpeed
import es.topophone.core.model.point.PositionSource
import es.topophone.receiver.session.ScenarioInfo
import java.util.Locale

const val SOURCE_BANNER_TAG = "source_banner"
const val SCENARIO_PROGRESS_TAG = "scenario_progress"
const val SCENARIO_SPEED_X1_TAG = "scenario_speed_x1"
const val SCENARIO_SPEED_X5_TAG = "scenario_speed_x5"
const val SCENARIO_REPEAT_TAG = "scenario_repeat"
const val SCENARIO_OTHER_TAG = "scenario_other"
const val SCENARIO_DISCONNECT_TAG = "scenario_disconnect"

private const val SECONDS_PER_MINUTE = 60

private fun scenarioSpeedTag(speed: ReplaySpeed) = if (speed == ReplaySpeed.X1) SCENARIO_SPEED_X1_TAG else SCENARIO_SPEED_X5_TAG

/** Minutos y segundos de un escenario, `m:ss` (S022/RF-16). */
fun formatScenarioTime(seconds: Int): String = "%d:%02d".format(Locale.ROOT, seconds / SECONDS_PER_MINUTE, seconds % SECONDS_PER_MINUTE)

/**
 * Qué fuente de posición es y, con el receptor virtual, el escenario, su avance y su velocidad (S022/RF-5, RF-16, RNF-T8): siempre
 * con texto, no solo con color. Con el GNSS del móvil dice que es SINGLE de pocos metros.
 */
@Composable
internal fun SourceDetails(state: ReceiverUiState, actions: ReceiverActions) {
    val scenario = state.snapshot.scenario
    when {
        scenario != null -> VirtualDetails(scenario, actions)
        state.snapshot.source == PositionSource.PHONE || state.snapshot.source == PositionSource.PHONE_MOCK ->
            Text(stringResource(R.string.source_phone_banner), modifier = Modifier.testTag(SOURCE_BANNER_TAG))
    }
}

@Composable
private fun VirtualDetails(scenario: ScenarioInfo, actions: ReceiverActions) {
    Text(
        stringResource(R.string.source_simulated_scenario, stringResource(scenario.id.nameRes())),
        style = MaterialTheme.typography.bodyLarge,
        modifier = Modifier.testTag(SOURCE_BANNER_TAG),
    )
    Text(
        stringResource(
            R.string.scenario_progress,
            formatScenarioTime(scenario.elapsedS.coerceAtLeast(0)),
            formatScenarioTime(scenario.durationS),
        ),
        modifier = Modifier.testTag(SCENARIO_PROGRESS_TAG),
    )
    SingleChoiceSegmentedButtonRow {
        ReplaySpeed.entries.forEachIndexed { index, speed ->
            SegmentedButton(
                selected = scenario.speed == speed,
                onClick = { actions.onScenarioSpeed(speed) },
                shape = SegmentedButtonDefaults.itemShape(index, ReplaySpeed.entries.size),
                modifier = Modifier.heightIn(min = MinTouchTarget).testTag(scenarioSpeedTag(speed)),
            ) { Text(stringResource(if (speed == ReplaySpeed.X1) R.string.replay_speed_x1 else R.string.replay_speed_x5)) }
        }
    }
}

/** Al terminar el escenario: repetirlo, elegir otro o desconectar (S022/RF-17). */
@Composable
internal fun ScenarioFinishedActions(actions: ReceiverActions) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        Button(
            onClick = actions.onRepeatScenario,
            modifier = Modifier.fillMaxWidth().heightIn(min = MinTouchTarget).testTag(SCENARIO_REPEAT_TAG),
        ) { Text(stringResource(R.string.scenario_repeat)) }
        OutlinedButton(
            onClick = actions.onOpenVirtual,
            modifier = Modifier.fillMaxWidth().heightIn(min = MinTouchTarget).testTag(SCENARIO_OTHER_TAG),
        ) { Text(stringResource(R.string.scenario_choose_other)) }
        OutlinedButton(
            onClick = actions.onDisconnect,
            modifier = Modifier.fillMaxWidth().heightIn(min = MinTouchTarget).testTag(SCENARIO_DISCONNECT_TAG),
        ) { Text(stringResource(R.string.receiver_disconnect)) }
    }
}
