package es.topophone.data.point

import android.database.sqlite.SQLiteConstraintException
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import androidx.room.withTransaction
import es.topophone.core.model.log.AppLog
import es.topophone.core.model.log.LogCategory
import es.topophone.core.model.point.Point
import es.topophone.core.model.point.PointChange
import es.topophone.core.model.point.PointHistoryEntry
import es.topophone.core.model.point.PointOrigin
import es.topophone.core.model.point.PointSnapshot
import es.topophone.core.model.point.PointSummary
import es.topophone.data.db.TopoPhoneDatabase
import es.topophone.data.project.ProjectRepository
import java.util.UUID
import kotlinx.coroutines.flow.Flow
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
)

/** Los puntos activos de un proyecto son los que no están en la papelera (S011/RF-22). */
@Dao
interface PointDao {
    /** Una inserción: con WAL y `synchronous=FULL`, o queda entera o no queda (RNF-T6). */
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(point: PointEntity)

    @Update(onConflict = OnConflictStrategy.ABORT)
    suspend fun update(point: PointEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertHistory(entry: PointHistoryEntity)

    /** Los tramos de las frases NMEA de un punto medido (S017/RF-12). */
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertNmea(chunks: List<PointNmeaEntity>)

    @Query("SELECT text FROM point_nmea WHERE point_id = :pointId AND seq = :seq")
    suspend fun nmeaChunk(pointId: String, seq: Int): String?

    @Query("SELECT * FROM points WHERE project_id = :projectId AND deleted_at IS NULL ORDER BY created_at")
    fun byProject(projectId: String): Flow<List<PointEntity>>

    @Query(
        "SELECT id, name, description, origin, ground_lat, ground_lon, ground_h IS NOT NULL AS has_height, " +
            "out_of_tolerance <> '' AS out_of_tolerance, edited, hidden, created_at FROM points " +
            "WHERE project_id = :projectId AND deleted_at IS NULL ORDER BY created_at DESC",
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
            "avg_time, dev_h, dev_v, auto_mode, nmea_chunks FROM points " +
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

    @Query("UPDATE points SET hidden = :hidden WHERE id IN (:ids)")
    suspend fun setHidden(ids: List<String>, hidden: Boolean)

    @Query("UPDATE points SET deleted_at = :now, trash_key = id WHERE id IN (:ids) AND deleted_at IS NULL")
    suspend fun moveToTrash(ids: List<String>, now: Long)

    @Query("SELECT * FROM points WHERE project_id = :projectId AND deleted_at IS NOT NULL ORDER BY deleted_at DESC")
    fun trash(projectId: String): Flow<List<PointEntity>>

    @Query("UPDATE points SET deleted_at = NULL, trash_key = '', name = :name WHERE id = :id AND deleted_at IS NOT NULL")
    suspend fun restore(id: String, name: String)

    @Query("DELETE FROM points WHERE id = :id AND deleted_at IS NOT NULL")
    suspend fun deleteFromTrash(id: String)

    @Query("DELETE FROM points WHERE deleted_at IS NOT NULL AND deleted_at < :limit")
    suspend fun purge(limit: Long): Int
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

    suspend fun setHidden(ids: List<String>, hidden: Boolean)

    suspend fun moveToTrash(ids: List<String>)

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

    override suspend fun create(point: Point): PointSaveResult = try {
        val entity = point.toEntity()
        val raw = entity.rawNmea.orEmpty()
        if (raw.length > NmeaChunks.MAX_CHARS) {
            val chunks = NmeaChunks.split(raw)
            database.withTransaction {
                dao.insert(entity.copy(rawNmea = "", nmeaChunks = chunks.size))
                dao.insertNmea(chunks.mapIndexed { seq, text -> PointNmeaEntity(point.id, seq, text) })
            }
        } else {
            dao.insert(entity)
        }
        // S014/RF-3, RF-7: nombre, proyecto y solución; nunca las coordenadas.
        val project = database.projects().byId(point.projectId)?.name ?: point.projectId
        log.info(LogCategory.POINTS, "Guardado ${point.name} en «$project»: ${point.measurement?.quality?.solution ?: "punto manual"}")
        PointSaveResult.Saved
    } catch (_: SQLiteConstraintException) {
        PointSaveResult.DuplicateName // el índice único de (proyecto, nombre, papelera)
    }

    override suspend fun nmeaChunk(pointId: String, seq: Int): String? = dao.nmeaChunk(pointId, seq)

    override fun points(projectId: String): Flow<List<Point>> = dao.byProject(projectId).map { list -> list.map { it.toPoint() } }

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
            )
        }
    }

    override fun point(id: String): Flow<Point?> = dao.observe(id).map { it?.toPoint() }

    override fun history(id: String): Flow<List<PointHistoryEntry>> = dao.history(id).map { list -> list.map { it.toEntry() } }

    override fun count(projectId: String): Flow<Int> = dao.count(projectId)

    override suspend fun lastName(projectId: String): String? = dao.lastName(projectId)

    override suspend fun nameExists(projectId: String, name: String): Boolean = dao.nameExists(projectId, name)

    override suspend fun names(projectId: String): Set<String> = dao.names(projectId).toSet()

    override suspend fun pointsForExport(projectId: String): List<Point> = dao.forExport(projectId).map { it.toPoint() }

    override suspend fun update(point: Point, change: PointChange): PointSaveResult = updateAll(listOf(point), change)

    override suspend fun updateAll(points: List<Point>, change: PointChange): PointSaveResult = try {
        database.withTransaction {
            val now = clock()
            for (point in points) {
                val current = dao.byId(point.id) ?: throw MissingPoint()
                val previous = PointSnapshot.of(current.toPoint())
                dao.insertHistory(previous.toHistoryEntity(UUID.randomUUID().toString(), point.id, now, change))
                dao.update(point.toEntity())
            }
        }
        PointSaveResult.Saved
    } catch (_: SQLiteConstraintException) {
        PointSaveResult.DuplicateName
    } catch (_: MissingPoint) {
        PointSaveResult.NotFound
    }

    override suspend fun setHidden(ids: List<String>, hidden: Boolean) = dao.setHidden(ids, hidden)

    override suspend fun moveToTrash(ids: List<String>) = dao.moveToTrash(ids, clock())

    override fun trash(projectId: String): Flow<List<TrashedPoint>> = dao.trash(projectId).map { list ->
        val now = clock()
        list.map { entity ->
            val daysIn = ((now - (entity.deletedAt ?: now)) / DAY_MS).toInt()
            TrashedPoint(entity.toPoint(), (ProjectRepository.TRASH_DAYS - daysIn).coerceAtLeast(0))
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

    override suspend fun deletePermanently(id: String) = dao.deleteFromTrash(id)

    override suspend fun purgeTrash(): Int = dao.purge(limit = clock() - ProjectRepository.TRASH_DAYS * DAY_MS)

    /** Deshace la transacción de [updateAll] si falta algún punto. */
    private class MissingPoint : RuntimeException()

    private companion object {
        const val DAY_MS = 24L * 60 * 60 * 1000
    }
}
