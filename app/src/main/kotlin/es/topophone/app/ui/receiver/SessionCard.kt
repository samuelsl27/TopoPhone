package es.topophone.app.ui.receiver

import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import es.topophone.app.R
import es.topophone.core.designsystem.component.MinTouchTarget

const val SHARE_SESSION_TAG = "share_session"

/** Tarjeta Sesión: compartir el fichero de la última sesión (S002/RF-43). */
@Composable
internal fun SessionCard(sessionAvailable: Boolean, onShareSession: () -> Unit) {
    ReceiverCard(stringResource(R.string.receiver_session_title)) {
        Text(stringResource(R.string.receiver_session_help), style = MaterialTheme.typography.bodyMedium)
        if (!sessionAvailable) Text(stringResource(R.string.receiver_no_session))
        OutlinedButton(
            onClick = onShareSession,
            enabled = sessionAvailable,
            modifier = Modifier.heightIn(min = MinTouchTarget).testTag(SHARE_SESSION_TAG),
        ) { Text(stringResource(R.string.receiver_share_session)) }
    }
}
