package es.topophone.app.ui.collector

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import es.topophone.app.R
import es.topophone.core.designsystem.component.MinTouchTarget
import es.topophone.core.model.format.DurationText
import es.topophone.core.model.point.MeasurementMode
import es.topophone.core.survey.TiltGuide

const val COLLECTOR_SOLUTION_LOST_TAG = "collector_solution_lost"
const val COLLECTOR_ERROR_TAG = "collector_error"
const val COLLECTOR_SAVED_TAG = "collector_saved"
const val COLLECTOR_PROGRESS_TAG = "collector_progress"

/** Una línea de aviso de la hoja del recolector: solo se enseña una a la vez (S060/RF-22). */
sealed interface CollectorNotice {
    data class Error(val error: CollectorError) : CollectorNotice

    data object SolutionLost : CollectorNotice

    data class TiltLost(val guide: TiltGuide) : CollectorNotice

    data object Paused : CollectorNotice

    data object NoEpochs : CollectorNotice

    data class Restarted(val discardedS: Double?) : CollectorNotice

    data class Saved(val name: String) : CollectorNotice
}

object CollectorNotices {
    /**
     * El aviso que toca, por prioridad: el error de una medida cancelada, solución degradada, inclinación perdida, pausa, sin épocas,
     * medida reiniciada con la base nueva y, al final, el punto guardado. Los de la medida solo cuentan mientras se mide (S060/RF-22).
     */
    fun current(state: CollectorUiState): CollectorNotice? {
        state.error?.let { return CollectorNotice.Error(it) }
        if (state.measuring) {
            if (state.solutionLost) return CollectorNotice.SolutionLost
            state.tiltLost?.let { return CollectorNotice.TiltLost(it) }
            if (state.paused == PauseState.PAUSED) return CollectorNotice.Paused
            if (state.noEpochsYet) return CollectorNotice.NoEpochs
            if (state.restarted) return CollectorNotice.Restarted(state.restartedDiscardedS)
        }
        return state.savedName?.let { CollectorNotice.Saved(it) }
    }
}

/**
 * La línea de aviso con su texto de siempre, en texto neutro con un icono (el color de error es solo del motivo de bloqueo, S060/RF-18).
 * Es una región viva para que TalkBack la lea al aparecer.
 */
@Composable
fun CollectorNoticeLine(notice: CollectorNotice?, modifier: Modifier = Modifier) {
    if (notice == null) return
    val (text, tag) = when (notice) {
        is CollectorNotice.Error -> when (notice.error) {
            CollectorError.NO_DATA -> stringResource(R.string.collector_no_data) to COLLECTOR_ERROR_TAG
            CollectorError.OUTSIDE_CRS_AREA -> stringResource(R.string.project_outside_crs) to COLLECTOR_ERROR_TAG
            CollectorError.TILT_MODE_CHANGED -> stringResource(R.string.collector_tilt_mode_changed) to COLLECTOR_TILT_CHANGED_TAG
        }
        CollectorNotice.SolutionLost -> stringResource(R.string.collector_solution_lost) to COLLECTOR_SOLUTION_LOST_TAG
        is CollectorNotice.TiltLost ->
            "${stringResource(R.string.collector_tilt_lost)} ${stringResource(notice.guide.textRes())}" to COLLECTOR_TILT_LOST_TAG
        CollectorNotice.Paused -> stringResource(R.string.collector_paused) to COLLECTOR_PAUSED_TAG
        CollectorNotice.NoEpochs -> stringResource(R.string.collector_no_epochs_yet) to COLLECTOR_NO_EPOCHS_TAG
        is CollectorNotice.Restarted -> {
            val discarded = notice.discardedS?.let { DurationText.format(it.toInt()) }
            val restarted = if (discarded == null) {
                stringResource(R.string.collector_restarted)
            } else {
                stringResource(R.string.collector_restarted_discarded, discarded)
            }
            restarted to COLLECTOR_RESTARTED_TAG
        }
        is CollectorNotice.Saved -> stringResource(R.string.collector_saved, notice.name) to COLLECTOR_SAVED_TAG
    }
    Row(
        modifier = modifier.semantics { liveRegion = LiveRegionMode.Polite },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (notice !is CollectorNotice.Saved) {
            Icon(painterResource(R.drawable.ic_warning), contentDescription = null, modifier = Modifier.size(20.dp))
        }
        Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag(tag))
    }
}

/**
 * La medición en curso en la hoja plegada: el progreso con el tiempo y las épocas, o «Midiendo…» en instantáneo, y «Terminar» (solo en
 * promediado) y «Cancelar» (S060/RF-15; S010/RF-9, RF-11; S017/RF-2 a RF-6).
 */
@Composable
internal fun MeasuringBar(state: CollectorUiState, actions: CollectorActions, modifier: Modifier = Modifier) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val progress = state.progress
        if (progress != null) {
            Column(
                modifier = Modifier.semantics(mergeDescendants = true) {
                }.testTag(COLLECTOR_PROGRESS_TAG),
            ) { AveragingProgress(progress) }
        } else {
            Text(stringResource(R.string.collector_measuring), modifier = Modifier.testTag(COLLECTOR_PROGRESS_TAG))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            if (state.settings.mode == MeasurementMode.AVERAGED) {
                Button(
                    onClick = actions.onFinish,
                    modifier = Modifier.weight(1f).heightIn(min = MinTouchTarget).testTag(COLLECTOR_FINISH_TAG),
                ) { Text(stringResource(R.string.collector_finish)) }
            }
            OutlinedButton(
                onClick = actions.onCancel,
                modifier = Modifier.weight(1f).heightIn(min = MinTouchTarget).testTag(COLLECTOR_CANCEL_TAG),
            ) { Text(stringResource(R.string.collector_cancel)) }
        }
    }
}
