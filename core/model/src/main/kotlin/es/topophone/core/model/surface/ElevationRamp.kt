package es.topophone.core.model.surface

import kotlin.math.floor

/**
 * La rampa de colores por cota de las superficies del mapa (S061/RF-10, RF-11): siete paradas (azul oscuro, azul, cian, verde,
 * amarillo, naranja y rojo) repartidas por igual entre [minM] y [maxM], en metros, interpoladas en RGB. Una cota fuera del rango
 * toma el color del extremo; si el mínimo y el máximo son iguales, todo es del color del centro. El color es opaco: la
 * transparencia la pone el mapa.
 */
class ElevationRamp(val minM: Double, val maxM: Double) {

    /** El color ARGB de [elevationM]; 0 (transparente) si no es un número. */
    fun argb(elevationM: Double): Int {
        if (elevationM.isNaN()) return 0
        val t = if (maxM <= minM) 0.5 else ((elevationM - minM) / (maxM - minM)).coerceIn(0.0, 1.0)
        val x = t * (STOPS.size - 1)
        val i = minOf(floor(x).toInt(), STOPS.size - 2)
        val f = x - i
        val a = STOPS[i]
        val b = STOPS[i + 1]
        var rgb = 0
        for (shift in intArrayOf(16, 8, 0)) {
            val ca = (a shr shift) and 0xFF
            val cb = (b shr shift) and 0xFF
            rgb = (rgb shl 8) or floor(ca + (cb - ca) * f + 0.5).toInt()
        }
        return OPAQUE or rgb
    }

    companion object {
        /** Las paradas en RGB, de la cota mínima a la máxima. */
        val STOPS = intArrayOf(0x1A237E, 0x1E88E5, 0x00BCD4, 0x43A047, 0xFDD835, 0xFB8C00, 0xE53935)

        private const val OPAQUE = 0xFF shl 24
    }
}
