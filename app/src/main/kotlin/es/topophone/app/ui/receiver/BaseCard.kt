package es.topophone.app.ui.receiver

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import es.topophone.app.R
import es.topophone.core.model.format.AngleFormat
import es.topophone.core.model.format.LengthFormat
import es.topophone.core.model.point.BaseSource
import es.topophone.core.survey.Baseline
import es.topophone.receiver.session.ConnectionState

const val BASE_SOURCE_TAG = "base_source"
const val BASE_LATITUDE_TAG = "base_latitude"
const val BASE_LONGITUDE_TAG = "base_longitude"
const val BASE_HEIGHT_TAG = "base_height"
const val BASE_ANTENNA_HEIGHT_TAG = "base_antenna_height"
const val BASELINE_TAG = "baseline"
const val BASE_UNAVAILABLE_TAG = "base_unavailable"

/** La base tal como la da su fuente y la línea base (S015/RF-13, RF-14, RF-16). */
@Composable
internal fun BaseCard(state: ReceiverUiState) {
    val snapshot = state.snapshot
    if (snapshot.connection !is ConnectionState.Receiving) return
    ReceiverCard(stringResource(R.string.base_title)) {
        val base = snapshot.base.position
        if (base == null) {
            Text(stringResource(R.string.base_unavailable), modifier = Modifier.testTag(BASE_UNAVAILABLE_TAG)) // RF-16
            return@ReceiverCard
        }
        val locale = uiLocale()
        val source = when (base.source) {
            BaseSource.RECEIVER -> stringResource(R.string.base_source_receiver)
            BaseSource.CORRECTIONS -> base.stationId?.let { stringResource(R.string.base_source_corrections, it) }
                ?: stringResource(R.string.base_source_corrections_no_station)
        }
        LabeledValue(stringResource(R.string.base_source), source, BASE_SOURCE_TAG)
        LabeledValue(
            stringResource(R.string.coord_latitude),
            AngleFormat.latitude(base.latitudeDeg, state.angularUnit, locale),
            BASE_LATITUDE_TAG,
        )
        LabeledValue(
            stringResource(R.string.coord_longitude),
            AngleFormat.longitude(base.longitudeDeg, state.angularUnit, locale),
            BASE_LONGITUDE_TAG,
        )
        LabeledValue(
            stringResource(R.string.coord_ellipsoidal_height),
            LengthFormat.metres(base.ellipsoidalHeightM, locale),
            BASE_HEIGHT_TAG,
        )
        base.antennaHeightM?.let {
            LabeledValue(stringResource(R.string.base_antenna_height), LengthFormat.metres(it, locale), BASE_ANTENNA_HEIGHT_TAG)
        }
        val baseline = snapshot.gga?.let { Baseline.fromGga(it, base.ecef) }
        LabeledValue(
            stringResource(R.string.baseline),
            baseline?.let { stringResource(R.string.baseline_km, String.format(locale, "%.3f", it / METRES_PER_KM)) }
                ?: stringResource(R.string.receiver_value_unavailable),
            BASELINE_TAG,
        ) // RF-14
    }
}

private const val METRES_PER_KM = 1_000.0
