package es.topophone.core.formats.shp.export

import es.topophone.core.formats.csv.AttributeColumns
import es.topophone.core.formats.csv.BaseProjection
import es.topophone.core.formats.csv.ExportColumn
import es.topophone.core.formats.gpkg.GpkgAttributeType
import es.topophone.core.formats.gpkg.GpkgPointAttributes
import es.topophone.core.model.line.ProjectLine
import es.topophone.core.model.point.Point
import es.topophone.core.model.point.PointBase
import es.topophone.core.model.point.PointCoordinates
import es.topophone.core.model.project.HeightSystem
import es.topophone.core.model.project.Project

/**
 * Prepara las capas de la exportación a Shapefile (S055/RF-1 a RF-3, RF-6, RF-7, RF-12 a RF-14b, RF-22, plan A1): de los puntos, las
 * líneas y los polígonos del proyecto a las columnas, los valores y las geometrías de cada capa, con su `.prj` y el resumen. No escribe
 * nada ni modifica nada del proyecto (RF-21); de escribirlo se encargan [ShapefileZipWriter], [DbfWriter] y [ShpWriter].
 *
 * Los valores de la capa de puntos son los de [GpkgPointAttributes], los mismos de S053 y sin redondear: las longitudes en metros y los
 * ángulos en grados, sin pasar por la unidad del proyecto (RF-6).
 */
class ShpExportBuilder(private val prj: ShpPrjDefinitions = ShpPrjDefinitions()) {

    /**
     * Las capas de [points], y de [lines] si las hay, en el sistema de [options] y el idioma de los nombres [language]. [allPoints] son
     * todos los puntos del proyecto, de donde salen los vértices de las líneas (con una selección, [points] son solo algunos).
     * [baseProjection] da la base de un punto en el sistema del proyecto (S015/RF-22); sin ella esas columnas van vacías.
     */
    fun build(
        points: List<Point>,
        project: Project,
        options: ShpExportOptions = ShpExportOptions(),
        baseProjection: (PointBase) -> BaseProjection? = { null },
        language: ShpNameLanguage = ShpNameLanguage.ES,
        lines: List<ProjectLine> = emptyList(),
        allPoints: List<Point> = points,
    ): ShpExport {
        val placement = Placement(project, options)
        val counts = Counts()
        val renamed = LinkedHashSet<ShpRenamedColumn>()
        val definition = if (placement.geographic) prj.geographicOf(project.crs) else prj.of(project.crs)
        val layers = ArrayList<ShpLayer>(3)
        if (points.isNotEmpty()) layers += pointLayer(points, project, placement, baseProjection, language, definition, counts, renamed)
        val features = if (points.isEmpty()) emptyList() else features(lines, allPoints, placement)
        for (kind in listOf(ShpLayerKind.LINES, ShpLayerKind.POLYGONS)) {
            val ofKind = features.filter { it.line.closed == (kind == ShpLayerKind.POLYGONS) }
            if (ofKind.isEmpty()) continue
            if (kind == ShpLayerKind.LINES) counts.lines = ofKind.size else counts.polygons = ofKind.size
            layers += lineLayer(kind, ofKind, placement, language, definition, counts, renamed)
        }
        val summary = ShpExportSummary(
            points = counts.points,
            lines = counts.lines,
            polygons = counts.polygons,
            pointsWithoutPosition = counts.withoutPosition,
            verticesWithoutHeight = counts.withoutHeight,
            renamedAttributes = renamed.toList(),
            prjMissing = definition == null,
            geographic = placement.geographic,
            epsg = if (placement.geographic) ShpPrjDefinitions.geographicEpsg(project.crs) else project.crs.epsg,
            heightSystem = placement.heightSystem,
        )
        return ShpExport(layers, summary)
    }

    /**
     * Cuántas líneas y cuántos polígonos se escribirían con [points] y [lines] en el sistema de [options], para mostrarlos antes de exportar
     * (S055/RF-3): sin construir las capas.
     */
    fun countLines(
        points: List<Point>,
        project: Project,
        options: ShpExportOptions,
        lines: List<ProjectLine>,
        allPoints: List<Point> = points,
    ): Pair<Int, Int> {
        if (points.isEmpty()) return 0 to 0
        val all = features(lines, allPoints, Placement(project, options))
        return all.count { !it.line.closed } to all.count { it.line.closed }
    }

    private fun pointLayer(
        points: List<Point>,
        project: Project,
        placement: Placement,
        baseProjection: (PointBase) -> BaseProjection?,
        language: ShpNameLanguage,
        definition: String?,
        counts: Counts,
        renamed: MutableSet<ShpRenamedColumn>,
    ): ShpLayer {
        val attributes = AttributeColumns.of(points)
        val fixedNames = ShpColumnNames.fixedPointNames(language)
        val attributeNames = ShpColumnNames.forAttributes(attributes, fixedNames)
        renamed += attributeNames.renamed
        val columns = ExportColumn.entries.mapIndexed { i, column ->
            DbfColumn(fixedNames[i], dbfType(GpkgPointAttributes.typeOf(column)))
        } + attributes.mapIndexed { i, column -> DbfColumn(attributeNames.names[i], dbfType(GpkgPointAttributes.typeOf(column))) }
        val rows = ArrayList<Array<Any?>>(points.size)
        val geometries = ArrayList<ShpGeometry>(points.size)
        for (point in points) {
            val projected = point.measurement?.base?.let(baseProjection)
            val row = arrayOfNulls<Any?>(columns.size)
            ExportColumn.entries.forEachIndexed { i, column -> row[i] = cell(GpkgPointAttributes.value(point, column, project, projected)) }
            val offset = ExportColumn.entries.size
            attributes.forEachIndexed { i, column -> row[offset + i] = cell(GpkgPointAttributes.attributeValue(point, column)) }
            rows += row
            val vertex = placement.vertex(point.coordinates)
            geometries += if (vertex == null) {
                counts.withoutPosition++
                ShpGeometry.Null
            } else {
                if (!placement.hasHeight(point.coordinates)) counts.withoutHeight++
                ShpGeometry.Point(vertex)
            }
        }
        counts.points = points.size
        return ShpLayer(ShpLayerKind.POINTS, columns, rows, geometries, definition)
    }

    /** Una línea o un polígono con los vértices que tienen posición, y cuántos de ellos no tienen altura. */
    private class Feature(val line: ProjectLine, val vertices: List<ShpVertex>, val withoutHeight: Int)

    /**
     * Las líneas que se escriben: sus vértices salen de [allPoints] con sus coordenadas actuales; los puntos sin posición no cuentan, y una
     * línea con menos de 2 vértices, o un polígono con menos de 3, no se escribe (como S053).
     */
    private fun features(lines: List<ProjectLine>, allPoints: List<Point>, placement: Placement): List<Feature> {
        if (lines.isEmpty()) return emptyList()
        val byId = allPoints.associateBy { it.id }
        return lines.mapNotNull { line ->
            var withoutHeight = 0
            val vertices = line.vertices.mapNotNull { vertex ->
                val coordinates = byId[vertex.pointId]?.coordinates ?: return@mapNotNull null
                placement.vertex(coordinates)?.also { if (!placement.hasHeight(coordinates)) withoutHeight++ }
            }
            val minimum = if (line.closed) MIN_POLYGON_VERTICES else MIN_LINE_VERTICES
            if (vertices.size >= minimum) Feature(line, vertices, withoutHeight) else null
        }
    }

    private fun lineLayer(
        kind: ShpLayerKind,
        features: List<Feature>,
        placement: Placement,
        language: ShpNameLanguage,
        definition: String?,
        counts: Counts,
        renamed: MutableSet<ShpRenamedColumn>,
    ): ShpLayer {
        val attributes = AttributeColumns.ofCodes(features.map { it.line.code })
        val fixedNames = ShpColumnNames.fixedLineNames(language)
        val attributeNames = ShpColumnNames.forAttributes(attributes, fixedNames)
        renamed += attributeNames.renamed
        val columns = listOf(
            DbfColumn(fixedNames[0], DbfType.TEXT),
            DbfColumn(fixedNames[1], DbfType.TEXT),
            DbfColumn(fixedNames[2], DbfType.INTEGER),
        ) + attributes.mapIndexed { i, column -> DbfColumn(attributeNames.names[i], dbfType(GpkgPointAttributes.typeOf(column))) }
        val rows = ArrayList<Array<Any?>>(features.size)
        val geometries = ArrayList<ShpGeometry>(features.size)
        for (feature in features) {
            val code = feature.line.code
            val row = arrayOfNulls<Any?>(columns.size)
            row[0] = feature.line.name
            row[1] = code?.code
            row[2] = feature.vertices.size.toLong()
            attributes.forEachIndexed { i, column -> row[LINE_FIXED_COLUMNS + i] = cell(GpkgPointAttributes.attributeValue(code, column)) }
            rows += row
            geometries += if (kind == ShpLayerKind.POLYGONS) ShpGeometry.Polygon(feature.vertices) else ShpGeometry.Line(feature.vertices)
            counts.withoutHeight += feature.withoutHeight
        }
        return ShpLayer(kind, columns, rows, geometries, definition)
    }

    private fun dbfType(type: GpkgAttributeType): DbfType = when (type) {
        GpkgAttributeType.TEXT -> DbfType.TEXT
        GpkgAttributeType.INTEGER, GpkgAttributeType.BOOLEAN -> DbfType.INTEGER
        GpkgAttributeType.REAL -> DbfType.DECIMAL
    }

    /** Un valor de [GpkgPointAttributes] como lo escribe el `.dbf`: un booleano es el entero 0 o 1 (S055, suposición de la spec). */
    private fun cell(value: Any?): Any? = if (value is Boolean) (if (value) 1L else 0L) else value

    private class Counts {
        var points = 0
        var lines = 0
        var polygons = 0
        var withoutPosition = 0
        var withoutHeight = 0
    }

    /**
     * Qué se escribe como X, Y y Z (S055/RF-12, RF-13): en proyectadas, el Este, el Norte y la altura del proyecto; en geográficas, la
     * longitud, la latitud y, si el usuario las eligió en un proyecto proyectado, la altura elipsoidal. Un proyecto geográfico escribe su
     * longitud, su latitud y la altura del proyecto (D2).
     */
    private class Placement(project: Project, options: ShpExportOptions) {
        val geographic: Boolean = options.coordinates == ShpCoordinates.GEOGRAPHIC || !project.crs.isUtm
        private val ellipsoidalZ: Boolean = options.coordinates == ShpCoordinates.GEOGRAPHIC && project.crs.isUtm
        val heightSystem: HeightSystem = if (ellipsoidalZ) HeightSystem.ELLIPSOIDAL else project.heightSystem

        /** El vértice de [c], o `null` si no tiene posición: sin X o sin Y finitas (RF-14b). Sin altura, Z = 0 (RF-14). */
        fun vertex(c: PointCoordinates): ShpVertex? {
            val x = if (geographic) c.ground.longitudeDeg else c.easting
            val y = if (geographic) c.ground.latitudeDeg else c.northing
            if (x == null || y == null || !x.isFinite() || !y.isFinite()) return null
            return ShpVertex(x, y, height(c) ?: 0.0)
        }

        fun hasHeight(c: PointCoordinates): Boolean = height(c) != null

        private fun height(c: PointCoordinates): Double? =
            (if (ellipsoidalZ) c.ground.ellipsoidalHeightM else c.height)?.takeIf { it.isFinite() }
    }

    companion object {
        private const val MIN_LINE_VERTICES = 2
        private const val MIN_POLYGON_VERTICES = 3
        private const val LINE_FIXED_COLUMNS = 3

        /**
         * Con una selección (S055/RF-2), las líneas y los polígonos cuyos vértices están todos entre los puntos elegidos [selected]: una
         * línea con un vértice fuera de la selección no se exporta (como S029/RF-2 y S053).
         */
        fun linesOf(lines: List<ProjectLine>, selected: Set<String>): List<ProjectLine> =
            lines.filter { line -> line.pointIds.all { it in selected } }
    }
}
