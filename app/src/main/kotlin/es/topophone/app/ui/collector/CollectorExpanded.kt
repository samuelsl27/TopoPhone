package es.topophone.app.ui.collector

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import es.topophone.app.R
import es.topophone.app.ui.baseshift.BaseShiftActiveBanner
import es.topophone.app.ui.baseshift.BaseShiftBaseChangedBanner
import es.topophone.app.ui.baseshift.BaseShiftDatumBanner
import es.topophone.app.ui.receiver.BaseNoticeBanners
import es.topophone.core.model.point.TiltMode
import es.topophone.core.model.project.LinearUnit

/**
 * Lo que añade la hoja expandida del recolector: nombre y descripción del punto, código con sus atributos, líneas y polígonos, el
 * jalón elegido, el control de precisión, las alertas de la base y la inclinación (una a la vez), las fotos y, midiendo, las
 * estadísticas del promediado (S060/RF-20 a RF-22). Ninguna fila lleva una altura fija: la hoja se desplaza si no cabe.
 */
@Composable
fun CollectorExpanded(
    state: CollectorUiState,
    unit: LinearUnit,
    actions: CollectorActions,
    modifier: Modifier = Modifier,
    stats: AverageStatsUi? = null,
    surface: SurfaceReading? = null,
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        NameField(state, actions)
        OutlinedTextField(
            value = state.description,
            onValueChange = actions.onDescription,
            label = { Text(stringResource(R.string.collector_description)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().testTag(COLLECTOR_DESCRIPTION_TAG),
        ) // S010/RF-4
        val codeEnabled = !state.measuring && state.pending == null && !state.auto.running
        CodeSelectorRow(state.code, enabled = codeEnabled, actions = actions) // S024/RF-1
        // S025/RF-8b: con una línea activa del mismo código los atributos son los de la línea y no se piden otra vez.
        if (!CollectorLineRules.appendsToActive(state.code.selected, state.line)) {
            AttributeFields(state.code, enabled = codeEnabled, actions = actions) // S024/RF-8, RF-9
        }
        CollectorLineStatus(state.line, state.openLines, enabled = codeEnabled, actions = actions) // S025/RF-3 a RF-6
        CollectorGearSelector(state, actions) // S046/RF-14, RF-15, RF-22
        if (state.tiltMode == TiltMode.RECEIVER) {
            // S016/RF-15: con el receptor que compensa, la altura del jalón es la configurada en el receptor y tienen que coincidir.
            Text(stringResource(R.string.pole_height_receiver_help), style = MaterialTheme.typography.bodyMedium)
        }
        // S010/RF-13; con la inclinación, también la inclinación máxima (S016/RF-20).
        PrecisionControlRow(state.settings.precision, unit, showTilt = state.tiltMode?.usesTilt == true) {
            actions.onSettings(state.settings.copy(precision = it))
        }
        // Con el automático, el modo de cada punto (instantáneo o promediado y su tiempo) se elige aquí (S017/RF-15).
        if (state.autoEnabled) ModeChoice(state, actions)
        state.baseShiftName?.let { BaseShiftActiveBanner(it, actions.onDeactivateBaseShift) } // S020/RF-11
        ExpandedAlert(state, actions)
        CollectorPhotoBar(state.photos, actions) // S017b/RF-1, RF-7, RF-8; S060/RF-21
        SurfaceReadingText(surface, unit) // S042/RF-33
        if (state.progress != null && stats != null) AverageStatsBlock(stats, unit) // S017/RF-3 a RF-5
    }
}

/**
 * La alerta de la base o de la inclinación, una sola a la vez y por prioridad: el cambio de datum del base shift, el cambio de base, la
 * guía de la inclinación y los avisos de la base de S015 (S060/RF-22; S020/RF-15, RF-16; S016/RF-12; S015/RF-15, RF-17).
 */
@Composable
private fun ExpandedAlert(state: CollectorUiState, actions: CollectorActions) {
    val shift = state.baseShiftName
    when {
        state.baseShiftDatumNotice != null ->
            BaseShiftDatumBanner(state.baseShiftDatumNotice, actions.onAcknowledgeBaseShiftDatum) // S020/RF-16
        state.baseShiftBaseChanged && shift != null ->
            BaseShiftBaseChangedBanner(shift, actions.onDeactivateBaseShift, actions.onRepeatBaseShift) // S020/RF-15
        state.tiltGuide != null -> TiltGuideBanner(state.tiltGuide) // S016/RF-12, RF-18
        else -> BaseNoticeBanners(state.notices, actions.onDismissBaseChange, actions.onDismissLongBaseline) // S015/RF-15, RF-17
    }
}
