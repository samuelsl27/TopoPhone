package es.topophone.app.experimental

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import es.topophone.app.R
import es.topophone.receiver.experimental.ReachLiveState

const val LIVE_BATTERY_TAG = "experimental_live_battery"
const val LIVE_LOGS_TAG = "experimental_live_logs"
const val LIVE_LOST_TAG = "experimental_live_lost"

/**
 * El estado en directo del receptor (S044/RF-13, RF-17): la batería y si hay logs escribiendo mientras hay directo; si no lo hay, solo
 * «Sin estado del receptor», sin ningún dato viejo. Los textos dicen el estado con palabras, no solo con color.
 */
@Composable
fun LiveStatusLines(live: ReachLiveState, modifier: Modifier = Modifier) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (!live.connected) {
            Text(
                stringResource(R.string.experimental_live_lost),
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.testTag(LIVE_LOST_TAG),
            )
            return@Column
        }
        Text(
            live.batteryPercent?.let { stringResource(R.string.experimental_battery, it) }
                ?: stringResource(R.string.experimental_battery_unknown),
            modifier = Modifier.testTag(LIVE_BATTERY_TAG),
        )
        Text(
            stringResource(
                when (live.logsWriting) {
                    true -> R.string.experimental_logs_writing
                    false -> R.string.experimental_logs_stopped
                    null -> R.string.experimental_logs_unknown
                },
            ),
            modifier = Modifier.testTag(LIVE_LOGS_TAG),
        )
    }
}
