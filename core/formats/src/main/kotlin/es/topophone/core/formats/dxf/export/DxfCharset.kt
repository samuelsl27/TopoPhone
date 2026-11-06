package es.topophone.core.formats.dxf.export

import java.nio.charset.Charset

/**
 * Windows-1252, la codificación de 8 bits que entienden los CAD con R12 (S029/RF-15, plan D8). Un carácter que no cabe pasa a `?` y se cuenta;
 * un carácter de control (salto de línea, tabulador…) pasa a un espacio sin contarse, porque partiría los pares del fichero.
 */
object DxfCharset {
    val CHARSET: Charset = Charset.forName("windows-1252")
    private const val REPLACEMENT = '?'
    private const val CONTROL_LIMIT = 0x20
    private const val DELETE = 0x7F

    /** [text] listo para escribir y cuántos caracteres de él han sido sustituidos por `?`. */
    class Cleaned(val text: String, val replaced: Int)

    fun clean(text: String): Cleaned {
        val encoder = CHARSET.newEncoder()
        val out = StringBuilder(text.length)
        var replaced = 0
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            val chars = Character.charCount(cp)
            val piece = text.substring(i, i + chars)
            when {
                cp < CONTROL_LIMIT || cp == DELETE -> out.append(' ')
                encoder.canEncode(piece) -> out.append(piece)
                else -> {
                    out.append(REPLACEMENT)
                    replaced++
                }
            }
            i += chars
        }
        return Cleaned(out.toString(), replaced)
    }
}
