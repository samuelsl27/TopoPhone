package es.topophone.core.formats.kml

import java.util.Locale

/**
 * Los colores de un KML: ocho cifras hexadecimales `aabbggrr` (transparencia, azul, verde, rojo), al revés que el `#rrggbb` de la web
 * (S031/RF-12, RF-23). La app usa ARGB opaco: la transparencia de un KML se ignora al leerlo.
 */
object KmlColors {
    private const val HEX_DIGITS = 8
    private const val BYTE = 0xFF
    private const val OPAQUE = 0xFF

    /** El color `aabbggrr` de [text] como ARGB opaco, o `null` si no son ocho cifras hexadecimales. */
    fun parse(text: String?): Int? {
        val trimmed = text?.trim() ?: return null
        if (trimmed.length != HEX_DIGITS || !trimmed.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) return null
        val value = trimmed.toLong(HEX_RADIX)
        val blue = ((value shr 16) and BYTE.toLong()).toInt()
        val green = ((value shr 8) and BYTE.toLong()).toInt()
        val red = (value and BYTE.toLong()).toInt()
        return (OPAQUE shl 24) or (red shl 16) or (green shl 8) or blue
    }

    /** El color [argb] como `aabbggrr` en minúsculas, con la transparencia [alpha] (la del color se ignora). */
    fun toKml(argb: Int, alpha: Int = OPAQUE): String = String.format(
        Locale.ROOT,
        "%02x%02x%02x%02x",
        alpha and BYTE,
        argb and BYTE,
        (argb shr 8) and BYTE,
        (argb shr 16) and BYTE,
    )

    private const val HEX_RADIX = 16
}
