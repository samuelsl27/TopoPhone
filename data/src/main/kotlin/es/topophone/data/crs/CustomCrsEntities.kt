package es.topophone.data.crs

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import es.topophone.data.project.ProjectEntity
import kotlinx.coroutines.flow.Flow

/**
 * Un SC de Mis SC (S035/RF-9, RF-10): la definición completa en su texto estable (`CrsDefinition.toText()`), su altura y las fechas. [nameKey] es el
 * nombre sin mayúsculas ni espacios de más: con su índice único no puede haber dos SC con el mismo nombre (RF-10).
 */
@Entity(tableName = "custom_crs", indices = [Index(value = ["name_key"], unique = true)])
data class CustomCrsEntity(
    @PrimaryKey val id: String,
    val name: String,
    @ColumnInfo(name = "name_key") val nameKey: String,
    val definition: String,
    /** `ELLIPSOIDAL`, `GEOID` o `CONSTANT`. */
    @ColumnInfo(name = "height_kind") val heightKind: String,
    /** La rejilla del geoide o la ondulación constante en metros (texto de `Double.toString`, sin redondear); NULL si es elipsoidal. */
    @ColumnInfo(name = "height_value") val heightValue: String?,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "modified_at") val modifiedAt: Long,
)

/**
 * Un cambio del SC de un proyecto (S035/RF-20, RF-21): el SC anterior y el nuevo como texto (`ProjectCrsText`), de modo que se pueden recuperar aunque
 * el SC ya no esté en Mis SC. [kind] es `CHANGE` o `UNDO` (un «Volver al SC anterior»).
 */
@Entity(
    tableName = "project_crs_history",
    foreignKeys = [
        ForeignKey(entity = ProjectEntity::class, parentColumns = ["id"], childColumns = ["project_id"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index("project_id")],
)
data class ProjectCrsHistoryEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "project_id") val projectId: String,
    @ColumnInfo(name = "at_ms") val atMs: Long,
    @ColumnInfo(name = "from_crs") val fromCrs: String,
    @ColumnInfo(name = "to_crs") val toCrs: String,
    val kind: String,
)

@Dao
interface CustomCrsDao {
    @Query("SELECT * FROM custom_crs ORDER BY name_key")
    fun observeAll(): Flow<List<CustomCrsEntity>>

    @Query("SELECT * FROM custom_crs ORDER BY name_key")
    suspend fun all(): List<CustomCrsEntity>

    @Query("SELECT * FROM custom_crs WHERE id = :id")
    suspend fun byId(id: String): CustomCrsEntity?

    @Query("SELECT * FROM custom_crs WHERE name_key = :nameKey")
    suspend fun byNameKey(nameKey: String): CustomCrsEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(entity: CustomCrsEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: CustomCrsEntity)

    @Query("DELETE FROM custom_crs WHERE id = :id")
    suspend fun delete(id: String)
}

@Dao
interface ProjectCrsHistoryDao {
    @Query("SELECT * FROM project_crs_history WHERE project_id = :projectId ORDER BY at_ms DESC, id DESC")
    fun observe(projectId: String): Flow<List<ProjectCrsHistoryEntity>>

    @Query("SELECT * FROM project_crs_history WHERE project_id = :projectId ORDER BY at_ms DESC, id DESC")
    suspend fun all(projectId: String): List<ProjectCrsHistoryEntity>

    @Query("SELECT * FROM project_crs_history WHERE project_id = :projectId ORDER BY at_ms DESC, id DESC LIMIT 1")
    suspend fun latest(projectId: String): ProjectCrsHistoryEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(entity: ProjectCrsHistoryEntity)
}
