package es.topophone.core.formats.dxf.export

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Los números del DXF (S029/RF-10, RNF-T9): punto decimal, sin separador de miles, sin notación científica y sin la configuración regional.
 * Se redondea el valor exacto del `Double`, así que el error de escribir es menor de medio paso: 0,05 mm a 4 decimales y 5e-10° a 9.
 */
object DxfNumberFormat {
    const val LINEAR_DECIMALS = 4
    const val DEGREE_DECIMALS = 9
    const val ELEVATION_DECIMALS = 3

    /** Un valor en la unidad lineal del proyecto, a 4 decimales. */
    fun linear(value: Double): String = format(value, LINEAR_DECIMALS)

    /** Un valor en grados, a 9 decimales. */
    fun degrees(value: Double): String = format(value, DEGREE_DECIMALS)

    /** El texto de una cota, a 3 decimales. */
    fun elevation(value: Double): String = format(value, ELEVATION_DECIMALS)

    internal fun format(value: Double, decimals: Int): String {
        require(value.isFinite()) { "Un valor no finito no se puede escribir en el DXF: $value" }
        val rounded = BigDecimal(value).setScale(decimals, RoundingMode.HALF_EVEN)
        // Un cero negativo ("-0.0000") no se escribe: el redondeo de un valor muy pequeño pierde el signo.
        return if (rounded.signum() == 0) BigDecimal.ZERO.setScale(decimals).toPlainString() else rounded.toPlainString()
    }
}
