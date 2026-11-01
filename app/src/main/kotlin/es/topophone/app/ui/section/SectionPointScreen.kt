package es.topophone.app.ui.section

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.unit.dp
import es.topophone.app.R
import es.topophone.app.ui.SectionScaffold
import es.topophone.app.ui.projects.unitSymbol
import es.topophone.app.ui.receiver.uiLocale
import es.topophone.core.designsystem.component.MinTouchTarget
import es.topophone.core.model.alignment.ChainageFormat
import es.topophone.core.model.alignment.OffsetFormat
import es.topophone.core.model.alignment.OffsetSide
import es.topophone.core.model.format.LengthFormat
import es.topophone.core.model.preferences.ThemeState

const val SECTION_POINT_CHAINAGE_TAG = "section_point_chainage"
const val SECTION_POINT_OFFSET_TAG = "section_point_offset"
const val SECTION_POINT_OUTSIDE_TAG = "section_point_outside"
const val SECTION_POINT_STATUS_TAG = "section_point_status"
const val SECTION_POINT_RESULT_TAG = "section_point_result"
const val SECTION_POINT_EASTING_TAG = "section_point_easting"
const val SECTION_POINT_NORTHING_TAG = "section_point_northing"
const val SECTION_POINT_ELEVATION_TAG = "section_point_elevation"
const val SECTION_POINT_NO_ELEVATION_TAG = "section_point_no_elevation"
const val SECTION_POINT_SLOPE_TAG = "section_point_slope"
const val SECTION_POINT_NAME_TAG = "section_point_name"
const val SECTION_POINT_SAVE_TAG = "section_point_save"
const val SECTION_POINT_SAVED_TAG = "section_point_saved"
const val SECTION_POINT_ANOTHER_TAG = "section_point_another"
const val SECTION_POINT_DUPLICATE_TAG = "section_point_duplicate"
const val SECTION_POINT_AREA_TAG = "section_point_area"
const val SECTION_POINT_VIEW_TAG = "section_point_view"

fun sectionPointModeTag(mode: SectionPointMode) = "section_point_mode_${mode.name}"

fun sectionPointCodeTag(code: String) = "section_point_code_$code"

/** Acciones de la pantalla del punto de una sección (S050). */
class SectionPointActions(
    val onChainage: (String) -> Unit = {},
    val onMode: (SectionPointMode) -> Unit = {},
    val onCode: (String) -> Unit = {},
    val onOffset: (String) -> Unit = {},
    val onName: (String) -> Unit = {},
    val onSave: () -> Unit = {},
    val onAnother: () -> Unit = {},
    val onShowSection: (Boolean) -> Unit = {},
)

/**
 * Calcular un punto de la sección de un eje y verla (S050/RF-9 a RF-11, RF-13, RF-15): un PK y un código o un desplazamiento dan el
 * Este y el Norte, la cota y la pendiente transversal con «baja» o «sube»; «Fuera de la sección» y los PK sin sección se dicen (RF-10,
 * RF-12); «Ver sección» dibuja el alzado (RF-13) y «Guardar punto» lo guarda con su código de sección (RF-15).
 */
@Composable
fun SectionPointScreen(
    themeState: ThemeState,
    onToggleSun: () -> Unit,
    state: SectionPointUiState,
    actions: SectionPointActions,
    onBack: () -> Unit,
) {
    SectionScaffold(
        title = stringResource(R.string.section_point_title),
        themeState = themeState,
        onToggleSun = onToggleSun,
        onNavigateBack = onBack,
    ) { padding ->
        Column(
            modifier = Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (state.loaded) Form(state, actions)
        }
    }
}

@Composable
private fun Form(state: SectionPointUiState, actions: SectionPointActions) {
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
        modifier = Modifier.fillMaxWidth().testTag(SECTION_POINT_CHAINAGE_TAG),
    )
    ChoiceRow {
        SectionPointMode.entries.forEach { mode ->
            Choice(
                stringResource(
                    if (mode ==
                        SectionPointMode.BY_CODE
                    ) {
                        R.string.section_point_mode_code
                    } else {
                        R.string.section_point_mode_offset
                    },
                ),
                sectionPointModeTag(mode),
                state.mode == mode,
            ) { actions.onMode(mode) }
        }
    }
    if (state.mode == SectionPointMode.BY_CODE) {
        if (state.codes.isNotEmpty()) {
            Text(stringResource(R.string.section_point_code), style = MaterialTheme.typography.labelLarge)
            ChoiceRow {
                state.codes.forEach { code -> Choice(code, sectionPointCodeTag(code), state.code == code) { actions.onCode(code) } }
            }
        }
    } else {
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
            modifier = Modifier.fillMaxWidth().testTag(SECTION_POINT_OFFSET_TAG),
        )
    }
    if (state.outside) {
        Text(
            stringResource(
                R.string.alignment_point_outside,
                ChainageFormat.format(state.startChainageM, state.unit, locale),
                ChainageFormat.format(state.endChainageM, state.unit, locale),
            ),
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.testTag(SECTION_POINT_OUTSIDE_TAG).semantics { liveRegion = LiveRegionMode.Polite },
        )
    }
    state.status?.let { status ->
        Text(
            statusText(status),
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.testTag(SECTION_POINT_STATUS_TAG).semantics { liveRegion = LiveRegionMode.Polite },
        )
    }
    state.reading?.let { Result(it, state, symbol) }
    state.section?.let { section ->
        OutlinedButton(
            onClick = { actions.onShowSection(!state.showSection) },
            modifier = Modifier.fillMaxWidth().heightIn(min = MinTouchTarget).testTag(SECTION_POINT_VIEW_TAG),
        ) { Text(stringResource(if (state.showSection) R.string.section_point_hide else R.string.section_point_view)) }
        if (state.showSection) {
            SectionDrawing(section, state.ground, state.unit, state.profileElevationM)
        }
    }
    if (state.reading != null) Save(state, actions)
}

@Composable
private fun statusText(status: SectionPointStatus): String = when (status) {
    SectionPointStatus.NoSection -> stringResource(R.string.section_point_status_no_section)
    is SectionPointStatus.Missing ->
        if (status.heightKindMismatch) {
            stringResource(R.string.section_point_status_mismatch)
        } else {
            stringResource(R.string.section_point_status_missing, status.codes.joinToString(", "))
        }
    SectionPointStatus.NoCode -> stringResource(R.string.section_point_status_no_code)
    SectionPointStatus.OutsideSection -> stringResource(R.string.section_point_status_outside)
}

@Composable
private fun sideText(offsetM: Double, state: SectionPointUiState): String {
    val locale = uiLocale()
    val magnitude = OffsetFormat.magnitude(offsetM, state.unit, locale) + " " + unitSymbol(state.unit)
    return when (OffsetFormat.side(offsetM)) {
        OffsetSide.LEFT -> stringResource(R.string.alignment_point_side_left, magnitude)
        OffsetSide.RIGHT -> stringResource(R.string.alignment_point_side_right, magnitude)
        OffsetSide.ON_AXIS -> stringResource(R.string.alignment_point_side_on_axis)
    }
}

@Composable
private fun Result(reading: SectionReading, state: SectionPointUiState, symbol: String) {
    val locale = uiLocale()
    val point = reading.point
    val phrase = stringResource(
        R.string.alignment_point_phrase,
        ChainageFormat.format(point.chainage, state.unit, locale),
        sideText(point.offset, state),
    )
    Card(modifier = Modifier.fillMaxWidth().testTag(SECTION_POINT_RESULT_TAG)) {
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
            reading.code?.let { Text(stringResource(R.string.section_point_code_result, it)) }
            val mono = MaterialTheme.typography.titleMedium.copy(fontFamily = FontFamily.Monospace)
            Text(
                stringResource(R.string.alignment_point_easting, LengthFormat.inUnit(point.easting, state.unit, locale), symbol),
                style = mono,
                modifier = Modifier.testTag(SECTION_POINT_EASTING_TAG),
            )
            Text(
                stringResource(R.string.alignment_point_northing, LengthFormat.inUnit(point.northing, state.unit, locale), symbol),
                style = mono,
                modifier = Modifier.testTag(SECTION_POINT_NORTHING_TAG),
            )
            val elevation = reading.elevationM
            if (elevation != null) {
                Text(
                    stringResource(R.string.alignment_point_elevation, LengthFormat.inUnit(elevation, state.unit, locale), symbol),
                    style = mono,
                    modifier = Modifier.testTag(SECTION_POINT_ELEVATION_TAG),
                )
            } else {
                Text(stringResource(R.string.section_point_no_elevation), modifier = Modifier.testTag(SECTION_POINT_NO_ELEVATION_TAG))
            }
            reading.slope?.let {
                Text(stringResource(R.string.section_point_slope, slopeLabel(it)), modifier = Modifier.testTag(SECTION_POINT_SLOPE_TAG))
            }
        }
    }
}

@Composable
private fun Save(state: SectionPointUiState, actions: SectionPointActions) {
    val saved = state.savedName
    if (saved != null) {
        Text(
            stringResource(R.string.alignment_point_saved, saved),
            modifier = Modifier.testTag(SECTION_POINT_SAVED_TAG).semantics { liveRegion = LiveRegionMode.Polite },
        )
        OutlinedButton(
            onClick = actions.onAnother,
            modifier = Modifier.fillMaxWidth().heightIn(min = MinTouchTarget).testTag(SECTION_POINT_ANOTHER_TAG),
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
                Text(stringResource(R.string.alignment_point_duplicate), modifier = Modifier.testTag(SECTION_POINT_DUPLICATE_TAG))
            }
        },
        singleLine = true,
        modifier = Modifier.fillMaxWidth().testTag(SECTION_POINT_NAME_TAG),
    )
    if (state.outsideCrsArea) {
        Text(
            stringResource(R.string.alignment_point_outside_area),
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.testTag(SECTION_POINT_AREA_TAG),
        )
    }
    Button(
        onClick = actions.onSave,
        enabled = state.canSave,
        modifier = Modifier.fillMaxWidth().heightIn(min = MinTouchTarget).testTag(SECTION_POINT_SAVE_TAG),
    ) { Text(stringResource(R.string.alignment_point_save)) }
}
