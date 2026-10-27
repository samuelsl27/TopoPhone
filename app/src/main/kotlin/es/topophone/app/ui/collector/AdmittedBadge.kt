package es.topophone.app.ui.collector

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import es.topophone.app.R
import es.topophone.core.model.point.AdmittedSolutions

const val ADMITTED_BADGE_TAG = "admitted_badge"

/**
 * Mientras se admita algo más que FIX, un aviso con icono y texto junto al tipo de solución, en el recolector y el replanteo
 * (S047/RF-14, RNF-T8). Con solo FIX no se muestra nada.
 */
@Composable
fun AdmittedBadge(admitted: AdmittedSolutions, modifier: Modifier = Modifier) {
    if (admitted.onlyFix) return
    val text = stringResource(R.string.admitted_badge, admitted.types.sorted().joinToString(", ") { it.name })
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier.semantics(mergeDescendants = true) {}.testTag(ADMITTED_BADGE_TAG),
    ) {
        Icon(
            painterResource(R.drawable.ic_warning),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.error,
            modifier = Modifier.size(20.dp),
        )
        Text(text, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
    }
}
