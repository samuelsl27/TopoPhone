package es.topophone.app.ui.cogo

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import es.topophone.app.R
import es.topophone.app.ui.SectionScaffold
import es.topophone.app.ui.geometry.FactorNotices
import es.topophone.app.ui.projects.unitSymbol
import es.topophone.app.ui.receiver.uiLocale
import es.topophone.core.designsystem.component.MinTouchTarget
import es.topophone.core.model.format.AngleFormat
import es.topophone.core.model.format.LengthFormat
import es.topophone.core.model.format.SlopeFormat
import es.topophone.core.model.geometry.DistanceMode
import es.topophone.core.model.preferences.ThemeState

const val INVERSE_HINT_TAG = "inverse_hint"
const val INVERSE_SWAP_TAG = "inverse_swap"
const val INVERSE_COPY_TAG = "inverse_copy"
const val INVERSE_COINCIDENT_TAG = "inverse_coincident"

fun inverseRowTag(key: String) = "inverse_row_$key"

/** Lo que el usuario hace en la inversa (S038/RF-3, RF-5, RF-9). */
class InverseActions(
    val onPick: (String) -> Unit = {},
    val onQuery: (String) -> Unit = {},
    val onChoose: (String) -> Unit = {},
    val onTyped: (slot: String, easting: String, northing: String) -> Unit = { _, _, _ -> },
    val onClosePicker: () -> Unit = {},
    val onMode: (DistanceMode) -> Unit = {},
    val onSwap: () -> Unit = {},
    val onCopy: (String) -> Unit = {},
)

/** Una fila del resultado: lo que se muestra y lo que se copia (S038/RF-6, RF-9). */
internal class InverseRow(val key: String, val label: String, val value: String)

@Composable
fun InverseRoute(
    projectId: String,
    themeState: ThemeState,
    onToggleSun: () -> Unit,
    viewModel: InverseViewModel = hiltViewModel(),
    onBack: () -> Unit,
) {
    LaunchedEffect(projectId) { viewModel.start(projectId) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val clipboard = LocalClipboardManager.current
    InverseScreen(
        themeState,
        onToggleSun,
        state,
        InverseActions(
            onPick = viewModel.inputs::open,
            onQuery = viewModel.inputs::setQuery,
            onChoose = viewModel.inputs::chooseProject,
            onTyped = viewModel.inputs::setTyped,
            onClosePicker = viewModel.inputs::close,
            onMode = viewModel::setMode,
            onSwap = viewModel::swap,
            onCopy = { clipboard.setText(AnnotatedString(it)) },
        ),
        onBack,
    )
}

/**
 * La inversa (S038/RF-6 a RF-9): dos puntos y, con ellos, las distancias de cuadrícula y de terreno, los azimuts, las diferencias y,
 * con altura, la distancia inclinada y la pendiente. Solo muestra; no guarda ningún punto.
 */
@Composable
fun InverseScreen(themeState: ThemeState, onToggleSun: () -> Unit, state: InverseUi, actions: InverseActions, onBack: () -> Unit) {
    SectionScaffold(
        title = stringResource(R.string.cogo_inverse),
        themeState = themeState,
        onToggleSun = onToggleSun,
        onNavigateBack = onBack,
    ) { padding ->
        if (!state.loaded) return@SectionScaffold
        val rows = inverseRows(state)
        val labelA = stringResource(R.string.cogo_point_a)
        val labelB = stringResource(R.string.cogo_point_b)
        androidx.compose.foundation.layout.Column(
            modifier = Modifier.padding(padding).padding(horizontal = 16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            CogoModeSelector(state.mode, actions.onMode)
            CogoPointField(SLOT_A, labelA, state.a, state.linearUnit, actions.onPick)
            OutlinedButton(
                onClick = actions.onSwap,
                modifier = Modifier.fillMaxWidth().heightIn(min = MinTouchTarget).testTag(INVERSE_SWAP_TAG),
            ) { Text(stringResource(R.string.cogo_swap)) }
            CogoPointField(SLOT_B, labelB, state.b, state.linearUnit, actions.onPick)
            Result(state, rows, actions.onCopy)
        }
    }
    val picker = state.picker
    if (picker.slot != null) {
        val label = if (picker.slot == SLOT_A) stringResource(R.string.cogo_point_a) else stringResource(R.string.cogo_point_b)
        val typed = (if (picker.slot == SLOT_A) state.aSelection else state.bSelection) as? CogoSelection.Typed
        CogoPickerDialog(picker, label, typed, actions.onQuery, actions.onChoose, actions.onTyped, actions.onClosePicker)
    }
}

@Composable
private fun Result(state: InverseUi, rows: List<InverseRow>, onCopy: (String) -> Unit) {
    val result = state.result
    if (result == null) {
        Text(stringResource(R.string.cogo_inverse_choose_hint), modifier = Modifier.testTag(INVERSE_HINT_TAG))
        return
    }
    androidx.compose.foundation.layout.Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (result.coincident) {
            Card(modifier = Modifier.fillMaxWidth().testTag(INVERSE_COINCIDENT_TAG).semantics(mergeDescendants = true) {}) {
                Text(
                    stringResource(R.string.cogo_coincident),
                    modifier = Modifier.padding(12.dp),
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        }
        Card(modifier = Modifier.fillMaxWidth()) {
            androidx.compose.foundation.layout.Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                rows.forEach {
                    Line(
                        it,
                        bold =
                        (it.key == "grid" && state.mode == DistanceMode.GRID) || (it.key == "ground" && state.mode == DistanceMode.GROUND),
                    )
                }
            }
        }
        FactorNotices(result.notices)
        val text = rows.joinToString("\n") { "${it.label}: ${it.value}" }
        OutlinedButton(
            onClick = { onCopy(text) },
            modifier = Modifier.fillMaxWidth().heightIn(min = MinTouchTarget).testTag(INVERSE_COPY_TAG),
        ) { Text(stringResource(R.string.cogo_copy)) }
    }
}

@Composable
private fun Line(row: InverseRow, bold: Boolean) {
    Row(
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().testTag(inverseRowTag(row.key)).semantics(mergeDescendants = true) {},
    ) {
        Text(row.label, style = MaterialTheme.typography.titleSmall, fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal)
        Text(row.value, style = MaterialTheme.typography.titleMedium.copy(fontFamily = FontFamily.Monospace))
    }
}

/** Las filas del resultado, en el orden de la spec (S038/RF-6, RF-7); la distancia activa lo dice con texto (RF-5). */
@Composable
internal fun inverseRows(state: InverseUi): List<InverseRow> {
    val result = state.result ?: return emptyList()
    val locale = uiLocale()
    val unit = unitSymbol(state.linearUnit)
    fun length(valueM: Double) = "${LengthFormat.inUnit(valueM, state.linearUnit, locale)} $unit"
    val undefined = stringResource(R.string.cogo_undefined)
    fun azimuth(rad: Double?) = rad?.let { AngleFormat.azimuth(it, state.angularUnit, locale) } ?: undefined
    val missing = stringResource(R.string.cogo_dash)
    val gridLabel = if (state.mode == DistanceMode.GRID) R.string.cogo_distance_grid_active else R.string.cogo_distance_grid
    val groundLabel = if (state.mode == DistanceMode.GROUND) R.string.cogo_distance_ground_active else R.string.cogo_distance_ground
    return buildList {
        add(InverseRow("grid", stringResource(gridLabel), length(result.gridDistance)))
        add(InverseRow("ground", stringResource(groundLabel), length(result.groundDistance)))
        add(InverseRow("azimuth", stringResource(R.string.cogo_azimuth_direct), azimuth(result.azimuthRad)))
        add(InverseRow("reverse", stringResource(R.string.cogo_azimuth_reverse), azimuth(result.reverseAzimuthRad)))
        add(InverseRow("de", stringResource(R.string.cogo_delta_easting), length(result.dEasting)))
        add(InverseRow("dn", stringResource(R.string.cogo_delta_northing), length(result.dNorthing)))
        add(InverseRow("dh", stringResource(R.string.cogo_delta_height), result.dHeight?.let(::length) ?: missing))
        result.slantDistance?.let { add(InverseRow("slant", stringResource(R.string.cogo_distance_slant), length(it))) }
        if (result.dHeight != null) {
            val slope = result.slopePercent?.let { "${SlopeFormat.percent(it, locale)} %" } ?: missing
            add(InverseRow("slope", stringResource(R.string.cogo_slope), slope))
        }
    }
}
