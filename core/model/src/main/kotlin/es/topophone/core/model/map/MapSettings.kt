package es.topophone.core.model.map

import es.topophone.core.model.project.LinearUnit
import java.math.BigDecimal
import java.text.NumberFormat
import java.util.Locale
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow

/** Mapa base del mapa del proyecto (S009/RF-6). */
enum class Basemap { IGN_VECTOR, OSM_VECTOR, PNOA_ORTHOPHOTO, NONE }

/** Mapa base y Catastro, los mismos para todos los proyectos (S009/RF-6 a RF-8). */
data class MapSettings(val basemap: Basemap = Basemap.IGN_VECTOR, val cadastre: Boolean = false)

/** Encuadre del mapa: centro en geográficas y zoom (S009/RF-21). */
data class MapViewport(val latitudeDeg: Double, val longitudeDeg: Double, val zoom: Double)

/** Longitud de la barra de escala en la unidad del proyecto y su ancho en píxeles (S009/RF-10). */
data class ScaleBarLength(val value: Double, val unit: LinearUnit, val widthPx: Float) {
    /** El valor sin ceros de sobra, con el separador del idioma ("0,5", "20.000"). */
    fun valueText(locale: Locale): String {
        val decimal = BigDecimal.valueOf(value).stripTrailingZeros()
        return NumberFormat.getNumberInstance(locale).apply {
            maximumFractionDigits = decimal.scale().coerceAtLeast(0)
            isGroupingUsed = true
        }.format(decimal)
    }
}

/** Barra de escala del mapa en las unidades lineales del proyecto (S009/RF-10, RF-12). */
object ScaleBar {
    private val MANTISSAS = doubleArrayOf(5.0, 2.0, 1.0)

    /** La mayor longitud de 1, 2 o 5 × 10ⁿ en [unit] cuyo ancho no pasa de [maxWidthPx]. */
    fun choose(metresPerPixel: Double, maxWidthPx: Float, unit: LinearUnit): ScaleBarLength {
        require(metresPerPixel > 0.0 && maxWidthPx > 0f) { "Escala no válida: $metresPerPixel m/px, $maxWidthPx px" }
        val maxValue = unit.fromMetres(metresPerPixel * maxWidthPx)
        var power = 10.0.pow(floor(log10(maxValue)))
        while (true) {
            MANTISSAS.forEach { mantissa ->
                val value = mantissa * power
                if (value <= maxValue) return ScaleBarLength(value, unit, (value * unit.metres / metresPerPixel).toFloat())
            }
            power /= 10
        }
    }
}
