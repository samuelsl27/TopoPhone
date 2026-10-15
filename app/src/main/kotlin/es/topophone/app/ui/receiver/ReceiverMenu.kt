package es.topophone.app.ui.receiver

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import es.topophone.app.R
import es.topophone.core.designsystem.component.StatusChip
import es.topophone.receiver.session.ConnectionState

const val MENU_STATUS_TAG = "menu_status"
const val MENU_COORDINATES_TAG = "menu_coordinates"
const val MENU_CORRECTIONS_TAG = "menu_corrections"
const val MENU_SESSIONS_TAG = "menu_sessions"

/** Menú de la pantalla del receptor: cuatro entradas, cada una con su resumen (S005/RF-9 a RF-13). */
@Composable
internal fun ReceiverMenu(state: ReceiverUiState, actions: ReceiverActions) {
    val snapshot = state.snapshot
    Card(modifier = Modifier.fillMaxWidth()) {
        ListRow(stringResource(R.string.receiver_menu_status), actions.onOpenStatus, Modifier.testTag(MENU_STATUS_TAG)) {
            val gga = snapshot.gga
            if (gga != null && snapshot.connection is ConnectionState.Receiving) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatusChip(stringResource(gga.status.nameRes()), gga.status.tone(), compact = true)
                    Text(stringResource(R.string.status_bar_satellites, gga.satellites))
                }
            } else {
                Text(stringResource(R.string.status_bar_no_data))
            }
        } // S005/RF-10
        HorizontalDivider()
        ListRow(stringResource(R.string.receiver_menu_coordinates), actions.onOpenCoordinates, Modifier.testTag(MENU_COORDINATES_TAG)) {
            Text(stringResource(R.string.receiver_menu_pole_height, state.poleHeightText))
        } // S005/RF-11
        HorizontalDivider()
        ListRow(stringResource(R.string.receiver_menu_corrections), actions.onOpenCorrections, Modifier.testTag(MENU_CORRECTIONS_TAG)) {
            val ntrip = ntripStatusText(snapshot.ntrip)
            val age = snapshot.gga?.correctionAgeS?.let { String.format(uiLocale(), "%.1f", it) }
            Text(if (age == null) ntrip else stringResource(R.string.receiver_menu_corrections_age, ntrip, age))
        } // S005/RF-12
        HorizontalDivider()
        val sessionsSummary: (@Composable () -> Unit)? = if (snapshot.recording) {
            { Text(stringResource(R.string.status_bar_recording)) }
        } else {
            null
        } // S005/RF-13
        ListRow(
            stringResource(R.string.receiver_menu_sessions),
            actions.onOpenSessions,
            Modifier.testTag(MENU_SESSIONS_TAG),
            sessionsSummary,
        )
    }
}
