package es.topophone.app.ui.projects

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import es.topophone.app.R
import es.topophone.app.ui.SectionScaffold
import es.topophone.app.ui.map.MapController
import es.topophone.core.designsystem.component.MinTouchTarget
import es.topophone.core.geodesy.GeoPoint
import es.topophone.core.model.map.MapSettings
import es.topophone.core.model.map.MapViewport
import es.topophone.core.model.preferences.ThemeState
import es.topophone.core.model.project.CentralPoint
import es.topophone.core.survey.CentralPointRejection
import es.topophone.core.survey.CentralPointResult

const val CENTRAL_POINT_PICK_TAG = "project_central_point_pick"
const val CENTRAL_POINT_PICKER_TAG = "project_central_point_picker"
const val CENTRAL_POINT_PICKER_MAP_TAG = "project_central_point_picker_map"
const val CENTRAL_POINT_PICKER_ERROR_TAG = "project_central_point_picker_error"
const val CENTRAL_POINT_PICKER_ACCEPT_TAG = "project_central_point_picker_accept"
const val CENTRAL_POINT_PICKER_CANCEL_TAG = "project_central_point_picker_cancel"

/**
 * Elegir el punto central tocando el mapa, a pantalla completa (S006/RF-33). Un toque coloca el punto con su símbolo si cae
 * dentro del ámbito del SC del proyecto y, si no, indica el motivo y no lo coloca (RF-34); «Aceptar» lo pasa al formulario y
 * «Cancelar» vuelve sin cambiar nada. Empieza en [start] o, sin él, en España entera.
 */
@Composable
internal fun CentralPointPicker(
    themeState: ThemeState,
    onToggleSun: () -> Unit,
    map: MapSlot,
    settings: MapSettings,
    start: MapViewport?,
    current: CentralPoint?,
    resolve: (Double, Double) -> CentralPointResult,
    onAccept: (CentralPoint) -> Unit,
    onCancel: () -> Unit,
) {
    var controller by remember { mutableStateOf<MapController?>(null) }
    var picked by remember { mutableStateOf(current) }
    var rejection by remember { mutableStateOf<CentralPointRejection?>(null) }
    val label = stringResource(R.string.central_point_map_label)
    val resolveTap by rememberUpdatedState(resolve)
    LaunchedEffect(controller, settings) { controller?.setSettings(settings) }
    LaunchedEffect(controller) {
        val map = controller ?: return@LaunchedEffect
        if (start != null) map.moveTo(start) else map.showSpain()
    }
    LaunchedEffect(controller) {
        controller?.mapTaps?.collect { tap ->
            when (val result = resolveTap(tap.latitudeDeg, tap.longitudeDeg)) {
                is CentralPointResult.Fixed -> {
                    picked = result.point
                    rejection = null
                }
                is CentralPointResult.Rejected -> rejection = result.reason
            }
        }
    }
    LaunchedEffect(controller, picked, label) {
        controller?.showCentralPoint(picked?.let { GeoPoint(it.latitudeDeg, it.longitudeDeg) }, label)
    }
    SectionScaffold(
        title = stringResource(R.string.central_point_pick_title),
        themeState = themeState,
        onToggleSun = onToggleSun,
        onNavigateBack = onCancel,
    ) { padding ->
        Box(modifier = Modifier.padding(padding).fillMaxSize().testTag(CENTRAL_POINT_PICKER_TAG)) {
            map(Modifier.fillMaxSize().testTag(CENTRAL_POINT_PICKER_MAP_TAG)) { controller = it }
            Surface(
                modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth(),
                color = MaterialTheme.colorScheme.surface.copy(alpha = PANEL_ALPHA),
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.central_point_pick_hint))
                    rejection?.let {
                        Text(
                            stringResource(centralPointRejectionText(it)),
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.testTag(CENTRAL_POINT_PICKER_ERROR_TAG),
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = onCancel,
                            modifier = Modifier.weight(1f).heightIn(min = MinTouchTarget).testTag(CENTRAL_POINT_PICKER_CANCEL_TAG),
                        ) { Text(stringResource(R.string.dialog_cancel)) }
                        Button(
                            onClick = { picked?.let(onAccept) },
                            enabled = picked != null,
                            modifier = Modifier.weight(1f).heightIn(min = MinTouchTarget).testTag(CENTRAL_POINT_PICKER_ACCEPT_TAG),
                        ) { Text(stringResource(R.string.central_point_pick_accept)) }
                    }
                }
            }
        }
    }
}

/** Opaco lo justo para leer el texto sin tapar el mapa del todo (RNF-T8). */
private const val PANEL_ALPHA = 0.92f
