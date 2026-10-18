package es.topophone.core.model.format

import es.topophone.core.model.project.AngularUnit
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

    /** Geográficas del proyecto (S008/RF-6): 6 decimales de segundo (0,03 mm) y 10 de grado o gon (0,01 mm). */
    private const val PROJECT_SECOND_DECIMALS = 6
    private const val PROJECT_DECIMALS = 10
    private const val CONVERGENCE_SECOND_DECIMALS = 2
    private const val CONVERGENCE_DECIMALS = 6
    private const val GON_PER_DEGREE = 400.0 / 360.0

    /** Latitud `40° 25′ 07,24408″ N` (S002/RF-34). */
    fun latitudeDms(latitudeDeg: Double, locale: Locale): String =
        dms(latitudeDeg, degreeDigits = 2, positive = "N", negative = "S", locale)

    /** Longitud `003° 42′ 13,64444″ W` (S002/RF-34). */
    fun longitudeDms(longitudeDeg: Double, locale: Locale): String =
        dms(longitudeDeg, degreeDigits = 3, positive = "E", negative = "W", locale)

    /** Grados decimales con 9 decimales y signo (S002/RF-34). */
    fun decimalDegrees(valueDeg: Double, locale: Locale): String = decimals(valueDeg, DEGREE_DECIMALS, locale, grouping = false)

    /** Latitud del proyecto en sus unidades angulares, con resolución de 0,1 mm o mejor (S008/RF-6). */
    fun latitude(latitudeDeg: Double, unit: AngularUnit, locale: Locale): String = geographic(latitudeDeg, unit, locale) {
        dms(latitudeDeg, degreeDigits = 2, positive = "N", negative = "S", locale, PROJECT_SECOND_DECIMALS)
    }

    /** Longitud del proyecto en sus unidades angulares (S008/RF-6). */
    fun longitude(longitudeDeg: Double, unit: AngularUnit, locale: Locale): String = geographic(longitudeDeg, unit, locale) {
        dms(longitudeDeg, degreeDigits = 3, positive = "E", negative = "W", locale, PROJECT_SECOND_DECIMALS)
    }

    /** Convergencia de meridianos con su signo: 0,01″ en DMS y 6 decimales en grados o gon (S008/RF-14). */
    fun convergence(valueRad: Double, unit: AngularUnit, locale: Locale): String {
        val degrees = Math.toDegrees(valueRad)
        return when (unit) {
            AngularUnit.DMS -> signedDms(degrees, locale)
            AngularUnit.DECIMAL_DEGREES -> decimals(degrees, CONVERGENCE_DECIMALS, locale, grouping = false) + "°"
            AngularUnit.GON -> decimals(degrees * GON_PER_DEGREE, CONVERGENCE_DECIMALS, locale, grouping = false) + " gon"
        }
    }

    private inline fun geographic(valueDeg: Double, unit: AngularUnit, locale: Locale, dms: () -> String): String = when (unit) {
        AngularUnit.DMS -> dms()
        AngularUnit.DECIMAL_DEGREES -> decimals(valueDeg, PROJECT_DECIMALS, locale, grouping = false) + "°"
        AngularUnit.GON -> decimals(valueDeg * GON_PER_DEGREE, PROJECT_DECIMALS, locale, grouping = false) + " gon"
    }

    /** `-2° 35′ 04,95″`: grados sin ceros a la izquierda y signo en lugar de hemisferio. */
    private fun signedDms(valueDeg: Double, locale: Locale): String {
        val (degrees, minutes, seconds) = sexagesimal(abs(valueDeg), CONVERGENCE_SECOND_DECIMALS, locale)
        val sign = if (valueDeg < 0 && (degrees != 0L || minutes != 0L || seconds.any { it in '1'..'9' })) "-" else ""
        return "$sign$degrees° ${minutes.toString().padStart(2, '0')}′ $seconds″"
    }

    /** Grados, minutos y segundos ya redondeados (los segundos como texto con su decimal). */
    private fun sexagesimal(absDeg: Double, secondDecimals: Int, locale: Locale): Triple<Long, Long, String> {
        // Se redondean los segundos totales una sola vez para que 59,999995″ pase a 1′ y 60′ a 1°.
        val scale = BigDecimal.TEN.pow(secondDecimals)
        val totalUnits = BigDecimal(absDeg).multiply(BigDecimal(3600)).multiply(scale).setScale(0, RoundingMode.HALF_UP).toLong()
        val unitsPerSecond = scale.toLong()
        val totalSeconds = totalUnits / unitsPerSecond
        val fraction = totalUnits % unitsPerSecond
        val separator = DecimalFormatSymbols.getInstance(locale).decimalSeparator
        val seconds = (totalSeconds % 60).toString().padStart(2, '0') + separator + fraction.toString().padStart(secondDecimals, '0')
        return Triple(totalSeconds / 3600, (totalSeconds % 3600) / 60, seconds)
    }

    private fun dms(
        valueDeg: Double,
        degreeDigits: Int,
        positive: String,
        negative: String,
        locale: Locale,
        secondDecimals: Int = SECOND_DECIMALS,
    ): String {
        // Se redondean los segundos totales una sola vez para que 59,999995″ pase a 1′ y 60′ a 1°.
        val scale = BigDecimal.TEN.pow(secondDecimals)
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
        val secondsText = seconds.toString().padStart(2, '0') + separator + fraction.toString().padStart(secondDecimals, '0')
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

/** Factor de escala y factor combinado de la proyección, con 8 decimales (S008/RF-13, RF-15). */
object FactorFormat {
    private const val FACTOR_DECIMALS = 8

    fun factor(value: Double, locale: Locale): String = decimals(value, FACTOR_DECIMALS, locale, grouping = false)
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
