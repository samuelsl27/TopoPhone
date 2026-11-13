package es.topophone.core.formats.kml.export

import es.topophone.core.formats.csv.AttributeColumns
import es.topophone.core.formats.csv.BaseProjection
import es.topophone.core.formats.csv.ExportColumn
import es.topophone.core.formats.gpkg.GpkgPointAttributes
import es.topophone.core.model.codes.PointCode
import es.topophone.core.model.line.ProjectLine
import es.topophone.core.model.point.Point
import es.topophone.core.model.point.PointBase
import es.topophone.core.model.point.PointCoordinates
import es.topophone.core.model.project.Project

/**
 * Lo que dice el resumen de una exportación a KML o KMZ (S031/RF-32): las marcas escritas, las carpetas, los puntos sin posición (que no se
 * escriben, RF-25) y las marcas sin altura con «Incluir altura» (RF-27); las fotos las cuentan quienes las escriben (RF-30).
 */
data class KmlExportSummary(
    val points: Int = 0,
    val lines: Int = 0,
    val polygons: Int = 0,
    val folders: Int = 0,
    val pointsWithoutPosition: Int = 0,
    val marksWithoutHeight: Int = 0,
)

/** Lo que se escribe: el documento y su resumen. [isEmpty] es que no hay ninguna marca (S031/RF-31). */
class KmlExport(val document: KmlDocument, val summary: KmlExportSummary) {
    val isEmpty: Boolean get() = document.folders.isEmpty()
}

/**
 * Prepara la exportación a KML o KMZ (S031/RF-20 a RF-27, RF-32, plan A5): de los puntos, las líneas y los polígonos del proyecto a las
 * carpetas por código con sus marcas, sus estilos, sus datos extendidos y su geometría, y al resumen. No escribe nada ni modifica nada del
 * proyecto (RF-31); de escribirlo se encarga [KmlWriter].
 *
 * Longitud y latitud son las del datum de los cálculos del proyecto (RF-24); la altura, la del proyecto (RF-26). Los datos extendidos son las
 * columnas de «Todas las columnas» de S012, la del código y una por atributo de código (S024/RF-21), con los valores de S055/RF-6: sin
 * redondear, en metros y grados, un booleano como 0 o 1 y vacío lo que el punto no tiene (RF-21).
 */
class KmlExportBuilder {

    /**
     * Las marcas de [points] y, de [lines], las que se escriben, con [options]. [allPoints] son todos los puntos del proyecto, de donde salen los
     * vértices de las líneas (con una selección, [points] son solo algunos). [baseProjection] da la base de un punto en el sistema del proyecto
     * (S015/RF-22); sin ella esas columnas van vacías. [photoNames] da los nombres de las fotos que un punto lleva en el KMZ (RF-28).
     */
    fun build(
        points: List<Point>,
        project: Project,
        options: KmlExportOptions = KmlExportOptions(),
        baseProjection: (PointBase) -> BaseProjection? = { null },
        lines: List<ProjectLine> = emptyList(),
        allPoints: List<Point> = points,
        photoNames: (Point) -> List<String> = { emptyList() },
    ): KmlExport {
        if (points.isEmpty()) return KmlExport(KmlDocument(project.name, emptyList(), emptyList()), KmlExportSummary())
        val attributes = AttributeColumns.of(points)
        val folders = Folders(project.name)
        var withoutPosition = 0
        var withoutHeight = 0
        var pointCount = 0
        for (point in points) {
            val vertex = vertexOf(point.coordinates)
            if (vertex == null) {
                withoutPosition++
                continue
            }
            val absolute = options.includeHeight && vertex.height != null
            if (options.includeHeight && vertex.height == null) withoutHeight++
            val projected = point.measurement?.base?.let(baseProjection)
            val data = ArrayList<Pair<String, String>>()
            for (column in ExportColumn.entries) data += column.header to text(GpkgPointAttributes.value(point, column, project, projected))
            for (column in attributes) data += column.header to text(GpkgPointAttributes.attributeValue(point, column))
            folders.add(
                point.code,
                KmlMark(point.name, point.description, null, data, KmlGeometry.Point(vertex), absolute, photoNames(point)),
            )
            pointCount++
        }
        val features = LineFeatures.of(lines, allPoints, options)
        var lineCount = 0
        var polygonCount = 0
        for (feature in features) {
            val vertices = feature.vertices
            if (options.includeHeight && !feature.complete) withoutHeight++
            val geometry = if (feature.line.closed) KmlGeometry.Polygon(ring(vertices)) else KmlGeometry.Line(vertices)
            val code = feature.line.code
            val data = listOf(
                "Name" to feature.line.name,
                "Code" to code?.code.orEmpty(),
                "Vertices" to vertices.size.toString(),
            ) + AttributeColumns.ofCodes(listOf(code)).map { it.header to text(GpkgPointAttributes.attributeValue(code, it)) }
            folders.add(code, KmlMark(feature.line.name, "", null, data, geometry, options.includeHeight && feature.complete))
            if (feature.line.closed) polygonCount++ else lineCount++
        }
        val document = folders.document()
        val summary = KmlExportSummary(
            points = pointCount,
            lines = lineCount,
            polygons = polygonCount,
            folders = document.folders.size,
            pointsWithoutPosition = withoutPosition,
            marksWithoutHeight = withoutHeight,
        )
        return KmlExport(document, summary)
    }

    /**
     * Cuántas líneas y cuántos polígonos se escribirían con [points] y [lines], para mostrarlos antes de exportar (S031/RF-20, RF-22): sin
     * construir el documento. Sin puntos no se escribe nada.
     */
    fun countLines(points: List<Point>, lines: List<ProjectLine>, allPoints: List<Point> = points): Pair<Int, Int> {
        if (points.isEmpty()) return 0 to 0
        val features = LineFeatures.of(lines, allPoints, KmlExportOptions())
        return features.count { !it.line.closed } to features.count { it.line.closed }
    }

    /** La posición de [c] en longitud y latitud del datum de los cálculos y su altura del proyecto; `null` si no tiene posición (RF-25). */
    private fun vertexOf(c: PointCoordinates): KmlVertex? {
        val lon = c.ground.longitudeDeg
        val lat = c.ground.latitudeDeg
        if (!lon.isFinite() || !lat.isFinite()) return null
        return KmlVertex(lon, lat, c.height?.takeIf { it.isFinite() })
    }

    /** Un valor de [GpkgPointAttributes] como texto: un booleano es 0 o 1, un decimal sin redondear y lo que falta, vacío (S031/RF-21). */
    private fun text(value: Any?): String = when (value) {
        null -> ""
        is Boolean -> if (value) "1" else "0"
        is Double -> if (value.isFinite()) KmlNumber.plain(value) else ""
        else -> value.toString()
    }

    /** El contorno de un polígono en sentido antihorario y cerrado, conservando su primer vértice (S031/RF-22). */
    private fun ring(vertices: List<KmlVertex>): List<KmlVertex> {
        val open = if (vertices.size > 1 && vertices.first().lon == vertices.last().lon && vertices.first().lat == vertices.last().lat) {
            vertices.dropLast(1)
        } else {
            vertices
        }
        val oriented = if (signedArea(open) < 0) listOf(open.first()) + open.drop(1).reversed() else open
        return oriented + oriented.first()
    }

    /** El doble del área con signo (positiva en sentido antihorario), con el primer vértice como origen para no perder cifras. */
    private fun signedArea(vertices: List<KmlVertex>): Double {
        val origin = vertices.first()
        var sum = 0.0
        for (i in 1 until vertices.size - 1) {
            val a = vertices[i]
            val b = vertices[i + 1]
            sum += (a.lon - origin.lon) * (b.lat - origin.lat) - (b.lon - origin.lon) * (a.lat - origin.lat)
        }
        return sum
    }

    /** Las carpetas por código en orden de aparición, con la del proyecto al final para lo que no tiene código (S031/RF-23). */
    private class Folders(private val projectName: String) {
        private val byCode = LinkedHashMap<String, MutableList<KmlMark>>()
        private val codes = LinkedHashMap<String, PointCode>()
        private val loose = ArrayList<KmlMark>()

        fun add(code: PointCode?, mark: KmlMark) {
            if (code == null) {
                loose += mark
            } else {
                codes.putIfAbsent(code.code, code)
                byCode.getOrPut(code.code) { ArrayList() } += mark
            }
        }

        fun document(): KmlDocument {
            val ids = styleIds()
            val styles = codes.values.map { KmlStyleDef(ids.getValue(it.code), OPAQUE or (it.color and RGB)) }
            val folders = byCode.map { (code, marks) ->
                KmlFolder(code, marks.map { it.withStyle(ids.getValue(code)) })
            } + if (loose.isEmpty()) emptyList() else listOf(KmlFolder(projectName, loose))
            return KmlDocument(projectName, styles, folders)
        }

        /** Un identificador de estilo por código: el código con solo letras, cifras, `_` y `-`, y un número si se repite. */
        private fun styleIds(): Map<String, String> {
            val taken = HashSet<String>()
            val ids = LinkedHashMap<String, String>()
            for (code in codes.keys) {
                val clean = code.map { if (isIdChar(it)) it else '_' }.joinToString("")
                val base = clean.ifEmpty { "codigo" }
                var candidate = base
                var n = 2
                while (!taken.add(candidate)) candidate = "${base}_${n++}"
                ids[code] = candidate
            }
            return ids
        }

        private fun isIdChar(c: Char) = (c.isLetterOrDigit() && c.code < ASCII_LIMIT) || c == '_' || c == '-'

        private fun KmlMark.withStyle(id: String) = KmlMark(name, description, id, data, geometry, absolute, photos)

        private companion object {
            const val OPAQUE = 0xFF000000.toInt()
            const val RGB = 0xFFFFFF
            const val ASCII_LIMIT = 128
        }
    }
}

/** Una línea o un polígono con los vértices que tienen posición y si todos traen altura (S031/RF-22, RF-27). */
internal class LineFeature(val line: ProjectLine, val vertices: List<KmlVertex>, val complete: Boolean)

internal object LineFeatures {
    private const val MIN_LINE_VERTICES = 2
    private const val MIN_POLYGON_VERTICES = 3

    /**
     * Las líneas que se escriben: sus vértices salen de [allPoints] con sus coordenadas actuales; los puntos sin posición no cuentan, y una
     * línea con menos de 2 vértices, o un polígono con menos de 3, no se escribe (como S055 y S053).
     */
    fun of(lines: List<ProjectLine>, allPoints: List<Point>, options: KmlExportOptions): List<LineFeature> {
        if (lines.isEmpty()) return emptyList()
        val byId = allPoints.associateBy { it.id }
        return lines.mapNotNull { line ->
            val vertices = line.vertices.mapNotNull { vertex ->
                val c = byId[vertex.pointId]?.coordinates ?: return@mapNotNull null
                val lon = c.ground.longitudeDeg
                val lat = c.ground.latitudeDeg
                if (!lon.isFinite() || !lat.isFinite()) null else KmlVertex(lon, lat, c.height?.takeIf { it.isFinite() })
            }
            val minimum = if (line.closed) MIN_POLYGON_VERTICES else MIN_LINE_VERTICES
            if (vertices.size >= minimum) LineFeature(line, vertices, vertices.all { it.height != null }) else null
        }
    }
}
