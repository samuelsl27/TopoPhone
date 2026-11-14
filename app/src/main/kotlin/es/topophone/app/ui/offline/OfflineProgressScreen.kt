package es.topophone.app.ui.offline

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import es.topophone.app.R
import es.topophone.core.designsystem.component.MinTouchTarget
import es.topophone.core.model.offline.SourceState
import es.topophone.core.model.offline.ZoneState

const val OFFLINE_PROGRESS_TAG = "offline_progress"
const val OFFLINE_PROGRESS_IDLE_TAG = "offline_progress_idle"
const val OFFLINE_PROGRESS_PHASE_TAG = "offline_progress_phase"
const val OFFLINE_PROGRESS_BAR_TAG = "offline_progress_bar"
const val OFFLINE_PROGRESS_PERCENT_TAG = "offline_progress_percent"
const val OFFLINE_PROGRESS_TILES_TAG = "offline_progress_tiles"
const val OFFLINE_PROGRESS_BYTES_TAG = "offline_progress_bytes"
const val OFFLINE_PROGRESS_CURRENT_TAG = "offline_progress_current"
const val OFFLINE_PAUSE_TAG = "offline_pause"
const val OFFLINE_RESUME_TAG = "offline_resume"
const val OFFLINE_RETRY_TAG = "offline_retry"
const val OFFLINE_CANCEL_TAG = "offline_cancel"
const val OFFLINE_CANCEL_DIALOG_TAG = "offline_cancel_dialog"
const val OFFLINE_CANCEL_CONFIRM_TAG = "offline_cancel_confirm"
const val OFFLINE_CANCEL_KEEP_TAG = "offline_cancel_keep"

fun offlineProgressSourceTag(key: String) = "offline_progress_source_$key"

/** Lo que la pantalla de la descarga pide hacer; lo atiende [OfflineProgressViewModel] (S037/RF-16). */
class OfflineProgressActions(val onPause: () -> Unit = {}, val onResume: () -> Unit = {}, val onCancel: () -> Unit = {}) {
    companion object {
        fun of(viewModel: OfflineProgressViewModel) = OfflineProgressActions(viewModel::pause, viewModel::resume, viewModel::cancel)
    }
}

/** La hoja con el progreso de la descarga de la zona (S037/RF-15 a RF-18). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OfflineProgressSheet(onDismiss: () -> Unit, viewModel: OfflineProgressViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        OfflineProgressContent(state, OfflineProgressActions.of(viewModel))
    }
}

/** El progreso: la fase, la barra con el porcentaje, las teselas y los MB, la fuente en curso y el estado de cada una, con pausa, reanudar y cancelar. */
@Composable
fun OfflineProgressContent(state: DownloadState, actions: OfflineProgressActions, modifier: Modifier = Modifier) {
    // S037/RF-17: mientras descarga y esta pantalla está a la vista, la pantalla no se apaga.
    KeepScreenOn(state.phase == DownloadPhase.RUNNING)
    val locale = LocalConfiguration.current.locales[0]
    var confirmingCancel by remember { mutableStateOf(false) }
    Column(
        modifier.fillMaxWidth().verticalScroll(
            rememberScrollState(),
        ).padding(horizontal = 16.dp, vertical = 8.dp).testTag(OFFLINE_PROGRESS_TAG),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            stringResource(R.string.offline_progress_title),
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.semantics {
                heading()
            },
        )
        if (state.phase == DownloadPhase.IDLE) {
            Text(stringResource(R.string.offline_progress_idle), modifier = Modifier.testTag(OFFLINE_PROGRESS_IDLE_TAG))
            return@Column
        }
        Text(state.name, style = MaterialTheme.typography.titleMedium)
        // Un cambio de fase (descargando, en pausa, terminada) se anuncia solo (RNF-T8).
        Text(
            phaseText(state),
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }.testTag(OFFLINE_PROGRESS_PHASE_TAG),
        )
        val barLabel = stringResource(R.string.offline_progress_bar)
        LinearProgressIndicator(
            progress = { state.fraction },
            modifier = Modifier.fillMaxWidth().testTag(OFFLINE_PROGRESS_BAR_TAG).semantics { contentDescription = barLabel },
        )
        Text(
            stringResource(R.string.offline_progress_percent, (state.fraction * PERCENT).toInt()),
            modifier = Modifier.testTag(OFFLINE_PROGRESS_PERCENT_TAG),
        )
        Text(
            stringResource(
                R.string.offline_progress_tiles,
                OfflineFormat.count(state.completedTiles, locale),
                OfflineFormat.count(state.sources.sumOf { it.expectedTiles }, locale),
            ),
            modifier = Modifier.testTag(OFFLINE_PROGRESS_TILES_TAG),
        )
        Text(
            stringResource(R.string.offline_progress_bytes, OfflineFormat.bytes(state.bytes, locale)),
            modifier = Modifier.testTag(OFFLINE_PROGRESS_BYTES_TAG),
        )
        state.currentSource?.let {
            Text(
                stringResource(R.string.offline_progress_current, sourceLabel(it)),
                modifier = Modifier.testTag(OFFLINE_PROGRESS_CURRENT_TAG),
            )
        }
        state.sources.forEach { item ->
            Row(
                Modifier.fillMaxWidth().heightIn(min = MinTouchTarget).semantics(mergeDescendants = true) {
                }.testTag(offlineProgressSourceTag(item.source.key)),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(sourceLabel(item.source))
                Text(
                    sourceStateText(item, locale),
                    color = if (item.state ==
                        SourceState.INCOMPLETE
                    ) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                )
            }
        }
        Buttons(state, actions) { confirmingCancel = true }
    }
    if (confirmingCancel) {
        AlertDialog(
            onDismissRequest = { confirmingCancel = false },
            title = { Text(stringResource(R.string.offline_cancel_title)) },
            text = { Text(stringResource(R.string.offline_cancel_text)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmingCancel = false
                        actions.onCancel()
                    },
                    modifier = Modifier.heightIn(min = MinTouchTarget).testTag(OFFLINE_CANCEL_CONFIRM_TAG),
                ) { Text(stringResource(R.string.offline_cancel_confirm)) }
            },
            dismissButton = {
                TextButton(
                    onClick = { confirmingCancel = false },
                    modifier = Modifier.heightIn(min = MinTouchTarget).testTag(OFFLINE_CANCEL_KEEP_TAG),
                ) { Text(stringResource(R.string.offline_cancel_keep)) }
            },
            modifier = Modifier.testTag(OFFLINE_CANCEL_DIALOG_TAG),
        )
    }
}

@Composable
private fun Buttons(state: DownloadState, actions: OfflineProgressActions, onAskCancel: () -> Unit) {
    val incomplete = state.phase == DownloadPhase.FINISHED && state.zoneState == ZoneState.INCOMPLETE
    when {
        state.phase == DownloadPhase.RUNNING ->
            Button(actions.onPause, Modifier.fillMaxWidth().heightIn(min = MinTouchTarget).testTag(OFFLINE_PAUSE_TAG)) {
                Text(stringResource(R.string.offline_action_pause))
            }
        state.phase == DownloadPhase.PAUSED ->
            Button(actions.onResume, Modifier.fillMaxWidth().heightIn(min = MinTouchTarget).testTag(OFFLINE_RESUME_TAG)) {
                Text(stringResource(R.string.offline_action_resume))
            }
        incomplete ->
            Button(actions.onResume, Modifier.fillMaxWidth().heightIn(min = MinTouchTarget).testTag(OFFLINE_RETRY_TAG)) {
                Text(stringResource(R.string.offline_action_retry))
            }
    }
    if (state.phase == DownloadPhase.RUNNING || state.phase == DownloadPhase.PAUSED) {
        OutlinedButton(onAskCancel, Modifier.fillMaxWidth().heightIn(min = MinTouchTarget).testTag(OFFLINE_CANCEL_TAG)) {
            Text(stringResource(R.string.offline_action_cancel))
        }
    }
}

@Composable
private fun phaseText(state: DownloadState): String = when (state.phase) {
    DownloadPhase.IDLE -> ""
    DownloadPhase.RUNNING -> stringResource(R.string.offline_phase_running)
    DownloadPhase.PAUSED -> stringResource(R.string.offline_phase_paused)
    DownloadPhase.FINISHED ->
        if (state.zoneState ==
            ZoneState.COMPLETE
        ) {
            stringResource(R.string.offline_phase_finished)
        } else {
            stringResource(R.string.offline_phase_finished_incomplete)
        }
}

/** El estado de una fuente con texto, no solo con color (RNF-T8). */
@Composable
private fun sourceStateText(item: SourceProgress, locale: java.util.Locale): String = when (item.state) {
    SourceState.PENDING -> stringResource(R.string.offline_state_pending)
    SourceState.DOWNLOADING -> stringResource(R.string.offline_state_downloading)
    SourceState.PAUSED -> stringResource(R.string.offline_state_paused)
    SourceState.COMPLETE -> stringResource(R.string.offline_state_complete)
    SourceState.INCOMPLETE -> stringResource(R.string.offline_state_incomplete, OfflineFormat.count(item.failedTiles, locale))
}

/** Mantiene la pantalla encendida mientras [active] y esta pantalla está a la vista (S037/RF-17). */
@Composable
fun KeepScreenOn(active: Boolean) {
    val view = LocalView.current
    DisposableEffect(view, active) {
        view.keepScreenOn = active
        onDispose { view.keepScreenOn = false }
    }
}

private const val PERCENT = 100
