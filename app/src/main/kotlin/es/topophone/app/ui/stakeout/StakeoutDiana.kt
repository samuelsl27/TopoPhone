package es.topophone.app.ui.stakeout

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import es.topophone.app.R
import es.topophone.core.survey.DianaView
import es.topophone.core.survey.StakeZone
import kotlin.math.abs

const val STAKEOUT_DIANA_TAG = "stakeout_diana"
const val STAKEOUT_ARROW_TAG = "stakeout_arrow"
const val STAKEOUT_INSIDE_TAG = "stakeout_inside"

/** Verde de "dentro de la tolerancia" (S018/RF-19); siempre con su texto, nunca solo con color (RNF-T8). */
private val InsideGreen = Color(0xFF2E7D32)

/**
 * La flecha hacia el punto de diseño respecto al rumbo del usuario: arriba es seguir de frente (S018/RF-7). Un `Canvas`
 * aparte del mapa, con su descripción para TalkBack.
 */
@Composable
fun StakeoutArrow(aheadM: Double, rightM: Double, modifier: Modifier = Modifier) {
    val degrees = DianaView.arrowDegrees(aheadM, rightM)
    val color = MaterialTheme.colorScheme.primary
    val where = when {
        abs(degrees) <= STRAIGHT_DEGREES -> stringResource(R.string.stakeout_arrow_ahead)
        abs(degrees) >= BEHIND_DEGREES -> stringResource(R.string.stakeout_arrow_behind)
        degrees > 0 -> stringResource(R.string.stakeout_arrow_right, degrees)
        else -> stringResource(R.string.stakeout_arrow_left, -degrees)
    }
    val description = stringResource(R.string.stakeout_arrow_description, where)
    Canvas(modifier = modifier.size(ARROW_SIZE).semantics { contentDescription = description }.testTag(STAKEOUT_ARROW_TAG)) {
        val radius = size.minDimension / 2f
        rotate(degrees = degrees.toFloat(), pivot = center) {
            val head = Path().apply {
                moveTo(center.x, center.y - radius)
                lineTo(center.x - radius * HEAD_HALF_WIDTH, center.y - radius * HEAD_BASE)
                lineTo(center.x + radius * HEAD_HALF_WIDTH, center.y - radius * HEAD_BASE)
                close()
            }
            drawPath(head, color)
            val shaftTop = Offset(center.x, center.y - radius * HEAD_BASE)
            drawLine(color, shaftTop, Offset(center.x, center.y + radius), strokeWidth = radius * SHAFT)
        }
    }
}

/**
 * La diana métrica a escala (S018/RF-15 a RF-17, RF-19): el punto de diseño en el centro, el círculo de la tolerancia y la
 * punta del jalón. El radio visible es [GuidanceUi.dianaRadiusM]; con [headingUp] el rumbo del usuario queda arriba.
 */
@Composable
fun StakeoutDiana(ui: GuidanceUi, toleranceM: Double, headingUp: Double?, modifier: Modifier = Modifier) {
    val inside = ui.zone == StakeZone.INSIDE
    val grid = MaterialTheme.colorScheme.onSurface.copy(alpha = GRID_ALPHA)
    val ring = if (inside) InsideGreen else MaterialTheme.colorScheme.primary
    val tipColor = MaterialTheme.colorScheme.error
    val description = stringResource(
        R.string.stakeout_diana_description,
        ui.dianaRadiusM.times(CENTIMETRES_PER_METRE).toInt(),
        (ui.guidance.distanceM * CENTIMETRES_PER_METRE).toInt(),
    )
    val tip = DianaView.position(ui.tipEastM, ui.tipNorthM, ui.dianaRadiusM, headingUp)
    val fraction = DianaView.toleranceFraction(toleranceM, ui.dianaRadiusM)
    Canvas(
        modifier = modifier.size(DIANA_MAX).semantics { contentDescription = description }.testTag(STAKEOUT_DIANA_TAG),
    ) {
        val radius = size.minDimension / 2f
        drawCircle(grid, radius, center, style = Stroke(width = 2f))
        drawLine(grid, Offset(center.x - radius, center.y), Offset(center.x + radius, center.y), strokeWidth = 1f)
        drawLine(grid, Offset(center.x, center.y - radius), Offset(center.x, center.y + radius), strokeWidth = 1f)
        val toleranceRadius = (radius * fraction).toFloat()
        if (inside) drawCircle(InsideGreen.copy(alpha = FILL_ALPHA), toleranceRadius, center)
        drawCircle(ring, toleranceRadius, center, style = Stroke(width = 4f))
        // El punto de diseño, una cruz en el centro.
        drawLine(ring, Offset(center.x - CROSS, center.y), Offset(center.x + CROSS, center.y), strokeWidth = 3f)
        drawLine(ring, Offset(center.x, center.y - CROSS), Offset(center.x, center.y + CROSS), strokeWidth = 3f)
        // La punta del jalón: y hacia arriba en la diana, hacia abajo en el lienzo.
        drawCircle(tipColor, TIP_RADIUS, Offset(center.x + (tip.x * radius).toFloat(), center.y - (tip.y * radius).toFloat()))
    }
}

private val ARROW_SIZE = 96.dp
private val DIANA_MAX = 220.dp
private const val STRAIGHT_DEGREES = 10
private const val BEHIND_DEGREES = 170
private const val HEAD_HALF_WIDTH = 0.45f
private const val HEAD_BASE = 0.35f
private const val SHAFT = 0.18f
private const val GRID_ALPHA = 0.35f
private const val FILL_ALPHA = 0.35f
private const val CROSS = 12f
private const val TIP_RADIUS = 10f
private const val CENTIMETRES_PER_METRE = 100.0
