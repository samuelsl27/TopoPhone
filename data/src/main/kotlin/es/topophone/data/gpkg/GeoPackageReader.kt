package es.topophone.data.gpkg

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import es.topophone.core.formats.gpkg.GpkgCrs
import es.topophone.core.formats.gpkg.GpkgCrsCatalog
import es.topophone.core.formats.gpkg.GpkgFileKind
import es.topophone.core.formats.gpkg.GpkgProbe
import es.topophone.core.model.cad.CadOrigin
import es.topophone.core.model.cad.GpkgColumn
import es.topophone.core.model.cad.GpkgLayerInfo
import es.topophone.core.model.cad.GpkgValue
import es.topophone.core.survey.LayerBox
import es.topophone.data.cad.FeatureSession
import es.topophone.data.cad.GpkgFeature
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Por qué no se abre un GeoPackage (S048/RF-3, RF-4). */
enum class GpkgOpenFailure { EMPTY, NOT_GEOPACKAGE, DAMAGED, NO_LAYERS, TOO_LARGE, NO_SPACE, READ_ERROR }

sealed interface GpkgOpenResult {
    class Opened(val session: GpkgSession) : GpkgOpenResult

    data class Failed(val reason: GpkgOpenFailure) : GpkgOpenResult
}

/**
 * Abre un GeoPackage para leerlo (S048/RF-1 a RF-7, RNF-T5). `SQLiteDatabase` solo abre rutas, así que el flujo del selector de
 * documentos se copia a un fichero temporal de [cacheDir] (decisión D1 del plan; como mucho 100 MB, y se comprueba antes que cabe) y
 * se abre de solo lectura. El temporal se borra al cerrar la sesión, al fallar, al cancelar y al arrancar la app. El original nunca se
 * modifica. Un fichero en modo WAL se lee con lo que tiene consolidado el fichero principal: lo que esté solo en el `-wal` no se copia.
 */
class GeoPackageReader(
    private val cacheDir: File,
    private val freeSpace: () -> Long = { cacheDir.usableSpace },
    private val newId: () -> String = { UUID.randomUUID().toString() },
    private val maxBytes: Long = GpkgFileKind.MAX_BYTES,
) {
    /**
     * Abre el fichero que da [source] (un flujo nuevo en cada llamada) y que mide [sizeBytes]. Cancelar la corrutina borra el temporal.
     */
    suspend fun open(sizeBytes: Long, source: () -> InputStream): GpkgOpenResult = withContext(Dispatchers.IO) {
        val head = try {
            source().use { GpkgFileKind.readHead(it) }
        } catch (e: IOException) {
            return@withContext GpkgOpenResult.Failed(GpkgOpenFailure.READ_ERROR)
        }
        when (GpkgFileKind.probe(head, sizeBytes)) {
            GpkgProbe.Empty -> return@withContext GpkgOpenResult.Failed(GpkgOpenFailure.EMPTY)
            GpkgProbe.NotSqlite -> return@withContext GpkgOpenResult.Failed(GpkgOpenFailure.NOT_GEOPACKAGE)
            GpkgProbe.Damaged -> return@withContext GpkgOpenResult.Failed(GpkgOpenFailure.DAMAGED)
            GpkgProbe.TooLarge -> return@withContext GpkgOpenResult.Failed(GpkgOpenFailure.TOO_LARGE)
            is GpkgProbe.Candidate -> Unit
        }
        cacheDir.mkdirs()
        if (freeSpace() < sizeBytes + SPACE_MARGIN_BYTES) return@withContext GpkgOpenResult.Failed(GpkgOpenFailure.NO_SPACE)

        val file = File(cacheDir, "$PREFIX${newId()}$SUFFIX")
        var db: SQLiteDatabase? = null
        try {
            copy(source, file)
            db = SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY)
            val session = describe(db, file)
            session?.let { GpkgOpenResult.Opened(it) } ?: GpkgOpenResult.Failed(GpkgOpenFailure.NOT_GEOPACKAGE).also { discard(db, file) }
        } catch (e: TooLargeStream) {
            discard(db, file)
            GpkgOpenResult.Failed(GpkgOpenFailure.TOO_LARGE)
        } catch (e: IOException) {
            discard(db, file)
            GpkgOpenResult.Failed(GpkgOpenFailure.READ_ERROR)
        } catch (e: SQLiteException) {
            discard(db, file)
            GpkgOpenResult.Failed(GpkgOpenFailure.DAMAGED)
        } catch (e: NoLayers) {
            discard(db, file)
            GpkgOpenResult.Failed(GpkgOpenFailure.NO_LAYERS)
        } catch (e: Throwable) {
            discard(db, file) // incluida la cancelación: no queda ni el temporal ni la base abierta
            throw e
        }
    }

    /** Borra los temporales que dejó una importación interrumpida (la app murió a mitad); devuelve cuántos ficheros. */
    fun cleanUpStale(): Int = cacheDir.listFiles { f -> f.name.startsWith(PREFIX) }.orEmpty().count { it.delete() }

    private class TooLargeStream : IOException()

    private class NoLayers : Exception()

    private suspend fun copy(source: () -> InputStream, file: File) {
        val context = currentCoroutineContext()
        source().use { input ->
            file.outputStream().use { output ->
                val buffer = ByteArray(BUFFER_BYTES)
                var total = 0L
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    total += read
                    if (total > maxBytes) throw TooLargeStream()
                    output.write(buffer, 0, read)
                    context.ensureActive()
                }
            }
        }
    }

    private fun discard(db: SQLiteDatabase?, file: File) {
        runCatching { db?.close() }
        deleteWithSideFiles(file)
    }

    /** La sesión del GeoPackage abierto, o null si no tiene las tablas de un GeoPackage. */
    private fun describe(db: SQLiteDatabase, file: File): GpkgSession? {
        val tables = db.stringColumn("SELECT name FROM sqlite_master WHERE type = 'table' ORDER BY rowid")
        if (!tables.containsAll(REQUIRED_TABLES)) return null
        val layers = ArrayList<GpkgLayerInfo>()
        val crs = HashMap<String, GpkgCrs>()
        val keys = HashMap<String, String>()
        val query = "SELECT c.table_name, g.column_name, g.geometry_type_name, g.srs_id FROM gpkg_contents c " +
            "JOIN gpkg_geometry_columns g ON g.table_name = c.table_name WHERE c.data_type = 'features' ORDER BY c.rowid"
        db.rawQuery(query, null).use { cursor ->
            while (cursor.moveToNext()) {
                val table = cursor.getString(0)
                if (table !in tables) continue // gpkg_contents lista una tabla que no está: se ignora
                val geometryColumn = cursor.getString(1)
                val srsId = cursor.getInt(3)
                val columns = db.tableColumns(table)
                if (columns.none { it.name == geometryColumn }) continue
                val pk = columns.firstOrNull { it.pk }?.name
                layers += GpkgLayerInfo(
                    table = table,
                    geometryColumn = geometryColumn,
                    geometryType = cursor.getString(2),
                    featureCount = db.longValue("SELECT COUNT(*) FROM ${quote(table)}"),
                    srsId = srsId,
                    columns = columns.filter { !it.pk && it.name != geometryColumn }.map { GpkgColumn(it.name, it.type) },
                    hasSpatialIndex = "rtree_${table}_$geometryColumn" in tables,
                )
                crs[table] = crsOf(db, srsId)
                keys[table] = pk ?: "rowid"
            }
        }
        if (layers.isEmpty()) throw NoLayers()
        val layerTables = layers.map { it.table }.toSet()
        val leftOut = tables.filter { !isGpkgInternal(it) && it !in layerTables }
        return GpkgSession(db, file, layers, leftOut, crs, keys)
    }

    private fun isGpkgInternal(table: String): Boolean =
        table.startsWith("sqlite_") || table.startsWith("gpkg_") || table.startsWith("rtree_")

    private fun crsOf(db: SQLiteDatabase, srsId: Int): GpkgCrs = db.rawQuery(
        "SELECT organization, organization_coordsys_id FROM gpkg_spatial_ref_sys WHERE srs_id = ?",
        arrayOf(srsId.toString()),
    ).use {
        if (it.moveToFirst()) GpkgCrsCatalog.resolve(srsId, it.getString(0), it.getInt(1)) else GpkgCrs.Undeclared
    }

    private class ColumnInfo(val name: String, val type: String, val pk: Boolean)

    private fun SQLiteDatabase.tableColumns(table: String): List<ColumnInfo> = rawQuery("PRAGMA table_info(${quote(table)})", null).use {
        buildList { while (it.moveToNext()) add(ColumnInfo(it.getString(1), it.getString(2).orEmpty(), it.getInt(5) > 0)) }
    }

    private fun SQLiteDatabase.stringColumn(sql: String): List<String> = rawQuery(sql, null).use {
        buildList { while (it.moveToNext()) add(it.getString(0)) }
    }

    private fun SQLiteDatabase.longValue(sql: String): Long = rawQuery(sql, null).use { if (it.moveToFirst()) it.getLong(0) else 0L }

    companion object {
        const val PREFIX = "gpkg-"
        const val SUFFIX = ".tmp"
        private const val BUFFER_BYTES = 64 * 1024
        private const val SPACE_MARGIN_BYTES = 8L * 1024 * 1024
        private val REQUIRED_TABLES = listOf("gpkg_contents", "gpkg_geometry_columns", "gpkg_spatial_ref_sys")

        /** Un nombre de tabla o de columna como identificador de SQL, a salvo de las comillas del fichero. */
        internal fun quote(name: String): String = "\"" + name.replace("\"", "\"\"") + "\""

        internal fun deleteWithSideFiles(file: File) {
            for (suffix in listOf("", "-wal", "-shm", "-journal")) File(file.path + suffix).delete()
        }
    }
}

/**
 * Un GeoPackage abierto de solo lectura. [close] cierra la base y borra el temporal; es seguro llamarlo varias veces.
 */
class GpkgSession internal constructor(
    private val db: SQLiteDatabase,
    private val file: File,
    override val layers: List<GpkgLayerInfo>,
    /** Tablas que no son capas con geometría (atributos, teselas, ajenas) y que no se importan (S048/RF-6). */
    override val leftOutTables: List<String>,
    private val crsByTable: Map<String, GpkgCrs>,
    private val keyByTable: Map<String, String>,
) : FeatureSession {

    override val origin: CadOrigin get() = CadOrigin.GEOPACKAGE

    val isReadOnly: Boolean get() = db.isReadOnly

    /** El sistema de coordenadas que declara la capa, frente a los de la app (RF-8, RF-9). */
    override fun crs(layer: GpkgLayerInfo): GpkgCrs = crsByTable.getValue(layer.table)

    /**
     * Recorre los objetos de [layer], uno a uno y sin cargarlos todos. Con [box] y una capa con índice espacial, solo los objetos
     * cuya envolvente corta el recuadro (en el sistema de la capa); sin índice, todos: el prefiltro es de [GeoPackageScan].
     */
    override fun forEachFeature(layer: GpkgLayerInfo, box: LayerBox?, action: (GpkgFeature) -> Unit) {
        val key = GeoPackageReader.quote(keyByTable.getValue(layer.table))
        val table = GeoPackageReader.quote(layer.table)
        val columns = layer.columns.joinToString("") { ", ${GeoPackageReader.quote(it.name)}" }
        val where = if (box != null && layer.hasSpatialIndex) {
            val rtree = GeoPackageReader.quote("rtree_${layer.table}_${layer.geometryColumn}")
            " WHERE $key IN (SELECT id FROM $rtree WHERE maxx >= ${num(box.minX)} AND minx <= ${num(box.maxX)} " +
                "AND maxy >= ${num(box.minY)} AND miny <= ${num(box.maxY)})"
        } else {
            ""
        }
        val select = "SELECT $key, ${GeoPackageReader.quote(layer.geometryColumn)}$columns FROM $table"
        openCursor(select, where).use { cursor ->
            while (cursor.moveToNext()) {
                val geometry = if (cursor.isNull(1)) null else cursor.getBlob(1)
                action(GpkgFeature(cursor.getLong(0), geometry, layer.columns.mapIndexed { i, column -> cursor.value(i + 2, column.type) }))
            }
        }
    }

    /**
     * La consulta con el índice espacial. Si ese SQLite no puede usarlo (sin el módulo `rtree`, o con la tabla del índice
     * dañada), se leen todos los objetos: el prefiltro solo evita trabajo, la prueba exacta de la zona decide después.
     */
    private fun openCursor(select: String, indexed: String): Cursor {
        if (indexed.isEmpty()) return db.rawQuery(select, null)
        return try {
            db.rawQuery(select + indexed, null)
        } catch (e: SQLiteException) {
            db.rawQuery(select, null)
        }
    }

    /**
     * Recorre la clave y los primeros 40 bytes de la geometría de cada objeto —la cabecera con su envolvente—, sin leer el resto del
     * blob (S048/RF-13). [head] es null si la geometría es NULL.
     */
    override fun forEachHead(layer: GpkgLayerInfo, action: (fid: Long, head: ByteArray?) -> Unit) {
        val key = GeoPackageReader.quote(keyByTable.getValue(layer.table))
        val sql = "SELECT $key, substr(${GeoPackageReader.quote(
            layer.geometryColumn,
        )}, 1, $HEAD_BYTES) FROM ${GeoPackageReader.quote(layer.table)}"
        db.rawQuery(sql, null).use { cursor ->
            while (cursor.moveToNext()) action(cursor.getLong(0), if (cursor.isNull(1)) null else cursor.getBlob(1))
        }
    }

    /**
     * Recorre las envolventes del índice espacial de la capa, sin tocar las geometrías. Devuelve false si no hay índice o este SQLite no
     * lo puede consultar, y entonces no se ha llamado a [action]. El R-tree guarda coordenadas de 32 bits redondeadas hacia fuera.
     */
    override fun forEachIndexRow(
        layer: GpkgLayerInfo,
        action: (fid: Long, minX: Double, maxX: Double, minY: Double, maxY: Double) -> Unit,
    ): Boolean {
        if (!layer.hasSpatialIndex) return false
        val rtree = GeoPackageReader.quote("rtree_${layer.table}_${layer.geometryColumn}")
        val cursor = try {
            db.rawQuery("SELECT id, minx, maxx, miny, maxy FROM $rtree", null)
        } catch (e: SQLiteException) {
            return false
        }
        cursor.use {
            while (it.moveToNext()) action(it.getLong(0), it.getDouble(1), it.getDouble(2), it.getDouble(3), it.getDouble(4))
        }
        return true
    }

    /** Las geometrías completas de esos objetos, de [BATCH] en [BATCH]. [geometry] es null si es NULL. */
    override fun forEachGeometry(layer: GpkgLayerInfo, ids: LongArray, action: (fid: Long, geometry: ByteArray?) -> Unit) {
        val key = GeoPackageReader.quote(keyByTable.getValue(layer.table))
        val select = "SELECT $key, ${GeoPackageReader.quote(
            layer.geometryColumn,
        )} FROM ${GeoPackageReader.quote(layer.table)} WHERE $key IN ("
        for (start in ids.indices step BATCH) {
            val batch = ids.copyOfRange(start, minOf(start + BATCH, ids.size)).joinToString(",")
            db.rawQuery("$select$batch)", null).use { cursor ->
                while (cursor.moveToNext()) action(cursor.getLong(0), if (cursor.isNull(1)) null else cursor.getBlob(1))
            }
        }
    }

    // Double.toString escribe siempre con punto y sin separadores, sea cual sea la configuración regional del móvil.
    private fun num(value: Double): String = value.toString()

    private companion object {
        const val HEAD_BYTES = 40
        const val BATCH = 500
    }

    private fun Cursor.value(index: Int, declaredType: String): GpkgValue {
        val type = declaredType.uppercase(Locale.ROOT)
        return when (getType(index)) {
            Cursor.FIELD_TYPE_NULL -> GpkgValue.Null
            Cursor.FIELD_TYPE_INTEGER -> getLong(index).let { if (type == "BOOLEAN") GpkgValue.Bool(it != 0L) else GpkgValue.Integer(it) }
            Cursor.FIELD_TYPE_FLOAT -> GpkgValue.Real(getDouble(index))
            Cursor.FIELD_TYPE_BLOB -> getBlob(index).let {
                if (it.size > GpkgValue.BLOB_LIMIT_BYTES) GpkgValue.Omitted(it.size) else GpkgValue.Blob(it)
            }
            else -> getString(index).let { if (type == "DATE" || type == "DATETIME") GpkgValue.Date(it) else GpkgValue.Text(it) }
        }
    }

    override fun close() {
        runCatching { db.close() }
        GeoPackageReader.deleteWithSideFiles(file)
    }
}
