package es.topophone.app.ui.collector

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import es.topophone.app.R

const val COLLECTOR_LINE_TAG = "collector_line"

/** La línea activa del proyecto con su nombre y su número de vértices, para que se vea dónde irán los puntos (S025/RF-3). */
@Composable
internal fun CollectorLineStatus(line: CollectorLineUi?) {
    if (line == null) return
    Column(verticalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.fillMaxWidth().testTag(COLLECTOR_LINE_TAG)) {
        Text(
            stringResource(R.string.collector_line_active, line.name),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            pluralStringResource(R.plurals.collector_line_vertices, line.vertexCount, line.vertexCount),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}
