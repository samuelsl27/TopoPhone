package es.topophone.app.ui.offline

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import es.topophone.app.R
import es.topophone.core.designsystem.component.MinTouchTarget
import es.topophone.core.geodesy.GeoBox
import es.topophone.core.model.offline.DetailLevel
import es.topophone.core.model.offline.DownloadableSource
import es.topophone.core.model.offline.OfflineAreaKind
import es.topophone.core.model.offline.OfflineLimits
import es.topophone.core.model.offline.SourceKind
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

const val OFFLINE_PLAN_TAG = "offline_plan"
const val OFFLINE_PLAN_NO_AREA_TAG = "offline_plan_no_area"
const val OFFLINE_RADIUS_TAG = "offline_radius"
const val OFFLINE_RADIUS_VALUE_TAG = "offline_radius_value"
const val OFFLINE_MARGIN_TAG = "offline_margin"
const val OFFLINE_MARGIN_VALUE_TAG = "offline_margin_value"
const val OFFLINE_NO_SOURCES_TAG = "offline_no_sources"
const val OFFLINE_ESTIMATE_TOTAL_TAG = "offline_estimate_total"
const val OFFLINE_ESTIMATE_STORAGE_TAG = "offline_estimate_storage"
const val OFFLINE_NAME_TAG = "offline_name"
const val OFFLINE_START_TAG = "offline_start"
const val OFFLINE_CONFIRM_TAG = "offline_confirm"
const val OFFLINE_CONFIRM_ACCEPT_TAG = "offline_confirm_accept"
const val OFFLINE_CONFIRM_CANCEL_TAG = "offline_confirm_cancel"

fun offlineAreaTag(kind: OfflineAreaKind) = "offline_area_${kind.key}"

fun offlineDetailTag(level: DetailLevel) = "offline_detail_${level.key}"

fun offlineSourceTag(key: String) = "offline_source_$key"

fun offlineEstimateTag(key: String) = "offline_estimate_$key"

fun offlineBlockTag(index: Int) = "offline_block_$index"

/** Los radios y los márgenes que se pueden elegir con el deslizador (S037/RF-3, RF-4). */
private val RADIUS_STEPS = listOf(100.0, 200.0, 500.0, 1_000.0, 2_000.0, 5_000.0, 10_000.0, 20_000.0)
private val MARGIN_STEPS = listOf(200.0, 500.0, 1_000.0, 2_000.0)

/** Lo que la hoja «Descargar zona» pide hacer; lo atiende [DownloadPlanViewModel] (S037/RF-2 a RF-15). */
class DownloadPlanActions(
    val onAreaKind: (OfflineAreaKind) -> Unit = {},
    val onRadius: (Double) -> Unit = {},
    val onMargin: (Double) -> Unit = {},
    val onDetail: (DetailLevel) -> Unit = {},
    val onToggleSource: (String) -> Unit = {},
    val onName: (String) -> Unit = {},
    val onConfirm: (EstimateConfirmation) -> Unit = {},
    val onStart: () -> Unit = {},
    val onCancelConfirmation: () -> Unit = {},
) {
    companion object {
        fun of(viewModel: DownloadPlanViewModel) = DownloadPlanActions(
            onAreaKind = viewModel::selectAreaKind,
            onRadius = viewModel::setRadius,
            onMargin = viewModel::setMargin,
            onDetail = viewModel::setDetail,
            onToggleSource = viewModel::toggleSource,
            onName = viewModel::setName,
            onConfirm = viewModel::confirm,
            onStart = viewModel::start,
        )
    }
}

/** La hoja «Descargar zona» de un proyecto; [visible] es lo que se ve ahora en el mapa (S037/RF-1, RF-2). Al empezar la descarga avisa con [onStarted]. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadPlanSheet(
    projectId: String,
    projectName: String,
    visible: GeoBox?,
    onDismiss: () -> Unit,
    onStarted: () -> Unit,
    viewModel: DownloadPlanViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(projectId) { viewModel.open(projectId, projectName, visible) }
    LaunchedEffect(state.started) { if (state.started) onStarted() }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        DownloadPlanContent(state, DownloadPlanActions.of(viewModel))
    }
}

/** El contenido de la hoja: el área, las fuentes, el nivel, la previsión con sus avisos y el botón «Descargar» (S037/RF-2 a RF-14). */
@Composable
fun DownloadPlanContent(state: DownloadPlanUiState, actions: DownloadPlanActions, modifier: Modifier = Modifier) {
    val locale = LocalConfiguration.current.locales[0]
    var dialog by remember { mutableStateOf<EstimateConfirmation?>(null) }
    Column(
        modifier.fillMaxWidth().verticalScroll(
            rememberScrollState(),
        ).padding(horizontal = 16.dp, vertical = 8.dp).testTag(OFFLINE_PLAN_TAG),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            stringResource(R.string.offline_plan_title),
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.semantics {
                heading()
            },
        )
        AreaSection(state, actions, locale)
        SourcesSection(state, actions)
        DetailSection(state, actions)
        EstimateSection(state, locale)
        OutlinedTextField(
            value = state.name,
            onValueChange = actions.onName,
            label = { Text(stringResource(R.string.offline_plan_name)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp).testTag(OFFLINE_NAME_TAG),
        )
        Button(
            onClick = {
                val pending = state.pendingConfirmations
                if (pending.isEmpty()) actions.onStart() else dialog = pending.first()
            },
            enabled = state.canStart,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp).heightIn(min = MinTouchTarget).testTag(OFFLINE_START_TAG),
        ) { Text(stringResource(R.string.offline_plan_download)) }
        Spacer(Modifier.size(16.dp))
    }
    dialog?.let { confirmation ->
        ConfirmationDialog(
            confirmation = confirmation,
            estimatedBytes = state.estimate?.totalBytes ?: 0,
            locale = locale,
            onAccept = {
                actions.onConfirm(confirmation)
                val rest = state.pendingConfirmations.filterNot { it == confirmation }
                dialog = rest.firstOrNull()
                if (rest.isEmpty()) actions.onStart()
            },
            onCancel = {
                dialog = null
                actions.onCancelConfirmation()
            },
        )
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 12.dp).semantics { heading() })
}

@Composable
private fun AreaSection(state: DownloadPlanUiState, actions: DownloadPlanActions, locale: Locale) {
    SectionTitle(stringResource(R.string.offline_plan_area))
    if (state.areaKinds.isEmpty()) {
        Text(stringResource(R.string.offline_plan_no_area), modifier = Modifier.testTag(OFFLINE_PLAN_NO_AREA_TAG))
        return
    }
    state.areaKinds.forEach { kind ->
        val label = when (kind) {
            OfflineAreaKind.RADIUS -> stringResource(R.string.offline_area_radius)
            OfflineAreaKind.VIEWPORT -> stringResource(R.string.offline_area_viewport)
            OfflineAreaKind.PROJECT -> stringResource(R.string.offline_area_project)
        }
        ChoiceRow(label, selected = kind == state.areaKind, tag = offlineAreaTag(kind)) { actions.onAreaKind(kind) }
    }
    when (state.areaKind) {
        OfflineAreaKind.RADIUS -> StepSlider(
            label = stringResource(R.string.offline_plan_radius, OfflineFormat.distance(state.radiusM, locale)),
            steps = RADIUS_STEPS,
            current = state.radiusM,
            sliderTag = OFFLINE_RADIUS_TAG,
            valueTag = OFFLINE_RADIUS_VALUE_TAG,
            onChange = actions.onRadius,
        )
        OfflineAreaKind.PROJECT -> StepSlider(
            label = stringResource(R.string.offline_plan_margin, OfflineFormat.distance(state.marginM, locale)),
            steps = MARGIN_STEPS,
            current = state.marginM,
            sliderTag = OFFLINE_MARGIN_TAG,
            valueTag = OFFLINE_MARGIN_VALUE_TAG,
            onChange = actions.onMargin,
        )
        else -> Unit
    }
}

@Composable
private fun SourcesSection(state: DownloadPlanUiState, actions: DownloadPlanActions) {
    SectionTitle(stringResource(R.string.offline_plan_sources))
    state.sources.forEach { choice ->
        Row(
            Modifier.fillMaxWidth().heightIn(min = MinTouchTarget).toggleable(
                value = choice.selected,
                role = Role.Checkbox,
                onValueChange = { actions.onToggleSource(choice.source.key) },
            ).testTag(offlineSourceTag(choice.source.key)),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(checked = choice.selected, onCheckedChange = null)
            Text(sourceLabel(choice.source), modifier = Modifier.padding(start = 12.dp))
        }
    }
    if (state.sources.none { it.selected }) {
        Text(
            stringResource(R.string.offline_plan_no_sources),
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.testTag(OFFLINE_NO_SOURCES_TAG),
        )
    }
}

@Composable
private fun DetailSection(state: DownloadPlanUiState, actions: DownloadPlanActions) {
    SectionTitle(stringResource(R.string.offline_plan_detail))
    DetailLevel.entries.forEach { level ->
        val label = when (level) {
            DetailLevel.NORMAL -> stringResource(R.string.offline_detail_normal)
            DetailLevel.HIGH -> stringResource(R.string.offline_detail_high)
            DetailLevel.MAX -> stringResource(R.string.offline_detail_max)
        }
        ChoiceRow(label, selected = level == state.detail, tag = offlineDetailTag(level)) { actions.onDetail(level) }
    }
}

@Composable
private fun EstimateSection(state: DownloadPlanUiState, locale: Locale) {
    val estimate = state.estimate ?: return
    SectionTitle(stringResource(R.string.offline_plan_estimate))
    estimate.sources.forEach { item ->
        Text(
            stringResource(
                R.string.offline_estimate_source,
                sourceLabel(item.source),
                OfflineFormat.count(item.tiles, locale),
                OfflineFormat.bytes(item.estimatedBytes, locale),
            ),
            modifier = Modifier.testTag(offlineEstimateTag(item.source.key)),
        )
    }
    Text(
        stringResource(
            R.string.offline_estimate_total,
            OfflineFormat.count(estimate.totalTiles, locale),
            OfflineFormat.bytes(estimate.totalBytes, locale),
        ),
        style = MaterialTheme.typography.titleSmall,
        modifier = Modifier.testTag(OFFLINE_ESTIMATE_TOTAL_TAG),
    )
    state.storage?.let { storage ->
        Text(
            stringResource(
                R.string.offline_estimate_storage,
                OfflineFormat.bytes(storage.freeBytes, locale),
                OfflineFormat.bytes(storage.usedBytes, locale),
                OfflineFormat.bytes(storage.limitBytes, locale),
            ),
            modifier = Modifier.testTag(OFFLINE_ESTIMATE_STORAGE_TAG),
        )
    }
    estimate.blocks.forEachIndexed { index, block ->
        Text(blockText(block, locale), color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag(offlineBlockTag(index)))
    }
}

@Composable
private fun blockText(block: EstimateBlock, locale: Locale): String = when (block) {
    EstimateBlock.NoInternet -> stringResource(R.string.offline_block_no_internet)
    is EstimateBlock.OpenFreeMapLimit -> stringResource(
        R.string.offline_block_openfreemap,
        OfflineFormat.count(OfflineLimits.OPENFREEMAP_MAX_TILES, locale),
        OfflineFormat.bytes(OfflineLimits.OPENFREEMAP_MAX_BYTES, locale),
        OfflineFormat.count(block.tiles, locale),
        OfflineFormat.bytes(block.bytes, locale),
    )
    is EstimateBlock.NoSpace -> stringResource(R.string.offline_block_no_space, OfflineFormat.bytes(block.missingBytes, locale))
    is EstimateBlock.OverLimit -> stringResource(R.string.offline_block_over_limit, OfflineFormat.bytes(block.missingBytes, locale))
}

@Composable
private fun ConfirmationDialog(
    confirmation: EstimateConfirmation,
    estimatedBytes: Long,
    locale: Locale,
    onAccept: () -> Unit,
    onCancel: () -> Unit,
) {
    val (title, text) = when (confirmation) {
        EstimateConfirmation.MobileData ->
            stringResource(R.string.offline_confirm_mobile_title) to
                stringResource(R.string.offline_confirm_mobile_text, OfflineFormat.bytes(estimatedBytes, locale))
        is EstimateConfirmation.OwnWms ->
            stringResource(R.string.offline_confirm_wms_title) to
                stringResource(R.string.offline_confirm_wms_text, confirmation.serviceName)
    }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = {
            TextButton(onClick = onAccept, modifier = Modifier.heightIn(min = MinTouchTarget).testTag(OFFLINE_CONFIRM_ACCEPT_TAG)) {
                Text(stringResource(R.string.offline_confirm_accept))
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel, modifier = Modifier.heightIn(min = MinTouchTarget).testTag(OFFLINE_CONFIRM_CANCEL_TAG)) {
                Text(stringResource(R.string.dialog_cancel))
            }
        },
        modifier = Modifier.testTag(OFFLINE_CONFIRM_TAG),
    )
}

/** Cómo se llama una fuente en pantalla: los mapas base con su texto traducido y las capas WMS con el título del servicio. */
@Composable
fun sourceLabel(source: DownloadableSource): String = when (source.kind) {
    SourceKind.IGN_VECTOR -> stringResource(R.string.offline_source_ign)
    SourceKind.PNOA -> stringResource(R.string.offline_source_pnoa)
    SourceKind.OPENFREEMAP -> stringResource(R.string.offline_source_osm)
    SourceKind.WMS -> source.title
}

@Composable
private fun ChoiceRow(label: String, selected: Boolean, tag: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(
            min = MinTouchTarget,
        ).selectable(selected = selected, role = Role.RadioButton, onClick = onClick).testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(label, modifier = Modifier.padding(start = 12.dp))
    }
}

/** Un deslizador que va por los valores de [steps]; el valor de ahora ([current]) puede no estar en la lista y entonces se marca el más cercano. */
@Composable
private fun StepSlider(
    label: String,
    steps: List<Double>,
    current: Double,
    sliderTag: String,
    valueTag: String,
    onChange: (Double) -> Unit,
) {
    val index = steps.indices.minBy { abs(steps[it] - current) }
    Column {
        Text(label, modifier = Modifier.padding(top = 4.dp).testTag(valueTag))
        // La zona táctil de 56 dp la da la caja; el deslizador de Material mide menos (RNF-T8).
        Box(Modifier.fillMaxWidth().heightIn(min = MinTouchTarget), contentAlignment = Alignment.Center) {
            Slider(
                value = index.toFloat(),
                onValueChange = { onChange(steps[it.roundToInt().coerceIn(0, steps.lastIndex)]) },
                valueRange = 0f..steps.lastIndex.toFloat(),
                steps = steps.size - 2,
                // TalkBack dice el nombre y el valor («Radio: 500 m»), no solo el porcentaje del recorrido (RNF-T8).
                modifier = Modifier.testTag(sliderTag).semantics { stateDescription = label },
            )
        }
    }
}
