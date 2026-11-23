package es.topophone.app.ui.collector

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import es.topophone.app.R
import es.topophone.core.designsystem.component.MinTouchTarget

const val COLLECTOR_REASON_ACTION_TAG = "collector_reason_action"

/** Lo que resuelve un motivo de bloqueo con un toque (S060/RF-16). */
enum class ReasonAction { OPEN_RECEIVER, OPEN_PROJECT_CRS, OPEN_TILT, OPEN_GEAR, EDIT_POLE, EDIT_TIME, EXPAND }

/** Por qué «Medir» no se puede pulsar: una frase y la acción que lo arregla (S060/RF-16). */
data class MeasureReason(val blocker: MeasureBlocker, @StringRes val textRes: Int, @StringRes val actionRes: Int, val action: ReasonAction)

object MeasureReasons {
    /**
     * El motivo de [blocker], o null si no hay bloqueo. La prioridad entre varios es la del ViewModel, que da uno solo (formulario,
     * equipo, posición, inclinación, atributos): no cambia la lógica de S010 (S060/RF-16, RF-32).
     */
    fun of(blocker: MeasureBlocker?): MeasureReason? = when (blocker) {
        null -> null
        MeasureBlocker.NO_POSITION -> reason(
            blocker,
            R.string.field_reason_no_position,
            R.string.field_action_receivers,
            ReasonAction.OPEN_RECEIVER,
        )
        MeasureBlocker.OUTSIDE_CRS_AREA ->
            reason(blocker, R.string.field_reason_outside_crs, R.string.field_action_crs, ReasonAction.OPEN_PROJECT_CRS)
        MeasureBlocker.TILT_MODE_MISSING -> reason(
            blocker,
            R.string.field_reason_tilt_mode,
            R.string.field_action_tilt,
            ReasonAction.OPEN_TILT,
        )
        MeasureBlocker.TILT_NOT_READY -> reason(
            blocker,
            R.string.field_reason_tilt_not_ready,
            R.string.field_action_tilt,
            ReasonAction.OPEN_TILT,
        )
        MeasureBlocker.INVALID_POLE -> reason(blocker, R.string.field_reason_pole, R.string.field_action_pole, ReasonAction.EDIT_POLE)
        MeasureBlocker.NO_GEAR -> reason(blocker, R.string.field_reason_gear, R.string.field_action_gear, ReasonAction.OPEN_GEAR)
        MeasureBlocker.ATTRIBUTES -> reason(blocker, R.string.field_reason_attributes, R.string.field_action_expand, ReasonAction.EXPAND)
        MeasureBlocker.INVALID_NAME -> reason(
            blocker,
            R.string.field_reason_name_invalid,
            R.string.field_action_expand,
            ReasonAction.EXPAND,
        )
        MeasureBlocker.NAME_TAKEN -> reason(blocker, R.string.field_reason_name_taken, R.string.field_action_expand, ReasonAction.EXPAND)
        MeasureBlocker.INVALID_SECONDS -> reason(blocker, R.string.field_reason_seconds, R.string.field_action_time, ReasonAction.EDIT_TIME)
        MeasureBlocker.AUTO_INTERVAL_TOO_SHORT ->
            reason(blocker, R.string.collector_auto_interval_too_short, R.string.field_action_time, ReasonAction.EDIT_TIME)
        MeasureBlocker.INVALID_AUTO_INTERVAL ->
            reason(blocker, R.string.field_reason_auto_interval, R.string.field_action_time, ReasonAction.EDIT_TIME)
        MeasureBlocker.INVALID_AUTO_DISTANCE ->
            reason(blocker, R.string.field_reason_auto_distance, R.string.field_action_time, ReasonAction.EDIT_TIME)
    }

    private fun reason(blocker: MeasureBlocker, @StringRes text: Int, @StringRes action: Int, kind: ReasonAction) =
        MeasureReason(blocker, text, action, kind)
}

/**
 * La línea del motivo bajo «Medir»: la frase en el color de error (es lo único que lo usa) y, junto a ella, la acción con 56 dp. Sin
 * motivo no dibuja nada (S060/RF-16, RF-18, RF-19).
 */
@Composable
fun MeasureReasonLine(reason: MeasureReason?, modifier: Modifier = Modifier, onAction: (ReasonAction) -> Unit) {
    if (reason == null) return
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            stringResource(reason.textRes),
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f).testTag(COLLECTOR_BLOCKER_TAG),
        )
        TextButton(
            onClick = { onAction(reason.action) },
            modifier = Modifier.heightIn(min = MinTouchTarget).testTag(COLLECTOR_REASON_ACTION_TAG),
        ) { Text(stringResource(reason.actionRes)) }
    }
}
