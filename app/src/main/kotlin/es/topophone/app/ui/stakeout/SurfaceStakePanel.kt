package es.topophone.app.ui.stakeout

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import es.topophone.app.R
import es.topophone.app.ui.collector.AverageStatsUi
import es.topophone.app.ui.collector.CollectorActions
import es.topophone.app.ui.collector.CollectorUiState
import es.topophone.app.ui.field.NumericEntryDialog
import es.topophone.app.ui.field.SettingPillData
import es.topophone.app.ui.field.SettingsRow
import es.topophone.app.ui.projects.unitSymbol
import es.topophone.app.ui.receiver.uiLocale
import es.topophone.core.model.format.LengthFormat
import es.topophone.core.model.format.NumberInput
import es.topophone.core.model.point.StakeoutSettings
import es.topophone.core.model.project.LinearUnit
import es.topophone.core.model.surface.SurfaceHeight

const val SURFACE_STAKE_PANEL_TAG = "surface_stake_panel"
const val SURFACE_STAKE_PLATE_TAG = "surface_stake_plate"
const val SURFACE_STAKE_PILL_OFFSET_TAG = "surface_stake_pill_offset"

/** Las acciones del replanteo de la superficie (S062/RF-10, RF-11). [onToleranceV] y [onOffset] dan false si lo escrito no vale. */
class SurfaceStakeActions(
    val onClose: () -> Unit = {},
    val onToleranceV: (String) -> Boolean = { false },
    val onOffset: (String) -> Boolean = { false },
)

/**
 * El replanteo de la superficie solo en cota como hoja de campo (S062/RF-7 a RF-11): la placa sin objetivo en planta, con el desmonte,
 * el terraplén o «En cota» y las cotas; la fila de ajustes con la tolerancia vertical y el desplazamiento, además del jalón de la hoja;
 * y «Medir». Sin «Anterior» ni «Siguiente»: cada medida es un punto nuevo.
 */
@Composable
fun SurfaceStakePanel(
    state: StakeoutUiState,
    actions: SurfaceStakeActions,
    modifier: Modifier = Modifier,
    collector: CollectorUiState? = null,
    collectorActions: CollectorActions = CollectorActions(),
    stats: AverageStatsUi? = null,
) {
    val unit = state.project?.linearUnit ?: LinearUnit.METRE
    StakeoutSheet(
        title = stringResource(R.string.surface_stake_title, state.target?.name.orEmpty()),
        onClose = actions.onClose,
        live = collector?.live,
        collector = collector,
        collectorActions = collectorActions,
        navigation = null,
        modifier = modifier,
        unit = unit,
        tag = SURFACE_STAKE_PANEL_TAG,
        stats = stats,
        plate = { SurfaceStakePlate(state.guidance, unit) },
        pills = {
            SurfaceStakePills(
                unit = unit,
                settings = state.settings,
                offsetM = state.saveInfo?.surface?.verticalOffsetM ?: 0.0,
                enabled = !state.measuring, // RF-23
                onToleranceV = actions.onToleranceV,
                onOffset = actions.onOffset,
            )
        },
        result = collector?.savedStake?.let { ref -> @Composable { ResultLine(ref) } },
    )
}

/**
 * La placa del replanteo de la superficie (S062/RF-7 a RF-9, RF-13): sin flecha, distancia ni desplazamientos; el desmonte, el terraplén
 * o «En cota» con el nivel vertical, y la cota de la superficie bajo la punta, el desplazamiento, la cota de diseño y la de la punta.
 * Fuera de la superficie lo dice en lugar de las cotas.
 */
@Composable
internal fun SurfaceStakePlate(guidance: GuidanceState, unit: LinearUnit, modifier: Modifier = Modifier) {
    when (guidance) {
        is GuidanceState.Off -> Text(
            stringResource(guidance.reason.textRes()),
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.titleMedium,
            modifier = modifier.semantics { liveRegion = LiveRegionMode.Polite }.testTag(STAKEOUT_OFF_TAG),
        )
        is GuidanceState.On -> {
            val ui = guidance.ui
            val numbers = Numbers(unit, unitSymbol(unit), uiLocale())
            Row(
                modifier = modifier.testTag(SURFACE_STAKE_PLATE_TAG),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    val level = ui.level
                    if (level != null) {
                        StakeoutLevelReading(level, numbers)
                    } else {
                        ui.levelUnavailable?.let { StakeoutLevelUnavailable(it) }
                    }
                    ui.surface?.let { surface ->
                        val height = (surface.height as? SurfaceHeight.Value)?.metres
                        if (height != null) Text(stringResource(R.string.stakeout_surface_height, numbers.length(height)))
                        Text(stringResource(R.string.surface_stake_offset, numbers.signed(surface.offsetM)))
                        surface.designM?.let { design ->
                            Text(stringResource(R.string.surface_stake_design, numbers.length(design)))
                            level?.let { Text(stringResource(R.string.surface_stake_tip, numbers.length(design + it.dzM))) }
                        }
                    }
                }
                ui.level?.let { StakeoutLevelView(it, numbers, Modifier.width(48.dp).height(120.dp)) }
            }
        }
    }
}

/** «Tolerancia V» y «Desplazamiento», con su teclado numérico (S062/RF-10, RF-11, RF-23). */
@Composable
internal fun SurfaceStakePills(
    unit: LinearUnit,
    settings: StakeoutSettings,
    offsetM: Double,
    enabled: Boolean,
    onToleranceV: (String) -> Boolean,
    onOffset: (String) -> Boolean,
) {
    var dialog by remember { mutableStateOf<String?>(null) }
    val locale = uiLocale()
    val symbol = unitSymbol(unit)
    val numbers = Numbers(unit, symbol, locale)
    SettingsRow(
        listOf(
            SettingPillData(
                stringResource(R.string.field_pill_tol_v),
                numbers.length(settings.validToleranceVM),
                STAKEOUT_PILL_TOLERANCE_V_TAG,
                enabled,
            ) { dialog = TOLERANCE_V },
            SettingPillData(
                stringResource(R.string.field_pill_offset),
                numbers.signed(offsetM),
                SURFACE_STAKE_PILL_OFFSET_TAG,
                enabled,
            ) { dialog = OFFSET },
        ),
    )
    val range = StakeoutSettings.toleranceRangeIn(unit)
    when (dialog) {
        TOLERANCE_V -> NumericEntryDialog(
            title = stringResource(R.string.field_pill_tol_v),
            label = stringResource(R.string.stakeout_tolerance_v_label, symbol),
            initial = LengthFormat.inUnit(settings.validToleranceVM, unit, locale),
            validate = { text -> R.string.stakeout_tolerance_invalid.takeIf { StakeoutSettings.parseTolerance(text, unit) == null } },
            errorArgs = listOf(
                LengthFormat.inUnit(range.start, unit, locale),
                LengthFormat.inUnit(range.endInclusive, unit, locale),
                symbol,
            ),
            onConfirm = { onToleranceV(it) },
            onDismiss = { dialog = null },
        )
        OFFSET -> NumericEntryDialog(
            title = stringResource(R.string.field_pill_offset),
            label = stringResource(R.string.surface_offset_label, symbol),
            initial = LengthFormat.inUnit(offsetM, unit, locale),
            validate = { text -> R.string.surface_offset_invalid.takeIf { NumberInput.parse(text)?.isFinite() != true } },
            onConfirm = { onOffset(it) },
            onDismiss = { dialog = null },
        )
        else -> Unit
    }
}

private const val TOLERANCE_V = "tolerance_v"
private const val OFFSET = "offset"
