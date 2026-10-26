package es.topophone.app.ui.stakeout

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import es.topophone.app.R
import es.topophone.app.ui.projects.unitSymbol
import es.topophone.app.ui.receiver.uiLocale
import es.topophone.core.designsystem.component.MinTouchTarget
import es.topophone.core.model.format.LengthFormat
import es.topophone.core.model.point.ElevationSource
import es.topophone.core.model.project.LinearUnit
import es.topophone.core.model.surface.SurfaceHeight

const val STAKEOUT_SOURCE_POINT_TAG = "stakeout_source_point"
const val STAKEOUT_SOURCE_SURFACE_TAG = "stakeout_source_surface"
const val STAKEOUT_SURFACE_HEIGHT_TAG = "stakeout_surface_height"
const val STAKEOUT_SURFACE_OFFSET_TAG = "stakeout_surface_offset"
const val STAKEOUT_SURFACE_REMOVED_TAG = "stakeout_surface_removed"

/**
 * Contra qué cota se replantea (S042/RF-23 a RF-27): el aviso de que la superficie ya no está activa; el conmutador «Cota del punto» /
 * «Superficie» cuando hay una superficie activa y una cota propia o escrita con la que elegir; y, replanteando contra la superficie,
 * su cota bajo la punta y el desplazamiento vertical. «Fuera de la superficie» sale en el nivel vertical (RF-23). El conmutador no
 * cambia con una medida en marcha.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ElevationSourceSection(
    surfaceActive: Boolean,
    source: ElevationSource,
    canChoose: Boolean,
    surfaceRemoved: Boolean,
    measuring: Boolean,
    guidance: GuidanceState,
    unit: LinearUnit,
    onSource: (ElevationSource) -> Unit,
) {
    if (surfaceRemoved) {
        Text(
            stringResource(R.string.stakeout_surface_removed),
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }.testTag(STAKEOUT_SURFACE_REMOVED_TAG),
        )
    }
    if (!surfaceActive) return
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (canChoose) {
            Text(stringResource(R.string.stakeout_source_title), style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = source == ElevationSource.POINT,
                    onClick = { onSource(ElevationSource.POINT) },
                    enabled = !measuring,
                    label = { Text(stringResource(R.string.stakeout_source_point)) },
                    modifier = Modifier.heightIn(min = MinTouchTarget).testTag(STAKEOUT_SOURCE_POINT_TAG),
                )
                FilterChip(
                    selected = source == ElevationSource.SURFACE,
                    onClick = { onSource(ElevationSource.SURFACE) },
                    enabled = !measuring,
                    label = { Text(stringResource(R.string.stakeout_source_surface)) },
                    modifier = Modifier.heightIn(min = MinTouchTarget).testTag(STAKEOUT_SOURCE_SURFACE_TAG),
                )
            }
        }
        if (source == ElevationSource.SURFACE) SurfaceReading(guidance, unit)
    }
}

/** La cota de la superficie bajo la punta y el desplazamiento vertical, con la guía encendida y la punta dentro de la superficie. */
@Composable
private fun SurfaceReading(guidance: GuidanceState, unit: LinearUnit) {
    val surface = (guidance as? GuidanceState.On)?.ui?.surface ?: return
    val value = surface.height as? SurfaceHeight.Value ?: return
    val locale = uiLocale()
    val symbol = unitSymbol(unit)

    fun length(metres: Double) = "${LengthFormat.inUnit(metres, unit, locale)} $symbol"
    Text(
        stringResource(R.string.stakeout_surface_height, length(value.metres)),
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.testTag(STAKEOUT_SURFACE_HEIGHT_TAG),
    )
    Text(
        stringResource(R.string.stakeout_surface_offset, length(surface.offsetM)),
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.testTag(STAKEOUT_SURFACE_OFFSET_TAG),
    )
}
