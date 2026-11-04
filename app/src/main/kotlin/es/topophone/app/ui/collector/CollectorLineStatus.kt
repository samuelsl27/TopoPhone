package es.topophone.app.ui.collector

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import es.topophone.app.R
import es.topophone.core.designsystem.component.MinTouchTarget
import es.topophone.core.model.line.LineSummary

const val COLLECTOR_LINE_TAG = "collector_line"
const val COLLECTOR_LINE_FINISH_TAG = "collector_line_finish"
const val COLLECTOR_LINE_UNDO_TAG = "collector_line_undo"
const val COLLECTOR_LINE_CONTINUE_TAG = "collector_line_continue"
const val COLLECTOR_LINE_CONTINUE_EMPTY_TAG = "collector_line_continue_empty"
const val COLLECTOR_LINE_CONTINUE_CLOSE_TAG = "collector_line_continue_close"

fun continueLineTag(name: String) = "collector_line_continue_$name"

/**
 * La línea activa del proyecto con su nombre y su número de vértices, y sus botones: «Terminar línea», «Deshacer vértice» y «Continuar
 * línea», que lista las abiertas del proyecto (S025/RF-3 a RF-6). Con una medida en marcha no se tocan.
 */
@Composable
internal fun CollectorLineStatus(line: CollectorLineUi?, openLines: List<LineSummary>, enabled: Boolean, actions: CollectorActions) {
    var picking by rememberSaveable { mutableStateOf(false) }
    if (line != null) {
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
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(
                onClick = actions.onUndoLineVertex,
                enabled = enabled && line.vertexCount > 0,
                modifier = Modifier.weight(1f).heightIn(min = MinTouchTarget).testTag(COLLECTOR_LINE_UNDO_TAG),
            ) { Text(stringResource(R.string.collector_line_undo)) }
            OutlinedButton(
                onClick = actions.onFinishLine,
                enabled = enabled,
                modifier = Modifier.weight(1f).heightIn(min = MinTouchTarget).testTag(COLLECTOR_LINE_FINISH_TAG),
            ) { Text(stringResource(R.string.collector_line_finish)) }
        }
    }
    if (openLines.isNotEmpty() || picking) {
        OutlinedButton(
            onClick = { picking = true },
            enabled = enabled,
            modifier = Modifier.fillMaxWidth().heightIn(min = MinTouchTarget).testTag(COLLECTOR_LINE_CONTINUE_TAG),
        ) { Text(stringResource(R.string.collector_line_continue)) }
    }
    if (picking) {
        ContinueLineDialog(openLines, onPick = { id ->
            picking = false
            actions.onContinueLine(id)
        }, onDismiss = { picking = false })
    }
}

/** Las líneas abiertas del proyecto con su código y su número de vértices, para continuar una (S025/RF-5). */
@Composable
internal fun ContinueLineDialog(openLines: List<LineSummary>, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.collector_line_continue_title)) },
        text = {
            if (openLines.isEmpty()) {
                Text(stringResource(R.string.collector_line_continue_empty), modifier = Modifier.testTag(COLLECTOR_LINE_CONTINUE_EMPTY_TAG))
            } else {
                LazyColumn(modifier = Modifier.heightIn(max = 360.dp)) {
                    items(openLines, key = { it.id }) { line -> OpenLineRow(line) { onPick(line.id) } }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.heightIn(min = MinTouchTarget).testTag(COLLECTOR_LINE_CONTINUE_CLOSE_TAG),
            ) { Text(stringResource(R.string.collector_close)) }
        },
    )
}

@Composable
private fun OpenLineRow(line: LineSummary, onClick: () -> Unit) {
    val vertices = pluralStringResource(R.plurals.collector_line_vertices, line.vertexCount, line.vertexCount)
    val detail = stringResource(R.string.collector_line_open_row, line.code?.code.orEmpty(), vertices)
    Column(
        modifier = Modifier.fillMaxWidth().heightIn(
            min = MinTouchTarget,
        ).clickable(onClick = onClick).padding(horizontal = 4.dp, vertical = 6.dp)
            .semantics(mergeDescendants = true) { contentDescription = line.name + ", " + detail }.testTag(continueLineTag(line.name)),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(line.name, style = MaterialTheme.typography.titleMedium)
        Text(detail, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 2.dp))
    }
}
