package es.topophone.core.formats.shp

import java.util.Locale

/**
 * Un nodo del WKT de un `.prj`: una palabra clave y sus valores (S030/RF-8). Cada valor es un texto (entre comillas o una palabra sin
 * corchetes, como `EAST`), un número o un nodo anidado.
 */
class WktNode(val keyword: String, val items: List<Any>) {
    val children: List<WktNode> get() = items.filterIsInstance<WktNode>()

    /** El primer hijo con esa palabra clave, sin distinguir mayúsculas. */
    fun child(keyword: String): WktNode? = children.firstOrNull { it.keyword.equals(keyword, ignoreCase = true) }

    /** El valor de texto que está en la posición [index] entre los valores, o null si no lo es. */
    fun text(index: Int): String? = items.getOrNull(index) as? String

    fun number(index: Int): Double? = items.getOrNull(index) as? Double

    /** El valor del `PARAMETER["nombre", valor]` con ese nombre, sin distinguir mayúsculas. */
    fun parameter(name: String): Double? =
        children.firstOrNull { it.keyword == "PARAMETER" && it.text(0).equals(name, ignoreCase = true) }?.number(1)
}

/**
 * Lee el WKT de un `.prj` (S030/RF-8): corchetes o paréntesis, textos entre comillas con la comilla doble duplicada, números con signo y
 * exponente y palabras sin corchetes como `NORTH`. Vale para el WKT1 de ESRI, de GDAL y de OGC. Un texto que no es un WKT entero, o que
 * se pasa de largo o de profundo, da null.
 */
object PrjReader {
    private const val MAX_CHARS = 200_000
    private const val MAX_DEPTH = 16
    private val NUMBER_CHARS = "0123456789+-.eE"

    fun parse(text: String): WktNode? {
        if (text.isBlank() || text.length > MAX_CHARS) return null
        val parser = Parser(text)
        val node = parser.node(0) ?: return null
        parser.skipSpaces()
        return node.takeIf { parser.atEnd }
    }

    private class Parser(val text: String) {
        var at = 0
        val atEnd: Boolean get() = at >= text.length

        fun skipSpaces() {
            while (at < text.length && text[at].isWhitespace()) at++
        }

        fun node(depth: Int): WktNode? {
            if (depth > MAX_DEPTH) return null
            skipSpaces()
            val start = at
            while (at < text.length && (text[at].isLetterOrDigit() || text[at] == '_')) at++
            if (at == start || !text[start].isLetter()) return null
            val keyword = text.substring(start, at).uppercase(Locale.ROOT)
            skipSpaces()
            val close = when (text.getOrNull(at)) {
                '[' -> ']'
                '(' -> ')'
                else -> return null
            }
            at++
            val items = ArrayList<Any>()
            while (true) {
                skipSpaces()
                when (val c = text.getOrNull(at) ?: return null) {
                    close -> {
                        at++
                        return WktNode(keyword, items)
                    }
                    ',' -> at++
                    '"' -> items += quoted() ?: return null
                    else -> items += (if (c in NUMBER_CHARS && c != 'e' && c != 'E') number() else word(depth)) ?: return null
                }
            }
        }

        fun quoted(): String? {
            at++ // la comilla que abre
            val out = StringBuilder()
            while (at < text.length) {
                val c = text[at++]
                if (c == '"') {
                    if (text.getOrNull(at) != '"') return out.toString()
                    out.append('"')
                    at++
                } else {
                    out.append(c)
                }
            }
            return null
        }

        fun number(): Double? {
            val start = at
            while (at < text.length && text[at] in NUMBER_CHARS) at++
            return text.substring(start, at).toDoubleOrNull()
        }

        /** Una palabra: un nodo si sigue un corchete y, si no, un texto como `EAST`. */
        fun word(depth: Int): Any? {
            val start = at
            val node = node(depth + 1)
            if (node != null) return node
            at = start
            while (at < text.length && (text[at].isLetterOrDigit() || text[at] == '_')) at++
            return if (at == start) null else text.substring(start, at)
        }
    }
}
