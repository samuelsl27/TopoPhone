package es.topophone.app.ui.section

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import es.topophone.app.R
import es.topophone.app.ui.projects.unitSymbol
import es.topophone.app.ui.receiver.uiLocale
import es.topophone.core.model.alignment.ChainageFormat
import es.topophone.core.model.alignment.OffsetFormat
import es.topophone.core.model.alignment.OffsetSide
import es.topophone.core.model.alignment.section.CrossSection
import es.topophone.core.model.alignment.section.HeightKind
import es.topophone.core.model.alignment.section.SectionPoint
import es.topophone.core.model.format.LengthFormat
import es.topophone.core.model.project.LinearUnit

const val SECTION_DRAWING_TAG = "section_drawing"
const val SECTION_DRAWING_LEGEND_TAG = "section_drawing_legend"

fun sectionDrawingPointTag(code: String) = "section_drawing_point_$code"

/**
 * El alzado de la sección de diseño en un PK (S050/RF-13): una polilínea con sus puntos y, debajo, la leyenda con el código, el
 * desplazamiento (con «izquierda» o «derecha») y la cota de cada punto, para leerla con TalkBack además de verla (RNF-T8). Si hay un
 * perfil de terreno en el mismo PK se dibuja con otro trazo. [elevationOf] da la cota de un punto (la absoluta o la de la rasante más su
 * diferencia) o null si no la hay (RF-11); sin cotas el alzado usa las diferencias.
 */
@Composable
fun SectionDrawing(
    section: CrossSection,
    ground: CrossSection?,
    unit: LinearUnit,
    profileElevationM: Double?,
    modifier: Modifier = Modifier,
) {
    val locale = uiLocale()
    val symbol = unitSymbol(unit)

    fun elevation(point: SectionPoint, of: CrossSection): Double? = when (of.heightKind) {
        HeightKind.ABSOLUTE -> point.heightM
        HeightKind.RELATIVE -> profileElevationM?.plus(point.heightM)
    }

    @Composable
    fun line(p: SectionPoint): String {
        val offset = OffsetFormat.magnitude(p.offsetM, unit, locale) + " " + symbol
        return when (OffsetFormat.side(p.offsetM)) {
            OffsetSide.LEFT -> stringResource(R.string.alignment_point_side_left, offset)
            OffsetSide.RIGHT -> stringResource(R.string.alignment_point_side_right, offset)
            OffsetSide.ON_AXIS -> stringResource(R.string.alignment_point_side_on_axis)
        }
    }
    val ink = MaterialTheme.colorScheme.primary
    val groundInk = MaterialTheme.colorScheme.tertiary
    val dotInk = MaterialTheme.colorScheme.onSurface
    val title = stringResource(R.string.section_view_title, ChainageFormat.format(section.chainage, unit, locale))
    val everything = (
        section.points.map { it.offsetM to (elevation(it, section) ?: it.heightM) } +
            ground?.points.orEmpty().map { it.offsetM to (elevation(it, ground!!) ?: it.heightM) }
        )
    val minX = everything.minOf { it.first }
    val maxX = everything.maxOf { it.first }
    val minZ = everything.minOf { it.second }
    val maxZ = everything.maxOf { it.second }

    Column(modifier = modifier.fillMaxWidth().testTag(SECTION_DRAWING_TAG), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        Canvas(modifier = Modifier.fillMaxWidth().height(180.dp).clearAndSetSemantics { }) {
            val pad = 16.dp.toPx()
            val w = size.width - 2 * pad
            val h = size.height - 2 * pad
            val dx = (maxX - minX).takeIf { it > 0.0 } ?: 1.0
            val dz = (maxZ - minZ).takeIf { it > 0.0 } ?: 1.0

            // Un solo factor en los dos ejes no cabría (las secciones son anchas y bajas): se exagera la vertical al ajustar al cuadro.
            fun at(x: Double, z: Double) = Offset((pad + (x - minX) / dx * w).toFloat(), (pad + h - (z - minZ) / dz * h).toFloat())

            fun draw(of: CrossSection, color: Color) {
                val pts = of.points.map { at(it.offsetM, elevation(it, of) ?: it.heightM) }
                pts.zipWithNext().forEach { (a, b) -> drawLine(color, a, b, strokeWidth = 3.dp.toPx(), cap = StrokeCap.Round) }
                pts.forEach { drawCircle(dotInk, radius = 4.dp.toPx(), center = it) }
            }
            ground?.let { draw(it, groundInk) }
            draw(section, ink)
            drawLine(dotInk.copy(alpha = 0.4f), at(0.0, minZ), at(0.0, maxZ), strokeWidth = Stroke.HairlineWidth)
        }
        Column(modifier = Modifier.testTag(SECTION_DRAWING_LEGEND_TAG), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            section.points.forEach { p ->
                val where = line(p)
                val text = elevation(p, section)?.let {
                    stringResource(R.string.section_view_point, p.code, where, LengthFormat.inUnit(it, unit, locale) + " " + symbol)
                } ?: stringResource(R.string.section_view_point_no_elevation, p.code, where)
                Text(
                    text,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(vertical = 1.dp).testTag(sectionDrawingPointTag(p.code)).semantics {
                        contentDescription =
                            text
                    },
                )
            }
            ground?.let { g ->
                Text(stringResource(R.string.section_view_ground), style = MaterialTheme.typography.labelLarge)
                g.points.forEach { p ->
                    val where = line(p)
                    val text = elevation(p, g)?.let {
                        stringResource(R.string.section_view_point, p.code, where, LengthFormat.inUnit(it, unit, locale) + " " + symbol)
                    } ?: stringResource(R.string.section_view_point_no_elevation, p.code, where)
                    Text(text, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag(sectionDrawingPointTag(p.code)))
                }
            }
        }
    }
}
