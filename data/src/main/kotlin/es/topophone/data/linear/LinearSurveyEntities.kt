package es.topophone.data.linear

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import es.topophone.data.alignment.AlignmentEntity
import es.topophone.data.point.PointEntity
import es.topophone.data.project.ProjectEntity
import kotlinx.coroutines.flow.Flow

/**
 * Una toma lineal (S052, plan: modelo de datos): un eje, un perfil longitudinal o un perfil transversal. [kind] es `AXIS`,
 * `LONGITUDINAL` o `CROSS`. [alignmentId] es el eje creado (toma de eje) o sobre el que se mide; pasa a NULL si se borra el eje (RF-8).
 * Sin [finishedAtMs] la toma está abierta y se reanuda tras cerrar la app (RF-9). Se borra con el proyecto.
 */
@Entity(
    tableName = "linear_surveys",
    foreignKeys = [
        ForeignKey(entity = ProjectEntity::class, parentColumns = ["id"], childColumns = ["project_id"], onDelete = ForeignKey.CASCADE),
        ForeignKey(
            entity = AlignmentEntity::class,
            parentColumns = ["id"],
            childColumns = ["alignment_id"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [Index("project_id"), Index("alignment_id")],
)
data class LinearSurveyEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "project_id") val projectId: String,
    val kind: String,
    @ColumnInfo(name = "alignment_id") val alignmentId: String?,
    val name: String,
    @ColumnInfo(name = "chainage_from") val chainageFrom: Double?,
    @ColumnInfo(name = "chainage_to") val chainageTo: Double?,
    @ColumnInfo(name = "interval_m") val intervalM: Double?,
    val chainage: Double?,
    val guided: Boolean,
    @ColumnInfo(name = "tolerance_m") val toleranceM: Double?,
    @ColumnInfo(name = "created_at_ms") val createdAtMs: Long,
    @ColumnInfo(name = "finished_at_ms") val finishedAtMs: Long?,
)

/**
 * Un punto del proyecto dentro de una toma (S052/RF-2): su orden, si sigue incluido y su PK, desplazamiento, diferencia de cota, marca y
 * código de sección calculados al añadirlo. Borrar el punto lo quita de la toma, pero borrar la toma no borra el punto (RF-8).
 */
@Entity(
    tableName = "linear_survey_points",
    primaryKeys = ["survey_id", "point_id"],
    foreignKeys = [
        ForeignKey(entity = LinearSurveyEntity::class, parentColumns = ["id"], childColumns = ["survey_id"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = PointEntity::class, parentColumns = ["id"], childColumns = ["point_id"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index("point_id")],
)
data class LinearSurveyPointEntity(
    @ColumnInfo(name = "survey_id") val surveyId: String,
    @ColumnInfo(name = "point_id") val pointId: String,
    val ordinal: Int,
    val included: Boolean,
    val chainage: Double?,
    @ColumnInfo(name = "offset") val offsetM: Double?,
    @ColumnInfo(name = "d_height") val dHeight: Double?,
    val flag: String,
    val code: String?,
)

@Dao
interface LinearSurveyDao {
    @Insert
    suspend fun insertSurvey(survey: LinearSurveyEntity)

    @Insert
    suspend fun insertPoint(point: LinearSurveyPointEntity)

    @Query("SELECT * FROM linear_surveys WHERE project_id = :projectId ORDER BY created_at_ms, rowid")
    fun surveys(projectId: String): Flow<List<LinearSurveyEntity>>

    @Query("SELECT * FROM linear_survey_points WHERE survey_id = :surveyId ORDER BY ordinal")
    fun points(surveyId: String): Flow<List<LinearSurveyPointEntity>>
}
