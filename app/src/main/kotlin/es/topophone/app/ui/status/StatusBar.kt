package es.topophone.app.ui.status

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import es.topophone.app.R
import es.topophone.app.ui.receiver.nameRes
import es.topophone.app.ui.receiver.tone
import es.topophone.app.ui.receiver.uiLocale
import es.topophone.core.designsystem.component.MinTouchTarget
import es.topophone.core.designsystem.component.StatusChip
import es.topophone.core.designsystem.theme.LocalStatusPalette
import es.topophone.core.designsystem.theme.StatusTone
import es.topophone.core.gnss.nmea.SolutionStatus
import es.topophone.core.gnss.nmea.TiltState

const val STATUS_BAR_TAG = "status_bar"
const val STATUS_BAR_SOLUTION_TAG = "status_bar_solution"
const val STATUS_BAR_SIMULATION_TAG = "status_bar_simulation"

/** De dónde llegan los datos que resume la barra. */
enum class StatusSource { NONE, RECEIVER, REPLAY }

/** Lo que muestra la barra de estado (S003/RF-17 a RF-22). */
data class StatusBarState(
    val source: StatusSource = StatusSource.NONE,
    val stale: Boolean = false,
    val solution: SolutionStatus? = null,
    val satellites: Int? = null,
    val correctionAgeS: Double? = null,
    val recording: Boolean = false,
    val tilt: TiltState? = null,
    /** Se está reconectando con el receptor (S004/RF-23). */
    val reconnecting: Boolean = false,
)

/** Resumen del receptor en todas las secciones; al pulsarla se abre Receptor (S003/RF-16 a RF-23). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun StatusBar(state: StatusBarState, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val openReceiver = stringResource(R.string.status_bar_open_receiver)
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = modifier.fillMaxWidth()) {
        FlowRow(
            modifier = Modifier
                .clickable(onClickLabel = openReceiver, onClick = onClick)
                .semantics(mergeDescendants = true) {}
                .heightIn(min = MinTouchTarget)
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .testTag(STATUS_BAR_TAG),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            if (state.source == StatusSource.REPLAY) {
                StatusChip(
                    stringResource(R.string.status_bar_simulation),
                    StatusTone.SIMULATION,
                    Modifier.testTag(STATUS_BAR_SIMULATION_TAG),
                )
            } // S003/RF-21
            Content(state)
            if (state.recording) Recording() // S003/RF-20
            state.tilt?.let { Text(stringResource(R.string.status_bar_tilt, stringResource(tiltNameRes(it)))) } // S003/RF-22
        }
    }
}

@Composable
private fun Content(state: StatusBarState) {
    val solution = state.solution
    when {
        state.source == StatusSource.NONE -> Text(stringResource(R.string.status_bar_no_receiver)) // S003/RF-17
        // S004/RF-23
        state.reconnecting -> Text(stringResource(R.string.status_bar_reconnecting), style = MaterialTheme.typography.titleSmall)
        state.stale -> Text(stringResource(R.string.status_bar_no_data), style = MaterialTheme.typography.titleSmall) // S003/RF-19
        solution != null -> {
            StatusChip(stringResource(solution.nameRes()), solution.tone(), Modifier.testTag(STATUS_BAR_SOLUTION_TAG)) // S003/RF-18
            state.satellites?.let { Text(stringResource(R.string.status_bar_satellites, it)) }
            state.correctionAgeS?.let {
                Text(stringResource(R.string.status_bar_correction_age, String.format(uiLocale(), "%.1f", it)))
            }
        }
    }
}

@Composable
private fun Recording() {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        val color = LocalStatusPalette.current.colorsFor(StatusTone.NEGATIVE).container
        Box(Modifier.size(10.dp).background(color, CircleShape))
        Text(stringResource(R.string.status_bar_recording))
    }
}

/** Nombre del estado de la inclinación de ETC (S003/RF-22). */
internal fun tiltNameRes(state: TiltState): Int = when (state) {
    TiltState.OFF -> R.string.tilt_off
    TiltState.FATAL -> R.string.tilt_fatal
    TiltState.SETUP -> R.string.tilt_setup
    TiltState.ALIGNMENT -> R.string.tilt_alignment
    TiltState.COMPENSATING -> R.string.tilt_compensating
}
