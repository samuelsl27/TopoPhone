package es.topophone.core.survey

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow

/**
 * Las marcas de PK que se rotulan en el mapa (S049/RF-17): a un intervalo redondo (1, 2 o 5 por una potencia de diez) que depende de la
 * escala del mapa, de modo que dos marcas queden separadas por unos [MIN_SPACING_PX] píxeles como mínimo.
 */
object ChainageMarks {
    /** La separación mínima entre dos marcas sobre la pantalla: lo que cabe de una etiqueta como `12+340` con el dedo al lado. */
    const val MIN_SPACING_PX = 90.0

    /** Como mucho este número de marcas por eje y vista, para no frenar el mapa (RNF-T13). */
    const val MAX_MARKS = 400

    private val STEPS = doubleArrayOf(1.0, 2.0, 5.0)

    /** El menor intervalo redondo, en metros, con el que dos marcas distan al menos [MIN_SPACING_PX] píxeles a [metresPerPixel] metros por píxel. */
    fun interval(metresPerPixel: Double, minSpacingPx: Double = MIN_SPACING_PX): Double {
        require(metresPerPixel > 0.0 && minSpacingPx > 0.0) { "La escala y la separación tienen que ser positivas" }
        val wanted = metresPerPixel * minSpacingPx
        val exponent = floor(log10(wanted)).toInt()
        for (e in exponent..exponent + 1) {
            for (step in STEPS) {
                val candidate = step * 10.0.pow(e)
                if (candidate >= wanted * (1 - ROUNDING)) return candidate
            }
        }
        return 10.0.pow(exponent + 1)
    }

    /** Los PK múltiplos de [interval] dentro de [start, end], ambos incluidos; vacío si habría más de [maxMarks]. */
    fun chainages(start: Double, end: Double, interval: Double, maxMarks: Int = MAX_MARKS): List<Double> {
        require(interval > 0.0) { "El intervalo tiene que ser positivo" }
        if (end < start) return emptyList()
        val first = ceil((start - ROUNDING_M) / interval).toLong()
        val last = floor((end + ROUNDING_M) / interval).toLong()
        if (last < first || last - first + 1 > maxMarks) return emptyList()
        return (first..last).map { it * interval }
    }

    private const val ROUNDING = 1e-9
    private const val ROUNDING_M = 1e-6
}
