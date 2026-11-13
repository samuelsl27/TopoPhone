package es.topophone.core.formats.kml.export

import es.topophone.core.formats.kml.KmlColors
import java.io.Writer

/**
 * Escribe un [KmlDocument] como KML 2.2 (S031/RF-21 a RF-23, RF-26, RF-27, RF-29): sangría de 2 espacios, fin de línea LF y un salto al final,
 * el texto con `&`, `<` y `>` escapados y los números con [KmlNumber]. La plantilla es la de `tools/formatos/generar_kml_exportado.py`, con la
 * que se comparan los bytes. Una marca con fotos lleva la descripción en `CDATA` con una imagen por foto (RF-29).
 */
object KmlWriter {
    private const val IMAGE_WIDTH = 400
    private const val FILL_ALPHA = 0x80
    internal const val PHOTO_FOLDER = "files/"

    /** Escribe [document] en [out] sin cerrarlo. */
    fun write(document: KmlDocument, out: Writer) {
        val lines = Lines(out)
        lines.add(0, "<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
        lines.add(0, "<kml xmlns=\"http://www.opengis.net/kml/2.2\">")
        lines.add(1, "<Document>")
        lines.add(2, "<name>${escape(document.name)}</name>")
        for (style in document.styles) style(lines, style)
        for (folder in document.folders) {
            lines.add(2, "<Folder>")
            lines.add(3, "<name>${escape(folder.name)}</name>")
            for (mark in folder.marks) mark(lines, mark)
            lines.add(2, "</Folder>")
        }
        lines.add(1, "</Document>")
        lines.add(0, "</kml>")
    }

    private class Lines(private val out: Writer) {
        fun add(level: Int, text: String) {
            repeat(level * 2) { out.write(' '.code) }
            out.write(text)
            out.write('\n'.code)
        }
    }

    private fun style(lines: Lines, style: KmlStyleDef) {
        val color = KmlColors.toKml(style.argb)
        val fill = KmlColors.toKml(style.argb, alpha = FILL_ALPHA)
        lines.add(2, "<Style id=\"${escapeAttribute(style.id)}\">")
        lines.add(3, "<IconStyle><color>$color</color></IconStyle>")
        lines.add(3, "<LineStyle><color>$color</color></LineStyle>")
        lines.add(3, "<PolyStyle><color>$fill</color></PolyStyle>")
        lines.add(2, "</Style>")
    }

    private fun mark(lines: Lines, mark: KmlMark) {
        lines.add(3, "<Placemark>")
        lines.add(4, "<name>${escape(mark.name)}</name>")
        description(mark)?.let { lines.add(4, "<description>$it</description>") }
        mark.styleId?.let { lines.add(4, "<styleUrl>#${escape(it)}</styleUrl>") }
        if (mark.data.isNotEmpty()) {
            lines.add(4, "<ExtendedData>")
            for ((name, value) in mark.data) {
                val body = if (value.isEmpty()) "<value/>" else "<value>${escape(value)}</value>"
                lines.add(5, "<Data name=\"${escapeAttribute(name)}\">$body</Data>")
            }
            lines.add(4, "</ExtendedData>")
        }
        geometry(lines, mark)
        lines.add(3, "</Placemark>")
    }

    private fun description(mark: KmlMark): String? {
        if (mark.photos.isNotEmpty()) {
            val text = escape(mark.description).replace("\n", "<br/>")
            val images = mark.photos.joinToString("") { "<br/><img src=\"$PHOTO_FOLDER${escapeAttribute(it)}\" width=\"$IMAGE_WIDTH\"/>" }
            return "<![CDATA[$text$images]]>"
        }
        return mark.description.takeIf { it.isNotEmpty() }?.let(::escape)
    }

    private fun geometry(lines: Lines, mark: KmlMark) {
        val mode = mark.absolute
        when (val geometry = mark.geometry) {
            is KmlGeometry.Point -> {
                lines.add(4, "<Point>")
                if (mode) lines.add(5, "<altitudeMode>absolute</altitudeMode>")
                lines.add(5, coordinates(listOf(geometry.vertex), mode))
                lines.add(4, "</Point>")
            }

            is KmlGeometry.Line -> {
                lines.add(4, "<LineString>")
                if (mode) lines.add(5, "<altitudeMode>absolute</altitudeMode>")
                lines.add(5, coordinates(geometry.vertices, mode))
                lines.add(4, "</LineString>")
            }

            is KmlGeometry.Polygon -> {
                lines.add(4, "<Polygon>")
                if (mode) lines.add(5, "<altitudeMode>absolute</altitudeMode>")
                lines.add(5, "<outerBoundaryIs>")
                lines.add(6, "<LinearRing>")
                lines.add(7, coordinates(geometry.ring, mode))
                lines.add(6, "</LinearRing>")
                lines.add(5, "</outerBoundaryIs>")
                lines.add(4, "</Polygon>")
            }
        }
    }

    private fun coordinates(vertices: List<KmlVertex>, withHeight: Boolean): String {
        val text = vertices.joinToString(" ") {
            val height = it.height
            val base = "${KmlNumber.coordinate(it.lon)},${KmlNumber.coordinate(it.lat)}"
            if (withHeight && height != null) "$base,${KmlNumber.height(height)}" else base
        }
        return "<coordinates>$text</coordinates>"
    }

    /** `&`, `<` y `>` en un texto. */
    internal fun escape(text: String): String = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    /** Lo mismo y además `"`, para el valor de un atributo. */
    internal fun escapeAttribute(text: String): String = escape(text).replace("\"", "&quot;")
}
