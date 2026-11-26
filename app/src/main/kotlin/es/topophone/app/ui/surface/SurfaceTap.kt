package es.topophone.app.ui.surface

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import es.topophone.app.R
import es.topophone.app.ui.projects.unitSymbol
import es.topophone.app.ui.receiver.uiLocale
import es.topophone.core.model.format.LengthFormat
import es.topophone.core.model.project.LinearUnit
import es.topophone.core.model.surface.Surface
import es.topophone.core.model.surface.SurfaceHeight
import es.topophone.core.model.surface.SurfaceKind
import es.topophone.data.surface.RasterProjectContext
import es.topophone.data.surface.SurfaceProbe

const val SURFACE_TAP_SHEET_TAG = "surface_tap_sheet"

fun surfaceTapRowTag(surfaceId: String) = "surface_tap_$surfaceId"

/** Una superficie bajo el toque: su nombre, su tipo, si es la activa y su cota allí, o null si cae en un hueco o sin dato (S061/RF-16 a RF-18). */
data class SurfaceTapRow(val surfaceId: String, val name: String, val kind: SurfaceKind, val active: Boolean, val heightM: Double?)

/** Lo que dice la tarjeta de un toque sobre las superficies visibles: una fila por superficie (S061/RF-16, RF-17). */
data class SurfaceTapUi(val rows: List<SurfaceTapRow>)

/**
 * Qué superficies visibles hay bajo un toque del mapa y su cota allí (S061/RF-16 a RF-18, plan A7): las que contienen la posición en
 * su recuadro, con la cota de la superficie (sin el desplazamiento vertical) o null si la posición cae en un hueco, fuera del contorno
 * o en una celda sin dato. Null si ninguna superficie visible está bajo el toque.
 */
class SurfaceTapResolver(private val probe: SurfaceProbe) {
    suspend fun resolve(
        surfaces: List<Surface>,
        activeId: String?,
        easting: Double,
        northing: Double,
        context: RasterProjectContext,
    ): SurfaceTapUi? {
        val under = surfaces.filter { it.visible && it.extent.contains(easting, northing) }
        if (under.isEmpty()) return null
        return SurfaceTapUi(
            under.map { surface ->
                val height = probe.heightAt(surface, easting, northing, context) as? SurfaceHeight.Value
                SurfaceTapRow(surface.id, surface.name, surface.kind, surface.id == activeId, height?.metres)
            },
        )
    }
}

/** La tarjeta de un toque sobre una o varias superficies visibles (S061/RF-16 a RF-18). La cota va en las unidades del proyecto. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SurfaceTapSheet(ui: SurfaceTapUi, unit: LinearUnit, onDismiss: () -> Unit) {
    val locale = uiLocale()
    val symbol = unitSymbol(unit)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            modifier = Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 24.dp)
                .testTag(SURFACE_TAP_SHEET_TAG),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            ui.rows.forEachIndexed { index, row ->
                if (index > 0) HorizontalDivider()
                Column(modifier = Modifier.testTag(surfaceTapRowTag(row.surfaceId)), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(row.name, style = MaterialTheme.typography.titleMedium)
                    Text(
                        stringResource(if (row.kind == SurfaceKind.RASTER) R.string.surface_kind_raster else R.string.surface_kind_tin),
                        style = MaterialTheme.typography.labelLarge,
                    )
                    // RNF-T8: que es la activa va en texto, no solo en color.
                    if (row.active) Text(stringResource(R.string.surface_state_active), color = MaterialTheme.colorScheme.primary)
                    val height = row.heightM
                    if (height != null) {
                        Text(stringResource(R.string.stakeout_surface_height, "${LengthFormat.inUnit(height, unit, locale)} $symbol"))
                    } else {
                        Text(stringResource(R.string.stakeout_level_outside_surface)) // RF-18
                    }
                }
            }
        }
    }
}
