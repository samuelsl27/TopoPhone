package es.topophone.app.ui.receiver

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import es.topophone.app.R
import es.topophone.core.designsystem.component.StatusChip
import es.topophone.core.designsystem.theme.StatusTone
import es.topophone.core.gnss.nmea.SolutionStatus
import es.topophone.core.model.format.LengthFormat
import es.topophone.receiver.session.ConnectionState
import es.topophone.receiver.session.ReceiverSnapshot
import java.util.Locale

const val SOLUTION_STATUS_TAG = "solution_status"
const val STATUS_SATELLITES_TAG = "status_satellites"
const val STATUS_HDOP_TAG = "status_hdop"
const val STATUS_CORRECTION_AGE_TAG = "status_correction_age"
const val STATUS_SIGMA_H_TAG = "status_sigma_h"
const val STATUS_SIGMA_V_TAG = "status_sigma_v"

/** Tarjeta Estado: solución, satélites, HDOP, edad de las correcciones y precisiones (S002/RF-12 a RF-17). */
@Composable
internal fun StatusCard(snapshot: ReceiverSnapshot) {
    ReceiverCard(stringResource(R.string.receiver_status_title)) {
        val gga = snapshot.gga
        // Solo lo que llega ahora: con la conexión perdida o sin datos no se enseña una solución antigua.
        if (gga == null || snapshot.connection !is ConnectionState.Receiving) {
            Text(stringResource(R.string.receiver_status_waiting))
            return@ReceiverCard
        }
        val locale = uiLocale()
        val unavailable = stringResource(R.string.receiver_value_unavailable)
        StatusChip(
            text = stringResource(gga.status.nameRes()),
            tone = gga.status.tone(),
            modifier = Modifier.testTag(SOLUTION_STATUS_TAG),
        ) // S002/RF-12, S002/RF-16
        LabeledValue(stringResource(R.string.receiver_status_satellites), gga.satellites.toString(), STATUS_SATELLITES_TAG)
        LabeledValue(
            stringResource(R.string.receiver_status_hdop),
            gga.hdop?.let { String.format(locale, "%.1f", it) } ?: unavailable,
            STATUS_HDOP_TAG,
        )
        LabeledValue(
            stringResource(R.string.receiver_status_correction_age),
            gga.correctionAgeS?.let { stringResource(R.string.receiver_seconds, String.format(locale, "%.1f", it)) } ?: unavailable,
            STATUS_CORRECTION_AGE_TAG,
        ) // S002/RF-13
        val gst = snapshot.gst
        LabeledValue(
            stringResource(R.string.receiver_status_sigma_h),
            gst?.horizontalM?.let { LengthFormat.metres(it, locale) } ?: unavailable,
            STATUS_SIGMA_H_TAG,
        ) // S002/RF-14, S002/RF-17
        LabeledValue(
            stringResource(R.string.receiver_status_sigma_v),
            gst?.verticalM?.let { LengthFormat.metres(it, locale) } ?: unavailable,
            STATUS_SIGMA_V_TAG,
        ) // S002/RF-15, S002/RF-17
    }
}

/** Fila etiqueta/valor; TalkBack la lee como un único elemento. */
@Composable
internal fun LabeledValue(label: String, value: String, tag: String) {
    Row(
        modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}.testTag(tag),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
        Text(value, style = MaterialTheme.typography.bodyLarge, fontFamily = FontFamily.Monospace)
    }
}

/** Idioma de la interfaz, el que elige el usuario en Ajustes (no el del sistema). */
@Composable
internal fun uiLocale(): Locale = LocalConfiguration.current.locales[0]

private fun SolutionStatus.nameRes(): Int = when (this) {
    SolutionStatus.NONE -> R.string.solution_none
    SolutionStatus.SINGLE -> R.string.solution_single
    SolutionStatus.DGPS -> R.string.solution_dgps
    SolutionStatus.FLOAT -> R.string.solution_float
    SolutionStatus.FIX -> R.string.solution_fix
    SolutionStatus.ESTIMATED -> R.string.solution_estimated
}

private fun SolutionStatus.tone(): StatusTone = when (this) {
    SolutionStatus.FIX -> StatusTone.POSITIVE
    SolutionStatus.FLOAT -> StatusTone.WARNING
    SolutionStatus.DGPS -> StatusTone.INFO
    SolutionStatus.SINGLE -> StatusTone.NEGATIVE
    SolutionStatus.NONE, SolutionStatus.ESTIMATED -> StatusTone.NEUTRAL
}
