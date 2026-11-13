package es.topophone.core.formats.wms

import es.topophone.core.formats.xml.XmlFormatException
import es.topophone.core.formats.xml.XmlStreamReader
import es.topophone.core.model.wms.WmsLayerInfo
import es.topophone.core.model.wms.WmsLimits
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.util.Locale

/** Lo que dice un servicio WMS de sí mismo (S036/RF-3): su versión, su título, su atribución, los formatos y el árbol de capas. */
data class WmsCapabilities(
    val version: String,
    val title: String,
    val attribution: String,
    val mapFormats: List<String>,
    val infoFormats: List<String>,
    val layers: List<WmsLayerInfo>,
) {
    /** Si alguna capa se puede añadir a un proyecto: con nombre y en Web Mercator (RF-9). */
    val hasAddableLayer: Boolean get() = layers.any { it.isAddable }
}

/** Por qué un documento no es el GetCapabilities de un servicio (S036/RF-8). */
enum class WmsCapabilitiesProblem { NOT_CAPABILITIES, SERVICE_EXCEPTION, MALFORMED, TOO_LARGE }

/** [serviceMessage] es el texto de la excepción que devolvió el servicio, si fue eso. */
class WmsCapabilitiesException(val problem: WmsCapabilitiesProblem, val serviceMessage: String? = null, cause: Throwable? = null) :
    Exception("${problem.name}${serviceMessage?.let { ": $it" } ?: ""}", cause)

/**
 * Lee el GetCapabilities de un servicio WMS 1.1.1 o 1.3.0 por eventos, sin construir un árbol (S036/RF-2, RF-3, RF-9, RF-11, RF-12):
 * un documento de 20 MB no cabe en un DOM. Lee el documento en la versión en que venga, aunque no sea la que se pidió. Los prefijos de los
 * espacios de nombres no cuentan, así que un prefijo sin declarar no impide leerlo; el `DOCTYPE` se salta y se entiende ISO-8859-1.
 *
 * Reglas (las mismas que el lector de referencia de `tools/formatos/generar_wms.py`):
 * - Las capas van en el orden del documento y solo se leen los 10 primeros niveles; las más profundas se ignoran.
 * - `queryable` y `opaque` se heredan del ascendiente si la capa no los declara; por defecto, no consultable y transparente.
 * - Los CRS de una capa son los suyos más los de sus ascendientes, los de ellos primero. En 1.1.1 un `SRS` puede llevar varios códigos
 *   separados por espacios o comas.
 * - La atribución del servicio es el `Title` del primer `Attribution` del documento, o vacía.
 * - Decide la raíz: si no es `WMS_Capabilities`, `WMT_MS_Capabilities` ni `ServiceExceptionReport` no es de capacidades, aunque el resto
 *   esté mal formado (una página HTML).
 */
object WmsCapabilitiesReader {
    private val CAPABILITIES_ROOTS = setOf("WMS_Capabilities", "WMT_MS_Capabilities")

    /** Lee [input]; más de [maxBytes] es [WmsCapabilitiesProblem.TOO_LARGE]. Un fallo de lectura sale tal cual como [IOException]. */
    fun read(input: InputStream, maxBytes: Long = WmsLimits.MAX_CAPABILITIES_BYTES): WmsCapabilities {
        val limited = LimitedInputStream(input, maxBytes)
        try {
            return Parser(XmlStreamReader(limited)).parse()
        } catch (error: XmlFormatException) {
            throw WmsCapabilitiesException(WmsCapabilitiesProblem.MALFORMED, cause = error)
        } catch (error: TooLargeException) {
            throw WmsCapabilitiesException(WmsCapabilitiesProblem.TOO_LARGE, cause = error)
        }
    }

    private class TooLargeException : IOException("El documento pasa del tamaño máximo")

    private class LimitedInputStream(input: InputStream, private val max: Long) : FilterInputStream(input) {
        private var count = 0L

        override fun read(): Int = super.read().also { if (it >= 0) add(1) }

        override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also { if (it > 0) add(it.toLong()) }

        private fun add(bytes: Long) {
            count += bytes
            if (count > max) throw TooLargeException()
        }
    }

    /** Una capa mientras se lee: sus datos se completan con lo que viene después de abrirla. */
    private class Draft(val parent: Draft?, val depth: Int, val ordinal: Int, val queryable: Boolean?, val opaque: Boolean?) {
        var name: String? = null
        var title = ""
        var abstract = ""
        val codes = ArrayList<String>()
    }

    private class Parser(private val xml: XmlStreamReader) {
        private var version = ""
        private var title = ""
        private var attribution = ""
        private val mapFormats = ArrayList<String>()
        private val infoFormats = ArrayList<String>()
        private val drafts = ArrayList<Draft>()
        private val path = ArrayList<String>()
        private val layerStack = ArrayList<Draft>()
        private val exceptionMessages = ArrayList<String>()
        private var rootKind = Root.NONE
        private var firstAttributionDepth = -1
        private var attributionSeen = false
        private var capture: Capture? = null
        private val text = StringBuilder()

        private enum class Root { NONE, CAPABILITIES, EXCEPTION }

        private class Capture(val depth: Int, val onText: (String) -> Unit)

        fun parse(): WmsCapabilities {
            while (true) {
                when (xml.next()) {
                    XmlStreamReader.Event.EOF -> return finish()
                    XmlStreamReader.Event.START -> start()
                    XmlStreamReader.Event.TEXT -> if (capture != null) text.append(xml.text)
                    XmlStreamReader.Event.END -> end()
                }
            }
        }

        private fun finish(): WmsCapabilities {
            when (rootKind) {
                Root.NONE -> throw WmsCapabilitiesException(WmsCapabilitiesProblem.MALFORMED)
                Root.EXCEPTION -> throw WmsCapabilitiesException(
                    WmsCapabilitiesProblem.SERVICE_EXCEPTION,
                    exceptionMessages.filter { it.isNotEmpty() }.joinToString(" "),
                )
                Root.CAPABILITIES -> Unit
            }
            return WmsCapabilities(version, title, attribution, mapFormats, infoFormats, drafts.map(::info))
        }

        private fun start() {
            val depth = xml.depth
            val name = xml.name
            while (path.size >= depth) path.removeAt(path.size - 1)
            path.add(name)
            if (depth == 1) {
                rootKind = when {
                    name in CAPABILITIES_ROOTS -> Root.CAPABILITIES
                    name == "ServiceExceptionReport" -> Root.EXCEPTION
                    else -> throw WmsCapabilitiesException(WmsCapabilitiesProblem.NOT_CAPABILITIES)
                }
                version = xml.attribute("version")?.trim().orEmpty()
                return
            }
            if (rootKind == Root.EXCEPTION) {
                if (name == "ServiceException") beginCapture(depth) { exceptionMessages += it }
                return
            }
            val parent = path.getOrNull(depth - 2)
            when {
                name == "Layer" && (parent == "Capability" || parent == "Layer") -> openLayer(depth)
                name == "Attribution" && !attributionSeen -> {
                    attributionSeen = true
                    firstAttributionDepth = depth
                }
                name == "Title" && parent == "Service" && depth == SERVICE_CHILD_DEPTH && title.isEmpty() ->
                    beginCapture(depth) { title = it }
                name == "Title" && parent == "Attribution" && depth == firstAttributionDepth + 1 && attribution.isEmpty() ->
                    beginCapture(depth) { attribution = it }
                name == "Format" && depth == FORMAT_DEPTH -> format(path[depth - 2])
                parent == "Layer" && layerStack.isNotEmpty() -> layerField(name, depth)
            }
        }

        private fun format(container: String) {
            val target = when (container) {
                "GetMap" -> mapFormats
                "GetFeatureInfo" -> infoFormats
                else -> return
            }
            beginCapture(xml.depth) { if (it.isNotEmpty()) target += it }
        }

        private fun openLayer(depth: Int) {
            val level = layerStack.size
            if (level >= WmsLimits.MAX_DEPTH) {
                xml.skipElement() // los niveles de más de 10 no se leen
                path.removeAt(path.size - 1)
                return
            }
            val draft = Draft(layerStack.lastOrNull(), level, drafts.size, flag("queryable"), flag("opaque"))
            drafts += draft
            layerStack += draft
            layerDepths += depth
        }

        private val layerDepths = ArrayList<Int>()

        private fun layerField(name: String, depth: Int) {
            if (depth != layerDepths.last() + 1) return
            val draft = layerStack.last()
            when (name) {
                "Name" -> beginCapture(depth) { draft.name = it }
                "Title" -> beginCapture(depth) { draft.title = it }
                "Abstract" -> beginCapture(depth) { draft.abstract = it }
                "CRS", "SRS" -> beginCapture(depth) { value ->
                    value.split(' ', ',', '\n', '\t', '\r').filter { it.isNotEmpty() }.forEach { draft.codes += it }
                }
            }
        }

        private fun flag(attribute: String): Boolean? {
            val value = xml.attribute(attribute)?.trim()?.lowercase(Locale.ROOT) ?: return null
            return value == "1" || value == "true"
        }

        private fun beginCapture(depth: Int, onText: (String) -> Unit) {
            capture = Capture(depth, onText)
            text.setLength(0)
        }

        private fun end() {
            val closing = xml.depth + 1
            capture?.let {
                if (it.depth == closing) {
                    it.onText(text.toString().trim())
                    capture = null
                }
            }
            if (layerDepths.isNotEmpty() && layerDepths.last() == closing && path.getOrNull(closing - 1) == "Layer") {
                layerDepths.removeAt(layerDepths.size - 1)
                layerStack.removeAt(layerStack.size - 1)
            }
            if (firstAttributionDepth == closing) firstAttributionDepth = Int.MIN_VALUE / 2
        }

        private fun info(draft: Draft): WmsLayerInfo {
            val chain = generateSequence(draft) { it.parent }.toList().asReversed()
            val codes = chain.flatMap { it.codes }
            var queryable = false
            var opaque = false
            chain.forEach { layer ->
                layer.queryable?.let { queryable = it }
                layer.opaque?.let { opaque = it }
            }
            return WmsLayerInfo(
                ordinal = draft.ordinal,
                depth = draft.depth,
                name = draft.name,
                title = draft.title,
                abstract = draft.abstract,
                transparent = !opaque,
                queryable = queryable,
                mercatorCode = WmsLimits.preferredWebMercator(codes),
            )
        }
    }

    private const val SERVICE_CHILD_DEPTH = 3
    private const val FORMAT_DEPTH = 5
}
