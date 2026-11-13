package es.topophone.app.ui.map

import kotlin.math.roundToInt

/**
 * Una pulsación larga en el mapa (S036/RF-34, A6 del plan): el punto en grados, sin redondear; el píxel de la vista que lo muestra, que
 * es el de la consulta de información de las capas; y la [area] visible en ese momento con su tamaño en píxeles, que es la que se envía a
 * los servicios. El mapa no gira ni se inclina, así que la zona visible es un rectángulo norte-sur.
 */
data class MapPress(val latitudeDeg: Double, val longitudeDeg: Double, val pixelX: Int, val pixelY: Int, val area: VisibleArea) {
    companion object {
        /** La pulsación en la posición de pantalla ([screenX], [screenY]); el píxel se redondea y no sale de la vista. */
        fun of(latitudeDeg: Double, longitudeDeg: Double, screenX: Float, screenY: Float, area: VisibleArea): MapPress = MapPress(
            latitudeDeg,
            longitudeDeg,
            screenX.roundToInt().coerceIn(0, maxOf(area.widthPx - 1, 0)),
            screenY.roundToInt().coerceIn(0, maxOf(area.heightPx - 1, 0)),
            area,
        )
    }
}
