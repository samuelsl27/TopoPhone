package es.topophone.data.project

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

/** Acceso a los proyectos y sus carpetas (S006). */
@Dao
interface ProjectDao {
    @Insert
    suspend fun insert(project: ProjectEntity)

    @Query("SELECT * FROM projects WHERE id = :id")
    suspend fun byId(id: String): ProjectEntity?
}
