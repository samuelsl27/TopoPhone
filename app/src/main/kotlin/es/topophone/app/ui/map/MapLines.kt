package es.topophone.app.ui.map

import es.topophone.core.model.line.ProjectLine
import es.topophone.core.model.point.PointSummary
import java.util.Locale

/**
 * Las líneas y los polígonos del proyecto listos para el mapa: un GeoJSON ya escrito, para que quien lo pinta (el hilo principal) solo se lo
 * pase al mapa (S025/RF-22, RNF-T13). [lines] y [polygons] son cuántos hay de cada.
 */
data class LineScene(val geoJson: String, val lines: Int, val polygons: Int)

/** Las líneas del proyecto en el mapa (S025/RF-22, RF-23). */
object MapLines {
    /** El color de una línea sin código: gris azulado oscuro. */
    const val DEFAULT_COLOR = 0x37474F

    /**
     * El GeoJSON de las líneas visibles, con sus vértices en las coordenadas actuales de sus puntos (RF-17): una `LineString` por línea
     * abierta y un `Polygon` por línea cerrada. Las ocultas no van (RF-23); una línea con menos de 2 vértices con posición, o un polígono con
     * menos de 3, tampoco. Cada elemento lleva `id`, `name`, `color` (`#RRGGBB`), `active` y `polygon`. `null` si no hay nada que dibujar.
     */
    fun scene(lines: List<ProjectLine>, points: List<PointSummary>): LineScene? {
        val positions = HashMap<String, PointSummary>(points.size * 2)
        points.forEach { positions[it.id] = it }
        val out = StringBuilder(FEATURES_HEAD)
        var open = 0
        var closed = 0
        for (line in lines) {
            if (line.hidden || line.deletedAtMs != null) continue
            val coordinates = ArrayList<PointSummary>(line.vertices.size + 1)
            for (vertex in line.vertices) positions[vertex.pointId]?.let { coordinates.add(it) }
            if (coordinates.size < (if (line.closed) 3 else 2)) continue
            if (open + closed > 0) out.append(',')
            appendFeature(out, line, coordinates)
            if (line.closed) closed++ else open++
        }
        if (open + closed == 0) return null
        return LineScene(out.append("]}").toString(), open, closed)
    }

    private fun appendFeature(out: StringBuilder, line: ProjectLine, vertices: List<PointSummary>) {
        val color = String.format(Locale.ROOT, "#%06X", (line.code?.color ?: DEFAULT_COLOR) and 0xFFFFFF)
        out.append("{\"type\":\"Feature\",\"properties\":{\"id\":").appendJson(line.id)
        out.append(",\"name\":").appendJson(line.name)
        out.append(",\"color\":\"").append(color).append('"')
        out.append(",\"active\":").append(line.active)
        out.append(",\"polygon\":").append(line.closed)
        out.append("},\"geometry\":{\"type\":\"").append(if (line.closed) "Polygon" else "LineString").append("\",\"coordinates\":")
        if (line.closed) out.append('[')
        out.append('[')
        vertices.forEach { appendCoordinate(out, it).append(',') }
        if (line.closed) appendCoordinate(out, vertices.first()) else out.setLength(out.length - 1)
        out.append(']')
        if (line.closed) out.append(']')
        out.append("}}")
    }

    private fun appendCoordinate(out: StringBuilder, point: PointSummary): StringBuilder =
        out.append('[').append(point.longitudeDeg).append(',').append(point.latitudeDeg).append(']')

    private fun StringBuilder.appendJson(text: String): StringBuilder {
        append('"')
        for (c in text) {
            when {
                c == '"' -> append("\\\"")
                c == '\\' -> append("\\\\")
                c < ' ' -> append(String.format(Locale.ROOT, "\\u%04x", c.code))
                else -> append(c)
            }
        }
        return append('"')
    }

    private const val FEATURES_HEAD = "{\"type\":\"FeatureCollection\",\"features\":["
}
