package es.topophone.app.ui.receiver

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import es.topophone.app.R
import es.topophone.core.gnss.nmea.TiltNotification
import es.topophone.core.model.format.AngleFormat
import es.topophone.core.model.format.LengthFormat
import es.topophone.core.survey.ReceiverModel
import es.topophone.core.survey.TiltEpoch
import es.topophone.core.survey.VerticalPole
import es.topophone.receiver.session.ConnectionState

const val STATUS_TILT_STATE_TAG = "status_tilt_state"
const val STATUS_TILT_NOTIFICATION_TAG = "status_tilt_notification"
const val STATUS_TILT_ANGLE_TAG = "status_tilt_angle"
const val STATUS_TILT_DIRECTION_TAG = "status_tilt_direction"
const val STATUS_TILT_HEADING_TAG = "status_tilt_heading"
const val STATUS_TILT_HORIZONTAL_TAG = "status_tilt_horizontal"
const val STATUS_TILT_VERTICAL_TAG = "status_tilt_vertical"

/**
 * La inclinación en la pantalla Estado (S016/RF-11): estado y aviso del ETC y, en estado 30, la inclinación, su dirección
 * y el rumbo con el formato angular de los proyectos nuevos, y la precisión de la inclinación en metros.
 */
@Composable
internal fun TiltStatusCard(state: ReceiverUiState) {
    val snapshot = state.snapshot
    val tilt = snapshot.epoch?.tilt ?: return
    if (snapshot.connection !is ConnectionState.Receiving) return
    ReceiverCard(stringResource(R.string.status_tilt_title)) {
        val locale = uiLocale()
        LabeledValue(stringResource(R.string.status_tilt_state), stringResource(tiltNameRes(tilt.state)), STATUS_TILT_STATE_TAG)
        if (tilt.notification != TiltNotification.OFF) {
            LabeledValue(
                stringResource(R.string.status_tilt_notification),
                stringResource(tilt.notification.nameRes()),
                STATUS_TILT_NOTIFICATION_TAG,
            )
        }
        if (!TiltEpoch.isCompensating(tilt)) return@ReceiverCard
        LabeledValue(
            stringResource(R.string.status_tilt_angle),
            AngleFormat.direction(checkNotNull(tilt.tiltRad), state.angularUnit, locale),
            STATUS_TILT_ANGLE_TAG,
        )
        LabeledValue(
            stringResource(R.string.status_tilt_direction),
            AngleFormat.direction(checkNotNull(tilt.tiltDirectionRad), state.angularUnit, locale),
            STATUS_TILT_DIRECTION_TAG,
        )
        tilt.headingRad?.let { heading ->
            LabeledValue(
                stringResource(R.string.status_tilt_heading),
                AngleFormat.direction(heading, state.angularUnit, locale),
                STATUS_TILT_HEADING_TAG,
            )
        }
        val antennaHeight = VerticalPole.antennaHeightM(state.poleHeightM, ReceiverModel.RS3)
        val accuracy = TiltEpoch.accuracy(tilt, antennaHeight) ?: return@ReceiverCard
        LabeledValue(
            stringResource(R.string.status_tilt_horizontal),
            LengthFormat.metres(accuracy.horizontalM, locale),
            STATUS_TILT_HORIZONTAL_TAG,
        )
        LabeledValue(
            stringResource(R.string.status_tilt_vertical),
            LengthFormat.metres(accuracy.verticalM, locale),
            STATUS_TILT_VERTICAL_TAG,
        )
    }
}

/** El aviso del ETC (S016/RF-11). */
internal fun TiltNotification.nameRes(): Int = when (this) {
    TiltNotification.OFF -> R.string.tilt_off
    TiltNotification.NONE -> R.string.tilt_notification_none
    TiltNotification.FAST_MOTION -> R.string.tilt_notification_fast_motion
    TiltNotification.BAD_GNSS -> R.string.tilt_notification_bad_gnss
    TiltNotification.FILTER_FAULT -> R.string.tilt_notification_filter_fault
}
