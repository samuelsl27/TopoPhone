package es.topophone.app.ui.receiver

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import es.topophone.app.R
import es.topophone.core.gnss.nmea.Constellation
import es.topophone.core.gnss.signal.SatelliteNumber
import es.topophone.core.model.format.AngleFormat
import es.topophone.core.model.format.LengthFormat
import es.topophone.receiver.session.ConnectionState

const val STATUS_LATITUDE_TAG = "status_latitude"
const val STATUS_LONGITUDE_TAG = "status_longitude"
const val STATUS_ELLIPSOIDAL_HEIGHT_TAG = "status_ellipsoidal_height"
const val STATUS_PDOP_TAG = "status_pdop"
const val STATUS_GSA_HDOP_TAG = "status_gsa_hdop"
const val STATUS_VDOP_TAG = "status_vdop"

fun constellationTag(constellation: Constellation) = "status_constellation_${constellation.name.lowercase()}"

/** Posición de la antena que da el receptor, con el formato angular de los proyectos nuevos (S015/RF-1). */
@Composable
internal fun PositionCard(state: ReceiverUiState) {
    val snapshot = state.snapshot
    val gga = snapshot.gga ?: return
    // Como la tarjeta Estado: con la conexión perdida o "Sin datos" no se enseña una posición antigua.
    if (snapshot.connection !is ConnectionState.Receiving || !gga.hasPosition) return
    ReceiverCard(stringResource(R.string.status_position_title)) {
        val locale = uiLocale()
        val altitude = gga.altitudeM
        val separation = gga.geoidSeparationM
        LabeledValue(
            stringResource(R.string.coord_latitude),
            AngleFormat.latitude(gga.latitudeDeg, state.angularUnit, locale),
            STATUS_LATITUDE_TAG,
        )
        LabeledValue(
            stringResource(R.string.coord_longitude),
            AngleFormat.longitude(gga.longitudeDeg, state.angularUnit, locale),
            STATUS_LONGITUDE_TAG,
        )
        LabeledValue(
            stringResource(R.string.coord_ellipsoidal_height),
            // ADR-6: altitud de la GGA más su separación.
            if (altitude != null && separation != null) {
                LengthFormat.metres(altitude + separation, locale)
            } else {
                stringResource(R.string.receiver_value_unavailable)
            },
            STATUS_ELLIPSOIDAL_HEIGHT_TAG,
        )
    }
}

/** DOP de la GSA y satélites usados y en vista por constelación (S015/RF-2, RF-3). */
@Composable
internal fun SatellitesCard(state: ReceiverUiState) {
    val sky = state.snapshot.sky ?: return
    if (state.snapshot.connection !is ConnectionState.Receiving) return
    ReceiverCard(stringResource(R.string.status_satellites_title)) {
        val locale = uiLocale()
        val unavailable = stringResource(R.string.receiver_value_unavailable)
        fun dop(value: Double?) = value?.let { String.format(locale, "%.1f", it) } ?: unavailable
        LabeledValue(stringResource(R.string.status_pdop), dop(sky.dop?.pdop), STATUS_PDOP_TAG)
        LabeledValue(stringResource(R.string.receiver_status_hdop), dop(sky.dop?.hdop), STATUS_GSA_HDOP_TAG)
        LabeledValue(stringResource(R.string.status_vdop), dop(sky.dop?.vdop), STATUS_VDOP_TAG)
        sky.counts.entries.sortedBy { it.key.ordinal }.forEach { (constellation, count) ->
            LabeledValue(
                stringResource(
                    R.string.status_constellation,
                    stringResource(constellation.nameRes()),
                    SatelliteNumber.letter(constellation),
                ),
                stringResource(R.string.receiver_satellites_in_view, count.used, count.inView),
                constellationTag(constellation),
            )
        }
    }
}

/** Nombre de la constelación en la interfaz. */
internal fun Constellation.nameRes(): Int = when (this) {
    Constellation.GPS -> R.string.gnss_gps
    Constellation.GLONASS -> R.string.gnss_glonass
    Constellation.GALILEO -> R.string.gnss_galileo
    Constellation.BEIDOU -> R.string.gnss_beidou
    Constellation.QZSS -> R.string.gnss_qzss
    Constellation.NAVIC -> R.string.gnss_navic
    Constellation.MULTIPLE, Constellation.UNKNOWN -> R.string.gnss_other
}
