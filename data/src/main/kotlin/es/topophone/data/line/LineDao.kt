package es.topophone.data.line

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/** Una línea con el número de vértices cuyo punto no está en la papelera (S025/RF-21). */
data class LineSummaryRow(@Embedded val line: ProjectLineEntity, @androidx.room.ColumnInfo(name = "vertex_count") val vertexCount: Int)

@Dao
interface LineDao {
    @Insert
    suspend fun insert(line: ProjectLineEntity)

    @Update
    suspend fun update(line: ProjectLineEntity)

    @Insert
    suspend fun insertVertices(vertices: List<LineVertexEntity>)

    @Insert
    suspend fun insertAttributes(attributes: List<LineAttributeEntity>)

    @Insert
    suspend fun insertHistory(entry: LineHistoryEntity)

    @Insert
    suspend fun insertHistoryAttributes(attributes: List<LineHistoryAttributeEntity>)

    @Insert
    suspend fun insertHistoryVertices(vertices: List<LineHistoryVertexEntity>)

    @Query("SELECT * FROM project_lines WHERE project_id = :projectId AND deleted_at IS NULL ORDER BY created_at")
    fun lines(projectId: String): Flow<List<ProjectLineEntity>>

    @Query("SELECT * FROM project_lines WHERE project_id = :projectId AND deleted_at IS NULL ORDER BY created_at")
    suspend fun linesNow(projectId: String): List<ProjectLineEntity>

    @Query(
        "SELECT l.*, (SELECT COUNT(*) FROM line_vertices v JOIN points p ON p.id = v.point_id " +
            "WHERE v.line_id = l.id AND p.deleted_at IS NULL) AS vertex_count " +
            "FROM project_lines l WHERE l.project_id = :projectId AND l.deleted_at IS NULL ORDER BY l.created_at",
    )
    fun summaries(projectId: String): Flow<List<LineSummaryRow>>

    @Query("SELECT * FROM project_lines WHERE id = :id")
    fun observe(id: String): Flow<ProjectLineEntity?>

    @Query("SELECT * FROM project_lines WHERE id = :id")
    suspend fun byId(id: String): ProjectLineEntity?

    @Query("SELECT * FROM project_lines WHERE project_id = :projectId AND active = 1 AND deleted_at IS NULL LIMIT 1")
    fun active(projectId: String): Flow<ProjectLineEntity?>

    @Query("SELECT * FROM project_lines WHERE project_id = :projectId AND active = 1 AND deleted_at IS NULL LIMIT 1")
    suspend fun activeNow(projectId: String): ProjectLineEntity?

    @Query("UPDATE project_lines SET active = 0 WHERE project_id = :projectId AND active = 1")
    suspend fun clearActive(projectId: String)

    /** Los vértices de las líneas activas del proyecto cuyo punto no está en la papelera, por línea y orden (S025/D7). */
    @Query(
        "SELECT v.* FROM line_vertices v JOIN project_lines l ON l.id = v.line_id JOIN points p ON p.id = v.point_id " +
            "WHERE l.project_id = :projectId AND l.deleted_at IS NULL AND p.deleted_at IS NULL ORDER BY v.line_id, v.ordinal",
    )
    fun visibleVertices(projectId: String): Flow<List<LineVertexEntity>>

    @Query(
        "SELECT v.* FROM line_vertices v JOIN points p ON p.id = v.point_id " +
            "WHERE v.line_id = :lineId AND p.deleted_at IS NULL ORDER BY v.ordinal",
    )
    fun visibleVerticesOf(lineId: String): Flow<List<LineVertexEntity>>

    /** Todos los vértices de la línea, también los de puntos en la papelera: lo que se reescribe al editar (S025/D7). */
    @Query("SELECT * FROM line_vertices WHERE line_id = :lineId ORDER BY ordinal")
    suspend fun allVerticesOf(lineId: String): List<LineVertexEntity>

    @Query("DELETE FROM line_vertices WHERE line_id = :lineId")
    suspend fun deleteVertices(lineId: String)

    @Query(
        "SELECT a.* FROM line_attributes a JOIN project_lines l ON l.id = a.line_id " +
            "WHERE l.project_id = :projectId AND l.deleted_at IS NULL ORDER BY a.line_id, a.ordinal",
    )
    fun attributesOfProject(projectId: String): Flow<List<LineAttributeEntity>>

    @Query("SELECT * FROM line_attributes WHERE line_id = :lineId ORDER BY ordinal")
    fun attributesOf(lineId: String): Flow<List<LineAttributeEntity>>

    @Query("SELECT * FROM line_attributes WHERE line_id = :lineId ORDER BY ordinal")
    suspend fun attributesOfNow(lineId: String): List<LineAttributeEntity>

    @Query("DELETE FROM line_attributes WHERE line_id = :lineId")
    suspend fun deleteAttributes(lineId: String)

    @Query("SELECT name FROM project_lines WHERE project_id = :projectId AND deleted_at IS NULL")
    suspend fun names(projectId: String): List<String>

    @Query("SELECT * FROM line_history WHERE line_id = :lineId ORDER BY changed_at DESC")
    fun history(lineId: String): Flow<List<LineHistoryEntity>>

    @Query(
        "SELECT a.* FROM line_history_attributes a JOIN line_history h ON h.id = a.history_id " +
            "WHERE h.line_id = :lineId ORDER BY a.history_id, a.ordinal",
    )
    fun historyAttributes(lineId: String): Flow<List<LineHistoryAttributeEntity>>

    @Query(
        "SELECT v.* FROM line_history_vertices v JOIN line_history h ON h.id = v.history_id " +
            "WHERE h.line_id = :lineId ORDER BY v.history_id, v.ordinal",
    )
    fun historyVertices(lineId: String): Flow<List<LineHistoryVertexEntity>>
}
