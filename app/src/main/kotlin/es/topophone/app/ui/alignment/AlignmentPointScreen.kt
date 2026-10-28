package es.topophone.app.ui.alignment

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import es.topophone.app.R
import es.topophone.app.ui.SectionScaffold
import es.topophone.app.ui.projects.unitSymbol
import es.topophone.app.ui.receiver.uiLocale
import es.topophone.core.designsystem.component.MinTouchTarget
import es.topophone.core.model.alignment.ChainageFormat
import es.topophone.core.model.alignment.OffsetFormat
import es.topophone.core.model.alignment.OffsetSide
import es.topophone.core.model.format.AngleFormat
import es.topophone.core.model.format.LengthFormat
import es.topophone.core.model.format.NumberInput
import es.topophone.core.model.preferences.ThemeState

const val POINT_AXIS_CHAINAGE_TAG = "point_axis_chainage"
const val POINT_AXIS_OFFSET_TAG = "point_axis_offset"
const val POINT_AXIS_SIDE_TAG = "point_axis_side"
const val POINT_AXIS_OUTSIDE_TAG = "point_axis_outside"
const val POINT_AXIS_RESULT_TAG = "point_axis_result"
const val POINT_AXIS_EASTING_TAG = "point_axis_easting"
const val POINT_AXIS_NORTHING_TAG = "point_axis_northing"
const val POINT_AXIS_AZIMUTH_TAG = "point_axis_azimuth"
const val POINT_AXIS_RADIUS_TAG = "point_axis_radius"
const val POINT_AXIS_ELEVATION_TAG = "point_axis_elevation"
const val POINT_AXIS_SLOPE_TAG = "point_axis_slope"
const val POINT_AXIS_NO_PROFILE_TAG = "point_axis_no_profile"
const val POINT_AXIS_NAME_TAG = "point_axis_name"
const val POINT_AXIS_SAVE_TAG = "point_axis_save"
const val POINT_AXIS_SAVED_TAG = "point_axis_saved"
const val POINT_AXIS_ANOTHER_TAG = "point_axis_another"
const val POINT_AXIS_DUPLICATE_TAG = "point_axis_duplicate"
const val POINT_AXIS_AREA_TAG = "point_axis_area"

/** Acciones de la pantalla de calcular un punto sobre un eje (S049). */
class AlignmentPointActions(
    val onChainage: (String) -> Unit = {},
    val onOffset: (String) -> Unit = {},
    val onName: (String) -> Unit = {},
    val onSave: () -> Unit = {},
    val onAnother: () -> Unit = {},
)

/**
 * Calcular un punto sobre un eje (S049/RF-20 a RF-22): un PK y un desplazamiento dan el Este y el Norte, el azimut y el radio del eje y, si
 * tiene rasante, la cota y la pendiente en ese PK. El desplazamiento se dice siempre con la palabra «izquierda» o «derecha» además del
 * signo (RNF-T8); un PK fuera del eje no calcula nada y dice el PK inicial y el final (RF-21); «Guardar punto» lo guarda (RF-22).
 */
@Composable
fun AlignmentPointScreen(
    themeState: ThemeState,
    onToggleSun: () -> Unit,
    state: AlignmentPointUiState,
    actions: AlignmentPointActions,
    onBack: () -> Unit,
) {
    SectionScaffold(
        title = stringResource(R.string.alignment_point_title),
        themeState = themeState,
        onToggleSun = onToggleSun,
        onNavigateBack = onBack,
    ) { padding ->
        Column(
            modifier = Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (state.loaded) {
                Form(state, actions)
            }
        }
    }
}

@Composable
private fun Form(state: AlignmentPointUiState, actions: AlignmentPointActions) {
    val locale = uiLocale()
    val symbol = unitSymbol(state.unit)
    Text(state.alignmentName, style = MaterialTheme.typography.titleMedium)
    Text(
        stringResource(
            R.string.alignment_point_axis,
            LengthFormat.metres(state.endChainageM - state.startChainageM, locale, decimals = 1),
            ChainageFormat.format(state.startChainageM, state.unit, locale),
            ChainageFormat.format(state.endChainageM, state.unit, locale),
        ),
    )
    OutlinedTextField(
        value = state.chainageText,
        onValueChange = actions.onChainage,
        label = { Text(stringResource(R.string.alignment_point_chainage)) },
        isError = state.chainageInvalid,
        supportingText = {
            Text(
                if (state.chainageInvalid) {
                    stringResource(R.string.alignment_point_invalid_chainage)
                } else {
                    stringResource(R.string.alignment_point_chainage_help, symbol)
                },
            )
        },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
        modifier = Modifier.fillMaxWidth().testTag(POINT_AXIS_CHAINAGE_TAG),
    )
    OutlinedTextField(
        value = state.offsetText,
        onValueChange = actions.onOffset,
        label = { Text(stringResource(R.string.alignment_point_offset, symbol)) },
        isError = state.offsetInvalid,
        supportingText = {
            Text(
                if (state.offsetInvalid) {
                    stringResource(R.string.alignment_point_invalid_offset)
                } else {
                    stringResource(R.string.alignment_point_offset_help)
                },
            )
        },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
        modifier = Modifier.fillMaxWidth().testTag(POINT_AXIS_OFFSET_TAG),
    )
    val offset = if (state.offsetText.isBlank()) 0.0 else NumberInput.parse(state.offsetText)?.let(state.unit::toMetres)
    if (offset !=
        null
    ) {
        Text(sideText(offset, state), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag(POINT_AXIS_SIDE_TAG))
    }
    if (state.outside) {
        Text(
            stringResource(
                R.string.alignment_point_outside,
                ChainageFormat.format(state.startChainageM, state.unit, locale),
                ChainageFormat.format(state.endChainageM, state.unit, locale),
            ),
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.testTag(POINT_AXIS_OUTSIDE_TAG).semantics { liveRegion = LiveRegionMode.Polite },
        )
    }
    state.reading?.let { Result(it, state, symbol) }
    if (state.reading != null) Save(state, actions)
}

@Composable
private fun sideText(offsetM: Double, state: AlignmentPointUiState): String {
    val locale = uiLocale()
    val magnitude = OffsetFormat.magnitude(offsetM, state.unit, locale) + " " + unitSymbol(state.unit)
    return when (OffsetFormat.side(offsetM)) {
        OffsetSide.LEFT -> stringResource(R.string.alignment_point_side_left, magnitude)
        OffsetSide.RIGHT -> stringResource(R.string.alignment_point_side_right, magnitude)
        OffsetSide.ON_AXIS -> stringResource(R.string.alignment_point_side_on_axis)
    }
}

/** El Este, el Norte, el azimut y el radio del eje, y la cota y la pendiente si hay rasante, en la unidad y el formato del proyecto. */
@Composable
private fun Result(reading: AxisReading, state: AlignmentPointUiState, symbol: String) {
    val locale = uiLocale()
    val point = reading.point
    val phrase = stringResource(
        R.string.alignment_point_phrase,
        ChainageFormat.format(point.chainage, state.unit, locale),
        sideText(point.offset, state),
    )
    Card(modifier = Modifier.fillMaxWidth().testTag(POINT_AXIS_RESULT_TAG)) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            // La frase entera, que TalkBack lee al cambiar el resultado (RNF-T8): PK y lado con la palabra.
            Text(
                phrase,
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.semantics {
                    contentDescription = phrase
                    liveRegion = LiveRegionMode.Polite
                },
            )
            val mono = MaterialTheme.typography.titleMedium.copy(fontFamily = FontFamily.Monospace)
            Text(
                stringResource(R.string.alignment_point_easting, LengthFormat.inUnit(point.easting, state.unit, locale), symbol),
                style = mono,
                modifier = Modifier.testTag(POINT_AXIS_EASTING_TAG),
            )
            Text(
                stringResource(R.string.alignment_point_northing, LengthFormat.inUnit(point.northing, state.unit, locale), symbol),
                style = mono,
                modifier = Modifier.testTag(POINT_AXIS_NORTHING_TAG),
            )
            Text(
                stringResource(R.string.alignment_point_azimuth, AngleFormat.direction(point.azimuth, state.angularUnit, locale)),
                modifier = Modifier.testTag(POINT_AXIS_AZIMUTH_TAG),
            )
            Text(
                if (point.radius.isInfinite()) {
                    stringResource(R.string.alignment_point_radius_straight)
                } else {
                    stringResource(R.string.alignment_point_radius, LengthFormat.inUnit(point.radius, state.unit, locale, 3), symbol)
                },
                modifier = Modifier.testTag(POINT_AXIS_RADIUS_TAG),
            )
            val elevation = reading.elevationM
            val slope = reading.slopePercent
            if (elevation != null && slope != null) {
                Text(
                    stringResource(R.string.alignment_point_elevation, LengthFormat.inUnit(elevation, state.unit, locale), symbol),
                    style = mono,
                    modifier = Modifier.testTag(POINT_AXIS_ELEVATION_TAG),
                )
                Text(
                    stringResource(
                        R.string.alignment_point_slope,
                        java.text.DecimalFormat("0.000", java.text.DecimalFormatSymbols(locale)).format(slope),
                    ),
                    modifier = Modifier.testTag(POINT_AXIS_SLOPE_TAG),
                )
            } else {
                Text(stringResource(R.string.alignment_point_no_profile), modifier = Modifier.testTag(POINT_AXIS_NO_PROFILE_TAG))
            }
        }
    }
}

@Composable
private fun Save(state: AlignmentPointUiState, actions: AlignmentPointActions) {
    val saved = state.savedName
    if (saved != null) {
        Text(
            stringResource(R.string.alignment_point_saved, saved),
            modifier = Modifier.testTag(POINT_AXIS_SAVED_TAG).semantics { liveRegion = LiveRegionMode.Polite },
        )
        OutlinedButton(
            onClick = actions.onAnother,
            modifier = Modifier.fillMaxWidth().heightIn(min = MinTouchTarget).testTag(POINT_AXIS_ANOTHER_TAG),
        ) { Text(stringResource(R.string.alignment_point_another)) }
        return
    }
    OutlinedTextField(
        value = state.name,
        onValueChange = actions.onName,
        label = { Text(stringResource(R.string.alignment_point_name)) },
        isError = state.duplicateName,
        supportingText = {
            if (state.duplicateName) {
                Text(
                    stringResource(R.string.alignment_point_duplicate),
                    modifier = Modifier.testTag(POINT_AXIS_DUPLICATE_TAG),
                )
            }
        },
        singleLine = true,
        modifier = Modifier.fillMaxWidth().testTag(POINT_AXIS_NAME_TAG),
    )
    if (state.outsideCrsArea) {
        Text(
            stringResource(R.string.alignment_point_outside_area),
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.testTag(POINT_AXIS_AREA_TAG),
        )
    }
    Button(
        onClick = actions.onSave,
        enabled = state.canSave,
        modifier = Modifier.fillMaxWidth().heightIn(min = MinTouchTarget).testTag(POINT_AXIS_SAVE_TAG),
    ) { Text(stringResource(R.string.alignment_point_save)) }
}
