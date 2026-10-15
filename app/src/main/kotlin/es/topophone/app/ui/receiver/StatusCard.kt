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
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

const val SOLUTION_STATUS_TAG = "solution_status"
const val STATUS_SATELLITES_TAG = "status_satellites"
const val STATUS_TIME_TAG = "status_time"
const val STATUS_HDOP_TAG = "status_hdop"
const val STATUS_CORRECTION_AGE_TAG = "status_correction_age"
const val STATUS_SIGMA_H_TAG = "status_sigma_h"
const val STATUS_SIGMA_V_TAG = "status_sigma_v"

/** Tarjeta Estado: solución, hora, satélites, HDOP, edad de las correcciones y precisiones (S002/RF-12 a RF-17, S003/RF-13 a RF-15). */
@Composable
internal fun StatusCard(snapshot: ReceiverSnapshot) {
    ReceiverCard(stringResource(R.string.receiver_status_title)) {
        val gga = snapshot.gga
        // Solo lo que llega ahora: con la conexión perdida o "Sin datos" no se enseña una solución antigua (S003/RF-13).
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
        snapshot.epoch?.let { epoch ->
            LabeledValue(
                stringResource(R.string.receiver_status_time),
                epochTime(epoch.utcMillisOfDay, epoch.date, locale),
                STATUS_TIME_TAG,
            )
        } // S003/RF-14
        val inView = snapshot.satellitesInView
        LabeledValue(
            stringResource(R.string.receiver_status_satellites),
            if (inView == null) gga.satellites.toString() else stringResource(R.string.receiver_satellites_in_view, gga.satellites, inView),
            STATUS_SATELLITES_TAG,
        ) // S002/RF-13, S003/RF-15
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

/**
 * Fecha y hora de una época en la hora local del móvil (RNF-T10); sin fecha no se puede pasar a hora
 * local, así que se muestra la hora UTC y "fecha no disponible" (S003/RF-14).
 */
@Composable
private fun epochTime(utcMillisOfDay: Int, date: LocalDate?, locale: Locale): String {
    val utcTime = LocalTime.ofNanoOfDay(utcMillisOfDay * NANOS_PER_MILLI)
    if (date == null) return stringResource(R.string.receiver_epoch_time_no_date, utcTime.format(UTC_TIME))
    val local = ZonedDateTime.of(date, utcTime, ZoneOffset.UTC).withZoneSameInstant(ZoneId.systemDefault())
    return local.format(DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM).withLocale(locale))
}

private const val NANOS_PER_MILLI = 1_000_000L
private val UTC_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")

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

/** Nombre de la solución en la interfaz (S002/RF-12). */
internal fun SolutionStatus.nameRes(): Int = when (this) {
    SolutionStatus.NONE -> R.string.solution_none
    SolutionStatus.SINGLE -> R.string.solution_single
    SolutionStatus.DGPS -> R.string.solution_dgps
    SolutionStatus.FLOAT -> R.string.solution_float
    SolutionStatus.FIX -> R.string.solution_fix
    SolutionStatus.ESTIMATED -> R.string.solution_estimated
}

/** Color de la solución, siempre junto a su nombre (S002/RF-16, RNF-T8). */
internal fun SolutionStatus.tone(): StatusTone = when (this) {
    SolutionStatus.FIX -> StatusTone.POSITIVE
    SolutionStatus.FLOAT -> StatusTone.WARNING
    SolutionStatus.DGPS -> StatusTone.INFO
    SolutionStatus.SINGLE -> StatusTone.NEGATIVE
    SolutionStatus.NONE, SolutionStatus.ESTIMATED -> StatusTone.NEUTRAL
}
