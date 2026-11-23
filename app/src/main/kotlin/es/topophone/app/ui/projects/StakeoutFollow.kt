package es.topophone.app.ui.projects

import android.os.SystemClock
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import es.topophone.app.R
import es.topophone.app.ui.map.MapController
import es.topophone.app.ui.map.StakeoutCameraPolicy
import es.topophone.app.ui.slope.SlopeStakeUiState
import es.topophone.app.ui.stakeout.AxisStakeoutUiState
import es.topophone.app.ui.stakeout.GuidanceState
import es.topophone.app.ui.stakeout.LineStakeoutUiState
import es.topophone.app.ui.stakeout.StakeoutUiState
import es.topophone.core.designsystem.component.MinTouchTarget
import es.topophone.core.geodesy.GeoPoint
import es.topophone.core.survey.HeadingResult

const val STAKEOUT_RECENTER_TAG = "stakeout_recenter"

/** El rumbo de marcha de la guía del replanteo abierto, o null si no hay replanteo o no hay rumbo (S060/RF-25). */
internal fun stakeoutHeadingRad(
    stakeout: StakeoutUiState?,
    line: LineStakeoutUiState?,
    axis: AxisStakeoutUiState?,
    slope: SlopeStakeUiState?,
): Double? {
    val guidance = stakeout?.guidance ?: line?.guidance ?: axis?.guidance ?: slope?.guidance
    val ui = (guidance as? GuidanceState.On)?.ui ?: return null
    return (ui.heading as? HeadingResult.Available)?.rad
}

/**
 * El mapa que sigue al jalón mientras se replantea (S060/RF-25, RF-26): centra en la posición, ajusta el zoom al destino y gira hacia la
 * marcha con [StakeoutCameraPolicy]. Mover o acercar el mapa a mano deja de seguir y saca «Recentrar»; al cerrar el replanteo vuelve el
 * norte arriba. [tip] es la punta del jalón (o la antena si aún no hay) y [design] el destino.
 */
@Composable
internal fun StakeoutFollow(
    controller: MapController?,
    active: Boolean,
    tip: GeoPoint?,
    design: GeoPoint?,
    headingRad: Double?,
    modifier: Modifier = Modifier,
) {
    val camera = remember { StakeoutCameraPolicy() }
    var following by remember { mutableStateOf(true) }
    if (controller != null) {
        LaunchedEffect(controller, active) {
            camera.reset()
            following = true
            if (!active) controller.resetBearing() // al cerrar el replanteo, el norte arriba
        }
        LaunchedEffect(controller, active) {
            controller.userGestures.collect {
                if (active) {
                    camera.onUserGesture()
                    following = false
                }
            }
        }
        LaunchedEffect(controller, active, tip, design, headingRad, following) {
            if (!active || tip == null) return@LaunchedEffect
            camera.next(tip, design, headingRad, controller.visibleShortSideDp, SystemClock.elapsedRealtime())?.let(controller::follow)
        }
    }
    if (active && !following) {
        Button(
            onClick = {
                camera.recenter()
                following = true
            },
            modifier = modifier.padding(top = 12.dp).heightIn(min = MinTouchTarget).testTag(STAKEOUT_RECENTER_TAG),
        ) { Text(stringResource(R.string.field_recenter)) }
    }
}
