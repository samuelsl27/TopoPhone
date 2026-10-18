package es.topophone.core.geodesy

import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * Cuadrícula del fondo neutro del mapa: meridianos y paralelos rectos en Web Mercator, separados
 * [spacingM] metros del terreno en el centro de la zona visible (S009/RF-14).
 */
object MapGrid {
    /** Entre meridianos y paralelos; si no caben, se duplica la separación. */
    const val MAX_LINES = 40

    /**
     * Líneas de la zona visible, cada una con sus dos extremos. Se anclan en múltiplos de la separación desde
     * el ecuador y el meridiano 0, y la separación se fija con la latitud del centro redondeada a 0,1°: al
     * mover el mapa, las líneas no se desplazan.
     */
    fun lines(south: Double, west: Double, north: Double, east: Double, spacingM: Double): List<List<GeoPoint>> {
        require(spacingM > 0.0) { "La separación debe ser positiva: $spacingM" }
        val referencePhi = Math.toRadians(((south + north) / 2 * 10).roundToInt() / 10.0)
        var step = spacingM * WebMercator.RADIUS_M / (Geodesy.primeVerticalRadius(referencePhi) * cos(referencePhi))
        val (x0, y0) = WebMercator.forward(south, west)
        val (x1, y1) = WebMercator.forward(north, east)
        while (multiples(x0, x1, step).count() + multiples(y0, y1, step).count() > MAX_LINES) step *= 2
        val meridians = multiples(x0, x1, step).map { k ->
            val longitude = WebMercator.inverse(k * step, 0.0).longitudeDeg
            listOf(GeoPoint(south, longitude), GeoPoint(north, longitude))
        }
        val parallels = multiples(y0, y1, step).map { k ->
            val latitude = WebMercator.inverse(0.0, k * step).latitudeDeg
            listOf(GeoPoint(latitude, west), GeoPoint(latitude, east))
        }
        return meridians + parallels
    }

    private fun multiples(from: Double, to: Double, step: Double): LongRange = ceil(from / step).toLong()..floor(to / step).toLong()
}
