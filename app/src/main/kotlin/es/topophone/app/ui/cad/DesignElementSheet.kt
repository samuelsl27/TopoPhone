package es.topophone.app.ui.cad

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import es.topophone.app.R
import es.topophone.core.model.cad.CadEntity
import es.topophone.core.model.cad.CadEntityKind
import es.topophone.core.model.project.LinearUnit
import java.text.NumberFormat
import kotlin.math.hypot

const val DESIGN_SHEET_TAG = "design_element_sheet"
const val DESIGN_NO_ELEVATION_TAG = "design_element_no_elevation"
const val DESIGN_ELEVATION_TAG = "design_element_elevation"

/**
 * La ficha de un elemento del dibujo que se ha tocado en el mapa: su tipo original, su capa y su dibujo, sus coordenadas o su
 * longitud y su número de vértices, y su cota o «Sin cota» (S040/RF-23, RF-29). Las longitudes y las cotas van en la unidad del
 * proyecto; en un proyecto en geográficas las coordenadas son longitud y latitud y no se da longitud.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DesignElementSheet(selection: DesignSelection, unit: LinearUnit, unitSymbol: String, geographic: Boolean, onDismiss: () -> Unit) {
    val entity = selection.entity
    val number = NumberFormat.getNumberInstance().apply {
        minimumFractionDigits = 3
        maximumFractionDigits = 3
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 24.dp).testTag(DESIGN_SHEET_TAG),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(stringResource(R.string.cad_element_title), style = MaterialTheme.typography.titleLarge)
            Text(stringResource(elementKindLabel(entity.kind)), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.cad_element_layer, selection.layerName))
            Text(stringResource(R.string.cad_element_drawing, selection.drawingName))
            if (entity.vertexCount == 1) {
                val east = number.format(if (geographic) entity.easting[0] else unit.fromMetres(entity.easting[0]))
                val north = number.format(if (geographic) entity.northing[0] else unit.fromMetres(entity.northing[0]))
                Text(
                    if (geographic) {
                        stringResource(R.string.cad_element_position_geographic, east, north)
                    } else {
                        stringResource(R.string.cad_element_position, east, north)
                    },
                )
            } else {
                if (!geographic) {
                    Text(stringResource(R.string.cad_element_length, number.format(unit.fromMetres(length(entity))), unitSymbol))
                }
                Text(stringResource(R.string.cad_element_vertices, entity.vertexCount))
            }
            Elevation(selection, unit, unitSymbol, number)
        }
    }
}

@Composable
private fun Elevation(selection: DesignSelection, unit: LinearUnit, unitSymbol: String, number: NumberFormat) {
    val heights = selection.entity.height
    if (!selection.hasElevation || heights == null) {
        Text(stringResource(R.string.cad_element_no_elevation), modifier = Modifier.testTag(DESIGN_NO_ELEVATION_TAG))
        return
    }
    val low = number.format(unit.fromMetres(heights.min()))
    val high = number.format(unit.fromMetres(heights.max()))
    val text = if (low == high) {
        stringResource(R.string.cad_element_elevation, low, unitSymbol)
    } else {
        stringResource(R.string.cad_element_elevation_range, low, high, unitSymbol)
    }
    Text(text, modifier = Modifier.testTag(DESIGN_ELEVATION_TAG))
}

/** La longitud de la polilínea en el plano, vértice a vértice. */
private fun length(entity: CadEntity): Double = (0 until entity.vertexCount - 1).sumOf {
    hypot(
        entity.easting[it + 1] - entity.easting[it],
        entity.northing[it + 1] - entity.northing[it],
    )
}

@StringRes
private fun elementKindLabel(kind: CadEntityKind) = when (kind) {
    CadEntityKind.POINT -> R.string.cad_element_point
    CadEntityKind.LINE -> R.string.cad_element_line
    CadEntityKind.POLYLINE -> R.string.cad_element_polyline
    CadEntityKind.ARC -> R.string.cad_element_arc
    CadEntityKind.CIRCLE -> R.string.cad_element_circle
    CadEntityKind.SPLINE -> R.string.cad_element_spline
    CadEntityKind.BLOCK_INSERT -> R.string.cad_element_block_insert
}
