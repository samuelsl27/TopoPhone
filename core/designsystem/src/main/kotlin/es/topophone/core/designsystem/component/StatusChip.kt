package es.topophone.core.designsystem.component

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.SemanticsPropertyReceiver
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import es.topophone.core.designsystem.theme.LocalStatusPalette
import es.topophone.core.designsystem.theme.StatusTone

/** Tono que pinta un [StatusChip], expuesto en la semántica para los tests de interfaz. */
val StatusToneKey = SemanticsPropertyKey<StatusTone>("StatusTone")
private var SemanticsPropertyReceiver.statusTone by StatusToneKey

/**
 * Estado con color **y** texto (RNF-T8).
 *
 * @param compact versión pequeña para la barra superior (S005).
 */
@Composable
fun StatusChip(text: String, tone: StatusTone, modifier: Modifier = Modifier, compact: Boolean = false) {
    val colors = LocalStatusPalette.current.colorsFor(tone)
    Surface(
        modifier = modifier.semantics(mergeDescendants = true) { statusTone = tone },
        shape = MaterialTheme.shapes.small,
        color = colors.container,
        contentColor = colors.content,
    ) {
        if (compact) {
            Text(
                text,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                softWrap = false,
            )
        } else {
            Text(text, modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp), style = MaterialTheme.typography.titleMedium)
        }
    }
}
