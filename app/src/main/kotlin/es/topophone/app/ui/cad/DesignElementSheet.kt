package es.topophone.app.ui.cad

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import es.topophone.app.R
import es.topophone.core.designsystem.component.MinTouchTarget
import es.topophone.core.model.cad.CadEntity
import es.topophone.core.model.cad.CadEntityKind
import es.topophone.core.model.cad.GpkgValue
import es.topophone.core.model.project.LinearUnit
import java.text.NumberFormat
import kotlin.math.hypot

const val DESIGN_SHEET_TAG = "design_element_sheet"
const val DESIGN_NO_ELEVATION_TAG = "design_element_no_elevation"
const val DESIGN_ELEVATION_TAG = "design_element_elevation"
const val DESIGN_STAKEOUT_TAG = "design_element_stakeout"
const val DESIGN_NAME_TAG = "design_element_name"
const val DESIGN_RING_TAG = "design_element_ring"
const val DESIGN_ATTRIBUTES_TAG = "design_element_attributes"
const val DESIGN_CONVERT_TAG = "design_element_convert"
const val DESIGN_INFORMATION_TAG = "design_element_information"

fun designAttributeTag(column: String) = "design_attribute_$column"

/**
 * La ficha de un elemento del dibujo que se ha tocado en el mapa: su tipo original, su capa y su dibujo, sus coordenadas o su
 * longitud y su número de vértices, y su cota o «Sin cota» (S040/RF-23, RF-29). Las longitudes y las cotas van en la unidad del
 * proyecto; en un proyecto en geográficas las coordenadas son longitud y latitud y no se da longitud.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DesignElementSheet(
    selection: DesignSelection,
    unit: LinearUnit,
    unitSymbol: String,
    geographic: Boolean,
    onDismiss: () -> Unit,
    onStakeout: () -> Unit = {},
    /** «Convertir en eje» de una polilínea; null si no se ofrece (S049/RF-6). */
    onConvertToAxis: (() -> Unit)? = null,
    /** «Información» de una línea: su longitud, su área y su tabla de segmentos; null si no se ofrece (S026/RF-1). */
    onInformation: (() -> Unit)? = null,
) {
    val entity = selection.entity
    val number = NumberFormat.getNumberInstance().apply {
        minimumFractionDigits = 3
        maximumFractionDigits = 3
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            modifier = Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 24.dp)
                .testTag(DESIGN_SHEET_TAG), // se desplaza: un objeto de GeoPackage puede traer muchos atributos
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(stringResource(R.string.cad_element_title), style = MaterialTheme.typography.titleLarge)
            Text(stringResource(elementKindLabel(entity.kind)), style = MaterialTheme.typography.titleMedium)
            // S048/RF-22
            entity.name?.let { Text(stringResource(R.string.cad_element_name, it), modifier = Modifier.testTag(DESIGN_NAME_TAG)) }
            if (entity.polygonRing) {
                Text(stringResource(R.string.cad_element_ring), modifier = Modifier.testTag(DESIGN_RING_TAG)) // S048/RF-17, RNF-T8
            }
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
            Attributes(selection.attributes)
            // S041/RF-1, RF-2: un punto o un centro de bloque abre el replanteo de siempre y una línea, el de línea.
            Button(
                onClick = onStakeout,
                modifier = Modifier.fillMaxWidth().heightIn(min = MinTouchTarget).testTag(DESIGN_STAKEOUT_TAG),
            ) { Text(stringResource(R.string.design_element_stakeout)) }
            if (onInformation != null &&
                entity.kind != CadEntityKind.POINT &&
                entity.kind != CadEntityKind.BLOCK_INSERT &&
                entity.vertexCount >= 2
            ) {
                OutlinedButton(
                    onClick = onInformation,
                    modifier = Modifier.fillMaxWidth().heightIn(min = MinTouchTarget).testTag(DESIGN_INFORMATION_TAG),
                ) { Text(stringResource(R.string.geometry_button)) } // S026/RF-1
            }
            if (onConvertToAxis != null &&
                (entity.kind == CadEntityKind.POLYLINE || entity.kind == CadEntityKind.LINE) &&
                entity.vertexCount >= 2
            ) {
                OutlinedButton(
                    onClick = onConvertToAxis,
                    modifier = Modifier.fillMaxWidth().heightIn(min = MinTouchTarget).testTag(DESIGN_CONVERT_TAG),
                ) { Text(stringResource(R.string.alignment_convert_button)) } // S049/RF-6
            }
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
internal fun elementKindLabel(kind: CadEntityKind) = when (kind) {
    CadEntityKind.POINT -> R.string.cad_element_point
    CadEntityKind.LINE -> R.string.cad_element_line
    CadEntityKind.POLYLINE -> R.string.cad_element_polyline
    CadEntityKind.ARC -> R.string.cad_element_arc
    CadEntityKind.CIRCLE -> R.string.cad_element_circle
    CadEntityKind.SPLINE -> R.string.cad_element_spline
    CadEntityKind.BLOCK_INSERT -> R.string.cad_element_block_insert
}

/** Todos los atributos del objeto de un GeoPackage, uno por línea, con su columna; nada si el elemento viene de un DXF (S048/RF-20). */
@Composable
private fun Attributes(attributes: Map<String, GpkgValue>) {
    if (attributes.isEmpty()) return
    Column(modifier = Modifier.testTag(DESIGN_ATTRIBUTES_TAG), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(stringResource(R.string.cad_element_attributes), style = MaterialTheme.typography.titleSmall)
        attributes.forEach { (column, value) ->
            Text(
                stringResource(R.string.cad_element_attribute, column, valueText(value)),
                modifier = Modifier.testTag(designAttributeTag(column)),
            )
        }
    }
}

@Composable
private fun valueText(value: GpkgValue): String = when (value) {
    GpkgValue.Null -> stringResource(R.string.cad_attribute_null)
    is GpkgValue.Integer -> NumberFormat.getIntegerInstance().apply { isGroupingUsed = false }.format(value.value)
    is GpkgValue.Real -> NumberFormat.getNumberInstance().apply {
        isGroupingUsed = false
        maximumFractionDigits = REAL_DIGITS
    }.format(value.value)
    is GpkgValue.Text -> value.value
    is GpkgValue.Date -> value.value
    is GpkgValue.Bool -> stringResource(if (value.value) R.string.cad_attribute_true else R.string.cad_attribute_false)
    is GpkgValue.Blob -> stringResource(R.string.cad_attribute_blob, value.bytes.size)
    is GpkgValue.Omitted -> stringResource(R.string.cad_attribute_omitted, value.bytes / BYTES_PER_KB)
}

private const val REAL_DIGITS = 9
private const val BYTES_PER_KB = 1024
