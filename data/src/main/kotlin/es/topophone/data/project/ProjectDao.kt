package es.topophone.data.project

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/** Acceso a los proyectos y sus carpetas (S006). Los proyectos de la papelera tienen `deleted_at`. */
@Dao
interface ProjectDao {
    @Insert
    suspend fun insert(project: ProjectEntity)

    @Update
    suspend fun update(project: ProjectEntity)

    @Query("SELECT * FROM projects WHERE id = :id")
    suspend fun byId(id: String): ProjectEntity?

    @Query("SELECT * FROM projects WHERE id = :id")
    fun observe(id: String): Flow<ProjectEntity?>

    /** S006/RF-1, RF-20: los de una carpeta (o los de ninguna), del abierto más recientemente al más antiguo. */
    @Query(
        """
        SELECT * FROM projects
        WHERE deleted_at IS NULL AND ((:folderId IS NULL AND folder_id IS NULL) OR folder_id = :folderId)
        ORDER BY COALESCE(opened_at, created_at) DESC
        """,
    )
    fun inFolder(folderId: String?): Flow<List<ProjectEntity>>

    /** Nombres en uso (fuera de la papelera), salvo el del propio proyecto (S006/RF-7). */
    @Query("SELECT name FROM projects WHERE deleted_at IS NULL AND id != :exceptId")
    suspend fun activeNames(exceptId: String = ""): List<String>

    @Query("UPDATE projects SET opened_at = :at WHERE id = :id")
    suspend fun setOpenedAt(id: String, at: Long)

    @Query("UPDATE projects SET deleted_at = :deletedAt WHERE id = :id")
    suspend fun setDeletedAt(id: String, deletedAt: Long?)

    @Query("UPDATE projects SET folder_id = :folderId WHERE id = :id")
    suspend fun setFolder(id: String, folderId: String?)

    @Insert
    suspend fun insertFolder(folder: ProjectFolderEntity)
}
