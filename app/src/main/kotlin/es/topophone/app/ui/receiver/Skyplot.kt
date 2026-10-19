package es.topophone.app.ui.receiver

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import es.topophone.app.R
import es.topophone.core.gnss.nmea.Constellation
import es.topophone.core.gnss.nmea.SkySatellite
import es.topophone.core.gnss.signal.SatelliteNumber
import es.topophone.receiver.session.ConnectionState
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

const val SKYPLOT_TAG = "skyplot"

/** Un satélite dibujado en el skyplot: posición en píxeles, etiqueta ("G05") y si va relleno (usado). */
data class SkyplotMark(val label: String, val constellation: Constellation, val x: Float, val y: Float, val filled: Boolean)

/**
 * Posición de cada satélite en un lienzo cuadrado de [sizePx] (plan de S015, A8): norte arriba, azimut en sentido
 * horario, horizonte en el borde y cénit en el centro; r = R·(90° − elevación)/90°.
 */
fun skyplotMarks(satellites: List<SkySatellite>, sizePx: Float): List<SkyplotMark> {
    val center = sizePx / 2
    return satellites.map { satellite ->
        val elevation = satellite.elevationRad.coerceIn(0.0, PI / 2)
        val r = center * (1 - elevation / (PI / 2))
        SkyplotMark(
            label = satellite.id.label,
            constellation = satellite.id.constellation,
            x = (center + r * sin(satellite.azimuthRad)).toFloat(),
            y = (center - r * cos(satellite.azimuthRad)).toFloat(),
            filled = satellite.used,
        )
    }
}

/** Color de cada constelación en el skyplot y la SNR; la letra la distingue también sin color (RNF-T8). */
fun constellationColor(constellation: Constellation): Color = when (constellation) {
    Constellation.GPS -> Color(0xFF1E5AA8)
    Constellation.GLONASS -> Color(0xFFB3261E)
    Constellation.GALILEO -> Color(0xFF00796B)
    Constellation.BEIDOU -> Color(0xFFC25E00)
    Constellation.QZSS -> Color(0xFF6A3FA0)
    Constellation.NAVIC -> Color(0xFF6D4C41)
    Constellation.MULTIPLE, Constellation.UNKNOWN -> Color(0xFF757575)
}

/** Skyplot de los satélites en vista con elevación y azimut (S015/RF-4 a RF-6). */
@Composable
internal fun SkyplotCard(state: ReceiverUiState) {
    val sky = state.snapshot.sky ?: return
    if (state.snapshot.connection !is ConnectionState.Receiving) return
    ReceiverCard(stringResource(R.string.skyplot_title)) {
        // TalkBack: un resumen por constelación, en lugar del dibujo (RNF-T8).
        val summary = sky.counts.entries.sortedBy { it.key.ordinal }.map { (constellation, count) ->
            val name =
                stringResource(
                    R.string.status_constellation,
                    stringResource(constellation.nameRes()),
                    SatelliteNumber.letter(constellation),
                )
            stringResource(R.string.skyplot_constellation, name, count.used, count.inView)
        }.joinToString("; ")
        val cardinal = listOf(
            stringResource(R.string.skyplot_north),
            stringResource(R.string.skyplot_east),
            stringResource(R.string.skyplot_south),
            stringResource(R.string.skyplot_west),
        )
        val measurer = rememberTextMeasurer()
        val textColor = MaterialTheme.colorScheme.onSurface
        val gridColor = MaterialTheme.colorScheme.outline
        val labelStyle = MaterialTheme.typography.labelMedium.copy(color = textColor, fontFamily = FontFamily.Monospace)
        val cardinalStyle = MaterialTheme.typography.labelLarge.copy(color = textColor)
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .semantics { contentDescription = summary }
                .testTag(SKYPLOT_TAG),
        ) {
            val margin = 28.dp.toPx()
            val plot = size.minDimension - 2 * margin
            val center = Offset(size.width / 2, size.height / 2)
            drawGrid(center, plot / 2, gridColor)
            cardinal.forEachIndexed { i, label ->
                val layout = measurer.measure(label, cardinalStyle)
                val angle = i * PI / 2
                val r = plot / 2 + margin / 2
                val at = Offset((center.x + r * sin(angle)).toFloat(), (center.y - r * cos(angle)).toFloat())
                drawText(layout, topLeft = at - Offset(layout.size.width / 2f, layout.size.height / 2f))
            }
            val dot = 7.dp.toPx()
            skyplotMarks(sky.satellites, plot).forEach { mark ->
                val at = Offset(center.x - plot / 2 + mark.x, center.y - plot / 2 + mark.y)
                val color = constellationColor(mark.constellation)
                if (mark.filled) drawCircle(color, dot, at) else drawCircle(color, dot, at, style = Stroke(2.dp.toPx()))
                drawText(measurer, mark.label, topLeft = at + Offset(dot + 2.dp.toPx(), -dot), style = labelStyle)
            }
        }
    }
}

/** Anillos a 0°, 30° y 60° de elevación y los ejes norte-sur y este-oeste. */
private fun DrawScope.drawGrid(center: Offset, radius: Float, color: Color) {
    val stroke = Stroke(1.dp.toPx())
    listOf(1f, 2f / 3, 1f / 3).forEach { drawCircle(color, radius * it, center, style = stroke) }
    drawLine(color, center - Offset(0f, radius), center + Offset(0f, radius), strokeWidth = stroke.width)
    drawLine(color, center - Offset(radius, 0f), center + Offset(radius, 0f), strokeWidth = stroke.width)
}
