package es.topophone.core.formats.kml.export

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Los números de un KML exportado, siempre con punto decimal y sin notación científica ni la configuración regional (S031/RNF-T9):
 * longitud y latitud con 9 decimales, que dan 0,1 mm (RF-24); la altura con 4 (RF-26); y el valor de un dato extendido tal como es,
 * sin redondear (RF-21).
 */
object KmlNumber {
    private const val COORDINATE_DECIMALS = 9
    private const val HEIGHT_DECIMALS = 4

    /** Longitud o latitud en grados con 9 decimales, redondeando el valor exacto del `Double` a la mitad par. */
    fun coordinate(value: Double): String = fixed(value, COORDINATE_DECIMALS)

    /** Altura en metros con 4 decimales. */
    fun height(value: Double): String = fixed(value, HEIGHT_DECIMALS)

    /** El `Double` con las cifras más cortas que lo recuperan exacto, sin exponente y sin ceros de más: `100`, `0.00001`, `440290.4580539461`. */
    fun plain(value: Double): String {
        require(value.isFinite()) { "Un número finito" }
        return BigDecimal.valueOf(value).stripTrailingZeros().toPlainString()
    }

    private fun fixed(value: Double, decimals: Int): String {
        require(value.isFinite()) { "Un número finito" }
        val text = BigDecimal(value).setScale(decimals, RoundingMode.HALF_EVEN).toPlainString()
        // Un valor que se redondea a cero no lleva el signo: -0,0000000001 es 0,000000000.
        return if (text.startsWith("-") && text.all { it == '-' || it == '0' || it == '.' }) text.substring(1) else text
    }
}
