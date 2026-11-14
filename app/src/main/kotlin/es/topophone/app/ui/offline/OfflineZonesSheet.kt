package es.topophone.app.ui.offline

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
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
import es.topophone.core.model.offline.ZoneState
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

const val OFFLINE_ENTRY_TAG = "offline_entry"
const val OFFLINE_ZONES_TAG = "offline_zones"
const val OFFLINE_ZONES_EMPTY_TAG = "offline_zones_empty"
const val OFFLINE_ZONES_USAGE_TAG = "offline_zones_usage"
const val OFFLINE_ZONES_USAGE_BAR_TAG = "offline_zones_usage_bar"
const val OFFLINE_ZONES_LIMIT_TAG = "offline_zones_limit"
const val OFFLINE_ZONES_LIMIT_VALUE_TAG = "offline_zones_limit_value"
const val OFFLINE_ZONES_DOWNLOAD_TAG = "offline_zones_download"
const val OFFLINE_ZONES_RUNNING_TAG = "offline_zones_running"
const val OFFLINE_ZONES_SEE_PROGRESS_TAG = "offline_zones_see_progress"
const val OFFLINE_RENAME_FIELD_TAG = "offline_rename_field"
const val OFFLINE_RENAME_SAVE_TAG = "offline_rename_save"
const val OFFLINE_RENAME_CANCEL_TAG = "offline_rename_cancel"
const val OFFLINE_DELETE_DIALOG_TAG = "offline_delete_dialog"
const val OFFLINE_DELETE_CONFIRM_TAG = "offline_delete_confirm"
const val OFFLINE_DELETE_CANCEL_TAG = "offline_delete_cancel"
const val OFFLINE_RENEW_DIALOG_TAG = "offline_renew_dialog"
const val OFFLINE_RENEW_CONFIRM_TAG = "offline_renew_confirm"
const val OFFLINE_RENEW_CANCEL_TAG = "offline_renew_cancel"

fun offlineZoneTag(id: String) = "offline_zone_$id"

fun offlineZoneStateTag(id: String) = "offline_zone_state_$id"

fun offlineZoneSizeTag(id: String) = "offline_zone_size_$id"

fun offlineZoneDateTag(id: String) = "offline_zone_date_$id"

fun offlineZoneUpdateTag(id: String) = "offline_zone_update_$id"

fun offlineZoneResumeTag(id: String) = "offline_zone_resume_$id"

fun offlineZoneRenameTag(id: String) = "offline_zone_rename_$id"

fun offlineZoneDeleteTag(id: String) = "offline_zone_delete_$id"

/** Los límites de almacenamiento que se pueden elegir con el deslizador, de 200 MB a 10 GB (S037/RF-31). */
private val LIMIT_STEPS = listOf(200L, 500L, 1_024L, 2_048L, 5_120L, 10_240L).map { it * 1024 * 1024 }

/** Lo que la hoja de las zonas pide hacer; lo atiende [OfflineZonesViewModel] y el [OfflineMapsHost] (S037/RF-24 a RF-27, RF-31). */
class OfflineZonesActions(
    val onLimit: (Long) -> Unit = {},
    val onDownloadZone: () -> Unit = {},
    val onSeeProgress: () -> Unit = {},
    val onRequestRenew: (String) -> Unit = {},
    val onConfirmRenew: () -> Unit = {},
    val onDismissRenew: () -> Unit = {},
    val onResume: (String) -> Unit = {},
    val onRename: (zoneId: String, name: String) -> Unit = { _, _ -> },
    val onRequestDelete: (String) -> Unit = {},
    val onConfirmDelete: () -> Unit = {},
    val onDismissDelete: () -> Unit = {},
)

/**
 * «Mapas sin conexión» de un proyecto: las zonas con sus fuentes, nivel, tamaño, fecha y estado, la ocupación y el límite, y «Descargar zona»; entra desde «Capas»
 * y abre la hoja de descarga y la del progreso (S037/RF-1, RF-24 a RF-27, RF-31).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OfflineMapsHost(projectId: String, projectName: String, visible: () -> GeoBox?, onDismiss: () -> Unit) {
    val zones: OfflineZonesViewModel = hiltViewModel()
    val state by zones.state.collectAsStateWithLifecycle()
    var showPlan by rememberSaveable { mutableStateOf(false) }
    var showProgress by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(projectId) { zones.open(projectId) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        OfflineZonesContent(
            state,
            OfflineZonesActions(
                onLimit = zones::setLimit,
                onDownloadZone = { showPlan = true },
                onSeeProgress = { showProgress = true },
                onRequestRenew = zones::requestRenew,
                onConfirmRenew = zones::confirmRenew,
                onDismissRenew = zones::dismissRenew,
                onResume = zones::resume,
                onRename = zones::rename,
                onRequestDelete = zones::requestDelete,
                onConfirmDelete = zones::confirmDelete,
                onDismissDelete = zones::dismissDelete,
            ),
        )
    }
    if (showPlan) {
        DownloadPlanSheet(
            projectId,
            projectName,
            visible(),
            onDismiss = { showPlan = false },
            onStarted = {
                showPlan = false
                showProgress = true
            },
        )
    }
    if (showProgress) OfflineProgressSheet(onDismiss = { showProgress = false })
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun OfflineZonesContent(state: OfflineZonesUiState, actions: OfflineZonesActions, modifier: Modifier = Modifier) {
    val locale = LocalConfiguration.current.locales[0]
    var renaming by rememberSaveable { mutableStateOf<String?>(null) }
    val busy = state.download.phase == DownloadPhase.RUNNING
    Column(
        modifier.fillMaxWidth().verticalScroll(
            rememberScrollState(),
        ).padding(horizontal = 16.dp, vertical = 8.dp).testTag(OFFLINE_ZONES_TAG),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            stringResource(R.string.offline_zones_title),
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.semantics {
                heading()
            },
        )
        Text(
            stringResource(
                R.string.offline_zones_usage,
                OfflineFormat.bytes(state.totalBytes, locale),
                OfflineFormat.bytes(state.limitBytes, locale),
            ),
            modifier = Modifier.testTag(OFFLINE_ZONES_USAGE_TAG),
        )
        val usageBarLabel = stringResource(R.string.offline_zones_usage_bar)
        LinearProgressIndicator(
            progress = { if (state.limitBytes <= 0) 0f else (state.totalBytes.toFloat() / state.limitBytes).coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth().testTag(OFFLINE_ZONES_USAGE_BAR_TAG).semantics { contentDescription = usageBarLabel },
        )
        LimitSlider(state.limitBytes, locale, actions.onLimit)
        // Solo mientras se descarga o está en pausa: una descarga terminada ya está en la lista de zonas.
        if (busy || state.download.phase == DownloadPhase.PAUSED) {
            OutlinedCard(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}.testTag(OFFLINE_ZONES_RUNNING_TAG)) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(stringResource(R.string.offline_zones_running, state.download.name))
                    OutlinedButton(
                        actions.onSeeProgress,
                        Modifier.fillMaxWidth().heightIn(min = MinTouchTarget).testTag(OFFLINE_ZONES_SEE_PROGRESS_TAG),
                    ) { Text(stringResource(R.string.offline_zones_see_progress)) }
                }
            }
        }
        Button(
            actions.onDownloadZone,
            Modifier.fillMaxWidth().heightIn(min = MinTouchTarget).testTag(OFFLINE_ZONES_DOWNLOAD_TAG),
            enabled = !busy,
        ) { Text(stringResource(R.string.offline_zones_download)) }
        if (state.zones.isEmpty()) {
            Text(stringResource(R.string.offline_zones_empty), modifier = Modifier.testTag(OFFLINE_ZONES_EMPTY_TAG))
        }
        state.zones.forEach { zone ->
            OutlinedCard(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}.testTag(offlineZoneTag(zone.zoneId))) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(zone.name, style = MaterialTheme.typography.titleMedium)
                    Text(
                        zoneStateText(zone),
                        color = zoneStateColor(zone.state),
                        modifier = Modifier.testTag(offlineZoneStateTag(zone.zoneId)),
                    )
                    Text(stringResource(R.string.offline_zone_sources, zone.sources.map { sourceLabel(it) }.joinToString()))
                    Text(stringResource(R.string.offline_zone_detail, detailLabel(zone.detail)))
                    Text(
                        stringResource(R.string.offline_zone_size, OfflineFormat.bytes(zone.sizeBytes, locale)),
                        modifier = Modifier.testTag(offlineZoneSizeTag(zone.zoneId)),
                    )
                    Text(
                        zone.downloadedAtMs?.let {
                            stringResource(
                                R.string.offline_zone_date,
                                DateFormat.getDateInstance(DateFormat.MEDIUM, locale).format(Date(it)),
                            )
                        }
                            ?: stringResource(R.string.offline_zone_no_date),
                        modifier = Modifier.testTag(offlineZoneDateTag(zone.zoneId)),
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        when (zone.state) {
                            ZoneState.COMPLETE -> ZoneButton(R.string.offline_zone_update, offlineZoneUpdateTag(zone.zoneId), !busy) {
                                actions.onRequestRenew(zone.zoneId)
                            }
                            ZoneState.INCOMPLETE -> ZoneButton(R.string.offline_action_resume, offlineZoneResumeTag(zone.zoneId), !busy) {
                                actions.onResume(zone.zoneId)
                            }
                        }
                        ZoneButton(R.string.offline_zone_rename, offlineZoneRenameTag(zone.zoneId)) { renaming = zone.zoneId }
                        ZoneButton(R.string.offline_zone_delete, offlineZoneDeleteTag(zone.zoneId)) { actions.onRequestDelete(zone.zoneId) }
                    }
                }
            }
        }
    }
    renaming?.let { zoneId ->
        val zone = state.zones.firstOrNull { it.zoneId == zoneId }
        if (zone == null) {
            renaming = null
        } else {
            RenameDialog(zone.name, onSave = { name ->
                renaming = null
                actions.onRename(zoneId, name)
            }, onCancel = { renaming = null })
        }
    }
    state.pendingRenew?.let { zone ->
        AlertDialog(
            onDismissRequest = actions.onDismissRenew,
            title = { Text(stringResource(R.string.offline_renew_title)) },
            text = { Text(stringResource(R.string.offline_renew_text, zone.name)) },
            confirmButton = {
                TextButton(actions.onConfirmRenew, Modifier.heightIn(min = MinTouchTarget).testTag(OFFLINE_RENEW_CONFIRM_TAG)) {
                    Text(stringResource(R.string.offline_renew_confirm))
                }
            },
            dismissButton = {
                TextButton(actions.onDismissRenew, Modifier.heightIn(min = MinTouchTarget).testTag(OFFLINE_RENEW_CANCEL_TAG)) {
                    Text(stringResource(R.string.dialog_cancel))
                }
            },
            modifier = Modifier.testTag(OFFLINE_RENEW_DIALOG_TAG),
        )
    }
    state.pendingDelete?.let { zone ->
        AlertDialog(
            onDismissRequest = actions.onDismissDelete,
            title = { Text(stringResource(R.string.offline_delete_title)) },
            text = { Text(stringResource(R.string.offline_delete_text, zone.name, OfflineFormat.bytes(zone.sizeBytes, locale))) },
            confirmButton = {
                TextButton(actions.onConfirmDelete, Modifier.heightIn(min = MinTouchTarget).testTag(OFFLINE_DELETE_CONFIRM_TAG)) {
                    Text(stringResource(R.string.offline_delete_confirm))
                }
            },
            dismissButton = {
                TextButton(actions.onDismissDelete, Modifier.heightIn(min = MinTouchTarget).testTag(OFFLINE_DELETE_CANCEL_TAG)) {
                    Text(stringResource(R.string.dialog_cancel))
                }
            },
            modifier = Modifier.testTag(OFFLINE_DELETE_DIALOG_TAG),
        )
    }
}

@Composable
private fun ZoneButton(label: Int, tag: String, enabled: Boolean = true, onClick: () -> Unit) {
    OutlinedButton(onClick, Modifier.heightIn(min = MinTouchTarget).testTag(tag), enabled = enabled) { Text(stringResource(label)) }
}

@Composable
private fun RenameDialog(current: String, onSave: (String) -> Unit, onCancel: () -> Unit) {
    var text by rememberSaveable { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(stringResource(R.string.offline_rename_title)) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text(stringResource(R.string.offline_plan_name)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag(OFFLINE_RENAME_FIELD_TAG),
            )
        },
        confirmButton = {
            TextButton({
                onSave(text)
            }, Modifier.heightIn(min = MinTouchTarget).testTag(OFFLINE_RENAME_SAVE_TAG), enabled = text.isNotBlank()) {
                Text(stringResource(R.string.offline_rename_save))
            }
        },
        dismissButton = {
            TextButton(onCancel, Modifier.heightIn(min = MinTouchTarget).testTag(OFFLINE_RENAME_CANCEL_TAG)) {
                Text(stringResource(R.string.dialog_cancel))
            }
        },
    )
}

/** El estado de una zona con texto y con color (RNF-T8): «Completa» o «Incompleta: 42 %». */
@Composable
private fun zoneStateText(zone: ZoneItem): String = when (zone.state) {
    ZoneState.COMPLETE -> stringResource(R.string.offline_state_complete)
    ZoneState.INCOMPLETE -> stringResource(R.string.offline_zone_state_incomplete, (zone.fraction * PERCENT).toInt())
}

@Composable
private fun zoneStateColor(state: ZoneState) = when (state) {
    ZoneState.INCOMPLETE -> MaterialTheme.colorScheme.error
    else -> MaterialTheme.colorScheme.onSurface
}

@Composable
private fun detailLabel(level: DetailLevel): String = when (level) {
    DetailLevel.NORMAL -> stringResource(R.string.offline_detail_normal)
    DetailLevel.HIGH -> stringResource(R.string.offline_detail_high)
    DetailLevel.MAX -> stringResource(R.string.offline_detail_max)
}

/** Un deslizador por los límites de 200 MB, 500 MB, 1, 2, 5 y 10 GB; el límite de ahora, si no es uno de ellos, marca el más cercano (RF-31). */
@Composable
private fun LimitSlider(limitBytes: Long, locale: Locale, onLimit: (Long) -> Unit) {
    val index = LIMIT_STEPS.indices.minBy { abs(LIMIT_STEPS[it] - limitBytes) }
    val label = stringResource(R.string.offline_zones_limit, OfflineFormat.bytes(LIMIT_STEPS[index], locale))
    Column {
        Text(label, modifier = Modifier.testTag(OFFLINE_ZONES_LIMIT_VALUE_TAG))
        // La zona táctil de 56 dp la da la caja; el deslizador de Material mide menos (RNF-T8).
        Box(Modifier.fillMaxWidth().heightIn(min = MinTouchTarget), contentAlignment = Alignment.Center) {
            Slider(
                value = index.toFloat(),
                onValueChange = { onLimit(LIMIT_STEPS[it.roundToInt().coerceIn(0, LIMIT_STEPS.lastIndex)]) },
                valueRange = 0f..LIMIT_STEPS.lastIndex.toFloat(),
                steps = LIMIT_STEPS.size - 2,
                // TalkBack dice el nombre y el valor («Límite de almacenamiento: 2 GB»), no solo el porcentaje del recorrido (RNF-T8).
                modifier = Modifier.testTag(OFFLINE_ZONES_LIMIT_TAG).semantics { stateDescription = label },
            )
        }
    }
}

private const val PERCENT = 100
