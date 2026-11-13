package es.topophone.core.formats.wms

import es.topophone.core.formats.xml.XmlFormatException
import es.topophone.core.formats.xml.XmlStreamReader
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.util.Locale

/** Lo que dice un servicio de lo que hay en un punto (S036/RF-36, RF-38, RF-39). */
sealed interface FeatureInfoResult {
    /** El texto que se muestra, ya sin cabeceras, geometrías ni valores vacíos. */
    data class Text(val text: String) : FeatureInfoResult

    /** El servicio contestó y no hay nada en ese punto (RF-38). */
    data object NoData : FeatureInfoResult

    /** El servicio contestó con una excepción (algunos la devuelven con HTTP 200): se muestra su mensaje (RF-39). */
    data class ServiceError(val message: String) : FeatureInfoResult
}

/**
 * Convierte la respuesta de una consulta de información (GetFeatureInfo) en el texto que ve el usuario (S036/RF-36, RF-38; A6 del plan).
 * Cada servicio contesta a su manera, así que se reconocen las formas habituales:
 * - Texto plano de GeoServer y MapServer (`Layer 'x'` o `Results for FeatureType '…'`, `clave = 'valor'`): se quitan las cabeceras, las
 *   direcciones internas de los nombres de tipo, las separaciones, las geometrías y los atributos vacíos o `null`, y queda `clave: valor`.
 * - GML y XML: los elementos hoja como `clave: valor`, sin geometrías, con el nombre de cada entidad como título.
 * - HTML y XHTML: sin etiquetas, con cada fila de tabla como `clave: valor`.
 * - `ServiceExceptionReport`: su mensaje; si dice que no se encontró nada, es «sin datos».
 * - Cualquier otro texto, tal cual.
 * Es «sin datos» lo vacío, «no features were found», «Search returned no results» y lo que solo deja cabeceras sin valores.
 */
object FeatureInfoText {
    private const val MAX_CHARS = 20_000
    private val NO_DATA = Regex(
        """(?i)^(no features? (were )?found\.?|search returned no results\.?|no results?( found)?\.?|sin resultados\.?)$""",
    )
    private val NOT_FOUND = Regex("""(?i)\bnot found\b|\bno (features?|results?)\b""")
    private val LAYER = Regex("""^Layer '(.*)'$""")
    private val FEATURE_TYPE = Regex("""^Results for FeatureType '(.*)':?$""")
    private val FEATURE_NUMBER = Regex("""^Feature \d+:?$""")
    private val SEPARATOR = Regex("""^-{3,}$""")
    private val PAIR = Regex("""^([^=]+?)\s*=\s*(.*)$""")
    private val DECLARED_ENCODING = Regex("""encoding\s*=\s*["']([^"']+)["']""")
    private val GEOMETRY_ELEMENTS = setOf(
        "boundedBy", "Envelope", "Box", "Point", "LineString", "LinearRing", "Polygon", "MultiPoint", "MultiLineString", "MultiPolygon",
        "MultiCurve", "MultiSurface", "MultiGeometry", "Curve", "Surface", "the_geom", "geom", "geometry", "msGeometry", "shape", "SHAPE",
    )
    private val FEATURE_CONTAINERS = setOf("featureMember", "featureMembers", "member")

    fun parse(body: ByteArray): FeatureInfoResult {
        val head = String(body, 0, minOf(body.size, 400), Charsets.ISO_8859_1).trimStart('﻿', ' ', '\n', '\r', '\t')
        val raw = when {
            head.startsWith("<") -> markup(body, head)
            else -> plain(decode(body))
        }
        return finish(raw)
    }

    /** El resultado ya limpio: «sin datos» si no queda nada con valor, y recortado si es enorme. */
    private fun finish(raw: FeatureInfoResult): FeatureInfoResult {
        if (raw !is FeatureInfoResult.Text) return raw
        val text = raw.text.trim()
        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        return when {
            text.isEmpty() || NO_DATA.matches(text) -> FeatureInfoResult.NoData
            lines.all { it.endsWith(":") } -> FeatureInfoResult.NoData // solo cabeceras: «Referencia catastral de la parcela:»
            text.length > MAX_CHARS -> FeatureInfoResult.Text(text.take(MAX_CHARS) + "…")
            else -> FeatureInfoResult.Text(text)
        }
    }

    private fun markup(body: ByteArray, head: String): FeatureInfoResult {
        val lower = head.lowercase(Locale.ROOT)
        return when {
            "<serviceexceptionreport" in lower -> exception(body) ?: html(decodeMarkup(body))
            "<html" in lower || "<!doctype html" in lower -> html(decodeMarkup(body))
            else -> try {
                xml(body)
            } catch (_: XmlFormatException) {
                html(decodeMarkup(body)) // XHTML con entidades como &oacute; o XML mal formado: se limpia como HTML
            }
        }
    }

    // --- texto plano -------------------------------------------------------------------------------------------------

    private fun plain(text: String): FeatureInfoResult {
        val out = ArrayList<String>()
        var heading: String? = null
        fun emit(line: String) {
            heading?.let {
                if (out.isNotEmpty()) out += ""
                out += it
                heading = null
            }
            out += line
        }
        for (raw in text.replace("\r\n", "\n").replace('\r', '\n').lines()) {
            val line = raw.trim()
            when {
                line.isEmpty() || SEPARATOR.matches(line) || line.equals("GetFeatureInfo results:", ignoreCase = true) -> Unit
                FEATURE_NUMBER.matches(line) -> Unit
                LAYER.matches(line) -> heading = LAYER.matchEntire(line)!!.groupValues[1]
                FEATURE_TYPE.matches(line) -> heading = typeName(FEATURE_TYPE.matchEntire(line)!!.groupValues[1])
                else -> {
                    val pair = PAIR.matchEntire(line)
                    if (pair == null) {
                        emit(line)
                    } else {
                        val value = pair.groupValues[2].trim().removeSurrounding("'")
                        if (value.isNotEmpty() && !value.equals("null", ignoreCase = true) && !value.startsWith("[GEOMETRY")) {
                            emit("${pair.groupValues[1].trim()}: $value")
                        }
                    }
                }
            }
        }
        return FeatureInfoResult.Text(out.joinToString("\n"))
    }

    /** El nombre de un tipo sin la dirección interna del servidor ni el espacio de nombres: lo que hay tras el último `:`. */
    private fun typeName(quoted: String): String = quoted.substringAfterLast(':').ifEmpty { quoted }

    // --- XML y GML ---------------------------------------------------------------------------------------------------

    private fun exception(body: ByteArray): FeatureInfoResult? = try {
        val message = serviceExceptionMessage(XmlStreamReader(ByteArrayInputStream(body)))
        if (NOT_FOUND.containsMatchIn(message)) FeatureInfoResult.NoData else FeatureInfoResult.ServiceError(message)
    } catch (_: XmlFormatException) {
        null
    }

    /** Los textos de los `ServiceException` del documento, unidos. */
    private fun serviceExceptionMessage(xml: XmlStreamReader): String {
        val messages = ArrayList<String>()
        val text = StringBuilder()
        var inside = false
        while (true) {
            when (xml.next()) {
                XmlStreamReader.Event.EOF -> break
                XmlStreamReader.Event.START -> if (xml.name == "ServiceException") {
                    inside = true
                    text.setLength(0)
                }
                XmlStreamReader.Event.TEXT -> if (inside) text.append(xml.text)
                XmlStreamReader.Event.END -> if (inside && xml.name == "ServiceException") {
                    messages += text.toString().trim()
                    inside = false
                }
            }
        }
        return messages.filter { it.isNotEmpty() }.joinToString(" ")
    }

    private fun xml(body: ByteArray): FeatureInfoResult {
        val xml = XmlStreamReader(ByteArrayInputStream(body))
        val out = ArrayList<String>()
        val text = StringBuilder()
        val path = ArrayList<String>()
        var heading: String? = null
        var leaf: String? = null
        while (true) {
            when (xml.next()) {
                XmlStreamReader.Event.EOF -> break
                XmlStreamReader.Event.START -> {
                    while (path.size >= xml.depth) path.removeAt(path.size - 1)
                    val parent = path.lastOrNull()
                    path += xml.name
                    when {
                        xml.name in GEOMETRY_ELEMENTS -> {
                            xml.skipElement()
                            path.removeAt(path.size - 1)
                        }
                        parent in FEATURE_CONTAINERS -> heading = xml.name
                        else -> {
                            leaf = xml.name
                            text.setLength(0)
                        }
                    }
                }
                XmlStreamReader.Event.TEXT -> if (leaf != null) text.append(xml.text)
                XmlStreamReader.Event.END -> {
                    val value = text.toString().trim()
                    if (leaf == xml.name && value.isNotEmpty() && !value.equals("null", ignoreCase = true)) {
                        heading?.let {
                            if (out.isNotEmpty()) out += ""
                            out += it
                            heading = null
                        }
                        out += "${xml.name}: $value"
                    }
                    leaf = null
                    text.setLength(0)
                }
            }
        }
        return FeatureInfoResult.Text(out.joinToString("\n"))
    }

    // --- HTML y XHTML ------------------------------------------------------------------------------------------------

    private val BLOCKS = Regex("""(?is)<(style|script|head)\b.*?</\1\s*>|<!--.*?-->|<!doctype[^>]*>|<\?xml[^>]*\?>""")
    private val LINE_BREAKS = Regex("""(?i)</tr\s*>|<br\s*/?>|</p\s*>|</div\s*>|</li\s*>|</h[1-6]\s*>|</table\s*>""")
    private val CELL_ENDS = Regex("""(?i)</t[dh]\s*>""")
    private val TAGS = Regex("""<[^>]*>""")
    private val WHITESPACE = Regex("""\s+""")
    private val ENTITY = Regex("""&(#x?[0-9a-fA-F]+|[a-zA-Z]+);""")

    private fun html(text: String): FeatureInfoResult {
        val flat = WHITESPACE.replace(BLOCKS.replace(text, " "), " ") // el espacio de un HTML no cuenta
        val marked = CELL_ENDS.replace(LINE_BREAKS.replace(flat, "\n"), "\t")
        val stripped = decodeEntities(TAGS.replace(marked, ""))
        val lines = stripped.lines().mapNotNull { row ->
            val cells = row.split('\t').map { it.trim() }.filter { it.isNotEmpty() }
            when {
                cells.isEmpty() -> null
                cells.size == 1 -> cells[0]
                else -> cells[0] + ": " + cells.drop(1).joinToString(" ")
            }
        }
        return FeatureInfoResult.Text(lines.joinToString("\n"))
    }

    private val NAMED_ENTITIES = mapOf(
        "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to " ", "aacute" to "á", "eacute" to "é",
        "iacute" to "í", "oacute" to "ó", "uacute" to "ú", "Aacute" to "Á", "Eacute" to "É", "Iacute" to "Í", "Oacute" to "Ó",
        "Uacute" to "Ú", "ntilde" to "ñ", "Ntilde" to "Ñ", "uuml" to "ü", "Uuml" to "Ü", "ccedil" to "ç", "Ccedil" to "Ç",
        "agrave" to "à", "egrave" to "è", "ograve" to "ò", "iquest" to "¿", "iexcl" to "¡", "laquo" to "«", "raquo" to "»",
        "ordm" to "º", "ordf" to "ª", "middot" to "·", "euro" to "€", "deg" to "°", "hellip" to "…", "ndash" to "–", "mdash" to "—",
    )

    private fun decodeEntities(text: String): String = ENTITY.replace(text) { match ->
        val name = match.groupValues[1]
        when {
            name.startsWith("#x") || name.startsWith("#X") -> name.substring(2).toIntOrNull(16)?.let(::codePoint)
            name.startsWith("#") -> name.substring(1).toIntOrNull()?.let(::codePoint)
            else -> NAMED_ENTITIES[name]
        } ?: match.value
    }

    private fun codePoint(value: Int): String? = if (value in 1..0x10FFFF) String(Character.toChars(value)) else null

    // --- caracteres --------------------------------------------------------------------------------------------------

    /** UTF-8 si los bytes lo son, y si no ISO-8859-1 (la codificación de varios servicios españoles). */
    private fun decode(bytes: ByteArray): String = try {
        Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes)).toString().removePrefix("﻿")
    } catch (_: java.nio.charset.CharacterCodingException) {
        String(bytes, Charsets.ISO_8859_1)
    }

    /** El texto de un XML o HTML con la codificación que declara (UTF-8 si no declara ninguna o no se conoce). */
    private fun decodeMarkup(bytes: ByteArray): String {
        val declared = DECLARED_ENCODING.find(String(bytes, 0, minOf(bytes.size, 200), Charsets.ISO_8859_1))?.groupValues?.get(1)
        val charset = declared?.let { runCatching { Charset.forName(it) }.getOrNull() } ?: Charsets.UTF_8
        return String(bytes, charset).removePrefix("﻿")
    }
}
