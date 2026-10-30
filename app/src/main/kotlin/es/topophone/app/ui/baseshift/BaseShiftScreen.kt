package es.topophone.app.ui.baseshift

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import es.topophone.app.R
import es.topophone.app.ui.SectionScaffold
import es.topophone.app.ui.projects.unitSymbol
import es.topophone.app.ui.receiver.uiLocale
import es.topophone.core.designsystem.component.MinTouchTarget
import es.topophone.core.model.format.LengthFormat
import es.topophone.core.model.point.BaseShift
import es.topophone.core.model.point.BaseShiftClass
import es.topophone.core.model.preferences.ThemeState

const val BASE_SHIFT_SOURCE_POINT_TAG = "base_shift_source_point"
const val BASE_SHIFT_SOURCE_TYPED_TAG = "base_shift_source_typed"
const val BASE_SHIFT_NAME_TAG = "base_shift_name"
const val BASE_SHIFT_EASTING_TAG = "base_shift_easting"
const val BASE_SHIFT_NORTHING_TAG = "base_shift_northing"
const val BASE_SHIFT_HEIGHT_TAG = "base_shift_height"
const val BASE_SHIFT_SECONDS_TAG = "base_shift_seconds"
const val BASE_SHIFT_MEASURE_TAG = "base_shift_measure"
const val BASE_SHIFT_CANCEL_TAG = "base_shift_cancel"
const val BASE_SHIFT_BLOCKER_TAG = "base_shift_blocker"
const val BASE_SHIFT_ERROR_TAG = "base_shift_error"
const val BASE_SHIFT_PROGRESS_TAG = "base_shift_progress"
const val BASE_SHIFT_RESULT_TAG = "base_shift_result"
const val BASE_SHIFT_FAR_TAG = "base_shift_far"
const val BASE_SHIFT_CONFIRM_FAR_TAG = "base_shift_confirm_far"
const val BASE_SHIFT_HORIZONTAL_TAG = "base_shift_horizontal"
const val BASE_SHIFT_ACCEPT_HORIZONTAL_TAG = "base_shift_accept_horizontal"
const val BASE_SHIFT_ACTIVATE_TAG = "base_shift_activate"
const val BASE_SHIFT_DISCARD_TAG = "base_shift_discard"
const val BASE_SHIFT_NO_HEIGHT_NOTE_TAG = "base_shift_no_height_note"
const val BASE_SHIFT_LIST_TAG = "base_shift_list"

fun baseShiftCandidateTag(id: String) = "base_shift_candidate_$id"

fun baseShiftItemTag(id: String) = "base_shift_item_$id"

fun baseShiftReactivateTag(id: String) = "base_shift_reactivate_$id"

fun baseShiftDeactivateTag(id: String) = "base_shift_deactivate_$id"

fun baseShiftDeleteTag(id: String) = "base_shift_delete_$id"

/** Acciones de la pantalla del desplazamiento de base (S020). */
class BaseShiftActions(
    val onSource: (KnownSource) -> Unit = {},
    val onSelectPoint: (String) -> Unit = {},
    val onName: (String) -> Unit = {},
    val onEasting: (String) -> Unit = {},
    val onNorthing: (String) -> Unit = {},
    val onHeight: (String) -> Unit = {},
    val onSeconds: (String) -> Unit = {},
    val onMeasure: () -> Unit = {},
    val onCancel: () -> Unit = {},
    val onAcceptHorizontal: () -> Unit = {},
    val onConfirmFar: () -> Unit = {},
    val onActivate: () -> Unit = {},
    val onDiscard: () -> Unit = {},
    val onReactivate: (String) -> Unit = {},
    val onDeactivate: () -> Unit = {},
    val onDelete: (String) -> Unit = {},
)

/**
 * Base shift (S020/RF-1 a RF-6, RF-12): elegir o escribir el punto conocido, medirlo con el receptor sin guardarlo como punto, ver el
 * desplazamiento (ΔX, ΔY, ΔZ, su equivalente en Este, Norte y altura, y su módulo) y activarlo. Los avisos van en texto además de en color
 * (RNF-T8).
 */
@Composable
fun BaseShiftScreen(
    themeState: ThemeState,
    onToggleSun: () -> Unit,
    state: BaseShiftUiState,
    actions: BaseShiftActions,
    onBack: () -> Unit,
) {
    SectionScaffold(
        title = stringResource(R.string.base_shift_title),
        themeState = themeState,
        onToggleSun = onToggleSun,
        onNavigateBack = onBack,
    ) { padding ->
        Column(
            modifier = Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (state.loaded) {
                if (state.projectName.isNotEmpty()) Text(state.projectName, style = MaterialTheme.typography.titleMedium)
                state.error?.let {
                    Text(errorText(it), color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag(BASE_SHIFT_ERROR_TAG))
                }
                when (state.step) {
                    BaseShiftStep.SETUP -> {
                        Setup(state, actions)
                        ShiftList(state, actions)
                    }
                    BaseShiftStep.MEASURING -> Measuring(state, actions)
                    BaseShiftStep.CHOOSE_HORIZONTAL -> ChooseHorizontal(actions)
                    BaseShiftStep.RESULT -> Result(state, actions)
                }
            }
        }
    }
}

@Composable
private fun errorText(error: BaseShiftError): String = stringResource(
    when (error) {
        BaseShiftError.NO_DATA -> R.string.base_shift_error_no_data
        BaseShiftError.OUTSIDE_CRS_AREA -> R.string.base_shift_error_outside_crs
        BaseShiftError.KNOWN_OUTSIDE_AREA -> R.string.base_shift_error_known_outside
        BaseShiftError.TOO_FAR -> R.string.base_shift_error_too_far
        BaseShiftError.DATUM_CHANGED -> R.string.base_shift_error_datum_changed
    },
)

@Composable
private fun Setup(state: BaseShiftUiState, actions: BaseShiftActions) {
    Text(stringResource(R.string.base_shift_known_point), style = MaterialTheme.typography.titleSmall)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        SourceButton(R.string.base_shift_source_point, state.source == KnownSource.POINT, BASE_SHIFT_SOURCE_POINT_TAG) {
            actions.onSource(KnownSource.POINT)
        }
        SourceButton(R.string.base_shift_source_typed, state.source == KnownSource.TYPED, BASE_SHIFT_SOURCE_TYPED_TAG) {
            actions.onSource(KnownSource.TYPED)
        }
    }
    if (state.source == KnownSource.POINT) {
        if (state.candidates.isEmpty()) Text(stringResource(R.string.base_shift_no_points))
        state.candidates.forEach { candidate ->
            Row(
                modifier = Modifier.fillMaxWidth().heightIn(min = MinTouchTarget).testTag(baseShiftCandidateTag(candidate.id))
                    .clickable(role = Role.RadioButton) { actions.onSelectPoint(candidate.id) },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = candidate.id == state.selectedPointId, onClick = null)
                Text(candidate.name, modifier = Modifier.padding(start = 12.dp))
            }
        }
    } else {
        TypedFields(state, actions)
    }
    OutlinedTextField(
        value = state.secondsText ?: state.seconds.toString(),
        onValueChange = actions.onSeconds,
        label = { Text(stringResource(R.string.base_shift_seconds)) },
        isError = !state.secondsValid,
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth().testTag(BASE_SHIFT_SECONDS_TAG),
    )
    state.blocker?.let {
        Text(blockerText(it), modifier = Modifier.testTag(BASE_SHIFT_BLOCKER_TAG).semantics { liveRegion = LiveRegionMode.Polite })
    }
    Button(
        onClick = actions.onMeasure,
        enabled = state.blocker == null,
        modifier = Modifier.fillMaxWidth().heightIn(min = MinTouchTarget).testTag(BASE_SHIFT_MEASURE_TAG),
    ) { Text(stringResource(R.string.base_shift_measure)) }
}

@Composable
private fun SourceButton(label: Int, selected: Boolean, tag: String, onClick: () -> Unit) {
    val modifier = Modifier.heightIn(min = MinTouchTarget).testTag(tag)
    if (selected) {
        Button(onClick = onClick, modifier = modifier) { Text(stringResource(label)) }
    } else {
        OutlinedButton(onClick = onClick, modifier = modifier) { Text(stringResource(label)) }
    }
}

@Composable
private fun TypedFields(state: BaseShiftUiState, actions: BaseShiftActions) {
    val symbol = unitSymbol(state.linearUnit)
    OutlinedTextField(
        value = state.nameText,
        onValueChange = actions.onName,
        label = { Text(stringResource(R.string.base_shift_name)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth().testTag(BASE_SHIFT_NAME_TAG),
    )
    OutlinedTextField(
        value = state.eastingText,
        onValueChange = actions.onEasting,
        label = {
            Text(
                if (state.geographic) stringResource(R.string.base_shift_latitude) else stringResource(R.string.base_shift_easting, symbol),
            )
        },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = Modifier.fillMaxWidth().testTag(BASE_SHIFT_EASTING_TAG),
    )
    OutlinedTextField(
        value = state.northingText,
        onValueChange = actions.onNorthing,
        label = {
            Text(
                if (state.geographic) {
                    stringResource(
                        R.string.base_shift_longitude,
                    )
                } else {
                    stringResource(R.string.base_shift_northing, symbol)
                },
            )
        },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = Modifier.fillMaxWidth().testTag(BASE_SHIFT_NORTHING_TAG),
    )
    OutlinedTextField(
        value = state.heightText,
        onValueChange = actions.onHeight,
        label = { Text(stringResource(R.string.base_shift_height, symbol)) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = Modifier.fillMaxWidth().testTag(BASE_SHIFT_HEIGHT_TAG),
    )
}

@Composable
private fun blockerText(blocker: BaseShiftBlocker): String = stringResource(
    when (blocker) {
        BaseShiftBlocker.NO_KNOWN_POINT -> R.string.base_shift_blocker_no_known
        BaseShiftBlocker.INVALID_SECONDS -> R.string.base_shift_blocker_seconds
        BaseShiftBlocker.NO_POSITION -> R.string.base_shift_blocker_no_position
        BaseShiftBlocker.NO_GEAR -> R.string.base_shift_blocker_no_gear
        BaseShiftBlocker.SHIFT_UNAVAILABLE -> R.string.base_shift_error_datum_changed
    },
)

@Composable
private fun Measuring(state: BaseShiftUiState, actions: BaseShiftActions) {
    val locale = uiLocale()
    val progress = state.progress
    Text(
        if (progress == null) {
            stringResource(R.string.base_shift_waiting)
        } else {
            stringResource(
                R.string.base_shift_measuring,
                "%.0f".format(locale, progress.elapsedS),
                "%.0f".format(locale, progress.requiredS),
                progress.epochsUsed,
            )
        },
        modifier = Modifier.testTag(BASE_SHIFT_PROGRESS_TAG).semantics { liveRegion = LiveRegionMode.Polite },
    )
    OutlinedButton(
        onClick = actions.onCancel,
        modifier = Modifier.fillMaxWidth().heightIn(min = MinTouchTarget).testTag(BASE_SHIFT_CANCEL_TAG),
    ) { Text(stringResource(R.string.base_shift_cancel)) }
}

@Composable
private fun ChooseHorizontal(actions: BaseShiftActions) {
    Text(stringResource(R.string.base_shift_no_height), modifier = Modifier.testTag(BASE_SHIFT_HORIZONTAL_TAG))
    Button(
        onClick = actions.onAcceptHorizontal,
        modifier = Modifier.fillMaxWidth().heightIn(min = MinTouchTarget).testTag(BASE_SHIFT_ACCEPT_HORIZONTAL_TAG),
    ) { Text(stringResource(R.string.base_shift_accept_horizontal)) }
    OutlinedButton(
        onClick = actions.onDiscard,
        modifier = Modifier.fillMaxWidth().heightIn(min = MinTouchTarget).testTag(BASE_SHIFT_DISCARD_TAG),
    ) { Text(stringResource(R.string.base_shift_discard)) }
}

@Composable
private fun Result(state: BaseShiftUiState, actions: BaseShiftActions) {
    val result = state.result ?: return
    val locale = uiLocale()
    val symbol = unitSymbol(state.linearUnit)
    fun length(valueM: Double) = LengthFormat.inUnit(valueM, state.linearUnit, locale, decimals = 4)
    Text(stringResource(R.string.base_shift_result_title), style = MaterialTheme.typography.titleSmall)
    Column(
        modifier = Modifier.testTag(BASE_SHIFT_RESULT_TAG).semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            stringResource(
                R.string.base_shift_result_xyz,
                length(result.vector.dx),
                length(result.vector.dy),
                length(result.vector.dz),
                symbol,
            ),
            fontFamily = FontFamily.Monospace,
        )
        Text(
            stringResource(
                R.string.base_shift_result_enu,
                length(result.deltaEastM),
                length(result.deltaNorthM),
                length(result.deltaHeightM),
                symbol,
            ),
            fontFamily = FontFamily.Monospace,
        )
        Text(stringResource(R.string.base_shift_result_module, length(result.moduleM), symbol), fontFamily = FontFamily.Monospace)
        if (result.horizontalOnly) {
            Text(
                stringResource(R.string.base_shift_no_height_note),
                modifier = Modifier.testTag(BASE_SHIFT_NO_HEIGHT_NOTE_TAG),
            )
        }
    }
    when (result.classification) {
        BaseShiftClass.REJECTED -> Unit // el error "supera 1.000 m" ya está arriba y no hay botón de activar
        BaseShiftClass.NEEDS_CONFIRMATION -> {
            Text(
                stringResource(R.string.base_shift_far_warning),
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.testTag(BASE_SHIFT_FAR_TAG),
            )
            if (state.farConfirmed) {
                Text(stringResource(R.string.base_shift_far_confirmed))
            } else {
                OutlinedButton(
                    onClick = actions.onConfirmFar,
                    modifier = Modifier.fillMaxWidth().heightIn(min = MinTouchTarget).testTag(BASE_SHIFT_CONFIRM_FAR_TAG),
                ) { Text(stringResource(R.string.base_shift_confirm_far)) }
            }
        }
        BaseShiftClass.OK -> Unit
    }
    val canActivate = when (result.classification) {
        BaseShiftClass.OK -> true
        BaseShiftClass.NEEDS_CONFIRMATION -> state.farConfirmed
        BaseShiftClass.REJECTED -> false
    }
    if (result.classification != BaseShiftClass.REJECTED) {
        Button(
            onClick = actions.onActivate,
            enabled = canActivate,
            modifier = Modifier.fillMaxWidth().heightIn(min = MinTouchTarget).testTag(BASE_SHIFT_ACTIVATE_TAG),
        ) { Text(stringResource(R.string.base_shift_activate)) }
    }
    OutlinedButton(
        onClick = actions.onDiscard,
        modifier = Modifier.fillMaxWidth().heightIn(min = MinTouchTarget).testTag(BASE_SHIFT_DISCARD_TAG),
    ) { Text(stringResource(R.string.base_shift_discard)) }
}

@Composable
private fun ShiftList(state: BaseShiftUiState, actions: BaseShiftActions) {
    Text(stringResource(R.string.base_shift_list_title), style = MaterialTheme.typography.titleSmall)
    if (state.shifts.isEmpty()) {
        Text(stringResource(R.string.base_shift_list_empty), modifier = Modifier.testTag(BASE_SHIFT_LIST_TAG))
        return
    }
    state.shifts.forEach { shift -> ShiftItem(shift, state, actions) }
}

@Composable
private fun ShiftItem(shift: BaseShift, state: BaseShiftUiState, actions: BaseShiftActions) {
    val locale = uiLocale()
    val symbol = unitSymbol(state.linearUnit)
    Card(modifier = Modifier.fillMaxWidth().testTag(baseShiftItemTag(shift.id))) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(shift.knownName, style = MaterialTheme.typography.titleSmall)
            Text(
                stringResource(
                    R.string.base_shift_result_module,
                    LengthFormat.inUnit(shift.vector.moduleM, state.linearUnit, locale, decimals = 3),
                    symbol,
                ),
                fontFamily = FontFamily.Monospace,
            )
            if (shift.active) Text(stringResource(R.string.base_shift_active), color = MaterialTheme.colorScheme.primary)
            if (shift.horizontalOnly) Text(stringResource(R.string.base_shift_no_height_note))
            if (shift.fromReplay) Text(stringResource(R.string.base_shift_from_replay))
            if (shift.datumNotice) Text(stringResource(R.string.base_shift_error_datum_changed), color = MaterialTheme.colorScheme.error)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (shift.active) {
                    OutlinedButton(
                        onClick = actions.onDeactivate,
                        modifier = Modifier.heightIn(min = MinTouchTarget).testTag(baseShiftDeactivateTag(shift.id)),
                    ) { Text(stringResource(R.string.base_shift_deactivate)) }
                } else {
                    OutlinedButton(
                        onClick = { actions.onReactivate(shift.id) },
                        modifier = Modifier.heightIn(min = MinTouchTarget).testTag(baseShiftReactivateTag(shift.id)),
                    ) { Text(stringResource(R.string.base_shift_reactivate)) }
                }
                OutlinedButton(
                    onClick = { actions.onDelete(shift.id) },
                    modifier = Modifier.heightIn(min = MinTouchTarget).testTag(baseShiftDeleteTag(shift.id)),
                ) { Text(stringResource(R.string.base_shift_delete)) }
            }
        }
    }
}
