package es.topophone.data.gpkg

import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteStatement
import es.topophone.core.formats.csv.AttributeColumns
import es.topophone.core.formats.gpkg.GpkgExportSchema
import es.topophone.core.formats.gpkg.GpkgGeometryWriter
import es.topophone.core.formats.gpkg.GpkgLineSchema
import es.topophone.core.formats.gpkg.GpkgPointAttributes
import es.topophone.core.formats.gpkg.GpkgSrsDefinition
import es.topophone.core.model.line.ProjectLine
import es.topophone.core.model.point.Point
import java.time.Instant
import java.time.format.DateTimeFormatter

/**
 * Las capas `lines` (líneas abiertas) y `polygons` (cerradas) de la exportación a GeoPackage (S025/RF-26, plan D13). Los vértices salen de
 * los puntos con sus coordenadas actuales, en el sistema de la capa de puntos ([geographic] si va en geográficas). Una capa sin elementos no
 * se crea; una línea con menos de 2 vértices con posición, o un polígono con menos de 3, no se escribe. No modifica ni las líneas ni los puntos.
 */
class GpkgLineLayers(
    private val lines: List<ProjectLine>,
    points: List<Point>,
    private val geographic: Boolean,
    private val clock: () -> Long = System::currentTimeMillis,
) : GpkgExtraLayer {
    private val pointsById = points.associateBy { it.id }

    private class Feature(val line: ProjectLine, val vertices: List<GpkgGeometryWriter.Vertex>)

    /** Cuántas líneas y cuántos polígonos se escribirán. */
    val counts: Pair<Int, Int>
        get() = features().let { all -> all.count { !it.line.closed } to all.count { it.line.closed } }

    override fun write(db: SQLiteDatabase, srs: GpkgSrsDefinition) {
        val all = features()
        val open = all.filter { !it.line.closed }
        val closed = all.filter { it.line.closed }
        val columns = AttributeColumns.ofCodes(all.map { it.line.code })
        if (open.isNotEmpty()) writeLayer(db, srs, GpkgLineSchema.LINES_TABLE, "LINESTRING", open, columns)
        if (closed.isNotEmpty()) writeLayer(db, srs, GpkgLineSchema.POLYGONS_TABLE, "POLYGON", closed, columns)
    }

    private fun features(): List<Feature> = lines.mapNotNull { line ->
        val vertices = line.vertices.mapNotNull { vertex ->
            val coordinates = pointsById[vertex.pointId]?.coordinates ?: return@mapNotNull null
            val x = if (geographic) coordinates.ground.longitudeDeg else coordinates.easting
            val y = if (geographic) coordinates.ground.latitudeDeg else coordinates.northing
            if (x == null || y == null || !x.isFinite() || !y.isFinite()) {
                null
            } else {
                GpkgGeometryWriter.Vertex(x, y, coordinates.height?.takeIf { it.isFinite() })
            }
        }
        if (vertices.size >= (if (line.closed) 3 else 2)) Feature(line, vertices) else null
    }

    private fun writeLayer(
        db: SQLiteDatabase,
        srs: GpkgSrsDefinition,
        table: String,
        geometryType: String,
        features: List<Feature>,
        columns: List<es.topophone.core.formats.csv.AttributeColumn>,
    ) {
        db.execSQL(GpkgLineSchema.createTable(table, geometryType, columns))
        var minX = Double.POSITIVE_INFINITY
        var minY = Double.POSITIVE_INFINITY
        var maxX = Double.NEGATIVE_INFINITY
        var maxY = Double.NEGATIVE_INFINITY
        db.compileStatement(GpkgLineSchema.insert(table, columns)).use { insert ->
            for (feature in features) {
                feature.vertices.forEach {
                    minX = minOf(minX, it.x)
                    maxX = maxOf(maxX, it.x)
                    minY = minOf(minY, it.y)
                    maxY = maxOf(maxY, it.y)
                }
                insert.clearBindings()
                insert.bindBlob(
                    1,
                    if (feature.line.closed) {
                        GpkgGeometryWriter.polygon(srs.srsId, feature.vertices)
                    } else {
                        GpkgGeometryWriter.lineString(srs.srsId, feature.vertices)
                    },
                )
                val code = feature.line.code
                bind(insert, 2, feature.line.name)
                bind(insert, 3, code?.code)
                bind(insert, 4, code?.description)
                bind(insert, 5, feature.vertices.size.toLong())
                columns.forEachIndexed { index, column -> bind(insert, 6 + index, GpkgPointAttributes.attributeValue(code, column)) }
                insert.executeInsert()
            }
        }
        db.compileStatement(GpkgExportSchema.INSERT_CONTENTS).use { insert ->
            insert.bindString(1, table)
            insert.bindString(2, table)
            insert.bindString(3, "")
            insert.bindString(4, DateTimeFormatter.ISO_INSTANT.format(Instant.ofEpochMilli(clock())))
            insert.bindDouble(5, minX)
            insert.bindDouble(6, minY)
            insert.bindDouble(7, maxX)
            insert.bindDouble(8, maxY)
            insert.bindLong(9, srs.srsId.toLong())
            insert.executeInsert()
        }
        db.compileStatement(GpkgLineSchema.INSERT_GEOMETRY_COLUMNS).use { insert ->
            insert.bindString(1, table)
            insert.bindString(2, GpkgExportSchema.GEOMETRY_COLUMN)
            insert.bindString(3, geometryType)
            insert.bindLong(4, srs.srsId.toLong())
            insert.executeInsert()
        }
    }

    private fun bind(statement: SQLiteStatement, index: Int, value: Any?) {
        when (value) {
            null -> statement.bindNull(index)
            is String -> statement.bindString(index, value)
            is Double -> if (value.isFinite()) statement.bindDouble(index, value) else statement.bindNull(index)
            is Long -> statement.bindLong(index, value)
            else -> error("Tipo de atributo no admitido: ${value.javaClass}")
        }
    }
}
