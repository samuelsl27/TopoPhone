package es.topophone.app.ui.collector

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import es.topophone.app.R
import es.topophone.app.ui.projects.unitSymbol
import es.topophone.app.ui.receiver.uiLocale
import es.topophone.core.designsystem.component.MinTouchTarget
import es.topophone.core.model.format.DurationText
import es.topophone.core.model.format.LengthFormat
import es.topophone.core.model.project.LinearUnit
import es.topophone.core.survey.Dispersion

const val COLLECTOR_FINISH_TAG = "collector_finish"
const val COLLECTOR_STATS_TAG = "collector_stats"
const val COLLECTOR_DEVIATION_TAG = "collector_deviation"
const val COLLECTOR_STABILITY_TAG = "collector_stability"
const val COLLECTOR_PLOT_TAG = "collector_plot"
const val COLLECTOR_PAUSED_TAG = "collector_paused"
const val COLLECTOR_NO_EPOCHS_TAG = "collector_no_epochs"
const val COLLECTOR_ANNOUNCE_TAG = "collector_announce"
const val COLLECTOR_LONG_PAUSE_SAVE_TAG = "collector_long_pause_save"
const val COLLECTOR_LONG_PAUSE_DISCARD_TAG = "collector_long_pause_discard"
const val COLLECTOR_LONG_PAUSE_WAIT_TAG = "collector_long_pause_wait"

private const val SECONDS_PER_MINUTE = 60

/** Lo más que ocupa el bloque de estadísticas: si no cabe, se desplaza y "Terminar" y "Cancelar" se ven siempre. */
internal val STATS_MAX_HEIGHT = 132.dp
private val PLOT_HEIGHT = 96.dp

/**
 * El avance de un promediado: tiempo reunido sobre el pedido, épocas usadas y descartadas y, cada minuto, el anuncio para
 * TalkBack (S017/RF-2; RNF-T8). Lo que cambia cada segundo no es una región viva: solo lo es el minuto.
 */
@Composable
internal fun AveragingProgress(progress: MeasureProgress) {
    Text(
        stringResource(
            R.string.collector_progress_time,
            DurationText.format(progress.elapsedS.toInt()),
            DurationText.format(progress.requiredS.toInt()),
        ),
    )
    LinearProgressIndicator(progress = { (progress.elapsedS / progress.requiredS).toFloat() }, modifier = Modifier.fillMaxWidth())
    Text(stringResource(R.string.collector_epochs, progress.epochsUsed, progress.epochsDiscarded))
    if (progress.requiredS >= SECONDS_PER_MINUTE) {
        val minutes = (progress.elapsedS / SECONDS_PER_MINUTE).toInt()
        val total = ((progress.requiredS + SECONDS_PER_MINUTE - 1) / SECONDS_PER_MINUTE).toInt()
        val announcement = stringResource(R.string.collector_announce_minutes, minutes, total)
        Box(
            Modifier.size(1.dp).testTag(COLLECTOR_ANNOUNCE_TAG).semantics {
                liveRegion = LiveRegionMode.Polite
                contentDescription = announcement
            },
        )
    }
}

/** Desviaciones típicas, estabilidad de la media y gráfico de dispersión, en la unidad del proyecto (S017/RF-3 a RF-5). */
@Composable
internal fun AverageStatsBlock(stats: AverageStatsUi, unit: LinearUnit) {
    val snapshot = stats.snapshot
    val locale = uiLocale()
    val symbol = unitSymbol(unit)
    val noData = stringResource(R.string.collector_no_data_stat)

    fun length(valueM: Double?) = valueM?.let { "${LengthFormat.inUnit(it, unit, locale)} $symbol" } ?: noData
    val deviationH = length(snapshot.deviationHorizontalM)
    Column(modifier = Modifier.testTag(COLLECTOR_STATS_TAG), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            stringResource(R.string.collector_deviation, deviationH, length(snapshot.deviationVerticalM)),
            modifier = Modifier.testTag(COLLECTOR_DEVIATION_TAG),
        )
        Text(
            stringResource(R.string.collector_stability, length(snapshot.stabilityHorizontalM), length(snapshot.stabilityVerticalM)),
            modifier = Modifier.testTag(COLLECTOR_STABILITY_TAG),
        )
        stats.dispersion?.let { plot ->
            val scale = length(plot.scaleM)
            Text(stringResource(R.string.collector_dispersion_scale, scale), style = MaterialTheme.typography.bodySmall)
            DispersionPlot(plot, stringResource(R.string.collector_dispersion_description, snapshot.used, scale, deviationH))
        }
    }
}

/**
 * Las épocas usadas, Este y Norte, alrededor de la media (el centro), hasta el radio de la escala (S017/RF-5). El texto de
 * [description] lo lee TalkBack: el gráfico no es la única forma de saber cuánto se dispersan.
 */
@Composable
internal fun DispersionPlot(plot: Dispersion, description: String) {
    val dot = MaterialTheme.colorScheme.primary
    val axis = MaterialTheme.colorScheme.outline
    Canvas(
        modifier = Modifier.fillMaxWidth().height(PLOT_HEIGHT).testTag(COLLECTOR_PLOT_TAG).semantics { contentDescription = description },
    ) {
        val half = size.minDimension / 2f
        val center = Offset(size.width / 2f, size.height / 2f)
        drawRect(axis, topLeft = Offset(center.x - half, center.y - half), size = Size(half * 2, half * 2), style = Stroke(1.dp.toPx()))
        drawLine(axis, Offset(center.x - half, center.y), Offset(center.x + half, center.y), strokeWidth = 1.dp.toPx())
        drawLine(axis, Offset(center.x, center.y - half), Offset(center.x, center.y + half), strokeWidth = 1.dp.toPx())
        val scale = plot.scaleM.toFloat()
        for (i in 0 until plot.count) {
            val x = center.x + plot.eastM[i] / scale * half
            val y = center.y - plot.northM[i] / scale * half // el Norte hacia arriba
            drawCircle(dot, radius = 1.5.dp.toPx(), center = Offset(x, y), alpha = 0.6f)
        }
    }
}

/** El promediado está parado por un corte; sigue solo al volver los datos (S017/RF-8, RF-9). */
@Composable
internal fun PausedBanner() {
    Text(
        stringResource(R.string.collector_paused),
        color = MaterialTheme.colorScheme.error,
        modifier = Modifier.testTag(COLLECTOR_PAUSED_TAG).semantics { liveRegion = LiveRegionMode.Polite },
    )
}

/** A los 5 min de corte: guardar con lo reunido, descartar o seguir esperando (S017/RF-10). */
@Composable
internal fun LongPauseDialog(onSave: () -> Unit, onDiscard: () -> Unit, onWait: () -> Unit) {
    AlertDialog(
        onDismissRequest = {},
        title = { Text(stringResource(R.string.collector_long_pause_title)) },
        text = { Text(stringResource(R.string.collector_long_pause_message)) },
        confirmButton = {
            Column {
                TextButton(onClick = onSave, modifier = Modifier.heightIn(min = MinTouchTarget).testTag(COLLECTOR_LONG_PAUSE_SAVE_TAG)) {
                    Text(stringResource(R.string.collector_long_pause_save))
                }
                TextButton(onClick = onWait, modifier = Modifier.heightIn(min = MinTouchTarget).testTag(COLLECTOR_LONG_PAUSE_WAIT_TAG)) {
                    Text(stringResource(R.string.collector_long_pause_wait))
                }
                TextButton(
                    onClick = onDiscard,
                    modifier = Modifier.heightIn(min = MinTouchTarget).testTag(COLLECTOR_LONG_PAUSE_DISCARD_TAG),
                ) {
                    Text(stringResource(R.string.collector_long_pause_discard))
                }
            }
        },
    )
}
