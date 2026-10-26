package es.topophone.app.ui.stakeout

import es.topophone.app.ui.map.StakeoutMarker
import es.topophone.core.geodesy.GeoPoint
import es.topophone.core.survey.CadPlacement
import es.topophone.core.survey.StakeoutZones

/** Del estado del replanteo de una línea a lo que se dibuja en el mapa (S041/RF-5). */
object LineStakeoutMarkers {
    /**
     * La línea elegida ([chosen], en geográficas) más gruesa que las demás; el objetivo de ahora (el vértice, el punto más cercano
     * o el punto de la serie); la punta con la guía encendida, y la línea de la punta al objetivo solo a más de 40 cm, porque
     * dentro de la zona de ayuda lo enseña la diana (S018/RF-14). Con la guía de la línea apagada no hay objetivo y solo se ve la
     * línea elegida. Sin replanteo o cerrado, nada.
     */
    fun of(state: LineStakeoutUiState?, chosen: List<GeoPoint>): StakeoutMarker? {
        if (state == null || state.element == null || state.closed != null) return null
        val project = state.project
        val ui = (state.guidance as? GuidanceState.On)?.ui
        val moving = ui?.objective
        val design = when {
            moving != null && project != null ->
                CadPlacement.geographic(project.crs, moving.easting, moving.northing)?.let { GeoPoint(it.first, it.second) }
            ui != null -> state.target?.coordinates?.ground?.let { GeoPoint(it.latitudeDeg, it.longitudeDeg) }
            else -> null
        }
        val tip = ui?.tip?.let { GeoPoint(it.latitudeDeg, it.longitudeDeg) }
        return StakeoutMarker(
            design,
            tip,
            line = design != null && tip != null && ui.guidance.distanceM > StakeoutZones.AID_RADIUS_M,
            chosen = chosen,
        )
    }
}
