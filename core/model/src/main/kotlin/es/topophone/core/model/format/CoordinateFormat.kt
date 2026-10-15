package es.topophone.core.model.format

import es.topophone.core.model.project.LinearUnit
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.DecimalFormatSymbols
import java.text.NumberFormat
import java.util.Locale
import kotlin.math.abs

/**
 * Formatos de ángulos para la interfaz, con la configuración regional del usuario (RNF-T9).
 * Los ficheros usan siempre `Locale.ROOT` y no pasan por aquí.
 */
object AngleFormat {

    private const val SECOND_DECIMALS = 5
    private const val DEGREE_DECIMALS = 9

    /** Latitud `40° 25′ 07,24408″ N` (S002/RF-34). */
    fun latitudeDms(latitudeDeg: Double, locale: Locale): String =
        dms(latitudeDeg, degreeDigits = 2, positive = "N", negative = "S", locale)

    /** Longitud `003° 42′ 13,64444″ W` (S002/RF-34). */
    fun longitudeDms(longitudeDeg: Double, locale: Locale): String =
        dms(longitudeDeg, degreeDigits = 3, positive = "E", negative = "W", locale)

    /** Grados decimales con 9 decimales y signo (S002/RF-34). */
    fun decimalDegrees(valueDeg: Double, locale: Locale): String = decimals(valueDeg, DEGREE_DECIMALS, locale, grouping = false)

    private fun dms(valueDeg: Double, degreeDigits: Int, positive: String, negative: String, locale: Locale): String {
        // Se redondean los segundos totales una sola vez para que 59,999995″ pase a 1′ y 60′ a 1°.
        val scale = BigDecimal.TEN.pow(SECOND_DECIMALS)
        val totalUnits = BigDecimal(abs(valueDeg)).multiply(BigDecimal(3600)).multiply(scale).setScale(0, RoundingMode.HALF_UP).toLong()
        val unitsPerSecond = scale.toLong()
        val totalSeconds = totalUnits / unitsPerSecond
        val fraction = totalUnits % unitsPerSecond
        val degrees = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        val separator = DecimalFormatSymbols.getInstance(locale).decimalSeparator
        val hemisphere = if (valueDeg < 0 && totalUnits != 0L) negative else positive
        val degreesText = degrees.toString().padStart(degreeDigits, '0')
        val secondsText = seconds.toString().padStart(2, '0') + separator + fraction.toString().padStart(SECOND_DECIMALS, '0')
        return "$degreesText° ${minutes.toString().padStart(2, '0')}′ $secondsText″ $hemisphere"
    }
}

/** Longitudes para la interfaz (S002/RF-35, S002/RF-36, S002/RF-38). */
object LengthFormat {
    fun metres(valueM: Double, locale: Locale, decimals: Int = 3): String = decimals(valueM, decimals, locale, grouping = true) + " m"

    /**
     * Una longitud en metros, pasada a la unidad del proyecto, con 3 decimales y sin el nombre de la
     * unidad, que es un texto de la interfaz (S006/RF-9).
     */
    fun inUnit(valueM: Double, unit: LinearUnit, locale: Locale, decimals: Int = 3): String =
        decimals(unit.fromMetres(valueM), decimals, locale, grouping = true)
}

private fun decimals(value: Double, digits: Int, locale: Locale, grouping: Boolean): String {
    val rounded = BigDecimal(value).setScale(digits, RoundingMode.HALF_UP)
    val normalized = if (rounded.signum() == 0) BigDecimal.ZERO.setScale(digits) else rounded
    val format = NumberFormat.getNumberInstance(locale).apply {
        minimumFractionDigits = digits
        maximumFractionDigits = digits
        isGroupingUsed = grouping
    }
    return format.format(normalized)
}
