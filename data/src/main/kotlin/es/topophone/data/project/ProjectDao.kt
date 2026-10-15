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

    /** S006/RF-4: todos los de fuera de la papelera, para buscar en todas las carpetas. */
    @Query("SELECT * FROM projects WHERE deleted_at IS NULL ORDER BY COALESCE(opened_at, created_at) DESC")
    fun active(): Flow<List<ProjectEntity>>

    /** Nombres en uso (fuera de la papelera), salvo el del propio proyecto (S006/RF-7). */
    @Query("SELECT name FROM projects WHERE deleted_at IS NULL AND id != :exceptId")
    suspend fun activeNames(exceptId: String = ""): List<String>

    @Query("UPDATE projects SET opened_at = :at WHERE id = :id")
    suspend fun setOpenedAt(id: String, at: Long)

    @Query("UPDATE projects SET deleted_at = :deletedAt WHERE id = :id")
    suspend fun setDeletedAt(id: String, deletedAt: Long?)

    @Query("UPDATE projects SET folder_id = :folderId WHERE id = :id")
    suspend fun setFolder(id: String, folderId: String?)

    @Query("UPDATE projects SET folder_id = :folderId, modified_at = :at WHERE id = :id")
    suspend fun moveToFolder(id: String, folderId: String?, at: Long)

    @Insert
    suspend fun insertFolder(folder: ProjectFolderEntity)

    @Query("SELECT * FROM project_folders")
    fun folders(): Flow<List<ProjectFolderEntity>>

    @Query("SELECT name FROM project_folders WHERE id != :exceptId")
    suspend fun folderNames(exceptId: String = ""): List<String>

    @Query("UPDATE project_folders SET name = :name WHERE id = :id")
    suspend fun renameFolder(id: String, name: String)

    /** S006/RF-23: los de la papelera, del último borrado al primero. */
    @Query("SELECT * FROM projects WHERE deleted_at IS NOT NULL ORDER BY deleted_at DESC")
    fun trashed(): Flow<List<ProjectEntity>>

    @Query("UPDATE projects SET deleted_at = NULL, name = :name WHERE id = :id")
    suspend fun restore(id: String, name: String)

    @Query("DELETE FROM projects WHERE id = :id")
    suspend fun delete(id: String)

    /** S006/RF-27: elimina los que llevan en la papelera desde [limit] o antes. */
    @Query("DELETE FROM projects WHERE deleted_at IS NOT NULL AND deleted_at <= :limit")
    suspend fun purge(limit: Long): Int

    /** S006/RF-21: la clave ajena saca sus proyectos (`folder_id` a null). */
    @Query("DELETE FROM project_folders WHERE id = :id")
    suspend fun deleteFolder(id: String)
}
