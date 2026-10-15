package es.topophone.data.project

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Un proyecto (S006). Los enums se guardan por su nombre, nunca por su posición, y las fechas en
 * ms UTC. Con `deleted_at` el proyecto está en la papelera (S006/RF-22).
 */
@Entity(
    tableName = "projects",
    foreignKeys = [
        ForeignKey(
            entity = ProjectFolderEntity::class,
            parentColumns = ["id"],
            childColumns = ["folder_id"],
            onDelete = ForeignKey.SET_NULL, // S006/RF-21: borrar la carpeta saca sus proyectos
        ),
    ],
    indices = [Index("folder_id"), Index("deleted_at")],
)
data class ProjectEntity(
    @PrimaryKey val id: String,
    val name: String,
    val author: String,
    val description: String,
    val crs: String,
    @ColumnInfo(name = "linear_unit") val linearUnit: String,
    @ColumnInfo(name = "angular_unit") val angularUnit: String,
    @ColumnInfo(name = "corrections_datum") val correctionsDatum: String,
    @ColumnInfo(name = "folder_id") val folderId: String?,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "modified_at") val modifiedAt: Long,
    @ColumnInfo(name = "opened_at") val openedAt: Long?,
    @ColumnInfo(name = "deleted_at") val deletedAt: Long?,
)

/** Una carpeta de proyectos; un solo nivel (S006/RF-17). */
@Entity(tableName = "project_folders")
data class ProjectFolderEntity(@PrimaryKey val id: String, val name: String)
