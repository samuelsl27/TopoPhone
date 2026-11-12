package es.topophone.core.formats.kml

/**
 * Qué es un fichero que se quiere importar como KML o KMZ, por su contenido y no por su extensión ni su tipo MIME (S031/RF-2, RF-3,
 * RNF-T5): un KMZ es un ZIP (`PK`) y un KML es un XML cuya raíz es `kml`, sin espacio de nombres o con uno de la versión 2.x. Un KML cortado
 * o mal formado sigue siendo un KML por su cabecera: se descubre al leerlo.
 */
object KmlFileKind {
    /** Cuántos bytes del principio bastan para reconocer un fichero. */
    const val HEAD_BYTES = 8 * 1024

    /** Lo máximo que puede pesar el fichero elegido (S031/RF-4). */
    const val MAX_FILE_BYTES = 100L * 1024 * 1024

    /** Lo máximo que puede descomprimir un KMZ (S031/RF-4). */
    const val MAX_UNCOMPRESSED_BYTES = 500L * 1024 * 1024

    /** [KML] y [KMZ] se leen; [EMPTY] es un fichero sin contenido y [NOT_KML] cualquier otra cosa (S031/RF-3). */
    enum class Kind { KML, KMZ, EMPTY, NOT_KML }

    private const val ROOT_NAME = "kml"
    private const val VERSION_MARK = "/kml/2."
    private const val BOM = '﻿'
    private val NAMESPACE = Regex("""xmlns(?::([A-Za-z_][\w.\-]*))?\s*=\s*(?:"([^"]*)"|'([^']*)')""")

    /** El tipo de un fichero de [sizeBytes] cuyos primeros bytes son [head] (como mucho [HEAD_BYTES]). */
    fun of(head: ByteArray, sizeBytes: Long): Kind {
        if (sizeBytes == 0L || head.isEmpty()) return Kind.EMPTY
        if (isZip(head)) return Kind.KMZ
        val text = decode(head)
        val start = rootStart(text) ?: return if (text.isBlank()) Kind.EMPTY else Kind.NOT_KML
        return if (isKmlRoot(text, start)) Kind.KML else Kind.NOT_KML
    }

    /**
     * El KML principal de un KMZ entre los nombres de sus entradas (S031/RF-2): `doc.kml` de la raíz y, si no lo hay, el primer `.kml` de la
     * raíz por orden alfabético, sin distinguir mayúsculas. `null` si la raíz no tiene ningún `.kml` (RF-3).
     */
    fun mainEntry(names: List<String>): String? {
        val roots = names.filter { !it.contains('/') && !it.contains('\\') && it.endsWith(".kml", ignoreCase = true) }
        return roots.firstOrNull { it.equals("doc.kml", ignoreCase = true) } ?: roots.minWithOrNull(String.CASE_INSENSITIVE_ORDER)
    }

    private fun isZip(head: ByteArray): Boolean {
        if (head.size < 4 || head[0] != 'P'.code.toByte() || head[1] != 'K'.code.toByte()) return false
        val kind = head[2].toInt() to head[3].toInt()
        return kind == (3 to 4) || kind == (5 to 6) || kind == (7 to 8)
    }

    private fun decode(head: ByteArray): String {
        val utf8Bom = head.size >= 3 && head[0] == 0xEF.toByte() && head[1] == 0xBB.toByte() && head[2] == 0xBF.toByte()
        val little = head.size >= 2 && head[0] == 0xFF.toByte() && head[1] == 0xFE.toByte()
        val big = head.size >= 2 && head[0] == 0xFE.toByte() && head[1] == 0xFF.toByte()
        val text = when {
            utf8Bom -> String(head, 3, head.size - 3, Charsets.UTF_8)
            little -> String(head, 2, head.size - 2, Charsets.UTF_16LE)
            big -> String(head, 2, head.size - 2, Charsets.UTF_16BE)
            else -> String(head, Charsets.ISO_8859_1)
        }
        return text.trimStart(BOM)
    }

    /** Dónde empieza la etiqueta raíz, tras los espacios, la declaración XML, los comentarios y el `DOCTYPE`; null si no llega a haberla. */
    private fun rootStart(text: String): Int? {
        var i = 0
        while (true) {
            while (i < text.length && text[i].isWhitespace()) i++
            if (i >= text.length || text[i] != '<') return null
            i = when {
                text.startsWith("<?", i) -> text.indexOf("?>", i + 2).let { if (it < 0) return null else it + 2 }
                text.startsWith("<!--", i) -> text.indexOf("-->", i + 4).let { if (it < 0) return null else it + 3 }
                text.startsWith("<!DOCTYPE", i) -> endOfDoctype(text, i) ?: return null
                else -> return i
            }
        }
    }

    private fun endOfDoctype(text: String, from: Int): Int? {
        val bracket = text.indexOf('[', from)
        val close = text.indexOf('>', from)
        if (close < 0) return null
        if (bracket < 0 || bracket > close) return close + 1
        val subsetEnd = text.indexOf("]", bracket)
        if (subsetEnd < 0) return null
        return text.indexOf('>', subsetEnd).let { if (it < 0) null else it + 1 }
    }

    private fun isKmlRoot(text: String, start: Int): Boolean {
        var end = start + 1
        while (end < text.length && !text[end].isWhitespace() && text[end] != '>' && text[end] != '/') end++
        val qualified = text.substring(start + 1, end)
        val colon = qualified.lastIndexOf(':')
        if (qualified.substring(colon + 1) != ROOT_NAME) return false
        val prefix = if (colon < 0) null else qualified.substring(0, colon)
        val tagEnd = text.indexOf('>', end).let { if (it < 0) text.length else it }
        val declared = NAMESPACE.findAll(text.substring(end, tagEnd)).firstOrNull { it.groups[1]?.value == prefix } ?: return true
        val uri = declared.groups[2]?.value ?: declared.groups[3]?.value.orEmpty()
        return uri.isEmpty() || uri.contains(VERSION_MARK)
    }
}
