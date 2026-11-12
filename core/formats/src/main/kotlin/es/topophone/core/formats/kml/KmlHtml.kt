package es.topophone.core.formats.kml

/**
 * El texto de una descripción de KML, que es HTML: sin etiquetas (S031/RF-11). `<br>` y el cierre de `<p>`, `<div>`, `<li>` y `<tr>` son
 * saltos de línea; el resto de las etiquetas se quita; después se resuelven las entidades (de modo que `&lt;b&gt;` queda como `<b>`), y
 * se quitan los espacios de los extremos de cada línea y las líneas vacías.
 */
object KmlHtml {
    private val lineBreaks = Regex("""<\s*br\s*/?\s*>|<\s*/\s*(?:p|div|li|tr)\s*>""", RegexOption.IGNORE_CASE)

    /** Una etiqueta empieza por una letra, `/`, `!` o `?`: un `<` suelto (`a < b`) es texto. */
    private val tags = Regex("""<[A-Za-z/!?][^>]*>""")

    private const val MAX_ENTITY_LENGTH = 12
    private const val HEX_RADIX = 16
    private const val DECIMAL_RADIX = 10

    /** Las entidades con nombre que se resuelven: las del HTML básico y las letras y signos de uso común en español. */
    private val NAMED = mapOf(
        "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to " ",
        "aacute" to "á", "eacute" to "é", "iacute" to "í", "oacute" to "ó", "uacute" to "ú",
        "Aacute" to "Á", "Eacute" to "É", "Iacute" to "Í", "Oacute" to "Ó", "Uacute" to "Ú",
        "agrave" to "à", "egrave" to "è", "igrave" to "ì", "ograve" to "ò", "ugrave" to "ù",
        "acirc" to "â", "ecirc" to "ê", "ocirc" to "ô", "ccedil" to "ç", "Ccedil" to "Ç",
        "ntilde" to "ñ", "Ntilde" to "Ñ", "uuml" to "ü", "Uuml" to "Ü", "ouml" to "ö", "auml" to "ä",
        "iexcl" to "¡", "iquest" to "¿", "ordm" to "º", "ordf" to "ª", "deg" to "°", "plusmn" to "±",
        "middot" to "·", "bull" to "•", "hellip" to "…", "ndash" to "–", "mdash" to "—",
        "lsquo" to "‘", "rsquo" to "’", "ldquo" to "“", "rdquo" to "”", "laquo" to "«", "raquo" to "»",
        "copy" to "©", "reg" to "®", "trade" to "™", "euro" to "€", "sup2" to "²", "sup3" to "³", "frac12" to "½",
    )

    /** El texto de [html] sin etiquetas, con las entidades resueltas y sin líneas vacías. */
    fun toText(html: CharSequence): String {
        if (html.isEmpty()) return ""
        var text = lineBreaks.replace(html, "\n")
        text = tags.replace(text, "")
        text = unescape(text)
        return text.replace("\r\n", "\n").replace('\r', '\n').lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.joinToString("\n")
    }

    private fun unescape(text: String): String {
        if (text.indexOf('&') < 0) return text
        val out = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c == '&') {
                val end = text.indexOf(';', i + 1)
                if (end > i + 1 && end - i <= MAX_ENTITY_LENGTH) {
                    val resolved = resolve(text.substring(i + 1, end))
                    if (resolved != null) {
                        out.append(resolved)
                        i = end + 1
                        continue
                    }
                }
            }
            out.append(c)
            i++
        }
        return out.toString()
    }

    private fun resolve(name: String): String? {
        if (name[0] != '#') return NAMED[name]
        val hex = name.length > 1 && (name[1] == 'x' || name[1] == 'X')
        val digits = name.substring(if (hex) 2 else 1)
        val code = digits.toIntOrNull(if (hex) HEX_RADIX else DECIMAL_RADIX) ?: return null
        return if (Character.isValidCodePoint(code) && code != 0) String(Character.toChars(code)) else null
    }
}
