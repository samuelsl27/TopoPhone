package es.topophone.core.model.alignment

import es.topophone.core.model.project.LinearUnit
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.DecimalFormatSymbols
import java.text.NumberFormat
import java.util.Locale
import kotlin.math.abs

/**
 * El PK con el formato del proyecto (S049/RNF-T9). En metros, kilómetros y metros: `1+240,50`. En pies, estaciones de 100 pies:
 * `40+69,88`. El valor se redondea una sola vez, así 999,996 m es `1+000,00` y no `0+1000,00`.
 */
object ChainageFormat {
    fun format(chainageM: Double, unit: LinearUnit, locale: Locale, decimals: Int = 2): String {
        val perStation = if (unit == LinearUnit.METRE) METRES_PER_KILOMETRE else FEET_PER_STATION
        val scaled = BigDecimal(unit.fromMetres(abs(chainageM))).setScale(decimals, RoundingMode.HALF_UP)
        val divisor = BigDecimal(perStation)
        val stations = scaled.divideToIntegralValue(divisor)
        val rest = scaled.subtract(stations.multiply(divisor))
        val restDigits = if (unit == LinearUnit.METRE) 3 else 2
        val format = NumberFormat.getNumberInstance(locale).apply {
            isGroupingUsed = false
            minimumFractionDigits = decimals
            maximumFractionDigits = decimals
            minimumIntegerDigits = restDigits
        }
        val negative = scaled.signum() != 0 && chainageM < 0
        return (if (negative) "-" else "") + stations.toBigInteger() + "+" + format.format(rest)
    }

    private const val METRES_PER_KILOMETRE = 1000
    private const val FEET_PER_STATION = 100
}

/** A qué lado del eje está un punto, en el sentido de los PK crecientes (S049/RNF-T8). */
enum class OffsetSide { LEFT, RIGHT, ON_AXIS }

/** El desplazamiento con signo (negativo a la izquierda, positivo a la derecha) escrito con su lado y no solo con el signo. */
object OffsetFormat {
    /** Por debajo de medio milímetro el punto está sobre el eje, que es la resolución con la que se escribe. */
    private const val ON_AXIS_M = 0.0005

    fun side(offsetM: Double): OffsetSide = when {
        abs(offsetM) < ON_AXIS_M -> OffsetSide.ON_AXIS
        offsetM < 0 -> OffsetSide.LEFT
        else -> OffsetSide.RIGHT
    }

    /** El valor absoluto del desplazamiento en la unidad del proyecto, con 3 decimales y sin el nombre de la unidad. */
    fun magnitude(offsetM: Double, unit: LinearUnit, locale: Locale, decimals: Int = 3): String {
        val rounded = BigDecimal(unit.fromMetres(abs(offsetM))).setScale(decimals, RoundingMode.HALF_UP)
        val symbols = DecimalFormatSymbols.getInstance(locale)
        val text = rounded.toPlainString()
        return if (symbols.decimalSeparator == '.') text else text.replace('.', symbols.decimalSeparator)
    }
}
