package es.topophone.app.ui.receiver

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import es.topophone.app.R
import es.topophone.core.model.format.AngleFormat
import es.topophone.core.model.format.LengthFormat
import es.topophone.core.survey.CoordinateReference
import es.topophone.core.survey.GroundPoint
import es.topophone.core.survey.Unavailable
import es.topophone.receiver.session.ConnectionState

const val POLE_HEIGHT_TAG = "pole_height"
const val COORD_LATITUDE_TAG = "coord_latitude"
const val COORD_LONGITUDE_TAG = "coord_longitude"
const val COORD_ELLIPSOIDAL_TAG = "coord_ellipsoidal"
const val COORD_UTM_ZONE_TAG = "coord_utm_zone"
const val COORD_EASTING_TAG = "coord_easting"
const val COORD_NORTHING_TAG = "coord_northing"
const val COORD_ORTHOMETRIC_TAG = "coord_orthometric"

/** Tarjeta Coordenadas: altura del jalón y punto del suelo (S002/RF-29 a RF-41; en Canarias, S008/RF-20, RF-21). */
@Composable
internal fun CoordinatesCard(state: ReceiverUiState, onPoleHeightChange: (String) -> Unit) {
    ReceiverCard(stringResource(R.string.receiver_coordinates_title)) {
        PoleHeightField(state.poleHeightText, state.poleHeightValid, onPoleHeightChange)
        val point = state.groundPoint
        // Como en la tarjeta Estado: con la conexión perdida o sin datos no se enseñan coordenadas antiguas.
        if (point == null || state.snapshot.connection !is ConnectionState.Receiving) {
            Text(stringResource(R.string.coordinates_waiting))
            return@ReceiverCard
        }
        GroundPointValues(point)
    }
}

@Composable
private fun PoleHeightField(text: String, valid: Boolean, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = text,
        onValueChange = onChange,
        label = { Text(stringResource(R.string.pole_height_label)) },
        supportingText = {
            Text(stringResource(if (valid) R.string.pole_height_help else R.string.pole_height_invalid)) // S002/RF-29, S002/RF-31
        },
        isError = !valid,
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
        modifier = Modifier.fillMaxWidth().testTag(POLE_HEIGHT_TAG),
    )
}

@Composable
private fun GroundPointValues(point: GroundPoint) {
    val locale = uiLocale()
    if (!point.isCentimetric) {
        Text(
            stringResource(R.string.coordinates_not_centimetric),
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.titleSmall,
        ) // S002/RF-40
    }
    val reference = when (point.reference) {
        CoordinateReference.CORRECTIONS_ASSUMED_ETRS89 -> R.string.coordinates_reference
        CoordinateReference.CORRECTIONS_ASSUMED_REGCAN95 -> R.string.coordinates_reference_regcan95 // S008/RF-20
    }
    Text(stringResource(reference), style = MaterialTheme.typography.bodySmall) // S002/RF-41
    CoordinateValue(
        stringResource(R.string.coord_latitude),
        listOf(AngleFormat.latitudeDms(point.latitudeDeg, locale), AngleFormat.decimalDegrees(point.latitudeDeg, locale)),
        COORD_LATITUDE_TAG,
    ) // S002/RF-34
    CoordinateValue(
        stringResource(R.string.coord_longitude),
        listOf(AngleFormat.longitudeDms(point.longitudeDeg, locale), AngleFormat.decimalDegrees(point.longitudeDeg, locale)),
        COORD_LONGITUDE_TAG,
    )
    val noHeight = stringResource(R.string.unavailable_no_height)
    CoordinateValue(
        stringResource(R.string.coord_ellipsoidal_height),
        listOf(point.ellipsoidalHeightM?.let { LengthFormat.metres(it, locale) } ?: noHeight),
        COORD_ELLIPSOIDAL_TAG,
    ) // S002/RF-33, S002/RF-35
    val utm = point.utm
    if (utm == null) {
        CoordinateValue(
            stringResource(R.string.coord_utm_zone),
            listOf(stringResource(R.string.unavailable_outside_utm)),
            COORD_UTM_ZONE_TAG,
        )
    } else {
        CoordinateValue(stringResource(R.string.coord_utm_zone), listOf(utm.zone.toString()), COORD_UTM_ZONE_TAG) // S002/RF-36
        CoordinateValue(stringResource(R.string.coord_easting), listOf(LengthFormat.metres(utm.easting, locale)), COORD_EASTING_TAG)
        CoordinateValue(stringResource(R.string.coord_northing), listOf(LengthFormat.metres(utm.northing, locale)), COORD_NORTHING_TAG)
    }
    val orthometric = point.orthometricHeightM?.let { LengthFormat.metres(it, locale) }
        ?: stringResource(
            if (Unavailable.NO_HEIGHT in point.unavailable) R.string.unavailable_no_height else R.string.unavailable_outside_geoids,
        ) // S002/RF-38, S002/RF-39, S008/RF-21
    CoordinateValue(stringResource(R.string.coord_orthometric_height), listOf(orthometric), COORD_ORTHOMETRIC_TAG)
}

/** Etiqueta encima y valores en tipografía monoespaciada (RNF-T8); TalkBack la lee entera. */
@Composable
private fun CoordinateValue(label: String, values: List<String>, tag: String) {
    Column(modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}.testTag(tag)) {
        Text(label, style = MaterialTheme.typography.labelLarge)
        values.forEach { Text(it, style = MaterialTheme.typography.titleMedium, fontFamily = FontFamily.Monospace) }
    }
}
