package es.topophone.app.ui.offline

import java.text.NumberFormat
import java.util.Locale

/** Cómo se escriben los tamaños, las teselas y las distancias de las zonas sin conexión (S037/RF-11); el separador es el del idioma de la interfaz. */
object OfflineFormat {
    private const val KB = 1024.0
    private const val MB = KB * 1024
    private const val GB = MB * 1024
    private const val SINGLE_DIGIT = 10
    private const val KM = 1000.0

    /** De KB a GB con un decimal por debajo de 10 y sin decimales por encima: `25 KB`, `1,5 MB`, `123 MB`, `2 GB`. */
    fun bytes(bytes: Long, locale: Locale): String {
        val (value, unit) = when {
            bytes >= GB -> bytes / GB to "GB"
            bytes >= MB -> bytes / MB to "MB"
            else -> maxOf(bytes / KB, 1.0).takeIf { bytes > 0 } to "KB"
        }
        return "${decimals(value ?: 0.0, locale)} $unit"
    }

    fun count(tiles: Long, locale: Locale): String = NumberFormat.getIntegerInstance(locale).format(tiles)

    /** `500 m` hasta 1 km y `2 km` o `2,5 km` desde ahí. */
    fun distance(metres: Double, locale: Locale): String =
        if (metres < KM) "${decimals(metres, locale, maxDecimals = 0)} m" else "${decimals(metres / KM, locale, maxDecimals = 1)} km"

    private fun decimals(value: Double, locale: Locale, maxDecimals: Int = if (value < SINGLE_DIGIT) 1 else 0): String =
        NumberFormat.getNumberInstance(locale).apply {
            maximumFractionDigits = maxDecimals
            minimumFractionDigits = 0
            isGroupingUsed = false
        }.format(value)
}
