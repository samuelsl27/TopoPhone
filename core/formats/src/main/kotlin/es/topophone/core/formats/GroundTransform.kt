package es.topophone.core.formats

import java.math.BigDecimal
import java.util.Locale

/**
 * El paso de cuadrícula a terreno que usa un fichero al exportar (S058/RF-13, RF-14): una homotecia de factor [factor] respecto al origen de
 * cuadrícula ([originEasting], [originNorthing], en metros). Lo construye quien exporta desde el plano del proyecto; los formatos solo lo aplican y
 * escriben su comentario de cabecera.
 */
data class GroundTransform(val originEasting: Double, val originNorthing: Double, val factor: Double) {
    /** El Este de terreno de un punto de cuadrícula. */
    fun easting(gridEasting: Double): Double = originEasting + factor * (gridEasting - originEasting)

    /** El Norte de terreno de un punto de cuadrícula. */
    fun northing(gridNorthing: Double): Double = originNorthing + factor * (gridNorthing - originNorthing)

    /** Una distancia o un delta de cuadrícula, en terreno. */
    fun distance(gridMetres: Double): Double = gridMetres * factor

    /**
     * El texto del comentario de cabecera, sin marca de comentario: dice que las coordenadas son de terreno y da el origen de cuadrícula (en la unidad
     * dada por [fromMetres] y [unitCode]) y el factor, con punto decimal y sin comas, puntos y comas ni tabuladores para no partir la línea (RNF-T9).
     */
    fun description(fromMetres: (Double) -> Double, unitCode: String): String {
        fun number(value: Double, decimals: Int) = BigDecimal(value).setScale(decimals, java.math.RoundingMode.HALF_UP).toPlainString()
        return String.format(
            Locale.ROOT,
            "$COMMENT_TEXT origin easting=%s northing=%s grid units=%s factor=%s",
            number(fromMetres(originEasting), ORIGIN_DECIMALS),
            number(fromMetres(originNorthing), ORIGIN_DECIMALS),
            unitCode,
            number(factor, FACTOR_DECIMALS),
        )
    }

    companion object {
        /** El principio del comentario de cabecera: lo que reconoce quien lee un fichero exportado en terreno (S058/RF-12, RF-13). */
        const val COMMENT_TEXT = "TopoPhone ground coordinates"

        private const val ORIGIN_DECIMALS = 4
        private const val FACTOR_DECIMALS = 10
    }
}
