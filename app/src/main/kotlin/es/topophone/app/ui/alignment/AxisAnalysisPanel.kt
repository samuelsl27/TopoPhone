package es.topophone.app.ui.alignment

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import es.topophone.app.R
import es.topophone.app.ui.collector.AverageStatsUi
import es.topophone.app.ui.collector.CollectorActions
import es.topophone.app.ui.collector.CollectorGearSelector
import es.topophone.app.ui.collector.CollectorUiState
import es.topophone.app.ui.collector.LongPauseDialog
import es.topophone.app.ui.collector.MeasureButton
import es.topophone.app.ui.collector.Measuring
import es.topophone.app.ui.collector.Messages
import es.topophone.app.ui.collector.OutOfToleranceDialog
import es.topophone.app.ui.collector.PauseState
import es.topophone.app.ui.projects.unitSymbol
import es.topophone.app.ui.receiver.uiLocale
import es.topophone.app.ui.stakeout.GuidanceOff
import es.topophone.app.ui.stakeout.KeepScreenOn
import es.topophone.app.ui.stakeout.axisSidePhrase
import es.topophone.app.ui.stakeout.textRes
import es.topophone.core.designsystem.component.MinTouchTarget
import es.topophone.core.model.alignment.ChainageFormat
import es.topophone.core.model.format.LengthFormat
import es.topophone.core.model.point.AxisHeightRef
import es.topophone.core.model.project.LinearUnit
import es.topophone.core.survey.AxisAnalysisValue
import es.topophone.core.survey.AxisHeightDiff
import kotlin.math.abs

const val ANALYSIS_PANEL_TAG = "axis_analysis_panel"
const val ANALYSIS_TITLE_TAG = "axis_analysis_title"
const val ANALYSIS_CLOSE_TAG = "axis_analysis_close"
const val ANALYSIS_REF_PROFILE_TAG = "axis_analysis_ref_profile"
const val ANALYSIS_REF_SURFACE_TAG = "axis_analysis_ref_surface"
const val ANALYSIS_RESULT_TAG = "axis_analysis_result"
const val ANALYSIS_PK_TAG = "axis_analysis_pk"
const val ANALYSIS_OFFSET_TAG = "axis_analysis_offset"
const val ANALYSIS_DIFF_TAG = "axis_analysis_diff"
const val ANALYSIS_REFERENCE_ELEVATION_TAG = "axis_analysis_reference_elevation"
const val ANALYSIS_NOTE_TAG = "axis_analysis_note"
const val ANALYSIS_NO_PROJECTION_TAG = "axis_analysis_no_projection"
const val ANALYSIS_OFF_TAG = "axis_analysis_off"

/** Lo que el usuario hace en «Analizar» (S054/RF-12). */
class AxisAnalysisActions(val onClose: () -> Unit = {}, val onReference: (AxisHeightRef) -> Unit = {})

/**
 * Panel de «Analizar», en el hueco del recolector y sin tapar el mapa (S054/RF-12 a RF-14): el PK y el desplazamiento de la punta del
 * jalón respecto al eje con la palabra «izquierda» o «derecha», la diferencia de cota con la rasante o con la superficie a elegir, o que
 * no hay proyección sobre el eje; y «Medir». La pantalla no se apaga mientras está abierto (RNF-T2).
 */
@Composable
fun AxisAnalysisPanel(
    state: AxisAnalysisUiState,
    actions: AxisAnalysisActions,
    modifier: Modifier = Modifier,
    collector: CollectorUiState? = null,
    collectorActions: CollectorActions = CollectorActions(),
    stats: AverageStatsUi? = null,
) {
    KeepScreenOn()
    val unit = state.project?.linearUnit ?: LinearUnit.METRE
    Card(modifier = modifier.fillMaxWidth().padding(8.dp).testTag(ANALYSIS_PANEL_TAG)) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.axis_analysis_title, state.alignmentName),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f).testTag(ANALYSIS_TITLE_TAG),
                )
                TextButton(onClick = actions.onClose, modifier = Modifier.heightIn(min = MinTouchTarget).testTag(ANALYSIS_CLOSE_TAG)) {
                    Text(stringResource(R.string.collector_close))
                }
            }
            if (!state.loaded) return@Column
            Column(
                modifier = Modifier.weight(1f, fill = false).heightIn(max = BODY_MAX_HEIGHT).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ReferenceChoice(state, actions)
                Result(state, unit)
                collector?.let { CollectorGearSelector(it, collectorActions) } // S046/RF-23
                collector?.let { Messages(it, collectorActions) }
            }
            if (collector != null) {
                if (collector.measuring) Measuring(collector, unit, stats, collectorActions) else MeasureButton(collector, collectorActions)
            }
        }
    }
    collector?.pending?.let { OutOfToleranceDialog(it, collectorActions.onConfirm, collectorActions.onDiscard) }
    if (collector?.paused == PauseState.LONG_PAUSE) {
        LongPauseDialog(collectorActions.onFinish, collectorActions.onCancel, collectorActions.onKeepWaiting)
    }
}

/** Con rasante y superficie activa, se elige contra cuál se calcula la diferencia de cota (S054/RF-12). */
@Composable
private fun ReferenceChoice(state: AxisAnalysisUiState, actions: AxisAnalysisActions) {
    if (!state.canChoose) return
    Text(stringResource(R.string.axis_analysis_choose), style = MaterialTheme.typography.titleSmall)
    Row(modifier = Modifier.selectableGroup().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(
            selected = state.reference == AxisHeightRef.PROFILE,
            onClick = { actions.onReference(AxisHeightRef.PROFILE) },
            label = { Text(stringResource(R.string.axis_analysis_ref_profile)) },
            modifier = Modifier.heightIn(min = MinTouchTarget).testTag(ANALYSIS_REF_PROFILE_TAG),
        )
        FilterChip(
            selected = state.reference == AxisHeightRef.SURFACE,
            onClick = { actions.onReference(AxisHeightRef.SURFACE) },
            label = { Text(stringResource(R.string.axis_analysis_ref_surface)) },
            modifier = Modifier.heightIn(min = MinTouchTarget).testTag(ANALYSIS_REF_SURFACE_TAG),
        )
    }
}

@Composable
private fun Result(state: AxisAnalysisUiState, unit: LinearUnit) {
    val off = state.off
    val analysis = state.analysis
    when {
        off != null || analysis == null -> Text(
            stringResource((off ?: GuidanceOff.NO_POSITION).textRes()),
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }.testTag(ANALYSIS_OFF_TAG),
        )
        analysis is AxisAnalysisValue.NoProjection -> Text(
            stringResource(R.string.axis_analysis_no_projection),
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }.testTag(ANALYSIS_NO_PROJECTION_TAG),
        )
        analysis is AxisAnalysisValue.Projected -> Projected(analysis, unit)
    }
}

/** El PK, el desplazamiento con su palabra y la diferencia de cota, con su frase para TalkBack (S054/RF-12, RNF-T8). */
@Composable
private fun Projected(value: AxisAnalysisValue.Projected, unit: LinearUnit) {
    val locale = uiLocale()
    val symbol = unitSymbol(unit)
    val pk = stringResource(R.string.axis_analysis_pk, ChainageFormat.format(value.chainageM, unit, locale))
    val offset = axisSidePhrase(value.offsetM, unit, symbol)
    val height = heightLines(value, unit)
    val phrase = stringResource(R.string.axis_analysis_a11y, pk, offset, height.diff.orEmpty().ifEmpty { height.note.orEmpty() })
    Column(
        modifier = Modifier.semantics(mergeDescendants = true) { contentDescription = phrase }.testTag(ANALYSIS_RESULT_TAG),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(pk, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.testTag(ANALYSIS_PK_TAG))
        Text(offset, style = MaterialTheme.typography.titleMedium, modifier = Modifier.testTag(ANALYSIS_OFFSET_TAG))
        height.diff?.let { Text(it, style = MaterialTheme.typography.titleMedium, modifier = Modifier.testTag(ANALYSIS_DIFF_TAG)) }
        height.reference?.let { Text(it, modifier = Modifier.testTag(ANALYSIS_REFERENCE_ELEVATION_TAG)) }
        height.note?.let { Text(it, modifier = Modifier.testTag(ANALYSIS_NOTE_TAG)) }
    }
}

/** La diferencia de cota, la cota de la referencia y la nota que explica por qué falta alguna (S054/RF-12). */
private class HeightLines(val diff: String?, val reference: String?, val note: String?)

@Composable
private fun heightLines(value: AxisAnalysisValue.Projected, unit: LinearUnit): HeightLines {
    val locale = uiLocale()
    val symbol = unitSymbol(unit)
    fun length(valueM: Double) = "${LengthFormat.inUnit(valueM, unit, locale)} $symbol"

    val refName = stringResource(
        when (value.heightRef) {
            AxisHeightRef.SURFACE -> R.string.axis_analysis_the_surface
            AxisHeightRef.SECTION -> R.string.axis_analysis_the_section
            else -> R.string.axis_analysis_the_profile
        },
    )
    return when (val h = value.height) {
        AxisHeightDiff.None -> HeightLines(null, null, stringResource(R.string.axis_analysis_note_no_reference))
        AxisHeightDiff.OutsideProfile -> HeightLines(null, null, stringResource(R.string.axis_analysis_note_outside_profile))
        AxisHeightDiff.OutsideSurface -> HeightLines(null, null, stringResource(R.string.axis_analysis_note_outside_surface))
        AxisHeightDiff.LoadingSurface -> HeightLines(null, null, stringResource(R.string.axis_analysis_note_loading_surface))
        AxisHeightDiff.OutsideSection -> HeightLines(null, null, stringResource(R.string.axis_analysis_note_outside_section))
        AxisHeightDiff.NoSection -> HeightLines(null, null, stringResource(R.string.axis_analysis_note_no_section))
        is AxisHeightDiff.Diff -> {
            val reference = stringResource(R.string.axis_analysis_reference_elevation, refName, length(h.referenceM))
            val d = h.dHeightM
            if (d == null) {
                HeightLines(null, reference, stringResource(R.string.axis_analysis_note_no_height))
            } else {
                val where = stringResource(
                    when {
                        abs(d) < AT_M -> R.string.axis_analysis_level
                        d > 0.0 -> R.string.axis_analysis_above
                        else -> R.string.axis_analysis_below
                    },
                )
                val signed = length(d).let { if (d > 0.0 && !it.startsWith("-")) "+$it" else it }
                HeightLines(stringResource(R.string.axis_analysis_diff, refName, signed, where), reference, null)
            }
        }
    }
}

/** Menos de medio milímetro es estar a la cota de la referencia, la resolución con la que se escribe. */
private const val AT_M = 0.0005

private val BODY_MAX_HEIGHT = 380.dp
