package es.topophone.data.baseshift

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import es.topophone.data.project.ProjectEntity
import kotlinx.coroutines.flow.Flow

/**
 * Un desplazamiento de base calculado (S020/RF-12): el punto conocido, la posición medida sin desplazar y el vector ECEF, sin
 * redondear (AGENTS.md). Se borra con el proyecto. Solo uno tiene [active] por proyecto; lo garantiza el repositorio al activar,
 * en una transacción. [knownHeight] es NULL si el punto conocido no tenía altura (RF-6); [correctionsDatum] es el datum de las
 * correcciones con que se calculó (RF-16) y la base, la que había entonces (RF-15). [datumNotice] marca que se desactivó por un
 * cambio de datum y el usuario aún no lo ha visto (RF-16).
 */
@Entity(
    tableName = "base_shifts",
    foreignKeys = [
        ForeignKey(entity = ProjectEntity::class, parentColumns = ["id"], childColumns = ["project_id"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index("project_id")],
)
data class BaseShiftEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "project_id") val projectId: String,
    @ColumnInfo(name = "created_at_ms") val createdAtMs: Long,
    @ColumnInfo(name = "from_replay") val fromReplay: Boolean,
    @ColumnInfo(name = "known_name") val knownName: String,
    @ColumnInfo(name = "known_point_id") val knownPointId: String?,
    @ColumnInfo(name = "known_easting") val knownEasting: Double,
    @ColumnInfo(name = "known_northing") val knownNorthing: Double,
    @ColumnInfo(name = "known_height") val knownHeight: Double?,
    @ColumnInfo(name = "measured_lat") val measuredLat: Double,
    @ColumnInfo(name = "measured_lon") val measuredLon: Double,
    @ColumnInfo(name = "measured_h") val measuredH: Double,
    @ColumnInfo(name = "epochs_used") val epochsUsed: Int,
    val dx: Double,
    val dy: Double,
    val dz: Double,
    @ColumnInfo(name = "horizontal_only") val horizontalOnly: Boolean,
    @ColumnInfo(name = "corrections_datum") val correctionsDatum: String,
    val active: Boolean,
    @ColumnInfo(name = "base_lat") val baseLat: Double?,
    @ColumnInfo(name = "base_lon") val baseLon: Double?,
    @ColumnInfo(name = "base_h") val baseH: Double?,
    @ColumnInfo(name = "datum_notice", defaultValue = "0") val datumNotice: Boolean = false,
)

@Dao
interface BaseShiftDao {
    @Insert
    suspend fun insert(shift: BaseShiftEntity)

    @Query("DELETE FROM base_shifts WHERE id = :id")
    suspend fun delete(id: String)

    @Query("SELECT * FROM base_shifts WHERE id = :id")
    suspend fun byId(id: String): BaseShiftEntity?

    @Query("SELECT * FROM base_shifts WHERE project_id = :projectId ORDER BY created_at_ms DESC, rowid DESC")
    fun all(projectId: String): Flow<List<BaseShiftEntity>>

    @Query("SELECT * FROM base_shifts WHERE project_id = :projectId AND active = 1")
    fun active(projectId: String): Flow<BaseShiftEntity?>

    @Query("SELECT * FROM base_shifts WHERE project_id = :projectId AND active = 1")
    suspend fun activeNow(projectId: String): BaseShiftEntity?

    @Query("UPDATE base_shifts SET active = 0 WHERE project_id = :projectId AND active = 1")
    suspend fun deactivateAll(projectId: String): Int

    @Query("UPDATE base_shifts SET active = 1 WHERE id = :id")
    suspend fun activate(id: String)

    @Query("SELECT project_id FROM base_shifts WHERE id = :id")
    suspend fun projectOf(id: String): String?

    @Query(
        "UPDATE base_shifts SET active = 0, datum_notice = 1 WHERE project_id = :projectId AND active = 1 AND corrections_datum <> :datum",
    )
    suspend fun deactivateOtherDatum(projectId: String, datum: String): Int

    @Query("UPDATE base_shifts SET datum_notice = 0 WHERE id = :id")
    suspend fun clearDatumNotice(id: String)
}
