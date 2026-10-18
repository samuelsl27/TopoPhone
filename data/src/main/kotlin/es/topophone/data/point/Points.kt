package es.topophone.data.point

import android.database.sqlite.SQLiteConstraintException
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import es.topophone.core.model.point.Point
import es.topophone.data.db.TopoPhoneDatabase
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

@Dao
interface PointDao {
    /** Una inserción: con WAL y `synchronous=FULL`, o queda entera o no queda (RNF-T6). */
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(point: PointEntity)

    @Query("SELECT * FROM points WHERE project_id = :projectId ORDER BY created_at")
    fun byProject(projectId: String): Flow<List<PointEntity>>

    @Query("SELECT COUNT(*) FROM points WHERE project_id = :projectId")
    fun count(projectId: String): Flow<Int>

    @Query("SELECT name FROM points WHERE project_id = :projectId ORDER BY created_at DESC LIMIT 1")
    suspend fun lastName(projectId: String): String?

    @Query("SELECT EXISTS(SELECT 1 FROM points WHERE project_id = :projectId AND name = :name)")
    suspend fun nameExists(projectId: String, name: String): Boolean
}

/** Resultado de guardar un punto. */
sealed interface PointSaveResult {
    data object Saved : PointSaveResult

    /** Ya hay un punto con ese nombre en el proyecto (S010/RF-3). */
    data object DuplicateName : PointSaveResult
}

/** Puntos recolectados de cada proyecto (S010). */
interface PointRepository {
    suspend fun create(point: Point): PointSaveResult

    fun points(projectId: String): Flow<List<Point>>

    fun count(projectId: String): Flow<Int>

    suspend fun lastName(projectId: String): String?

    suspend fun nameExists(projectId: String, name: String): Boolean
}

class RoomPointRepository(database: TopoPhoneDatabase) : PointRepository {
    private val dao = database.points()

    override suspend fun create(point: Point): PointSaveResult = try {
        dao.insert(point.toEntity())
        PointSaveResult.Saved
    } catch (_: SQLiteConstraintException) {
        PointSaveResult.DuplicateName // el índice único de (proyecto, nombre)
    }

    override fun points(projectId: String): Flow<List<Point>> = dao.byProject(projectId).map { list -> list.map { it.toPoint() } }

    override fun count(projectId: String): Flow<Int> = dao.count(projectId)

    override suspend fun lastName(projectId: String): String? = dao.lastName(projectId)

    override suspend fun nameExists(projectId: String, name: String): Boolean = dao.nameExists(projectId, name)
}
