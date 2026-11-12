package es.topophone.core.formats.kml

import es.topophone.core.formats.xml.XmlFormatException
import es.topophone.core.formats.xml.XmlStreamReader
import es.topophone.core.formats.xml.XmlStreamReader.Event
import es.topophone.core.model.cad.GpkgIgnoredReason
import java.io.InputStream
import java.util.EnumMap

/**
 * Lee un KML por flujo, una sola vez y sin construir un árbol (S031/A2): entrega cada marca con geometría a medida que la lee y cuenta los
 * elementos que no se importan. Se apoya en [XmlStreamReader], que ignora los espacios de nombres (`gx:Track` es `Track`).
 *
 * Reglas (las mismas que el lector de referencia de `tools/formatos/generar_kml.py`):
 * - Una carpeta es un `Folder` o un `Document`, salvo el `Document` raíz; su nombre se lee antes que su contenido, como en todo KML real.
 * - Una marca da, por tipo, sus partes válidas y cuántas geometrías vacías tenía; una marca sin geometría no se entrega.
 * - Un vínculo de red a un `.kml` del mismo KMZ se lee como una carpeta (RF-2b); cualquier otro se cuenta como ignorado (RF-9).
 * - Los estilos se resuelven al terminar la marca con los declarados hasta entonces (RF-12).
 */
class KmlParser {

    /**
     * Lee [input], el KML principal de [entry] en [kmz] (o un KML suelto, sin [kmz]), y entrega cada marca con geometría a [sink].
     * [XmlFormatException] si el XML está cortado o mal formado y [KmlDamagedException] si [input] no es un KML.
     */
    fun parse(input: InputStream, kmz: KmzEntries? = null, entry: String = "", sink: (KmlPlacemark) -> Unit): KmlParseResult {
        val run = Run(kmz, sink)
        run.visited += entry
        if (!run.document(input, entry, 0, emptyList())) throw KmlDamagedException("No es un KML")
        return KmlParseResult(run.ignored)
    }

    private class Group {
        val parts = ArrayList<KmlPart>()
        var empty = 0

        fun toGroup() = KmlGeometryGroup(parts, empty)
    }

    private class Run(val kmz: KmzEntries?, val sink: (KmlPlacemark) -> Unit) {
        val ignored = EnumMap<GpkgIgnoredReason, Int>(GpkgIgnoredReason::class.java)
        val visited = HashSet<String>()

        fun ignore(reason: GpkgIgnoredReason) {
            ignored.merge(reason, 1, Int::plus)
        }

        /**
         * Lee un documento KML entero con la ruta de carpetas [base]. Devuelve `false`, sin haber entregado nada, si lo que hay no es un
         * KML (no es XML o su raíz no es `kml`); un XML cortado después de la raíz lanza [XmlFormatException].
         */
        fun document(input: InputStream, entry: String, depth: Int, base: List<String>): Boolean {
            val reader: XmlStreamReader
            try {
                reader = XmlStreamReader(input)
                if (reader.next() != Event.START) return false
            } catch (e: XmlFormatException) {
                return false
            }
            if (reader.name != ROOT || !namespaceIsKml(reader.attribute("xmlns"))) return false
            Doc(reader, entry, depth).root(base)
            return true
        }

        private fun namespaceIsKml(uri: String?): Boolean = uri.isNullOrEmpty() || uri.contains(VERSION_MARK)

        private inner class Doc(val reader: XmlStreamReader, val entry: String, val depth: Int) {
            val styles = KmlStyleTable()

            /** Llama a [onChild] en el inicio de cada hijo del elemento recién abierto; [onChild] tiene que consumirlo entero. */
            private inline fun children(onChild: (String) -> Unit) {
                val inside = reader.depth
                while (true) {
                    when (reader.next()) {
                        Event.START -> onChild(reader.name)
                        Event.END -> if (reader.depth < inside) return
                        Event.TEXT -> Unit
                        Event.EOF -> return
                    }
                }
            }

            /** El texto del elemento recién abierto, hasta su cierre. */
            private fun text(): String {
                val out = StringBuilder()
                while (true) {
                    when (reader.next()) {
                        Event.TEXT -> out.append(reader.text)
                        Event.START -> reader.skipElement()
                        Event.END, Event.EOF -> return out.toString()
                    }
                }
            }

            fun root(base: List<String>) = children { child ->
                when (child) {
                    "Document" -> container(base, ownName = false)
                    "Folder" -> container(base, ownName = true)
                    "Placemark" -> placemark(base)
                    else -> other(child, base)
                }
            }

            private fun container(segments: List<String>, ownName: Boolean) {
                var path = segments
                children { child ->
                    when (child) {
                        "name" -> {
                            val label = text().trim()
                            if (ownName && label.isNotEmpty()) path = segments + label
                        }

                        "Placemark" -> placemark(path)
                        "Folder", "Document" -> container(path, ownName = true)
                        else -> other(child, path)
                    }
                }
            }

            /** Lo que puede haber en un contenedor además de marcas y carpetas: estilos, vínculos, imágenes y recorridos. */
            private fun other(child: String, segments: List<String>) {
                when (child) {
                    "Style" -> {
                        val id = reader.attribute("id")
                        val style = style()
                        if (!id.isNullOrEmpty()) styles.addStyle(id, style)
                    }

                    "StyleMap" -> {
                        val id = reader.attribute("id")
                        val normal = styleMap()
                        if (!id.isNullOrEmpty() && normal != null) styles.addStyleMap(id, normal)
                    }

                    "NetworkLink" -> link(segments)

                    "GroundOverlay", "ScreenOverlay", "PhotoOverlay" -> {
                        ignore(GpkgIgnoredReason.OVERLAY)
                        reader.skipElement()
                    }

                    "Tour" -> {
                        ignore(GpkgIgnoredReason.TOUR)
                        reader.skipElement()
                    }

                    else -> reader.skipElement()
                }
            }

            // ---------- estilos ----------

            private fun style(): KmlStyle {
                var icon: Int? = null
                var line: Int? = null
                children { child ->
                    when (child) {
                        "IconStyle" -> icon = color()
                        "LineStyle" -> line = color()
                        else -> reader.skipElement()
                    }
                }
                return KmlStyle(icon, line)
            }

            private fun color(): Int? {
                var color: Int? = null
                children { child -> if (child == "color") color = KmlColors.parse(text()) else reader.skipElement() }
                return color
            }

            private fun styleMap(): KmlStyleMapNormal? {
                var normal: KmlStyleMapNormal? = null
                children { child ->
                    if (child != "Pair") {
                        reader.skipElement()
                    } else {
                        var key = ""
                        var url: String? = null
                        var inline: KmlStyle? = null
                        children { part ->
                            when (part) {
                                "key" -> key = text().trim()
                                "styleUrl" -> url = text().trim()
                                "Style" -> inline = style()
                                else -> reader.skipElement()
                            }
                        }
                        if (key == "normal" && normal == null) {
                            normal = inline?.let { KmlStyleMapNormal.Inline(it) } ?: url?.let { KmlStyleMapNormal.Reference(it) }
                        }
                    }
                }
                return normal
            }

            // ---------- vínculos de red ----------

            private fun link(segments: List<String>) {
                var name = ""
                var href = ""
                children { child ->
                    when (child) {
                        "name" -> name = text().trim()
                        "Link", "Url" -> children { part -> if (part == "href") href = text().trim() else reader.skipElement() }
                        else -> reader.skipElement()
                    }
                }
                if (!follow(name, href, segments)) ignore(GpkgIgnoredReason.NETWORK_LINK)
            }

            /** Lee el `.kml` del propio KMZ al que apunta el vínculo como una carpeta con su nombre (S031/RF-2b). */
            private fun follow(name: String, href: String, segments: List<String>): Boolean {
                val entries = kmz ?: return false
                if (depth >= MAX_LINK_DEPTH) return false
                val target = KmlLinks.resolve(entry, href, entries.names) ?: return false
                if (!visited.add(target)) return false
                val stream = entries.open(target) ?: return false
                val label = name.ifEmpty { target.substringAfterLast('/').substringBeforeLast('.') }
                return stream.use { document(it, target, depth + 1, segments + label) }
            }

            // ---------- marcas y geometrías ----------

            private fun placemark(path: List<String>) {
                var name = ""
                var description = ""
                var styleUrl: String? = null
                var inline: KmlStyle? = null
                val data = LinkedHashMap<String, String>()
                val points = Group()
                val lines = Group()
                children { child ->
                    when (child) {
                        "name" -> name = text().trim()
                        "description" -> description = KmlHtml.toText(text())
                        "styleUrl" -> styleUrl = text().trim()
                        "Style" -> inline = style()
                        "ExtendedData" -> extendedData(data)
                        else -> if (!geometry(child, points, lines)) reader.skipElement()
                    }
                }
                val pointGroup = points.toGroup()
                val lineGroup = lines.toGroup()
                if (pointGroup.isNothing && lineGroup.isNothing) return
                val style = styles.resolve(inline, styleUrl)
                sink(
                    KmlPlacemark(
                        path = path,
                        name = name,
                        description = description,
                        data = data,
                        points = pointGroup,
                        lines = lineGroup,
                        iconColor = style.colorFor(KmlColorTarget.POINT),
                        lineColor = style.colorFor(KmlColorTarget.LINE),
                    ),
                )
            }

            private fun extendedData(data: MutableMap<String, String>) = children { child ->
                when (child) {
                    "Data" -> {
                        val key = reader.attribute("name")
                        var value = ""
                        children { part -> if (part == "value") value = text().trim() else reader.skipElement() }
                        if (!key.isNullOrEmpty()) data[key] = value
                    }

                    "SchemaData" -> children { part ->
                        if (part == "SimpleData") {
                            val key = reader.attribute("name")
                            val value = text().trim()
                            if (!key.isNullOrEmpty()) data[key] = value
                        } else {
                            reader.skipElement()
                        }
                    }

                    else -> reader.skipElement()
                }
            }

            /** Lee la geometría que empieza en [tag] y la reparte en [points] y [lines]; `false` si [tag] no es una geometría. */
            private fun geometry(tag: String, points: Group, lines: Group): Boolean {
                when (tag) {
                    "Point" -> simple(points, MIN_POINT_VERTICES, firstOnly = true)
                    "LineString" -> simple(lines, MIN_LINE_VERTICES, firstOnly = false)
                    "LinearRing" -> simple(lines, MIN_RING_VERTICES, firstOnly = false)
                    "Polygon" -> polygon(lines)
                    "MultiGeometry" -> children { child -> if (!geometry(child, points, lines)) reader.skipElement() }
                    "Model" -> {
                        ignore(GpkgIgnoredReason.MODEL)
                        reader.skipElement()
                    }

                    "Track", "MultiTrack" -> {
                        ignore(GpkgIgnoredReason.TRACK)
                        reader.skipElement()
                    }

                    else -> return false
                }
                return true
            }

            /** Un `Point`, `LineString` o `LinearRing`: sus `coordinates` y su `altitudeMode`. */
            private fun simple(group: Group, minVertices: Int, firstOnly: Boolean) {
                var coordinates: String? = null
                var absolute = false
                children { child ->
                    when (child) {
                        "coordinates" -> coordinates = text()
                        "altitudeMode" -> if (text().trim() == ABSOLUTE) absolute = true
                        else -> reader.skipElement()
                    }
                }
                val vertices = coordinates?.let { KmlCoordinates.parse(it) }
                if (vertices == null || vertices.size < minVertices) {
                    group.empty++
                } else {
                    val used = if (firstOnly) vertices.first() else vertices
                    group.parts += KmlPart(used, contour = false, absolute = absolute, isPoint = firstOnly)
                }
            }

            /** Un `Polygon`: el contorno exterior y, por cada hueco válido, otra parte; sin contorno válido es una geometría vacía. */
            private fun polygon(group: Group) {
                var outer: KmlVertices? = null
                val holes = ArrayList<KmlVertices>()
                var absolute = false
                children { child ->
                    when (child) {
                        "outerBoundaryIs" -> outer = ring()
                        "innerBoundaryIs" -> ring()?.let { if (it.size >= MIN_RING_VERTICES) holes += it }
                        "altitudeMode" -> if (text().trim() == ABSOLUTE) absolute = true
                        else -> reader.skipElement()
                    }
                }
                val exterior = outer
                if (exterior == null || exterior.size < MIN_RING_VERTICES) {
                    group.empty++
                    return
                }
                group.parts += KmlPart(exterior, contour = true, absolute = absolute)
                for (hole in holes) group.parts += KmlPart(hole, contour = true, absolute = absolute)
            }

            /** El `LinearRing` de un `outerBoundaryIs` o `innerBoundaryIs`: sus vértices, o `null` si no hay o no se leen. */
            private fun ring(): KmlVertices? {
                var vertices: KmlVertices? = null
                children { child ->
                    if (child == "LinearRing") {
                        children { part -> if (part == "coordinates") vertices = KmlCoordinates.parse(text()) else reader.skipElement() }
                    } else {
                        reader.skipElement()
                    }
                }
                return vertices
            }
        }
    }

    private companion object {
        const val ROOT = "kml"
        const val VERSION_MARK = "/kml/2."
        const val ABSOLUTE = "absolute"
        const val MAX_LINK_DEPTH = 3
        const val MIN_POINT_VERTICES = 1
        const val MIN_LINE_VERTICES = 2
        const val MIN_RING_VERTICES = 4
    }
}
