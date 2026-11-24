package es.topophone.app.ui.stakeout

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import es.topophone.app.R
import es.topophone.app.ui.projects.TerrainMark
import es.topophone.app.ui.projects.rememberTerrainView
import es.topophone.app.ui.projects.unitSymbol
import es.topophone.app.ui.receiver.uiLocale
import es.topophone.core.designsystem.theme.LocalStatusPalette
import es.topophone.core.designsystem.theme.StatusTone
import es.topophone.core.model.point.StakeMode
import es.topophone.core.model.point.StakeoutSettings
import es.topophone.core.model.project.LinearUnit
import es.topophone.core.model.project.Project
import es.topophone.core.survey.LevelState
import es.topophone.core.survey.StakeZone
import kotlin.math.abs

const val STAKEOUT_PLATE_TAG = "stakeout_plate"
const val STAKEOUT_ARRIVED_TAG = "stakeout_arrived"

object ArrivalRules {
    /**
     * «En el punto»: dentro de la tolerancia horizontal (la zona `INSIDE` de S018) y, si hay cota de diseño, también en cota dentro de la
     * tolerancia vertical (S019); sin cota de diseño solo cuenta la horizontal (S060/RF-28). Los avisos sonoros y la vibración siguen
     * saliendo de las zonas y los niveles, sin cambios (RF-31).
     */
    fun within(ui: GuidanceUi): Boolean = ui.zone == StakeZone.INSIDE && (ui.level == null || ui.level.state == LevelState.ON_GRADE)
}

/**
 * La placa de guiado: la flecha hacia el destino a la izquierda, la distancia en grande y, a la derecha, los desplazamientos (Norte y Este
 * por distancia; adelante, atrás, izquierda y derecha por dirección), y debajo la diferencia de cota con «Desmonte» o «Terraplén». Con la
 * guía apagada dice por qué. Dentro de las tolerancias se pone en verde con el texto «En el punto» (S060/RF-24, RF-28, RNF-T8). [extra]
 * pone lo propio de una línea, un eje o un talud bajo la distancia (RF-29).
 */
@Composable
internal fun GuidancePlate(
    project: Project?,
    settings: StakeoutSettings,
    guidance: GuidanceState,
    modifier: Modifier = Modifier,
    extra: (@Composable (GuidanceUi, Numbers) -> Unit)? = null,
) {
    when (guidance) {
        is GuidanceState.Off -> Text(
            stringResource(guidance.reason.textRes()),
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.titleMedium,
            modifier = modifier.semantics { liveRegion = LiveRegionMode.Polite }.testTag(STAKEOUT_OFF_TAG),
        )
        is GuidanceState.On -> PlateContent(project, settings, guidance.ui, modifier, extra)
    }
}

@Composable
private fun PlateContent(
    project: Project?,
    settings: StakeoutSettings,
    ui: GuidanceUi,
    modifier: Modifier,
    extra: (@Composable (GuidanceUi, Numbers) -> Unit)?,
) {
    val unit = project?.linearUnit ?: LinearUnit.METRE
    val numbers = Numbers(unit, unitSymbol(unit), uiLocale())
    val arrived = ArrivalRules.within(ui)
    val positive = LocalStatusPalette.current.colorsFor(StatusTone.POSITIVE)
    val container = if (arrived) positive.container else MaterialTheme.colorScheme.surfaceVariant
    val content = if (arrived) positive.content else MaterialTheme.colorScheme.onSurfaceVariant
    val byDirection = settings.mode == StakeMode.BY_DIRECTION
    val ahead = ui.guidance.aheadM
    val right = ui.guidance.rightM
    Surface(
        color = container,
        contentColor = content,
        shape = MaterialTheme.shapes.medium,
        modifier = modifier.fillMaxWidth().testTag(STAKEOUT_PLATE_TAG),
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (byDirection && ahead != null && right != null) StakeoutArrow(ahead, right, Modifier.size(ARROW_SIZE))
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    project?.let { TerrainMark(rememberTerrainView(it), STAKEOUT_TERRAIN_TAG) } // S058/RF-9
                    Text(
                        stringResource(R.string.stakeout_distance, numbers.length(ui.guidance.distanceM)),
                        style = MaterialTheme.typography.headlineMedium,
                        modifier = Modifier.testTag(STAKEOUT_DISTANCE_TAG),
                    )
                    if (arrived) {
                        Text(
                            stringResource(R.string.field_plate_arrived),
                            style = MaterialTheme.typography.titleLarge,
                            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }.testTag(STAKEOUT_ARRIVED_TAG),
                        ) // S060/RF-28: con texto, no solo con color (RNF-T8)
                    }
                    extra?.invoke(ui, numbers)
                }
                Offsets(settings.mode, ui, numbers)
            }
            ui.level?.let { StakeoutLevelReading(it, numbers) } // S019/RF-6, RF-8: Desmonte, Terraplén o En cota
            ui.levelUnavailable?.let { StakeoutLevelUnavailable(it) } // S019/RF-5
        }
    }
}

/** Los desplazamientos de la placa según el modo (S060/RF-24; S018/RF-6, RF-7, RF-11). */
@Composable
private fun Offsets(mode: StakeMode, ui: GuidanceUi, numbers: Numbers) {
    val ahead = ui.guidance.aheadM
    val right = ui.guidance.rightM
    Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        if (mode == StakeMode.BY_DIRECTION && ahead != null && right != null) {
            Text(
                stringResource(if (ahead >= 0.0) R.string.stakeout_ahead else R.string.stakeout_back, numbers.length(abs(ahead))),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.testTag(STAKEOUT_AHEAD_TAG),
            )
            Text(
                stringResource(if (right >= 0.0) R.string.stakeout_right else R.string.stakeout_left, numbers.length(abs(right))),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.testTag(STAKEOUT_RIGHT_TAG),
            )
        } else {
            // Por distancia, o por dirección sin rumbo (S018/RF-11): las diferencias de Norte y Este.
            Text(
                stringResource(R.string.stakeout_north, numbers.signed(ui.guidance.dNorthM)),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.testTag(STAKEOUT_NORTH_TAG),
            )
            Text(
                stringResource(R.string.stakeout_east, numbers.signed(ui.guidance.dEastM)),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.testTag(STAKEOUT_EAST_TAG),
            )
        }
    }
}

private val ARROW_SIZE = 56.dp
