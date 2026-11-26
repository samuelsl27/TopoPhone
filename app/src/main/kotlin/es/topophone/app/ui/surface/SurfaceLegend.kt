package es.topophone.app.ui.surface

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.unit.dp
import es.topophone.app.R
import es.topophone.app.ui.projects.unitSymbol
import es.topophone.app.ui.receiver.uiLocale
import es.topophone.core.model.format.LengthFormat
import es.topophone.core.model.project.LinearUnit
import es.topophone.core.model.surface.ElevationRamp

const val SURFACE_LEGEND_TAG = "surface_legend"

/**
 * La leyenda de los colores por cota de las superficies del mapa (S061/RF-11): una barra con la rampa, la cota máxima arriba y la
 * mínima abajo, en las unidades del proyecto. Las cifras van escritas, no solo en color (RNF-T8), y TalkBack lee la frase entera.
 */
@Composable
fun SurfaceLegend(ramp: ElevationRamp, unit: LinearUnit, modifier: Modifier = Modifier) {
    val locale = uiLocale()
    val symbol = unitSymbol(unit)
    val max = "${LengthFormat.inUnit(ramp.maxM, unit, locale, decimals = 1)} $symbol"
    val min = "${LengthFormat.inUnit(ramp.minM, unit, locale, decimals = 1)} $symbol"
    val description = stringResource(R.string.surface_legend_description, min, max)
    val colors = ElevationRamp.STOPS.reversed().map { Color(0xFF000000.toInt() or it) }
    Column(
        modifier = modifier
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.9f), MaterialTheme.shapes.small)
            .padding(8.dp)
            .clearAndSetSemantics {
                contentDescription = description
                testTag = SURFACE_LEGEND_TAG
            },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(max, style = MaterialTheme.typography.labelMedium)
        Box(Modifier.padding(vertical = 4.dp).width(16.dp).height(96.dp).background(Brush.verticalGradient(colors)))
        Text(min, style = MaterialTheme.typography.labelMedium)
    }
}
