package es.topophone.app.ui.collector

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import es.topophone.app.ui.field.FieldSheet
import es.topophone.app.ui.field.FieldSheetValue
import es.topophone.core.model.project.LinearUnit

/**
 * El recolector como hoja de campo sobre el mapa: plegada lleva la línea de estado, las pastillas, el motivo y «Medir»; expandida añade
 * el nombre, el código, las fotos y lo demás (S060/RF-1, RF-3, RF-4, RF-6). Sustituye a `CollectorPanel`. La pantalla no se apaga
 * mientras está abierta (S010/RF-7).
 */
@Composable
fun CollectorSheet(
    state: CollectorUiState,
    unit: LinearUnit,
    actions: CollectorActions,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    stats: AverageStatsUi? = null,
    autoLive: AutoLive? = null,
    /** La cota de la superficie activa bajo la punta, sin replantear (S042/RF-33). */
    surface: SurfaceReading? = null,
) {
    KeepScreenOn()
    var value by rememberSaveable { mutableStateOf(FieldSheetValue.Folded) }
    FieldSheet(
        value = value,
        onValueChange = { value = it },
        onClose = onClose,
        modifier = modifier,
        tag = COLLECTOR_PANEL_TAG,
        closeTag = COLLECTOR_CLOSE_TAG,
        header = { StatusLine(state.live, unit, state.settings.precision, savedName = state.savedName) },
        folded = {
            CollectorFolded(state, unit, actions, onExpand = { value = FieldSheetValue.Expanded }, autoLive = autoLive, showStatus = false)
        },
        expanded = { CollectorExpanded(state, unit, actions, stats = stats, surface = surface) },
    )
    state.pending?.let { OutOfToleranceDialog(it, actions.onConfirm, actions.onDiscard) } // S010/RF-14, RF-15
    if (state.paused == PauseState.LONG_PAUSE) LongPauseDialog(actions.onFinish, actions.onCancel, actions.onKeepWaiting) // S017/RF-10
}
