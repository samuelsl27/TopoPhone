package es.topophone.app.ui.status

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
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
import es.topophone.core.designsystem.component.MinTouchTarget
import es.topophone.core.designsystem.component.StatusChip
import es.topophone.core.designsystem.theme.StatusTone
import es.topophone.core.gnss.nmea.SolutionStatus

const val STATUS_INDICATOR_TAG = "status_indicator"
const val STATUS_INDICATOR_SOLUTION_TAG = "status_indicator_solution"
const val STATUS_INDICATOR_SIMULATION_TAG = "status_indicator_simulation"

/** De dónde llegan los datos que resume el indicador. */
enum class StatusSource { NONE, RECEIVER, REPLAY }

/** Lo que muestra el indicador de estado (S003/RF-17 a RF-21, S004/RF-23). */
data class StatusIndicatorState(
    val source: StatusSource = StatusSource.NONE,
    val stale: Boolean = false,
    val solution: SolutionStatus? = null,
    val satellites: Int? = null,
    /** Se está reconectando con el receptor (S004/RF-23). */
    val reconnecting: Boolean = false,
)

/**
 * Resumen del receptor en el extremo derecho de la barra superior de todas las secciones; al
 * pulsarlo se abre Receptor (S003/RF-16 a RF-23). Va en las acciones de la barra, que se miden
 * antes que el título: si no cabe todo, se acorta el título y el indicador se ve entero (S005/RF-18).
 */
@Composable
fun StatusIndicator(state: StatusIndicatorState, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val openReceiver = stringResource(R.string.status_bar_open_receiver)
    Row(
        modifier = modifier
            .clickable(onClickLabel = openReceiver, onClick = onClick)
            .semantics(mergeDescendants = true) {}
            .heightIn(min = MinTouchTarget)
            .padding(horizontal = 4.dp)
            .testTag(STATUS_INDICATOR_TAG),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (state.source == StatusSource.REPLAY) {
            StatusChip(
                stringResource(R.string.status_bar_simulation),
                StatusTone.SIMULATION,
                Modifier.testTag(STATUS_INDICATOR_SIMULATION_TAG),
                compact = true,
            )
        } // S003/RF-21
        Content(state)
    }
}

@Composable
private fun Content(state: StatusIndicatorState) {
    val solution = state.solution
    when {
        state.source == StatusSource.NONE -> Label(stringResource(R.string.status_bar_no_receiver)) // S003/RF-17
        state.reconnecting -> Label(stringResource(R.string.status_bar_reconnecting)) // S004/RF-23
        state.stale -> Label(stringResource(R.string.status_bar_no_data)) // S003/RF-19
        solution != null -> {
            StatusChip(stringResource(solution.nameRes()), solution.tone(), Modifier.testTag(STATUS_INDICATOR_SOLUTION_TAG), compact = true)
            state.satellites?.let { Label(stringResource(R.string.status_bar_satellites, it)) } // S003/RF-18
        }
    }
}

@Composable
private fun Label(text: String) {
    Text(text, style = MaterialTheme.typography.labelLarge, maxLines = 1, softWrap = false)
}
