package es.topophone.app.ui.stakeout

import es.topophone.app.ui.map.StakeoutMarker
import es.topophone.core.geodesy.GeoPoint
import es.topophone.core.survey.StakeoutZones

/** Del estado del replanteo a lo que se dibuja en el mapa (S018/RF-14). */
object StakeoutMarkers {
    /**
     * El punto de diseño siempre; la punta con la guía encendida y la línea entre ellos solo a más de 40 cm, porque dentro de
     * la zona de ayuda lo enseña la diana. Sin replanteo o sin punto de diseño, nada.
     */
    fun of(state: StakeoutUiState?): StakeoutMarker? {
        val target = state?.target ?: return null
        val ground = target.coordinates.ground
        val design = GeoPoint(ground.latitudeDeg, ground.longitudeDeg)
        val ui = (state.guidance as? GuidanceState.On)?.ui ?: return StakeoutMarker(design, tip = null, line = false)
        val tip = ui.tip?.let { GeoPoint(it.latitudeDeg, it.longitudeDeg) }
        return StakeoutMarker(design, tip, line = tip != null && ui.guidance.distanceM > StakeoutZones.AID_RADIUS_M)
    }
}
