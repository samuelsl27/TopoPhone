package es.topophone.app.ui.receiver

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import es.topophone.app.R
import es.topophone.app.receiver.VisibleNotices
import es.topophone.core.designsystem.component.MinTouchTarget
import es.topophone.core.designsystem.theme.LocalStatusPalette
import es.topophone.core.designsystem.theme.StatusTone
import es.topophone.core.model.format.LengthFormat
import java.util.Locale

const val LONG_BASELINE_NOTICE_TAG = "notice_long_baseline"
const val BASE_CHANGED_NOTICE_TAG = "notice_base_changed"

fun noticeCloseTag(tag: String) = "${tag}_close"

/** Los avisos de la base que no se han cerrado, en la pantalla Estado y en el recolector (S015/RF-15, RF-17). */
@Composable
fun BaseNoticeBanners(notices: VisibleNotices, onDismissChange: (Int) -> Unit, onDismissLongBaseline: (Int) -> Unit) {
    if (notices.longBaselineM == null && notices.change == null) return
    val locale = uiLocale()
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        notices.longBaselineM?.let { metres ->
            NoticeBanner(
                stringResource(R.string.notice_long_baseline, String.format(locale, "%.3f", metres / METRES_PER_KM)),
                LONG_BASELINE_NOTICE_TAG,
            ) { onDismissLongBaseline(notices.baseSerial) }
        }
        notices.change?.let { change ->
            NoticeBanner(stringResource(R.string.notice_base_changed, distance(change.distanceM, locale)), BASE_CHANGED_NOTICE_TAG) {
                onDismissChange(change.count)
            }
        }
    }
}

@Composable
private fun NoticeBanner(text: String, tag: String, onClose: () -> Unit) {
    val colors = LocalStatusPalette.current.colorsFor(StatusTone.WARNING)
    Surface(
        color = colors.container,
        contentColor = colors.content,
        shape = MaterialTheme.shapes.small,
        // TalkBack lee el aviso de una vez; el botón Cerrar sigue siendo un elemento aparte.
        modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}.testTag(tag),
    ) {
        Row(modifier = Modifier.padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(text, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
            TextButton(
                onClick = onClose,
                colors = ButtonDefaults.textButtonColors(contentColor = colors.content),
                modifier = Modifier.heightIn(min = MinTouchTarget).testTag(noticeCloseTag(tag)),
            ) { Text(stringResource(R.string.notice_close)) }
        }
    }
}

/** En metros por debajo de 1 km y en km a partir de ahí. */
private fun distance(metres: Double, locale: Locale): String =
    if (metres < METRES_PER_KM) LengthFormat.metres(metres, locale) else String.format(locale, "%.3f km", metres / METRES_PER_KM)

private const val METRES_PER_KM = 1_000.0
