package es.topophone.data.point

import android.database.sqlite.SQLiteConstraintException
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import androidx.room.withTransaction
import es.topophone.core.model.alignment.slope.SlopeKind
import es.topophone.core.model.codes.AttributeCarryOver
import es.topophone.core.model.codes.CodeSymbol
import es.topophone.core.model.codes.RecentCodes
import es.topophone.core.model.codes.SurveyCode
import es.topophone.core.model.log.AppLog
import es.topophone.core.model.log.LogCategory
import es.topophone.core.model.point.Point
import es.topophone.core.model.point.PointChange
import es.topophone.core.model.point.PointHistoryEntry
import es.topophone.core.model.point.PointOrigin
import es.topophone.core.model.point.PointSnapshot
import es.topophone.core.model.point.PointSummary
import es.topophone.data.db.TopoPhoneDatabase
import es.topophone.data.line.LineHistoryWriter
import es.topophone.data.project.ProjectRepository
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

/** Un punto para la lista de objetos y el mapa, sin las frases NMEA (S011/RF-1, RF-3). */
data class PointSummaryRow(
    val id: String,
    val name: String,
    val description: String,
    val origin: String,
    @ColumnInfo(name = "ground_lat") val groundLat: Double,
    @ColumnInfo(name = "ground_lon") val groundLon: Double,
    @ColumnInfo(name = "has_height") val hasHeight: Boolean,
    @ColumnInfo(name = "out_of_tolerance") val outOfTolerance: Boolean,
    val edited: Boolean,
    val hidden: Boolean,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    /** Cuántos puntos activos del proyecto son replanteos de este (S018/RF-33). */
    @ColumnInfo(name = "stake_count") val stakeCount: Int,
    /** Cuántas fotos tiene el punto (S017b/RF-26). */
    @ColumnInfo(name = "photo_count") val photoCount: Int,
    /** El tipo del punto de talud replanteado, o null (S051/RF-16). */
    @ColumnInfo(name = "slope_kind") val slopeKind: String? = null,
    /** El codigo del punto, su color y su simbolo (S024/RF-10, RF-17). */
    val code: String? = null,
    @ColumnInfo(name = "code_color") val codeColor: Int? = null,
    @ColumnInfo(name = "code_symbol") val codeSymbol: String? = null,
)

/** Los puntos activos de un proyecto son los que no están en la papelera (S011/RF-22). */
@Dao
interface PointDao {
    /** Una inserción: con WAL y `synchronous=FULL`, o queda entera o no queda (RNF-T6). */
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(point: PointEntity)

    /** Varias inserciones en la transacción de quien llama: si una repite nombre, falla y se revierte todo (S028/RF-26). */
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAll(points: List<PointEntity>)

    @Update(onConflict = OnConflictStrategy.ABORT)
    suspend fun update(point: PointEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertHistory(entry: PointHistoryEntity)

    /** Los tramos de las frases NMEA de un punto medido (S017/RF-12). */
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertNmea(chunks: List<PointNmeaEntity>)

    /** Los valores de los atributos del codigo de cada punto (S024/RF-10). */
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAttributes(attributes: List<PointAttributeEntity>)

    @Query("DELETE FROM point_attributes WHERE point_id = :pointId")
    suspend fun deleteAttributes(pointId: String)

    @Query(
        "SELECT a.* FROM point_attributes a JOIN points p ON p.id = a.point_id " +
            "WHERE p.project_id = :projectId AND p.deleted_at IS NULL ORDER BY a.point_id, a.ordinal",
    )
    fun attributesOfProject(projectId: String): Flow<List<PointAttributeEntity>>

    @Query(
        "SELECT a.* FROM point_attributes a JOIN points p ON p.id = a.point_id " +
            "WHERE p.project_id = :projectId AND p.deleted_at IS NULL ORDER BY a.point_id, a.ordinal",
    )
    suspend fun attributesOfProjectNow(projectId: String): List<PointAttributeEntity>

    @Query(
        "SELECT a.* FROM point_attributes a JOIN points p ON p.id = a.point_id " +
            "WHERE p.project_id = :projectId AND p.deleted_at IS NOT NULL ORDER BY a.point_id, a.ordinal",
    )
    fun attributesOfTrash(projectId: String): Flow<List<PointAttributeEntity>>

    @Query("SELECT * FROM point_attributes WHERE point_id = :pointId ORDER BY ordinal")
    fun attributesOf(pointId: String): Flow<List<PointAttributeEntity>>

    @Query("SELECT * FROM point_attributes WHERE point_id = :pointId ORDER BY ordinal")
    suspend fun attributesOfNow(pointId: String): List<PointAttributeEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertHistoryAttributes(attributes: List<PointHistoryAttributeEntity>)

    @Query(
        "SELECT a.* FROM point_history_attributes a JOIN point_history h ON h.id = a.history_id " +
            "WHERE h.point_id = :pointId ORDER BY a.history_id, a.ordinal",
    )
    fun historyAttributes(pointId: String): Flow<List<PointHistoryAttributeEntity>>

    @Query("SELECT text FROM point_nmea WHERE point_id = :pointId AND seq = :seq")
    suspend fun nmeaChunk(pointId: String, seq: Int): String?

    @Query("SELECT * FROM points WHERE project_id = :projectId AND deleted_at IS NULL ORDER BY created_at")
    fun byProject(projectId: String): Flow<List<PointEntity>>

    @Query(
        "SELECT points.id, points.name, points.description, points.origin, points.ground_lat, points.ground_lon, " +
            "points.ground_h IS NOT NULL AS has_height, points.out_of_tolerance <> '' AS out_of_tolerance, points.edited, " +
            "points.hidden, points.created_at, COALESCE(stakes.n, 0) AS stake_count, " +
            "COALESCE(shots.n, 0) AS photo_count, points.slope_kind, points.code, points.code_color, points.code_symbol FROM points " +
            // Una sola agregación de los replanteos del proyecto: una subconsulta por fila sería cuadrática (S011 RNF, 10.000 puntos).
            "LEFT JOIN (SELECT project_id AS p, stake_design_id AS d, COUNT(*) AS n FROM points " +
            "WHERE project_id = :projectId AND deleted_at IS NULL AND stake_design_id IS NOT NULL " +
            "GROUP BY project_id, stake_design_id) stakes ON stakes.d = points.id AND stakes.p = points.project_id " +
            "LEFT JOIN (SELECT point_id, COUNT(*) AS n FROM point_photos WHERE point_id IN " +
            "(SELECT id FROM points WHERE project_id = :projectId) GROUP BY point_id) shots ON shots.point_id = points.id " +
            "WHERE points.project_id = :projectId AND points.deleted_at IS NULL ORDER BY points.created_at DESC",
    )
    fun summaries(projectId: String): Flow<List<PointSummaryRow>>

    @Query("SELECT * FROM points WHERE id = :id")
    fun observe(id: String): Flow<PointEntity?>

    @Query("SELECT * FROM points WHERE id = :id")
    suspend fun byId(id: String): PointEntity?

    /** Los activos para exportar, sin las frases NMEA, que el CSV no usa y pueden ocupar mucho (S012/RF-1, RF-3). */
    @Query(
        "SELECT id, project_id, name, description, origin, antenna_lat, antenna_lon, antenna_h, pole_height, " +
            "ground_lat, ground_lon, ground_h, easting, northing, height, measured_ground_lat, measured_ground_lon, " +
            "measured_ground_h, measured_easting, measured_northing, measured_height, solution, satellites, hdop, " +
            "correction_age, sigma_h, sigma_v, mode, epochs_used, epochs_discarded, from_replay, out_of_tolerance, " +
            "'' AS raw_nmea, measured_from, measured_to, typed_form, typed_first, typed_second, typed_height, " +
            "typed_height_kind, edited, hidden, copy_of, created_at, deleted_at, trash_key, base_lat, base_lon, base_h, " +
            "base_source, base_station, base_antenna_height, baseline, tilt_mode, tilt_verified, tilt, tilt_direction, " +
            "tilt_heading, tilt_max, tilt_sigma_h, tilt_sigma_v, tilt_pole_e, tilt_pole_n, tilt_pole_u, tilt_receiver_pole, " +
            "avg_time, dev_h, dev_v, auto_mode, nmea_chunks, stake_design_id, stake_design_name, stake_design_east, " +
            "stake_design_north, stake_design_height, stake_design_lat, stake_design_lon, stake_design_ground_h, stake_d_east, " +
            "stake_d_north, stake_d_horizontal, stake_d_height, stake_design_elevation, stake_tol_h, stake_tol_v, " +
            "stake_within, stake_cad_drawing, stake_cad_layer, stake_cad_kind, stake_mode, stake_chainage, stake_offset, " +
            "stake_d_lateral, stake_axis, stake_thickness, stake_d_chainage, stake_elev_source, stake_surface_name, " +
            "stake_surface_imported_ms, stake_surface_elev, stake_vertical_offset, import_id, code, code_description, code_color, " +
            "code_symbol, stake_line_id, stake_line_name FROM points " +
            "WHERE project_id = :projectId AND deleted_at IS NULL ORDER BY created_at",
    )
    suspend fun forExport(projectId: String): List<PointEntity>

    @Query("SELECT * FROM point_history WHERE point_id = :pointId ORDER BY changed_at DESC")
    fun history(pointId: String): Flow<List<PointHistoryEntity>>

    @Query("SELECT COUNT(*) FROM points WHERE project_id = :projectId AND deleted_at IS NULL")
    fun count(projectId: String): Flow<Int>

    @Query("SELECT name FROM points WHERE project_id = :projectId AND deleted_at IS NULL ORDER BY created_at DESC LIMIT 1")
    suspend fun lastName(projectId: String): String?

    @Query("SELECT EXISTS(SELECT 1 FROM points WHERE project_id = :projectId AND name = :name AND deleted_at IS NULL)")
    suspend fun nameExists(projectId: String, name: String): Boolean

    @Query("SELECT name FROM points WHERE project_id = :projectId AND deleted_at IS NULL")
    suspend fun names(projectId: String): List<String>

    /** Los codigos de los puntos activos, del mas reciente al mas antiguo (S024/RF-1). */
    @Query(
        "SELECT code FROM points WHERE project_id = :projectId AND deleted_at IS NULL AND code IS NOT NULL " +
            "GROUP BY code ORDER BY MAX(created_at) DESC, MAX(rowid) DESC LIMIT 100",
    )
    fun recentCodes(projectId: String): Flow<List<String>>

    /** Solo las columnas del codigo: las coordenadas y la medida no se tocan (S024/RF-16). */
    @Query(
        "UPDATE points SET code = :code, code_description = :description, code_color = :color, code_symbol = :symbol WHERE id = :id",
    )
    suspend fun setCode(id: String, code: String?, description: String?, color: Int?, symbol: String?)

    @Query("UPDATE points SET hidden = :hidden WHERE id IN (:ids)")
    suspend fun setHidden(ids: List<String>, hidden: Boolean)

    @Query("UPDATE points SET deleted_at = :now, trash_key = id WHERE id IN (:ids) AND deleted_at IS NULL")
    suspend fun moveToTrash(ids: List<String>, now: Long)

    /** Como [moveToTrash] para los puntos activos de una importación; devuelve cuántos (S028/RF-29). */
    @Query("UPDATE points SET deleted_at = :now, trash_key = id WHERE import_id = :importId AND deleted_at IS NULL")
    suspend fun trashImport(importId: String, now: Long): Int

    @Query("SELECT * FROM points WHERE project_id = :projectId AND deleted_at IS NOT NULL ORDER BY deleted_at DESC")
    fun trash(projectId: String): Flow<List<PointEntity>>

    @Query("UPDATE points SET deleted_at = NULL, trash_key = '', name = :name WHERE id = :id AND deleted_at IS NOT NULL")
    suspend fun restore(id: String, name: String)

    @Query("DELETE FROM points WHERE id = :id AND deleted_at IS NOT NULL")
    suspend fun deleteFromTrash(id: String)

    @Query("SELECT id FROM points WHERE deleted_at IS NOT NULL AND deleted_at < :limit")
    suspend fun expiredIds(limit: Long): List<String>

    @Query("DELETE FROM points WHERE deleted_at IS NOT NULL AND deleted_at < :limit")
    suspend fun purge(limit: Long): Int
}

/** Resultado de cambiar el codigo de varios puntos (S024/RF-13). */
sealed interface CodeChangeResult {
    data class Changed(val count: Int) : CodeChangeResult

    /** Algun punto ya no existe: no se cambio ninguno. */
    data object NotFound : CodeChangeResult
}

/** Resultado de guardar un punto. */
sealed interface PointSaveResult {
    data object Saved : PointSaveResult

    /** Ya hay un punto con ese nombre en el proyecto (S010/RF-3, S011/RF-9, RF-25). */
    data object DuplicateName : PointSaveResult

    /** El punto ya no existe. */
    data object NotFound : PointSaveResult
}

/** Un punto de la papelera con los días que le quedan (S011/RF-23). */
data class TrashedPoint(val point: Point, val daysLeft: Int)

/** Puntos de cada proyecto (S010, S011). Salvo la papelera, todo trata solo los puntos activos. */
interface PointRepository {
    /**
     * Guarda el punto. Con más frases NMEA de las que caben en su fila ([NmeaChunks.MAX_CHARS]), van en tramos aparte, en la
     * misma transacción (S017/RF-12).
     */
    suspend fun create(point: Point): PointSaveResult

    /**
     * Guarda todos los puntos, en el orden dado, en una sola transacción: si uno repite nombre, no se guarda ninguno y devuelve
     * `DuplicateName` (S028/RF-25, RF-26). No toca los puntos que ya están (RF-19).
     */
    suspend fun createAll(points: List<Point>): PointSaveResult

    /** El tramo [seq] de las frases NMEA de un punto que las tiene aparte (`Measurement.nmeaChunks`); null si no existe (S017/RF-12). */
    suspend fun nmeaChunk(pointId: String, seq: Int): String?

    fun points(projectId: String): Flow<List<Point>>

    /** Del más reciente al más antiguo (S011/RF-1). */
    fun summaries(projectId: String): Flow<List<PointSummary>>

    fun point(id: String): Flow<Point?>

    /** Versiones anteriores, del cambio más reciente al más antiguo (S011/RF-11). */
    fun history(id: String): Flow<List<PointHistoryEntry>>

    fun count(projectId: String): Flow<Int>

    suspend fun lastName(projectId: String): String?

    suspend fun nameExists(projectId: String, name: String): Boolean

    suspend fun names(projectId: String): Set<String>

    /** Los puntos activos, ocultos incluidos, en orden de creación y sin las frases NMEA (S012/RF-1, RF-3). */
    suspend fun pointsForExport(projectId: String): List<Point>

    /** Guarda [point] y, en la misma transacción, su versión anterior en el historial (S011/RF-10). */
    suspend fun update(point: Point, change: PointChange): PointSaveResult

    /** Como [update] para varios puntos, todos o ninguno (S011/RF-14). */
    suspend fun updateAll(points: List<Point>, change: PointChange): PointSaveResult

    /**
     * Pone a los puntos [ids] el [code] (o ninguno), todos o ninguno: en una transaccion guarda el estado anterior de cada uno en el
     * historial, conserva los atributos que coinciden en nombre y tipo y pone el valor por defecto en los demas. No toca las
     * coordenadas, la medida ni el indicador de editado (S024/RF-13, RF-14, RF-16).
     */
    suspend fun changeCode(ids: List<String>, code: SurveyCode?): CodeChangeResult

    /** Los ultimos 10 codigos distintos usados en el proyecto, el mas reciente primero (S024/RF-1). */
    fun recentCodes(projectId: String): Flow<List<String>>

    suspend fun setHidden(ids: List<String>, hidden: Boolean)

    suspend fun moveToTrash(ids: List<String>)

    /** Manda a la papelera los puntos activos de la importación [importId] y devuelve cuántos (S028/RF-29). */
    suspend fun trashImport(importId: String): Int

    fun trash(projectId: String): Flow<List<TrashedPoint>>

    /** Lo devuelve al proyecto con [newName] o con el suyo; si está ocupado, `DuplicateName` (S011/RF-24, RF-25). */
    suspend fun restore(id: String, newName: String? = null): PointSaveResult

    suspend fun deletePermanently(id: String)

    /** Borra los puntos que llevan 30 días en la papelera (S011/RF-26); devuelve cuántos. */
    suspend fun purgeTrash(): Int
}

class RoomPointRepository(
    private val database: TopoPhoneDatabase,
    private val clock: () -> Long = System::currentTimeMillis,
    private val log: AppLog = AppLog.NONE,
) : PointRepository {
    private val dao = database.points()
    private val lineHistory = LineHistoryWriter(database.lines(), clock) { UUID.randomUUID().toString() }

    override suspend fun create(point: Point): PointSaveResult = try {
        val entity = point.toEntity()
        val raw = entity.rawNmea.orEmpty()
        // El punto, sus frases NMEA y los atributos de su codigo van en una sola transaccion (S024/RF-10, RNF-T6).
        database.withTransaction {
            if (raw.length > NmeaChunks.MAX_CHARS) {
                val chunks = NmeaChunks.split(raw)
                dao.insert(entity.copy(rawNmea = "", nmeaChunks = chunks.size))
                dao.insertNmea(chunks.mapIndexed { seq, text -> PointNmeaEntity(point.id, seq, text) })
            } else {
                dao.insert(entity)
            }
            dao.insertAttributes(point.attributeEntities())
        }
        // S014/RF-3, RF-7: nombre, proyecto y solución; nunca las coordenadas.
        val project = database.projects().byId(point.projectId)?.name ?: point.projectId
        log.info(LogCategory.POINTS, "Guardado ${point.name} en «$project»: ${point.measurement?.quality?.solution ?: "punto manual"}")
        PointSaveResult.Saved
    } catch (_: SQLiteConstraintException) {
        PointSaveResult.DuplicateName // el índice único de (proyecto, nombre, papelera)
    }

    override suspend fun createAll(points: List<Point>): PointSaveResult = try {
        database.withTransaction {
            dao.insertAll(points.map { it.toEntity() })
            dao.insertAttributes(points.flatMap { it.attributeEntities() })
        }
        PointSaveResult.Saved
    } catch (_: SQLiteConstraintException) {
        PointSaveResult.DuplicateName
    }

    private fun withAttributes(entities: List<PointEntity>, attributes: List<PointAttributeEntity>): List<Point> {
        val byPoint = attributes.groupBy { it.pointId }
        return entities.map { it.toPoint(byPoint[it.id].orEmpty()) }
    }

    override suspend fun nmeaChunk(pointId: String, seq: Int): String? = dao.nmeaChunk(pointId, seq)

    override fun points(projectId: String): Flow<List<Point>> =
        combine(dao.byProject(projectId), dao.attributesOfProject(projectId)) { list, attributes -> withAttributes(list, attributes) }

    override fun summaries(projectId: String): Flow<List<PointSummary>> = dao.summaries(projectId).map { rows ->
        rows.map { row ->
            PointSummary(
                id = row.id,
                name = row.name,
                description = row.description,
                origin = PointOrigin.entries.firstOrNull { it.name == row.origin } ?: PointOrigin.MEASURED,
                latitudeDeg = row.groundLat,
                longitudeDeg = row.groundLon,
                hasHeight = row.hasHeight,
                outOfTolerance = row.outOfTolerance,
                edited = row.edited,
                hidden = row.hidden,
                createdAtMs = row.createdAt,
                stakeCount = row.stakeCount,
                photoCount = row.photoCount,
                slopeKind = SlopeKind.entries.firstOrNull { it.name == row.slopeKind },
                code = row.code,
                codeColor = row.codeColor,
                codeSymbol = CodeSymbol.entries.firstOrNull { it.name == row.codeSymbol },
            )
        }
    }

    override fun point(id: String): Flow<Point?> = combine(dao.observe(id), dao.attributesOf(id)) { entity, attributes ->
        entity?.toPoint(attributes)
    }

    override fun history(id: String): Flow<List<PointHistoryEntry>> = combine(dao.history(id), dao.historyAttributes(id)) {
            list,
            attributes,
        ->
        val byHistory = attributes.groupBy { it.historyId }
        list.map { it.toEntry(byHistory[it.id].orEmpty()) }
    }

    override fun count(projectId: String): Flow<Int> = dao.count(projectId)

    override suspend fun lastName(projectId: String): String? = dao.lastName(projectId)

    override suspend fun nameExists(projectId: String, name: String): Boolean = dao.nameExists(projectId, name)

    override suspend fun names(projectId: String): Set<String> = dao.names(projectId).toSet()

    override suspend fun pointsForExport(projectId: String): List<Point> =
        withAttributes(dao.forExport(projectId), dao.attributesOfProjectNow(projectId))

    override suspend fun update(point: Point, change: PointChange): PointSaveResult = updateAll(listOf(point), change)

    override suspend fun updateAll(points: List<Point>, change: PointChange): PointSaveResult = try {
        database.withTransaction {
            val now = clock()
            for (point in points) {
                val current = dao.byId(point.id) ?: throw MissingPoint()
                val previous = PointSnapshot.of(current.toPoint(dao.attributesOfNow(point.id)))
                val historyId = UUID.randomUUID().toString()
                dao.insertHistory(previous.toHistoryEntity(historyId, point.id, now, change))
                dao.insertHistoryAttributes(previous.historyAttributeEntities(historyId))
                dao.update(point.toEntity())
                dao.deleteAttributes(point.id)
                dao.insertAttributes(point.attributeEntities())
            }
        }
        PointSaveResult.Saved
    } catch (_: SQLiteConstraintException) {
        PointSaveResult.DuplicateName
    } catch (_: MissingPoint) {
        PointSaveResult.NotFound
    }

    override fun recentCodes(projectId: String): Flow<List<String>> = dao.recentCodes(projectId).map { RecentCodes.distinct(it) }

    override suspend fun changeCode(ids: List<String>, code: SurveyCode?): CodeChangeResult = try {
        database.withTransaction {
            val now = clock()
            for (id in ids) {
                val current = dao.byId(id) ?: throw MissingPoint()
                val point = current.toPoint(dao.attributesOfNow(id))
                val historyId = UUID.randomUUID().toString()
                val previous = PointSnapshot.of(point)
                dao.insertHistory(previous.toHistoryEntity(historyId, id, now, PointChange.CODE))
                dao.insertHistoryAttributes(previous.historyAttributeEntities(historyId))
                val values = code?.let { AttributeCarryOver.apply(point.code?.attributes.orEmpty(), it.attributes).values }.orEmpty()
                dao.setCode(id, code?.code, code?.description, code?.color, code?.symbol?.name)
                dao.deleteAttributes(id)
                dao.insertAttributes(values.mapIndexed { i, a -> PointAttributeEntity(id, i, a.name, a.type.name, a.value) })
            }
        }
        CodeChangeResult.Changed(ids.size)
    } catch (_: MissingPoint) {
        CodeChangeResult.NotFound
    }

    override suspend fun setHidden(ids: List<String>, hidden: Boolean) = dao.setHidden(ids, hidden)

    override suspend fun moveToTrash(ids: List<String>) = dao.moveToTrash(ids, clock())

    override suspend fun trashImport(importId: String): Int = dao.trashImport(importId, clock())

    override fun trash(projectId: String): Flow<List<TrashedPoint>> = combine(dao.trash(projectId), dao.attributesOfTrash(projectId)) {
            list,
            attributes,
        ->
        val now = clock()
        val byPoint = attributes.groupBy { it.pointId }
        list.map { entity ->
            val daysIn = ((now - (entity.deletedAt ?: now)) / DAY_MS).toInt()
            TrashedPoint(entity.toPoint(byPoint[entity.id].orEmpty()), (ProjectRepository.TRASH_DAYS - daysIn).coerceAtLeast(0))
        }
    }

    override suspend fun restore(id: String, newName: String?): PointSaveResult {
        val current = dao.byId(id) ?: return PointSaveResult.NotFound
        return try {
            dao.restore(id, newName ?: current.name)
            PointSaveResult.Saved
        } catch (_: SQLiteConstraintException) {
            PointSaveResult.DuplicateName
        }
    }

    // S025/RF-25: las líneas que usan el punto lo quitan anotándolo en su historial, en la misma transacción.
    override suspend fun deletePermanently(id: String) = database.withTransaction {
        if (dao.byId(id)?.deletedAt != null) lineHistory.recordPointRemoval(listOf(id))
        dao.deleteFromTrash(id)
    }

    override suspend fun purgeTrash(): Int = database.withTransaction {
        val limit = clock() - ProjectRepository.TRASH_DAYS * DAY_MS
        lineHistory.recordPointRemoval(dao.expiredIds(limit))
        dao.purge(limit)
    }

    /** Deshace la transacción de [updateAll] si falta algún punto. */
    private class MissingPoint : RuntimeException()

    private companion object {
        const val DAY_MS = 24L * 60 * 60 * 1000
    }
}
