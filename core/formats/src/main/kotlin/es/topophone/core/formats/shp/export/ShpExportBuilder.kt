package es.topophone.core.formats.shp.export

import es.topophone.core.formats.csv.AttributeColumns
import es.topophone.core.formats.csv.BaseProjection
import es.topophone.core.formats.csv.ExportColumn
import es.topophone.core.formats.gpkg.GpkgAttributeType
import es.topophone.core.formats.gpkg.GpkgPointAttributes
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
     * Las capas de [points] en el sistema de [options] y el idioma de los nombres [language]. [baseProjection] da la base de un punto en el
     * sistema del proyecto (S015/RF-22); sin ella esas columnas van vacías.
     */
    fun build(
        points: List<Point>,
        project: Project,
        options: ShpExportOptions = ShpExportOptions(),
        baseProjection: (PointBase) -> BaseProjection? = { null },
        language: ShpNameLanguage = ShpNameLanguage.ES,
    ): ShpExport {
        val placement = Placement(project, options)
        val counts = Counts()
        val renamed = LinkedHashSet<ShpRenamedColumn>()
        val definition = if (placement.geographic) prj.geographicOf(project.crs) else prj.of(project.crs)
        val layers = ArrayList<ShpLayer>(1)
        if (points.isNotEmpty()) layers += pointLayer(points, project, placement, baseProjection, language, definition, counts, renamed)
        val summary = ShpExportSummary(
            points = counts.points,
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

    private fun dbfType(type: GpkgAttributeType): DbfType = when (type) {
        GpkgAttributeType.TEXT -> DbfType.TEXT
        GpkgAttributeType.INTEGER, GpkgAttributeType.BOOLEAN -> DbfType.INTEGER
        GpkgAttributeType.REAL -> DbfType.DECIMAL
    }

    /** Un valor de [GpkgPointAttributes] como lo escribe el `.dbf`: un booleano es el entero 0 o 1 (S055, suposición de la spec). */
    private fun cell(value: Any?): Any? = if (value is Boolean) (if (value) 1L else 0L) else value

    private class Counts {
        var points = 0
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
}
