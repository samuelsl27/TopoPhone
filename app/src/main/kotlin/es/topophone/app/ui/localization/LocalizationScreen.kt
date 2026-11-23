package es.topophone.app.ui.localization

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import es.topophone.app.ui.projects.formatDateTime
import es.topophone.app.ui.projects.unitSymbol
import es.topophone.app.ui.receiver.uiLocale
import es.topophone.core.designsystem.component.MinTouchTarget
import es.topophone.core.model.format.AngleFormat
import es.topophone.core.model.format.FactorFormat
import es.topophone.core.model.format.LengthFormat
import es.topophone.core.model.localization.LocalizationLimits
import es.topophone.core.model.localization.LocalizationOrigin
import es.topophone.core.model.localization.LocalizationPair
import es.topophone.core.model.localization.LocalizationParams
import es.topophone.core.model.localization.VerticalKind
import es.topophone.core.model.preferences.ThemeState
import es.topophone.core.model.project.Project
import es.topophone.core.survey.localization.LocalizationFailure
import es.topophone.core.survey.localization.LocalizationOutcome

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

fun localizationPairHorizontalTag(id: String) = "localization_pair_h_$id"

fun localizationPairVerticalTag(id: String) = "localization_pair_v_$id"

fun localizationRemovePairTag(id: String) = "localization_remove_pair_$id"

const val LOCALIZATION_FIX_SCALE_TAG = "localization_fix_scale"
const val LOCALIZATION_APPLY_TAG = "localization_apply"
const val LOCALIZATION_REMOVE_TAG = "localization_remove"
const val LOCALIZATION_DISCARD_TAG = "localization_discard"
const val LOCALIZATION_TERRAIN_NOTE_TAG = "localization_terrain_note"
const val LOCALIZATION_CONFIRM_APPLY_TAG = "localization_confirm_apply"
const val LOCALIZATION_CONFIRM_APPLY_TEXT_TAG = "localization_confirm_apply_text"
const val LOCALIZATION_CANCEL_APPLY_TAG = "localization_cancel_apply"
const val LOCALIZATION_CONFIRM_REMOVE_TAG = "localization_confirm_remove"
const val LOCALIZATION_CANCEL_REMOVE_TAG = "localization_cancel_remove"
const val LOCALIZATION_NOTICE_TAG = "localization_notice"
const val LOCALIZATION_HISTORY_TAG = "localization_history"

fun localizationReactivateTag(id: String) = "localization_reactivate_$id"
const val LOCALIZATION_RESULT_TAG = "localization_result"
const val LOCALIZATION_RESULT_FAILED_TAG = "localization_result_failed"
const val LOCALIZATION_TRANSLATION_TAG = "localization_translation"
const val LOCALIZATION_ROTATION_TAG = "localization_rotation"
const val LOCALIZATION_SCALE_TAG = "localization_scale"
const val LOCALIZATION_VERTICAL_TAG = "localization_vertical"
const val LOCALIZATION_ALIGNED_TAG = "localization_aligned"
const val LOCALIZATION_RMS_TAG = "localization_rms"
const val LOCALIZATION_WORST_TAG = "localization_worst"
const val LOCALIZATION_SCALE_WARNING_TAG = "localization_scale_warning"
const val LOCALIZATION_LIMIT_RESIDUAL_TAG = "localization_limit_residual"
const val LOCALIZATION_LIMIT_SCALE_TAG = "localization_limit_scale"

fun localizationResidualTag(id: String) = "localization_residual_$id"

fun localizationResidualHighTag(id: String) = "localization_residual_high_$id"

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
    val onComponent: (pairId: String, horizontal: Boolean, enabled: Boolean) -> Unit = { _, _, _ -> },
    val onRemovePair: (String) -> Unit = {},
    val onFixScale: (Boolean) -> Unit = {},
    val onLimits: (residualText: String, scaleText: String) -> Unit = { _, _ -> },
    val onRequestApply: () -> Unit = {},
    val onCancelApply: () -> Unit = {},
    val onConfirmApply: () -> Unit = {},
    val onRequestRemove: () -> Unit = {},
    val onCancelRemove: () -> Unit = {},
    val onConfirmRemove: () -> Unit = {},
    val onReactivate: (String) -> Unit = {},
    val onDiscardDraft: () -> Unit = {},
    val onDismissNotice: () -> Unit = {},
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
                state.notice?.let { NoticeCard(it, actions.onDismissNotice) }
                StatusCard(state, project)
                FixScaleRow(state.fixScale, actions.onFixScale)
                PairsSection(state, project, actions)
                ResultSection(state, project, actions)
                ApplySection(state, actions)
                HistorySection(state, project, actions)
                ApplyDialogs(state, actions)
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
private fun NoticeCard(notice: LocalizationNotice, onDismiss: () -> Unit) {
    val text = when (notice) {
        is LocalizationNotice.Applied -> stringResource(R.string.localization_notice_applied, notice.pointsChanged)
        is LocalizationNotice.Removed -> stringResource(R.string.localization_notice_removed, notice.pointsChanged)
        LocalizationNotice.DraftReady -> stringResource(R.string.localization_notice_draft_ready)
        LocalizationNotice.TerrainActive -> stringResource(R.string.localization_notice_terrain)
        LocalizationNotice.CannotSolve -> stringResource(R.string.localization_notice_cannot_solve)
        LocalizationNotice.StaleCrs -> stringResource(R.string.localization_notice_stale_crs)
        LocalizationNotice.NotFound -> stringResource(R.string.localization_notice_not_found)
    }
    Card(modifier = Modifier.fillMaxWidth().testTag(LOCALIZATION_NOTICE_TAG).semantics { liveRegion = LiveRegionMode.Polite }) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(text, modifier = Modifier.weight(1f))
            OutlinedButton(onClick = onDismiss, modifier = Modifier.heightIn(min = MinTouchTarget)) {
                Text(stringResource(R.string.localization_notice_ok))
            }
        }
    }
}

/** Aplicar, descartar el borrador y quitar la activa (S039/RF-14, RF-16, RF-21, RF-22). */
@Composable
private fun ApplySection(state: LocalizationUiState, actions: LocalizationActions) {
    if (state.terrain) {
        Text(
            stringResource(R.string.localization_terrain_note),
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.testTag(LOCALIZATION_TERRAIN_NOTE_TAG),
        )
    }
    Button(
        onClick = actions.onRequestApply,
        enabled = state.canApply,
        modifier = Modifier.fillMaxWidth().heightIn(min = MinTouchTarget).testTag(LOCALIZATION_APPLY_TAG),
    ) { Text(stringResource(R.string.localization_apply)) }
    if (state.draft != null) {
        OutlinedButton(
            onClick = actions.onDiscardDraft,
            modifier = Modifier.fillMaxWidth().heightIn(min = MinTouchTarget).testTag(LOCALIZATION_DISCARD_TAG),
        ) { Text(stringResource(R.string.localization_discard)) }
    }
    if (state.active != null) {
        OutlinedButton(
            onClick = actions.onRequestRemove,
            modifier = Modifier.fillMaxWidth().heightIn(min = MinTouchTarget).testTag(LOCALIZATION_REMOVE_TAG),
        ) { Text(stringResource(R.string.localization_remove)) }
    }
}

/** Las localizaciones quitadas o sustituidas, con su fecha y su RMS, y «Reactivar» (S039/RF-21). */
@Composable
private fun HistorySection(state: LocalizationUiState, project: Project, actions: LocalizationActions) {
    if (state.history.isEmpty()) return
    val locale = uiLocale()
    val unit = unitSymbol(project.linearUnit)
    Text(
        stringResource(R.string.localization_history_title),
        style = MaterialTheme.typography.titleSmall,
        modifier = Modifier.testTag(LOCALIZATION_HISTORY_TAG),
    )
    state.history.forEach { item ->
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                val date = item.appliedAtMs?.let { formatDateTime(it, locale) } ?: "—"
                val label = item.name ?: stringResource(R.string.localization_history_pairs, item.pairs.size)
                Text(stringResource(R.string.localization_history_item, label, date), style = MaterialTheme.typography.bodyMedium)
                item.rmsHorizontal?.let {
                    Text(stringResource(R.string.localization_history_rms, "${LengthFormat.inUnit(it, project.linearUnit, locale)} $unit"))
                }
                OutlinedButton(
                    onClick = { actions.onReactivate(item.id) },
                    modifier = Modifier.heightIn(min = MinTouchTarget).testTag(localizationReactivateTag(item.id)),
                ) { Text(stringResource(R.string.localization_reactivate)) }
            }
        }
    }
}

@Composable
private fun ApplyDialogs(state: LocalizationUiState, actions: LocalizationActions) {
    state.confirmApply?.let { dialog ->
        val text = if (dialog.stage == ApplyStage.CONFIRM) {
            stringResource(R.string.localization_confirm_apply_text, dialog.pointsAffected)
        } else {
            val names = dialog.highPairNames.joinToString(", ")
            buildString {
                if (names.isNotEmpty()) append(stringResource(R.string.localization_confirm_warning_residual, names))
                if (dialog.scaleOutOfLimit) {
                    if (isNotEmpty()) append("\n\n")
                    append(stringResource(R.string.localization_confirm_warning_scale))
                }
            }
        }
        AlertDialog(
            onDismissRequest = actions.onCancelApply,
            title = {
                Text(
                    stringResource(
                        if (dialog.stage ==
                            ApplyStage.CONFIRM
                        ) {
                            R.string.localization_confirm_apply_title
                        } else {
                            R.string.localization_confirm_warning_title
                        },
                    ),
                )
            },
            text = { Text(text, modifier = Modifier.testTag(LOCALIZATION_CONFIRM_APPLY_TEXT_TAG)) },
            confirmButton = {
                Button(
                    onClick = actions.onConfirmApply,
                    modifier = Modifier.heightIn(min = MinTouchTarget).testTag(LOCALIZATION_CONFIRM_APPLY_TAG),
                ) {
                    Text(stringResource(R.string.localization_confirm_ok))
                }
            },
            dismissButton = {
                OutlinedButton(
                    onClick = actions.onCancelApply,
                    modifier = Modifier.heightIn(min = MinTouchTarget).testTag(LOCALIZATION_CANCEL_APPLY_TAG),
                ) {
                    Text(stringResource(R.string.localization_add_cancel))
                }
            },
        )
    }
    state.confirmRemove?.let { count ->
        AlertDialog(
            onDismissRequest = actions.onCancelRemove,
            title = { Text(stringResource(R.string.localization_remove)) },
            text = { Text(stringResource(R.string.localization_confirm_remove_text, count)) },
            confirmButton = {
                Button(
                    onClick = actions.onConfirmRemove,
                    modifier = Modifier.heightIn(min = MinTouchTarget).testTag(LOCALIZATION_CONFIRM_REMOVE_TAG),
                ) {
                    Text(stringResource(R.string.localization_confirm_ok))
                }
            },
            dismissButton = {
                OutlinedButton(
                    onClick = actions.onCancelRemove,
                    modifier = Modifier.heightIn(min = MinTouchTarget).testTag(LOCALIZATION_CANCEL_REMOVE_TAG),
                ) {
                    Text(stringResource(R.string.localization_add_cancel))
                }
            },
        )
    }
}

@Composable
private fun ResultSection(state: LocalizationUiState, project: Project, actions: LocalizationActions) {
    val outcome = state.outcome ?: return
    val locale = uiLocale()
    val unit = unitSymbol(project.linearUnit)
    fun length(valueM: Double) = "${LengthFormat.inUnit(valueM, project.linearUnit, locale)} $unit"
    fun ppm(value: Double) = String.format(locale, "%.3f", value)
    Text(stringResource(R.string.localization_result_title), style = MaterialTheme.typography.titleSmall)
    when (outcome) {
        is LocalizationOutcome.Failed -> Text(
            stringResource(
                if (outcome.reason == LocalizationFailure.NO_PAIRS) {
                    R.string.localization_result_failed_no_pairs
                } else {
                    R.string.localization_result_failed_degenerate
                },
            ),
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.testTag(LOCALIZATION_RESULT_FAILED_TAG).semantics { liveRegion = LiveRegionMode.Polite },
        )
        is LocalizationOutcome.Solved -> {
            val solution = outcome.solution
            val p = solution.params
            Card(modifier = Modifier.fillMaxWidth().testTag(LOCALIZATION_RESULT_TAG).semantics { liveRegion = LiveRegionMode.Polite }) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (p.hasHorizontal) {
                        Text(
                            stringResource(R.string.localization_result_translation, length(p.translationE), length(p.translationN)),
                            modifier = Modifier.testTag(LOCALIZATION_TRANSLATION_TAG),
                        )
                        Text(
                            stringResource(
                                R.string.localization_result_rotation,
                                AngleFormat.convergence(p.rotationRad, project.angularUnit, locale),
                            ),
                            modifier = Modifier.testTag(LOCALIZATION_ROTATION_TAG),
                        )
                        Text(
                            stringResource(R.string.localization_result_scale, FactorFormat.factor(p.scale, locale), ppm(p.scalePpm)),
                            modifier = Modifier.testTag(LOCALIZATION_SCALE_TAG),
                        )
                        if (state.assessment?.scaleOutOfLimit == true) {
                            Text(
                                stringResource(R.string.localization_scale_warning),
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.testTag(LOCALIZATION_SCALE_WARNING_TAG),
                            )
                        }
                    }
                    Text(verticalText(p, ::length, ::ppm), modifier = Modifier.testTag(LOCALIZATION_VERTICAL_TAG))
                    if (solution.verticalAligned) {
                        Text(stringResource(R.string.localization_result_aligned), modifier = Modifier.testTag(LOCALIZATION_ALIGNED_TAG))
                    }
                    Text(
                        stringResource(
                            R.string.localization_result_rms,
                            solution.rmsHorizontal?.let(::length) ?: "—",
                            solution.rmsVertical?.let(::length) ?: "—",
                        ),
                        modifier = Modifier.testTag(LOCALIZATION_RMS_TAG),
                    )
                    val worst = state.pairs.firstOrNull { it.id == solution.worstPairId }
                    if (worst != null) {
                        Text(
                            stringResource(R.string.localization_result_worst, worst.pointName),
                            modifier = Modifier.testTag(LOCALIZATION_WORST_TAG),
                        )
                    }
                }
            }
            Text(stringResource(R.string.localization_residuals_title), style = MaterialTheme.typography.titleSmall)
            solution.residuals.forEach { residual ->
                val pair = state.pairs.firstOrNull { it.id == residual.pairId } ?: return@forEach
                val high = state.assessment?.highPairIds?.contains(residual.pairId) == true
                Column(modifier = Modifier.fillMaxWidth().testTag(localizationResidualTag(residual.pairId))) {
                    Text(
                        stringResource(
                            R.string.localization_residual_row,
                            pair.pointName,
                            residual.east?.let(::length) ?: "—",
                            residual.north?.let(::length) ?: "—",
                            residual.horizontal?.let(::length) ?: "—",
                            residual.vertical?.let(::length) ?: "—",
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    if (high) {
                        Text(
                            stringResource(R.string.localization_residual_high),
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.testTag(localizationResidualHighTag(residual.pairId)),
                        )
                    }
                }
            }
        }
    }
    LimitsSection(state, project, actions)
}

@Composable
private fun verticalText(p: LocalizationParams, length: (Double) -> String, ppm: (Double) -> String): String = when (p.verticalKind) {
    VerticalKind.NONE -> stringResource(R.string.localization_result_vertical_none)
    VerticalKind.OFFSET -> stringResource(R.string.localization_result_vertical_offset, length(p.dh))
    VerticalKind.PLANE -> stringResource(R.string.localization_result_vertical_plane, length(p.dh), ppm(p.slopePpm))
}

/** Los umbrales de aviso, ajustables (S039/RF-13): el del residuo en la unidad del proyecto y el de la escala. */
@Composable
private fun LimitsSection(state: LocalizationUiState, project: Project, actions: LocalizationActions) {
    val locale = uiLocale()
    val unit = unitSymbol(project.linearUnit)
    val residualShown = LengthFormat.inUnit(state.residualLimitM, project.linearUnit, locale)
    val scaleShown = String.format(locale, "%.4f", state.scaleLimit)
    var residualText by remember(residualShown) { mutableStateOf(residualShown) }
    var scaleText by remember(scaleShown) { mutableStateOf(scaleShown) }
    Text(stringResource(R.string.localization_limits_title), style = MaterialTheme.typography.titleSmall)
    NumberField(
        stringResource(R.string.localization_limit_residual, unit),
        residualText,
        {
            residualText = it
            actions.onLimits(it, scaleText)
        },
        LOCALIZATION_LIMIT_RESIDUAL_TAG,
    )
    NumberField(
        stringResource(R.string.localization_limit_scale),
        scaleText,
        {
            scaleText = it
            actions.onLimits(residualText, it)
        },
        LOCALIZATION_LIMIT_SCALE_TAG,
    )
}

@Composable
private fun FixScaleRow(fixScale: Boolean, onChange: (Boolean) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().heightIn(
                min = MinTouchTarget,
            ).toggleable(value = fixScale, role = Role.Switch, onValueChange = onChange)
                .testTag(LOCALIZATION_FIX_SCALE_TAG),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Switch(checked = fixScale, onCheckedChange = null)
            Text(stringResource(R.string.localization_fix_scale), modifier = Modifier.weight(1f))
        }
        Text(stringResource(R.string.localization_fix_scale_help), style = MaterialTheme.typography.bodySmall)
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
private fun ComponentRow(text: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit, tag: String) {
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = MinTouchTarget)
            .toggleable(value = checked, enabled = enabled, role = Role.Checkbox, onValueChange = onChange).testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Checkbox(checked = checked, onCheckedChange = null, enabled = enabled)
        Text(text)
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
            ComponentRow(
                stringResource(R.string.localization_pair_component_h),
                pair.useHorizontal,
                true,
                { actions.onComponent(pair.id, true, it) },
                localizationPairHorizontalTag(pair.id),
            )
            ComponentRow(
                stringResource(R.string.localization_pair_component_v),
                pair.useVertical,
                pair.scH != null && pair.knownH != null,
                { actions.onComponent(pair.id, false, it) },
                localizationPairVerticalTag(pair.id),
            )
            OutlinedButton(
                onClick = { actions.onRemovePair(pair.id) },
                modifier = Modifier.heightIn(min = MinTouchTarget).testTag(localizationRemovePairTag(pair.id)),
            ) { Text(stringResource(R.string.localization_pair_remove)) }
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
