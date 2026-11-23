package es.topophone.app.ui.localization

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import es.topophone.app.R
import es.topophone.app.ui.SectionScaffold
import es.topophone.app.ui.projects.unitSymbol
import es.topophone.app.ui.receiver.uiLocale
import es.topophone.core.designsystem.component.MinTouchTarget
import es.topophone.core.model.format.LengthFormat
import es.topophone.core.model.localization.LocalizationLimits
import es.topophone.core.model.localization.LocalizationOrigin
import es.topophone.core.model.localization.LocalizationPair
import es.topophone.core.model.preferences.ThemeState
import es.topophone.core.model.project.Project

const val LOCALIZATION_STATUS_TAG = "localization_status"
const val LOCALIZATION_PAIRS_TITLE_TAG = "localization_pairs_title"
const val LOCALIZATION_PAIRS_EMPTY_TAG = "localization_pairs_empty"

fun localizationPairTag(id: String) = "localization_pair_$id"

const val LOCALIZATION_ADD_PAIR_TAG = "localization_add_pair"
const val LOCALIZATION_FORM_TAG = "localization_form"
const val LOCALIZATION_FORM_ERROR_TAG = "localization_form_error"
const val LOCALIZATION_FORM_EASTING_TAG = "localization_form_easting"
const val LOCALIZATION_FORM_NORTHING_TAG = "localization_form_northing"
const val LOCALIZATION_FORM_HEIGHT_TAG = "localization_form_height"
const val LOCALIZATION_FORM_SOURCE_TYPED_TAG = "localization_form_source_typed"
const val LOCALIZATION_FORM_SOURCE_POINT_TAG = "localization_form_source_point"
const val LOCALIZATION_FORM_CONFIRM_TAG = "localization_form_confirm"
const val LOCALIZATION_FORM_CANCEL_TAG = "localization_form_cancel"
const val LOCALIZATION_NO_GNSS_TAG = "localization_no_gnss"

fun localizationGnssTag(id: String) = "localization_gnss_$id"

fun localizationKnownTag(id: String) = "localization_known_$id"

fun localizationUnconfirmedTag(id: String) = "localization_unconfirmed_$id"

fun localizationConfirmPairTag(id: String) = "localization_confirm_pair_$id"

/** Acciones de la pantalla de localización (S039); crecen con cada tarea. */
class LocalizationActions(
    val onOpenAddPair: () -> Unit = {},
    val onCloseAddPair: () -> Unit = {},
    val onSelectGnss: (String) -> Unit = {},
    val onSource: (KnownSource) -> Unit = {},
    val onSelectKnown: (String) -> Unit = {},
    val onEasting: (String) -> Unit = {},
    val onNorthing: (String) -> Unit = {},
    val onHeight: (String) -> Unit = {},
    val onConfirmAddPair: () -> Unit = {},
    val onConfirmPair: (String) -> Unit = {},
)

/**
 * Localización (S039/RF-1): el estado del proyecto (sin localización, o activa con su RMS) y la lista de pares con sus coordenadas conocidas y las
 * componentes que usan. Los avisos van en texto además de en color (RNF-T8).
 */
@Composable
fun LocalizationScreen(
    themeState: ThemeState,
    onToggleSun: () -> Unit,
    state: LocalizationUiState,
    actions: LocalizationActions,
    onBack: () -> Unit,
) {
    SectionScaffold(
        title = stringResource(R.string.localization_title),
        themeState = themeState,
        onToggleSun = onToggleSun,
        onNavigateBack = onBack,
    ) { padding ->
        Column(
            modifier = Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val project = state.project
            if (state.loaded && project != null) {
                Text(project.name, style = MaterialTheme.typography.titleMedium)
                StatusCard(state, project)
                PairsSection(state, project, actions)
            }
        }
    }
}

@Composable
private fun StatusCard(state: LocalizationUiState, project: Project) {
    val locale = uiLocale()
    val unit = unitSymbol(project.linearUnit)
    fun length(valueM: Double) = "${LengthFormat.inUnit(valueM, project.linearUnit, locale)} $unit"
    val active = state.active
    val text = when {
        active == null -> stringResource(R.string.localization_status_none)
        active.origin == LocalizationOrigin.SAVED_CRS -> stringResource(R.string.localization_status_saved, active.name.orEmpty())
        else -> stringResource(
            R.string.localization_status_active,
            active.rmsHorizontal?.let(::length) ?: "—",
            active.rmsVertical?.let(::length) ?: "—",
        )
    }
    Card(modifier = Modifier.fillMaxWidth().testTag(LOCALIZATION_STATUS_TAG).semantics { liveRegion = LiveRegionMode.Polite }) {
        Text(text, modifier = Modifier.padding(16.dp), style = MaterialTheme.typography.titleSmall)
    }
}

@Composable
private fun PairsSection(state: LocalizationUiState, project: Project, actions: LocalizationActions) {
    Text(
        stringResource(R.string.localization_pairs_title, state.pairs.size, LocalizationLimits.MAX_PAIRS),
        style = MaterialTheme.typography.titleSmall,
        modifier = Modifier.testTag(LOCALIZATION_PAIRS_TITLE_TAG),
    )
    if (state.pairs.isEmpty()) {
        Text(stringResource(R.string.localization_pairs_empty), modifier = Modifier.testTag(LOCALIZATION_PAIRS_EMPTY_TAG))
    }
    state.pairs.forEach { pair -> PairRow(pair, project, actions) }
    val form = state.addPair
    if (form == null) {
        Button(
            onClick = actions.onOpenAddPair,
            enabled = state.pairs.size < LocalizationLimits.MAX_PAIRS,
            modifier = Modifier.fillMaxWidth().heightIn(min = MinTouchTarget).testTag(LOCALIZATION_ADD_PAIR_TAG),
        ) { Text(stringResource(R.string.localization_add_pair)) }
    } else {
        AddPairForm(state, form, project, actions)
    }
}

@Composable
private fun AddPairForm(state: LocalizationUiState, form: AddPairUi, project: Project, actions: LocalizationActions) {
    val unit = unitSymbol(project.linearUnit)
    Card(modifier = Modifier.fillMaxWidth().testTag(LOCALIZATION_FORM_TAG)) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.localization_add_gnss), style = MaterialTheme.typography.titleSmall)
            if (state.gnssCandidates.isEmpty()) {
                Text(stringResource(R.string.localization_add_gnss_none), modifier = Modifier.testTag(LOCALIZATION_NO_GNSS_TAG))
            }
            Column(modifier = Modifier.heightIn(max = 240.dp).verticalScroll(rememberScrollState())) {
                state.gnssCandidates.forEach { candidate ->
                    ChoiceRow(candidate.name, form.gnssId == candidate.id, {
                        actions.onSelectGnss(candidate.id)
                    }, localizationGnssTag(candidate.id))
                }
            }
            Text(stringResource(R.string.localization_add_known), style = MaterialTheme.typography.titleSmall)
            ChoiceRow(
                stringResource(R.string.localization_add_source_typed),
                form.source == KnownSource.TYPED,
                { actions.onSource(KnownSource.TYPED) },
                LOCALIZATION_FORM_SOURCE_TYPED_TAG,
            )
            ChoiceRow(
                stringResource(R.string.localization_add_source_point),
                form.source == KnownSource.POINT,
                { actions.onSource(KnownSource.POINT) },
                LOCALIZATION_FORM_SOURCE_POINT_TAG,
            )
            if (form.source == KnownSource.TYPED) {
                NumberField(
                    stringResource(R.string.localization_add_easting, unit),
                    form.eastingText,
                    actions.onEasting,
                    LOCALIZATION_FORM_EASTING_TAG,
                )
                NumberField(
                    stringResource(R.string.localization_add_northing, unit),
                    form.northingText,
                    actions.onNorthing,
                    LOCALIZATION_FORM_NORTHING_TAG,
                )
                NumberField(
                    stringResource(R.string.localization_add_height, unit),
                    form.heightText,
                    actions.onHeight,
                    LOCALIZATION_FORM_HEIGHT_TAG,
                )
            } else {
                if (state.knownCandidates.isEmpty()) Text(stringResource(R.string.localization_add_known_none))
                Column(modifier = Modifier.heightIn(max = 240.dp).verticalScroll(rememberScrollState())) {
                    state.knownCandidates.forEach { candidate ->
                        ChoiceRow(
                            candidate.name,
                            form.knownPointId == candidate.id,
                            { actions.onSelectKnown(candidate.id) },
                            localizationKnownTag(candidate.id),
                        )
                    }
                }
            }
            form.error?.let {
                Text(
                    pairErrorText(it),
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.testTag(LOCALIZATION_FORM_ERROR_TAG).semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(
                    onClick = actions.onConfirmAddPair,
                    modifier = Modifier.heightIn(min = MinTouchTarget).testTag(LOCALIZATION_FORM_CONFIRM_TAG),
                ) { Text(stringResource(R.string.localization_add_confirm)) }
                OutlinedButton(
                    onClick = actions.onCloseAddPair,
                    modifier = Modifier.heightIn(min = MinTouchTarget).testTag(LOCALIZATION_FORM_CANCEL_TAG),
                ) { Text(stringResource(R.string.localization_add_cancel)) }
            }
        }
    }
}

@Composable
private fun ChoiceRow(text: String, selected: Boolean, onClick: () -> Unit, tag: String) {
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(
            min = MinTouchTarget,
        ).selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(text)
    }
}

@Composable
private fun NumberField(label: String, value: String, onChange: (String) -> Unit, tag: String) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = Modifier.fillMaxWidth().heightIn(min = MinTouchTarget).testTag(tag),
    )
}

@Composable
private fun pairErrorText(error: PairError): String = stringResource(
    when (error) {
        PairError.NO_GNSS -> R.string.localization_error_no_gnss
        PairError.NO_KNOWN_POINT -> R.string.localization_error_no_known
        PairError.INVALID_COORDINATES -> R.string.localization_error_invalid
        PairError.NO_MEASUREMENT -> R.string.localization_error_no_measurement
        PairError.ALREADY_IN_DRAFT -> R.string.localization_error_already
        PairError.LIMIT_REACHED -> R.string.localization_error_limit
        PairError.NO_POSITION -> R.string.localization_error_no_position
    },
)

@Composable
private fun PairRow(pair: LocalizationPair, project: Project, actions: LocalizationActions) {
    val locale = uiLocale()
    val unit = unitSymbol(project.linearUnit)
    fun length(valueM: Double) = "${LengthFormat.inUnit(valueM, project.linearUnit, locale)} $unit"
    val noHeight = stringResource(R.string.localization_no_height)
    Card(modifier = Modifier.fillMaxWidth().testTag(localizationPairTag(pair.id))) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(pair.pointName, style = MaterialTheme.typography.titleSmall)
            Text(
                stringResource(
                    R.string.localization_pair_known,
                    length(pair.knownE),
                    length(pair.knownN),
                    pair.knownH?.let(::length) ?: noHeight,
                ),
                style = MaterialTheme.typography.bodyMedium,
            )
            if (!pair.confirmed) {
                Text(
                    stringResource(R.string.localization_pair_unconfirmed),
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.testTag(localizationUnconfirmedTag(pair.id)),
                )
                OutlinedButton(
                    onClick = { actions.onConfirmPair(pair.id) },
                    modifier = Modifier.heightIn(min = MinTouchTarget).testTag(localizationConfirmPairTag(pair.id)),
                ) { Text(stringResource(R.string.localization_pair_confirm)) }
            }
        }
    }
}
