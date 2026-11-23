package es.topophone.app.ui.localization

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import es.topophone.app.R

/** Etiqueta del indicador «Localizado» (S039/RF-14). */
const val LOCALIZATION_BANNER_TAG = "localization_banner"

/**
 * El indicador «Localizado»: los puntos GNSS del proyecto se muestran en el sistema de la obra por una localización activa (S039/RF-14). Va en
 * texto, no solo en color (RNF-T8).
 */
@Composable
fun LocalizationActiveBanner(modifier: Modifier = Modifier) {
    Card(
        modifier = modifier.fillMaxWidth().testTag(LOCALIZATION_BANNER_TAG),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(R.string.localization_active_banner), style = MaterialTheme.typography.bodyMedium)
        }
    }
}
