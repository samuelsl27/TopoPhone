package es.topophone.app.ui.stakeout

import es.topophone.app.ui.map.StakeoutMarker
import es.topophone.core.geodesy.GeoPoint
import es.topophone.core.survey.StakeoutZones

/** Del estado del replanteo por PK a lo que se dibuja en el mapa (S054/RF-1). */
object AxisStakeoutMarkers {
    /**
     * El punto de diseño del PK y el desplazamiento elegidos; la punta con la guía encendida, y la línea entre ellos solo a más de
     * 40 cm, porque dentro de la zona de ayuda lo enseña la diana (S018/RF-14). Sin replanteo abierto, cerrado por el borrado del eje o
     * sin punto de diseño, nada.
     */
    fun of(state: AxisStakeoutUiState?): StakeoutMarker? {
        if (state == null || !state.started || state.closed != null) return null
        val ground = state.stakeTarget?.coordinates?.ground ?: return null
        val design = GeoPoint(ground.latitudeDeg, ground.longitudeDeg)
        val ui = (state.guidance as? GuidanceState.On)?.ui ?: return StakeoutMarker(design, tip = null, line = false)
        val tip = ui.tip?.let { GeoPoint(it.latitudeDeg, it.longitudeDeg) }
        return StakeoutMarker(design, tip, line = tip != null && ui.guidance.distanceM > StakeoutZones.AID_RADIUS_M)
    }
}
