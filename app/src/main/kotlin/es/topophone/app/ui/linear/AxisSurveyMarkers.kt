package es.topophone.app.ui.linear

import es.topophone.app.ui.map.StakeoutMarker
import es.topophone.core.geodesy.GeoPoint

/** De la toma de un eje a lo que se dibuja en el mapa (S052/RF-3). */
object AxisSurveyMarkers {
    /**
     * Los puntos incluidos de la toma unidos en el orden de medida, con la línea gruesa de una línea elegida y sin guía de replanteo.
     * Sin toma abierta o sin ningún punto, nada.
     */
    fun of(state: AxisSurveyUiState?): StakeoutMarker? {
        if (state?.survey == null) return null
        val line = state.included.map { GeoPoint(it.latitudeDeg, it.longitudeDeg) }
        return if (line.isEmpty()) null else StakeoutMarker(design = null, tip = null, line = false, chosen = line)
    }
}
