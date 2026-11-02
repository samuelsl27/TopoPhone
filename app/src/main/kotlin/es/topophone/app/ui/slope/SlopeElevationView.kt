package es.topophone.app.ui.slope

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import es.topophone.core.model.alignment.slope.SlopeKind
import es.topophone.core.survey.SlopeEdgeResult
import kotlin.math.max
import kotlin.math.min

const val SLOPE_ELEVATION_TAG = "slope_elevation"

/**
 * Lo que dibuja el alzado del talud: la sección vista de frente con el borde en el origen y la distancia [catchT] / [tipT] medida hacia
 * fuera del eje (S051/RF-10, RF-11). Las alturas son cotas. [kind] y [ratio] fijan el talud de diseño; sin ellos no hay talud que dibujar.
 */
data class SlopeElevation(
    val kind: SlopeKind,
    val ratio: Double,
    val edgeHeightM: Double,
    val catchT: Double?,
    val catchHeightM: Double?,
    val tipT: Double?,
    val tipHeightM: Double?,
) {
    /** La cota del talud de diseño a [t] metros hacia fuera del borde: sube en desmonte y baja en terraplén. */
    fun slopeAt(t: Double): Double = edgeHeightM + (if (kind == SlopeKind.CUT) 1.0 else -1.0) * t / ratio

    companion object {
        /**
         * El alzado del PK y el lado de ahora, o null si no hay borde o no se sabe si es desmonte o terraplén (el terreno lo dice o, sin
         * él, la cota de la punta).
         */
        fun of(state: SlopeStakeUiState): SlopeElevation? {
            val edge = state.outcome?.edgeOrNull() ?: return null
            val found = (state.outcome as? SlopePointOutcome.Found)?.point
            val live = state.live
            val kind = live?.kind ?: found?.kind ?: return null
            val ratio = edge.rule.ratioOf(kind)
            val direction = if (kind == SlopeKind.CUT) 1.0 else -1.0
            val side = edge.rule.side.sign
            return SlopeElevation(
                kind = kind,
                ratio = ratio,
                edgeHeightM = edge.heightM,
                catchT = found?.let { side * (it.offsetM - edge.offsetM) },
                catchHeightM = found?.heightM,
                tipT = live?.let { (it.slopeHeightM - edge.heightM) * direction * ratio },
                tipHeightM = live?.let { it.slopeHeightM + it.dHeightM },
            )
        }
    }
}

/** El borde de la sección de un resultado que lo tiene, para quien necesite sus datos. */
internal fun SlopePointOutcome.edgeOrNull(): SlopeEdgeResult.Edge? = when (this) {
    is SlopePointOutcome.Found -> edge
    is SlopePointOutcome.AtEdge -> edge
    is SlopePointOutcome.NoIntersection -> edge
    is SlopePointOutcome.NoTerrain -> edge
    is SlopePointOutcome.Unavailable -> null
}

/**
 * El alzado en vivo (S051/RF-10, RF-11): una línea de talud de diseño desde el borde, el punto de talud del terreno si se conoce y la punta
 * del jalón. [description] es la frase que lee TalkBack, porque el dibujo no se lee (RNF-T8).
 */
@Composable
fun SlopeElevationView(elevation: SlopeElevation, description: String, modifier: Modifier = Modifier) {
    val slope = MaterialTheme.colorScheme.primary
    val ground = MaterialTheme.colorScheme.tertiary
    val tip = MaterialTheme.colorScheme.error
    val axis = MaterialTheme.colorScheme.outline
    Canvas(
        modifier = modifier.fillMaxWidth().height(ELEVATION_HEIGHT).testTag(SLOPE_ELEVATION_TAG)
            .clearAndSetSemantics { contentDescription = description },
    ) {
        val reach = max(MIN_REACH_M, listOfNotNull(elevation.catchT, elevation.tipT).maxOrNull()?.times(REACH_MARGIN) ?: 0.0)
        val heights = listOfNotNull(
            elevation.edgeHeightM,
            elevation.slopeAt(reach),
            elevation.catchHeightM,
            elevation.tipHeightM,
        )
        val low = heights.min()
        val high = max(heights.max(), low + MIN_SPAN_M)
        val margin = PAD.toPx()
        val width = size.width - 2 * margin
        val height = size.height - 2 * margin

        // El eje de la sección a la izquierda y la plataforma de ahí al borde (t = 0).
        val platform = width * PLATFORM_SHARE
        fun x(t: Double) = margin + platform + ((t / reach) * (width - platform).toDouble()).toFloat()
        fun y(z: Double) = margin + height - (((z - low) / (high - low)) * height).toFloat()

        drawLine(axis, Offset(margin, y(elevation.edgeHeightM)), Offset(x(0.0), y(elevation.edgeHeightM)), strokeWidth = LINE.toPx())
        drawLine(slope, Offset(x(0.0), y(elevation.edgeHeightM)), Offset(x(reach), y(elevation.slopeAt(reach))), strokeWidth = LINE.toPx())
        if (elevation.catchT != null && elevation.catchHeightM != null) {
            drawLine(
                ground,
                Offset(x(0.0), y(elevation.catchHeightM)),
                Offset(x(elevation.catchT), y(elevation.catchHeightM)),
                strokeWidth = HAIR.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(DASH.toPx(), DASH.toPx())),
            )
            drawCircle(ground, DOT.toPx(), Offset(x(elevation.catchT), y(elevation.catchHeightM)))
        }
        if (elevation.tipT != null && elevation.tipHeightM != null) {
            val t = min(max(elevation.tipT, 0.0), reach)
            drawLine(
                tip,
                Offset(x(t), y(elevation.slopeAt(t))),
                Offset(x(t), y(elevation.tipHeightM)),
                strokeWidth = HAIR.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(DASH.toPx(), DASH.toPx())),
            )
            drawCircle(tip, DOT.toPx(), Offset(x(t), y(elevation.tipHeightM)), style = Stroke(LINE.toPx()))
        }
    }
}

private val ELEVATION_HEIGHT = 120.dp
private val PAD = 8.dp
private val LINE = 3.dp
private val HAIR = 2.dp
private val DASH = 4.dp
private val DOT = 6.dp
private const val MIN_REACH_M = 3.0
private const val REACH_MARGIN = 1.25
private const val MIN_SPAN_M = 1.0
private const val PLATFORM_SHARE = 0.2f
