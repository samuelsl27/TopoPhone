package es.topophone.data.cogo

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey
import es.topophone.data.project.ProjectEntity

/**
 * El borrador de la poligonal de un proyecto (S038/RF-26, plan D11): una fila por proyecto con los puntos de entrada y los tramos
 * escritos en JSON (`TraverseDraftJson`), y la hora del último cambio en ms UTC. Se borra con el proyecto.
 */
@Entity(
    tableName = "traverse_drafts",
    foreignKeys = [
        ForeignKey(entity = ProjectEntity::class, parentColumns = ["id"], childColumns = ["project_id"], onDelete = ForeignKey.CASCADE),
    ],
)
data class TraverseDraftEntity(
    @PrimaryKey @ColumnInfo(name = "project_id") val projectId: String,
    val data: String,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)
