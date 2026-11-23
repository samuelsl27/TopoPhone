package es.topophone.app.ui.cogo

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import es.topophone.app.R
import es.topophone.app.ui.geometry.ModeSelector
import es.topophone.app.ui.projects.unitSymbol
import es.topophone.app.ui.receiver.uiLocale
import es.topophone.core.designsystem.component.MinTouchTarget
import es.topophone.core.model.format.LengthFormat
import es.topophone.core.model.geometry.DistanceMode
import es.topophone.core.model.project.LinearUnit

const val COGO_PICKER_TAG = "cogo_picker"
const val COGO_PICKER_QUERY_TAG = "cogo_picker_query"
const val COGO_TYPED_EASTING_TAG = "cogo_typed_easting"
const val COGO_TYPED_NORTHING_TAG = "cogo_typed_northing"
const val COGO_TYPED_USE_TAG = "cogo_typed_use"

fun cogoPointTag(slot: String) = "cogo_point_$slot"

fun cogoPickerPointTag(name: String) = "cogo_picker_point_$name"

fun cogoNumberTag(key: String) = "cogo_number_$key"

fun cogoNumberErrorTag(key: String) = "cogo_number_error_$key"

/**
 * Un punto de entrada de COGO (S038/RF-3): el botón que abre el selector, con el nombre del punto elegido, su Este, su Norte y su altura
 * (o «sin altura»), o lo que falta o ya no está (RF-28). Toda la información va en un solo elemento para TalkBack.
 */
@Composable
fun CogoPointField(slot: String, label: String, value: CogoSlotValue, linearUnit: LinearUnit, onPick: (String) -> Unit) {
    val locale = uiLocale()
    val unit = unitSymbol(linearUnit)
    fun length(valueM: Double) = "${LengthFormat.inUnit(valueM, linearUnit, locale)} $unit"
    Card(
        modifier = Modifier.fillMaxWidth().heightIn(min = MinTouchTarget).testTag(cogoPointTag(slot)).clickable { onPick(slot) }
            .semantics(mergeDescendants = true) {},
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            when (value) {
                CogoSlotValue.Empty -> Text(stringResource(R.string.cogo_point_choose, label), style = MaterialTheme.typography.titleMedium)
                CogoSlotValue.Incomplete -> {
                    Text(stringResource(R.string.cogo_point_typed, label), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.cogo_point_incomplete), color = MaterialTheme.colorScheme.error)
                }
                is CogoSlotValue.Missing -> {
                    Text(stringResource(R.string.cogo_point_name, label, value.name), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.cogo_point_missing, value.name), color = MaterialTheme.colorScheme.error)
                }
                is CogoSlotValue.Found -> {
                    val title =
                        value.name?.let { stringResource(R.string.cogo_point_name, label, it) }
                            ?: stringResource(R.string.cogo_point_typed, label)
                    Text(title, style = MaterialTheme.typography.titleMedium)
                    val mono = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace)
                    Text(
                        stringResource(R.string.cogo_point_coordinates, length(value.point.easting), length(value.point.northing)),
                        style = mono,
                    )
                    val height = value.point.height
                    Text(
                        if (height ==
                            null
                        ) {
                            stringResource(R.string.cogo_point_no_height)
                        } else {
                            stringResource(R.string.cogo_point_height, length(height))
                        },
                        style = mono,
                    )
                }
            }
        }
    }
}

/** El selector de un punto de entrada: la lista del proyecto con búsqueda, o su Este y su Norte escritos (S038/RF-3). */
@Composable
fun CogoPickerDialog(
    picker: CogoPickerUi,
    label: String,
    typed: CogoSelection.Typed?,
    onQuery: (String) -> Unit,
    onChoose: (String) -> Unit,
    onTyped: (slot: String, easting: String, northing: String) -> Unit,
    onClose: () -> Unit,
) {
    val slot = picker.slot ?: return
    var easting by remember(slot) { mutableStateOf(typed?.easting.orEmpty()) }
    var northing by remember(slot) { mutableStateOf(typed?.northing.orEmpty()) }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(stringResource(R.string.cogo_picker_title, label)) },
        text = {
            Column(
                modifier = Modifier.testTag(COGO_PICKER_TAG).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = picker.query,
                    onValueChange = onQuery,
                    label = { Text(stringResource(R.string.cogo_search)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag(COGO_PICKER_QUERY_TAG),
                )
                if (picker.candidates.isEmpty()) {
                    Text(stringResource(R.string.cogo_no_points))
                } else {
                    LazyColumn(modifier = Modifier.heightIn(max = 220.dp)) {
                        items(picker.candidates, key = { it.id }) { point ->
                            Text(
                                point.name,
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.fillMaxWidth().heightIn(min = MinTouchTarget).clickable { onChoose(point.id) }
                                    .padding(vertical = 14.dp).testTag(cogoPickerPointTag(point.name)),
                            )
                        }
                    }
                }
                Text(stringResource(R.string.cogo_type_title), style = MaterialTheme.typography.titleSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = easting,
                        onValueChange = { easting = it },
                        label = { Text(stringResource(R.string.cogo_easting)) },
                        singleLine = true,
                        modifier = Modifier.weight(1f).testTag(COGO_TYPED_EASTING_TAG),
                    )
                    OutlinedTextField(
                        value = northing,
                        onValueChange = { northing = it },
                        label = { Text(stringResource(R.string.cogo_northing)) },
                        singleLine = true,
                        modifier = Modifier.weight(1f).testTag(COGO_TYPED_NORTHING_TAG),
                    )
                }
                TextButton(
                    onClick = { onTyped(slot, easting, northing) },
                    modifier = Modifier.heightIn(min = MinTouchTarget).testTag(COGO_TYPED_USE_TAG),
                ) { Text(stringResource(R.string.cogo_use_typed)) }
            }
        },
        confirmButton = {
            TextButton(onClick = onClose, modifier = Modifier.heightIn(min = MinTouchTarget)) {
                Text(stringResource(R.string.geometry_close))
            }
        },
    )
}

/**
 * Un número escrito en la unidad del proyecto (S038/RF-4): una distancia, un azimut, un ángulo o un desnivel. Si no es válido
 * ([invalid]) se dice con texto, no solo con color.
 */
@Composable
fun CogoNumberField(key: String, label: String, text: String, invalid: Boolean, onChange: (String) -> Unit, modifier: Modifier = Modifier) {
    OutlinedTextField(
        value = text,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        isError = invalid,
        supportingText = if (invalid) {
            { Text(stringResource(R.string.cogo_invalid_number), modifier = Modifier.testTag(cogoNumberErrorTag(key))) }
        } else {
            null
        },
        modifier = modifier.fillMaxWidth().heightIn(min = MinTouchTarget).testTag(cogoNumberTag(key)),
    )
}

/** Cuadrícula o Terreno, la elección de S026 que vale para toda la app, diciendo siempre cuál está activa (S038/RF-5). */
@Composable
fun CogoModeSelector(mode: DistanceMode, onMode: (DistanceMode) -> Unit) = ModeSelector(mode, onMode)

/** La elección Cuadrícula/Terreno o, con el proyecto en terreno, solo «Terreno» (S058/RF-9). */
@Composable
fun CogoModeOrTerrain(mode: DistanceMode, onMode: (DistanceMode) -> Unit, terrain: Boolean) {
    // S058/RF-9: con el proyecto en terreno se calcula en terreno y no hay elección Cuadrícula/Terreno.
    if (terrain) {
        androidx.compose.material3.Text(
            androidx.compose.ui.res.stringResource(es.topophone.app.R.string.terrain_ground),
            style = androidx.compose.material3.MaterialTheme.typography.labelLarge,
            modifier = androidx.compose.ui.Modifier.testTag(COGO_TERRAIN_TAG),
        )
    } else {
        ModeSelector(mode, onMode)
    }
}

const val COGO_TERRAIN_TAG = "cogo_terrain"
