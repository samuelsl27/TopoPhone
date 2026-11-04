package es.topophone.data.gpkg

import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteStatement
import es.topophone.core.formats.csv.AttributeColumn
import es.topophone.core.formats.csv.BaseProjection
import es.topophone.core.formats.gpkg.GpkgExportSchema
import es.topophone.core.formats.gpkg.GpkgGeometryWriter
import es.topophone.core.formats.gpkg.GpkgPointAttributes
import es.topophone.core.formats.gpkg.GpkgSrsDefinition
import es.topophone.core.formats.gpkg.GpkgSrsDefinitions
import es.topophone.core.model.point.Point
import es.topophone.core.model.point.PointBase
import es.topophone.core.model.project.Project
import java.io.File
import java.time.Instant
import java.time.format.DateTimeFormatter

/** En qué sistema se escribe la capa de puntos: el del proyecto o las geográficas de su datum (S053/RF-6, RF-7). */
enum class GpkgExportCrs { PROJECT, GEOGRAPHIC }

/**
 * Lo que dice el resumen de una exportación (S053/RF-8, RF-9): los puntos escritos, cuántos van sin geometría porque no tienen Este y Norte
 * en el sistema del proyecto, el sistema declarado y si va con su definición completa por no tener un código reconocido.
 */
data class GpkgExportSummary(val points: Int, val withoutGeometry: Int, val srsId: Int, val customDefinition: Boolean)

/** No se ha podido escribir el fichero (S053/RF-12): no queda ningún fichero. */
class GpkgExportException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Una capa más (líneas o polígonos, que llegan con S025) que se escribe en la misma transacción que la de puntos (S053/RF-3, D4). */
fun interface GpkgExtraLayer {
    fun write(db: SQLiteDatabase, srs: GpkgSrsDefinition)
}

/**
 * Escribe los puntos de un proyecto a un GeoPackage (S053/RF-1 a RF-8, RF-11 a RF-13, plan A4). `SQLiteDatabase` solo crea ficheros por ruta:
 * se escribe en [into] (la caché de la app) todo el esquema y todas las filas en **una transacción**, y si algo falla se borra el fichero.
 * El fichero tiene un solo archivo (journal `DELETE`, sin `-wal`). No modifica los puntos: solo los lee.
 */
class GeoPackageWriter(private val clock: () -> Long = System::currentTimeMillis) {

    /**
     * Escribe [points] en [into], sustituyendo lo que hubiera. Devuelve su resumen, o `null` sin puntos y sin crear el fichero (RF-11).
     * [crs] elige el sistema de la capa (RF-6, RF-7); un proyecto en geográficas siempre las escribe en geográficas. [srs] sustituye al
     * sistema que sale del proyecto, para uno sin código reconocido (RF-8). [baseProjection] da la base de un punto en el sistema del
     * proyecto (S015/RF-22). Lanza [GpkgExportException] si no se puede escribir, sin dejar fichero (RF-12).
     */
    fun write(
        points: List<Point>,
        project: Project,
        crs: GpkgExportCrs,
        into: File,
        baseProjection: (PointBase) -> BaseProjection? = { null },
        srs: GpkgSrsDefinition? = null,
        extraLayers: List<GpkgExtraLayer> = emptyList(),
        /** Los atributos de los códigos de los puntos, una columna tipada por cada uno (S024/RF-21). */
        attributes: List<AttributeColumn> = emptyList(),
    ): GpkgExportSummary? {
        if (points.isEmpty()) return null
        val geographic = crs == GpkgExportCrs.GEOGRAPHIC || !project.crs.isUtm
        val definition = srs ?: if (geographic) GpkgSrsDefinitions.geographicOf(project.crs) else GpkgSrsDefinitions.of(project.crs)
        into.delete()
        File(into.path + "-journal").delete()
        var db: SQLiteDatabase? = null
        try {
            db = SQLiteDatabase.openOrCreateDatabase(into, null)
            db.disableWriteAheadLogging()
            db.execSQL("PRAGMA application_id = ${GpkgExportSchema.APPLICATION_ID}")
            db.version = GpkgExportSchema.USER_VERSION
            db.beginTransaction()
            try {
                val summary = writeAll(db, points, project, geographic, definition, baseProjection, extraLayers, attributes)
                db.setTransactionSuccessful()
                return summary
            } finally {
                db.endTransaction()
            }
        } catch (e: Exception) {
            runCatching { db?.close() }
            db = null
            into.delete()
            File(into.path + "-journal").delete()
            throw GpkgExportException("No se ha podido escribir el GeoPackage", e)
        } finally {
            runCatching { db?.close() }
        }
    }

    private fun writeAll(
        db: SQLiteDatabase,
        points: List<Point>,
        project: Project,
        geographic: Boolean,
        definition: GpkgSrsDefinition,
        baseProjection: (PointBase) -> BaseProjection?,
        extraLayers: List<GpkgExtraLayer>,
        attributes: List<AttributeColumn>,
    ): GpkgExportSummary {
        GpkgExportSchema.createTables.forEach(db::execSQL)
        val systems = GpkgSrsDefinitions.mandatory.filter { it.srsId != definition.srsId } + definition
        db.compileStatement(GpkgExportSchema.INSERT_SRS).use { insert ->
            for (s in systems) {
                insert.clearBindings()
                insert.bindString(1, s.name)
                insert.bindLong(2, s.srsId.toLong())
                insert.bindString(3, s.organization)
                insert.bindLong(4, s.organizationCode.toLong())
                insert.bindString(5, s.definition)
                if (s.description != null) insert.bindString(6, s.description!!) else insert.bindNull(6)
                insert.executeInsert()
            }
        }
        db.execSQL(GpkgExportSchema.createPointsTable(attributes))
        val extent = Extent()
        var withoutGeometry = 0
        db.compileStatement(GpkgExportSchema.insertPoint(attributes)).use { insert ->
            for (point in points) {
                val coordinates = point.coordinates
                val x = if (geographic) coordinates.ground.longitudeDeg else coordinates.easting
                val y = if (geographic) coordinates.ground.latitudeDeg else coordinates.northing
                val blob = GpkgGeometryWriter.of(definition.srsId, x, y, coordinates.height)
                if (x == null || y == null || !x.isFinite() || !y.isFinite()) withoutGeometry++ else extent.add(x, y)
                insert.clearBindings()
                insert.bindBlob(1, blob)
                val projected = point.measurement?.base?.let(baseProjection)
                GpkgExportSchema.columns.forEachIndexed { index, column ->
                    bind(insert, index + 2, GpkgPointAttributes.value(point, column, project, projected))
                }
                val offset = GpkgExportSchema.columns.size + 2
                attributes.forEachIndexed { index, column ->
                    bind(insert, offset + index, GpkgPointAttributes.attributeValue(point, column))
                }
                insert.executeInsert()
            }
        }
        writeContents(db, definition, extent)
        extraLayers.forEach { it.write(db, definition) }
        return GpkgExportSummary(points.size, withoutGeometry, definition.srsId, definition.isCustom)
    }

    private fun writeContents(db: SQLiteDatabase, definition: GpkgSrsDefinition, extent: Extent) {
        db.compileStatement(GpkgExportSchema.INSERT_CONTENTS).use { insert ->
            insert.bindString(1, GpkgExportSchema.POINTS_TABLE)
            insert.bindString(2, GpkgExportSchema.POINTS_TABLE)
            insert.bindString(3, "")
            insert.bindString(4, DateTimeFormatter.ISO_INSTANT.format(Instant.ofEpochMilli(clock())))
            if (extent.empty) {
                (5..8).forEach(insert::bindNull)
            } else {
                insert.bindDouble(5, extent.minX)
                insert.bindDouble(6, extent.minY)
                insert.bindDouble(7, extent.maxX)
                insert.bindDouble(8, extent.maxY)
            }
            insert.bindLong(9, definition.srsId.toLong())
            insert.executeInsert()
        }
        db.compileStatement(GpkgExportSchema.INSERT_GEOMETRY_COLUMNS).use { insert ->
            insert.bindString(1, GpkgExportSchema.POINTS_TABLE)
            insert.bindString(2, GpkgExportSchema.GEOMETRY_COLUMN)
            insert.bindLong(3, definition.srsId.toLong())
            insert.executeInsert()
        }
    }

    private fun bind(statement: SQLiteStatement, index: Int, value: Any?) {
        when (value) {
            null -> statement.bindNull(index)
            is String -> statement.bindString(index, value)
            is Double -> if (value.isFinite()) statement.bindDouble(index, value) else statement.bindNull(index)
            is Long -> statement.bindLong(index, value)
            is Boolean -> statement.bindLong(index, if (value) 1L else 0L)
            else -> error("Tipo de atributo no admitido: ${value.javaClass}")
        }
    }

    /** El recuadro de los puntos que tienen geometría, para `gpkg_contents`. */
    private class Extent {
        var minX = Double.POSITIVE_INFINITY
        var minY = Double.POSITIVE_INFINITY
        var maxX = Double.NEGATIVE_INFINITY
        var maxY = Double.NEGATIVE_INFINITY
        val empty: Boolean get() = minX > maxX

        fun add(x: Double, y: Double) {
            minX = minOf(minX, x)
            maxX = maxOf(maxX, x)
            minY = minOf(minY, y)
            maxY = maxOf(maxY, y)
        }
    }
}
