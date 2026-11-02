package es.topophone.app.ui.linear

import android.content.Context
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import es.topophone.app.R
import es.topophone.app.ui.projects.unitSymbol
import es.topophone.app.ui.receiver.uiLocale
import es.topophone.core.designsystem.component.MinTouchTarget
import es.topophone.core.model.alignment.ChainageFormat
import es.topophone.core.model.alignment.OffsetFormat
import es.topophone.core.model.alignment.OffsetSide
import es.topophone.core.model.format.LengthFormat
import es.topophone.core.model.project.LinearUnit
import es.topophone.core.model.survey.linear.SurveyFlag
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow

const val PROFILE_VIEW_TAG = "profile_view"
const val PROFILE_CANVAS_TAG = "profile_canvas"
const val PROFILE_SELECTED_TAG = "profile_selected"
const val PROFILE_EMPTY_TAG = "profile_empty"
const val PROFILE_ZOOM_TAG = "profile_zoom"
const val PROFILE_ZOOM_IN_TAG = "profile_zoom_in"
const val PROFILE_ZOOM_OUT_TAG = "profile_zoom_out"
const val PROFILE_PAN_LEFT_TAG = "profile_pan_left"
const val PROFILE_PAN_RIGHT_TAG = "profile_pan_right"
const val PROFILE_FIT_TAG = "profile_fit"

/**
 * El alzado de un perfil tomado (S052/RF-13, RF-19): el PK o el desplazamiento en horizontal y la cota en vertical, el terreno medido, la
 * línea de referencia (rasante o sección de diseño) y, al tocar un punto, su cota, la de la referencia y el desmonte o terraplén. Se
 * acerca y se desplaza con el gesto de dos dedos o con los botones, que miden lo mismo que el resto (RNF-T8). El texto del punto elegido
 * es lo que lee TalkBack, porque el dibujo no se lee.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ProfileView(chart: ProfileChart, unit: LinearUnit, modifier: Modifier = Modifier) {
    val domain = chart.domain()
    var viewport by remember { mutableStateOf(ProfileViewport()) }
    var selected by remember { mutableStateOf<Int?>(null) }
    val locale = uiLocale()
    val context = LocalContext.current
    val measurer = rememberTextMeasurer()
    val ink = ProfileInk(
        ground = MaterialTheme.colorScheme.tertiary,
        reference = MaterialTheme.colorScheme.primary,
        rejected = MaterialTheme.colorScheme.error,
        axis = MaterialTheme.colorScheme.outline,
        text = MaterialTheme.colorScheme.onSurface,
        selected = MaterialTheme.colorScheme.secondary,
    )
    Column(modifier = modifier.fillMaxWidth().testTag(PROFILE_VIEW_TAG), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (domain == null) {
            Text(stringResource(R.string.profile_empty), modifier = Modifier.testTag(PROFILE_EMPTY_TAG))
            return@Column
        }
        val window = viewport.window(domain.first, domain.second)
        val currentWindow by rememberUpdatedState(window)
        val heights = chart.heightRange(window)
        val description = stringResource(
            R.string.profile_chart_description,
            chart.profilePoints.size,
            axisText(context, chart.axis, domain.first, unit, locale),
            axisText(context, chart.axis, domain.second, unit, locale),
        )
        Canvas(
            modifier = Modifier.fillMaxWidth().height(CHART_HEIGHT).testTag(PROFILE_CANVAS_TAG)
                .clearAndSetSemantics { contentDescription = description }
                .pointerInput(chart, domain) {
                    detectTapGestures { tap ->
                        val w = currentWindow
                        val fraction = (tap.x - LEFT_MARGIN.toPx()) / plotWidth(size.width.toFloat(), this)
                        selected = chart.nearest(w.first + fraction * (w.second - w.first))
                    }
                }
                .pointerInput(domain) {
                    detectTransformGestures { centroid, pan, zoom, _ ->
                        val width = plotWidth(size.width.toFloat(), this)
                        val anchor = ((centroid.x - LEFT_MARGIN.toPx()) / width).toDouble().coerceIn(0.0, 1.0)
                        viewport = viewport.zoomBy(zoom.toDouble(), anchor).panBy(-(pan.x / width).toDouble())
                    }
                },
        ) {
            drawProfile(chart, window, heights, selected, ink, measurer, unit, locale, context)
        }
        val point = selected?.let { chart.points.getOrNull(it) }
        Text(
            point?.let { selectionText(chart, it, unit, locale) } ?: stringResource(R.string.profile_select_hint),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.fillMaxWidth().testTag(PROFILE_SELECTED_TAG).semantics { liveRegion = LiveRegionMode.Polite },
        )
        Text(
            stringResource(R.string.profile_zoom, formatZoom(viewport.zoom, locale)),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.testTag(PROFILE_ZOOM_TAG),
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ChartButton(R.string.profile_zoom_in, PROFILE_ZOOM_IN_TAG) { viewport = viewport.zoomIn() }
            ChartButton(R.string.profile_zoom_out, PROFILE_ZOOM_OUT_TAG) { viewport = viewport.zoomOut() }
            ChartButton(R.string.profile_pan_left, PROFILE_PAN_LEFT_TAG) { viewport = viewport.panBy(-PAN_STEP) }
            ChartButton(R.string.profile_pan_right, PROFILE_PAN_RIGHT_TAG) { viewport = viewport.panBy(PAN_STEP) }
            ChartButton(R.string.profile_fit, PROFILE_FIT_TAG) { viewport = viewport.reset() }
        }
    }
}

@Composable
private fun ChartButton(label: Int, tag: String, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, modifier = Modifier.heightIn(min = MinTouchTarget).testTag(tag)) { Text(stringResource(label)) }
}

private class ProfileInk(
    val ground: Color,
    val reference: Color,
    val rejected: Color,
    val axis: Color,
    val text: Color,
    val selected: Color,
)

private fun plotWidth(totalPx: Float, density: androidx.compose.ui.unit.Density): Float =
    max(1f, totalPx - with(density) { LEFT_MARGIN.toPx() + RIGHT_MARGIN.toPx() })

/** Dibuja los ejes con sus números, la referencia, el terreno, los puntos con marca y el punto elegido (S052/RF-13, RF-19). */
private fun DrawScope.drawProfile(
    chart: ProfileChart,
    window: Pair<Double, Double>,
    heights: Pair<Double, Double>,
    selected: Int?,
    ink: ProfileInk,
    measurer: TextMeasurer,
    unit: LinearUnit,
    locale: Locale,
    context: Context,
) {
    val left = LEFT_MARGIN.toPx()
    val top = TOP_MARGIN.toPx()
    val width = size.width - left - RIGHT_MARGIN.toPx()
    val height = size.height - top - BOTTOM_MARGIN.toPx()
    val spanX = window.second - window.first
    val spanY = heights.second - heights.first
    fun px(x: Double) = left + ((x - window.first) / spanX * width).toFloat()
    fun py(z: Double) = top + height - ((z - heights.first) / spanY * height).toFloat()
    val label = TextStyle(color = ink.text, fontSize = LABEL_SP)

    // Las marcas de los ejes, con la cuadrícula suave.
    for (x in ticks(window.first, window.second, TICKS_X)) {
        drawLine(ink.axis.copy(alpha = GRID_ALPHA), Offset(px(x), top), Offset(px(x), top + height))
        val text = measurer.measure(axisText(context, chart.axis, x, unit, locale, short = true), label)
        drawText(text, topLeft = Offset(px(x) - text.size.width / 2f, top + height + 2.dp.toPx()))
    }
    for (z in ticks(heights.first, heights.second, TICKS_Y)) {
        drawLine(ink.axis.copy(alpha = GRID_ALPHA), Offset(left, py(z)), Offset(left + width, py(z)))
        val text = measurer.measure(LengthFormat.inUnit(z, unit, locale, decimals = 1), label)
        drawText(text, topLeft = Offset(left - text.size.width - 4.dp.toPx(), py(z) - text.size.height / 2f))
    }
    drawRect(ink.axis, Offset(left, top), Size(width, height), style = Stroke(1.dp.toPx()))

    clipRect(left, top, left + width, top + height) {
        // La referencia, discontinua, para no confundirla con el terreno.
        if (chart.reference.size >= 2) {
            val path = Path()
            chart.reference.forEachIndexed { i, (x, z) -> if (i == 0) path.moveTo(px(x), py(z)) else path.lineTo(px(x), py(z)) }
            drawPath(
                path,
                ink.reference,
                style = Stroke(LINE.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(DASH.toPx(), DASH.toPx()))),
            )
        }
        // El terreno: la línea une solo los puntos del perfil.
        val profile = chart.points.filter { it.inProfile }
        if (profile.size >= 2) {
            val path = Path()
            profile.forEachIndexed { i, p -> if (i == 0) path.moveTo(px(p.x), py(p.groundM)) else path.lineTo(px(p.x), py(p.groundM)) }
            drawPath(path, ink.ground, style = Stroke(LINE.toPx()))
        }
        chart.points.forEachIndexed { i, p ->
            val center = Offset(px(p.x), py(p.groundM))
            if (p.inProfile) {
                drawCircle(ink.ground, DOT.toPx(), center)
            } else {
                drawCircle(ink.rejected, DOT.toPx(), center, style = Stroke(LINE.toPx())) // hueco: fuera del perfil
            }
            if (i == selected) {
                drawLine(ink.selected, Offset(center.x, top), Offset(center.x, top + height), strokeWidth = LINE.toPx())
                drawCircle(ink.selected, DOT.toPx() * 1.6f, center, style = Stroke(LINE.toPx()))
            }
        }
    }
}

/** Unas marcas con un paso de 1, 2 o 5 por una potencia de 10, unas [target] entre [low] y [high] (RF-13). */
internal fun ticks(low: Double, high: Double, target: Int): List<Double> {
    val span = high - low
    if (!(span > 0.0)) return emptyList()
    val raw = span / target
    val base = 10.0.pow(floor(log10(raw)))
    val step = listOf(1.0, 2.0, 5.0, 10.0).map { it * base }.first { it >= raw }
    val first = ceil(low / step) * step
    return generateSequence(first) { it + step }.takeWhile { it <= high + step * 1e-9 }.toList()
}

private fun axisText(context: Context, axis: ProfileAxis, x: Double, unit: LinearUnit, locale: Locale, short: Boolean = false): String =
    when (axis) {
        ProfileAxis.CHAINAGE -> ChainageFormat.format(x, unit, locale, decimals = if (short) 0 else 2)
        ProfileAxis.OFFSET -> sideText(context, x, unit, locale, short)
    }

private fun sideText(context: Context, offsetM: Double, unit: LinearUnit, locale: Locale, short: Boolean): String {
    val magnitude = OffsetFormat.magnitude(offsetM, unit, locale, decimals = if (short) 1 else 3) + " " + symbolOf(context, unit)
    return when (OffsetFormat.side(offsetM)) {
        OffsetSide.LEFT -> context.getString(R.string.alignment_point_side_left, magnitude)
        OffsetSide.RIGHT -> context.getString(R.string.alignment_point_side_right, magnitude)
        OffsetSide.ON_AXIS -> context.getString(R.string.alignment_point_side_on_axis)
    }
}

/** La frase del punto elegido: dónde está, su cota, la de la referencia, desmonte o terraplén y su marca (RF-12, RF-13, RF-18). */
@Composable
private fun selectionText(chart: ProfileChart, p: ProfileChartPoint, unit: LinearUnit, locale: Locale): String {
    val symbol = unitSymbol(unit)
    fun length(valueM: Double) = LengthFormat.inUnit(valueM, unit, locale) + " " + symbol
    val parts = mutableListOf<String>()
    parts += when (chart.axis) {
        ProfileAxis.CHAINAGE -> stringResource(R.string.profile_selected_chainage, ChainageFormat.format(p.x, unit, locale))
        ProfileAxis.OFFSET -> sideText(LocalContext.current, p.x, unit, locale, short = false)
    }
    parts += stringResource(R.string.profile_selected_ground, length(p.groundM))
    p.referenceM?.let {
        parts += stringResource(
            if (chart.axis == ProfileAxis.CHAINAGE) R.string.profile_selected_grade else R.string.profile_selected_design,
            length(it),
        )
    }
    p.dHeightM?.let { d ->
        parts += when (CutFill.of(d)) {
            CutFill.CUT -> stringResource(R.string.profile_cut, length(abs(d)))
            CutFill.FILL -> stringResource(R.string.profile_fill, length(abs(d)))
            CutFill.ON_REFERENCE -> stringResource(R.string.profile_on_reference)
        }
    }
    when (p.flag) {
        SurveyFlag.NONE -> Unit
        SurveyFlag.OUT_OF_AXIS -> parts += stringResource(R.string.profile_flag_out_axis)
        SurveyFlag.NO_PROJECTION -> parts += stringResource(R.string.profile_flag_no_projection)
        SurveyFlag.OUT_OF_SECTION -> parts += stringResource(R.string.profile_flag_out_section)
    }
    return parts.joinToString(" · ")
}

private fun symbolOf(context: Context, unit: LinearUnit): String = context.getString(
    when (unit) {
        LinearUnit.METRE -> R.string.unit_symbol_metre
        LinearUnit.INTERNATIONAL_FOOT -> R.string.unit_symbol_foot
        LinearUnit.US_SURVEY_FOOT -> R.string.unit_symbol_us_survey_foot
    },
)

private fun formatZoom(zoom: Double, locale: Locale): String = String.format(locale, "%.1f", zoom)

private val CHART_HEIGHT = 240.dp
private val LEFT_MARGIN = 52.dp
private val RIGHT_MARGIN = 12.dp
private val TOP_MARGIN = 8.dp
private val BOTTOM_MARGIN = 24.dp
private val LINE = 2.dp
private val DASH = 6.dp
private val DOT = 4.dp
private val LABEL_SP = 11.sp
private const val GRID_ALPHA = 0.25f
private const val TICKS_X = 5
private const val TICKS_Y = 4
private const val PAN_STEP = 0.5
