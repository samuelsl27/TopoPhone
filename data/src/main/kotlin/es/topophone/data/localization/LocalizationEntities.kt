package es.topophone.data.localization

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Update
import es.topophone.data.project.ProjectEntity
import kotlinx.coroutines.flow.Flow

/**
 * Una localización de un proyecto (S039/RF-13, RF-16, RF-21): el borrador (`DRAFT`), la activa (`ACTIVE`) o una retirada o sustituida (`ARCHIVED`).
 * Se borra con el proyecto. Solo una por proyecto es `ACTIVE` y solo una `DRAFT`; lo garantiza el repositorio en una transacción. [params] es el texto
 * estable de `LocalizationParams` (sin redondear); [crsText] y [heightSystem] son el SC y las alturas del proyecto con que se calculó. [origin] es
 * `PAIRS` o `SAVED_CRS` (RF-28); [name] solo lo lleva la de un SC guardado.
 */
@Entity(
    tableName = "localizations",
    foreignKeys = [
        ForeignKey(entity = ProjectEntity::class, parentColumns = ["id"], childColumns = ["project_id"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index("project_id")],
)
data class LocalizationEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "project_id") val projectId: String,
    val status: String,
    val origin: String,
    val name: String?,
    @ColumnInfo(name = "created_at_ms") val createdAtMs: Long,
    @ColumnInfo(name = "applied_at_ms") val appliedAtMs: Long?,
    @ColumnInfo(name = "fix_scale") val fixScale: Boolean,
    @ColumnInfo(name = "residual_limit_m") val residualLimitM: Double,
    @ColumnInfo(name = "scale_limit") val scaleLimit: Double,
    @ColumnInfo(name = "crs_text") val crsText: String,
    @ColumnInfo(name = "height_system") val heightSystem: String,
    val params: String?,
    @ColumnInfo(name = "rms_h") val rmsH: Double?,
    @ColumnInfo(name = "rms_v") val rmsV: Double?,
)

/**
 * Un par de una localización (S039). El punto GNSS va por su identificador y su nombre, copiado: el punto puede borrarse; no es clave foránea. La
 * posición en el SC sin localizar y las conocidas se guardan sin redondear; los residuos, los del último cálculo (NULL si la componente no entra).
 */
@Entity(
    tableName = "localization_pairs",
    foreignKeys = [
        ForeignKey(
            entity = LocalizationEntity::class,
            parentColumns = ["id"],
            childColumns = ["localization_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("localization_id")],
)
data class LocalizationPairEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "localization_id") val localizationId: String,
    val position: Int,
    @ColumnInfo(name = "point_id") val pointId: String?,
    @ColumnInfo(name = "point_name") val pointName: String,
    @ColumnInfo(name = "sc_e") val scE: Double,
    @ColumnInfo(name = "sc_n") val scN: Double,
    @ColumnInfo(name = "sc_h") val scH: Double?,
    @ColumnInfo(name = "known_e") val knownE: Double,
    @ColumnInfo(name = "known_n") val knownN: Double,
    @ColumnInfo(name = "known_h") val knownH: Double?,
    @ColumnInfo(name = "use_h") val useHorizontal: Boolean,
    @ColumnInfo(name = "use_v") val useVertical: Boolean,
    val confirmed: Boolean,
    @ColumnInfo(name = "res_e") val resE: Double?,
    @ColumnInfo(name = "res_n") val resN: Double?,
    @ColumnInfo(name = "res_v") val resV: Double?,
)

/** Un SC de «Mis SC» guardado con una localización (S039/RF-24, plan D8): el SC base, sus alturas y los parámetros. El nombre es único sin distinguir mayúsculas. */
@Entity(tableName = "localized_crs", indices = [Index(value = ["name"], unique = true)])
data class LocalizedCrsEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(collate = ColumnInfo.NOCASE) val name: String,
    @ColumnInfo(name = "crs_text") val crsText: String,
    @ColumnInfo(name = "height_system") val heightSystem: String,
    val params: String,
    @ColumnInfo(name = "created_at_ms") val createdAtMs: Long,
)

/** Un punto GNSS que una localización puede mover: su medida en el SC sin localizar (S039/RF-17). */
data class GnssPointRow(
    val id: String,
    @ColumnInfo(name = "measured_easting") val measuredEasting: Double?,
    @ColumnInfo(name = "measured_northing") val measuredNorthing: Double?,
    @ColumnInfo(name = "measured_height") val measuredHeight: Double?,
)

@Dao
interface LocalizationDao {
    @Insert
    suspend fun insert(localization: LocalizationEntity)

    @Update
    suspend fun update(localization: LocalizationEntity)

    @Query("DELETE FROM localizations WHERE id = :id")
    suspend fun delete(id: String)

    @Query("SELECT * FROM localizations WHERE id = :id")
    suspend fun byId(id: String): LocalizationEntity?

    @Query("SELECT * FROM localizations WHERE project_id = :projectId AND status = :status ORDER BY created_at_ms DESC, rowid DESC LIMIT 1")
    suspend fun withStatusNow(projectId: String, status: String): LocalizationEntity?

    @Query("SELECT * FROM localizations WHERE project_id = :projectId AND status = :status ORDER BY created_at_ms DESC, rowid DESC LIMIT 1")
    fun withStatus(projectId: String, status: String): Flow<LocalizationEntity?>

    @Query("SELECT * FROM localizations WHERE project_id = :projectId AND status = 'ARCHIVED' ORDER BY applied_at_ms DESC, rowid DESC")
    fun archived(projectId: String): Flow<List<LocalizationEntity>>

    @Query("UPDATE localizations SET status = :status WHERE id = :id")
    suspend fun setStatus(id: String, status: String)

    @Query("SELECT project_id FROM localizations WHERE id = :id")
    suspend fun projectOf(id: String): String?

    @Query("SELECT * FROM localization_pairs WHERE localization_id = :localizationId ORDER BY position, rowid")
    suspend fun pairsNow(localizationId: String): List<LocalizationPairEntity>

    @Query("SELECT * FROM localization_pairs WHERE localization_id = :localizationId ORDER BY position, rowid")
    fun pairs(localizationId: String): Flow<List<LocalizationPairEntity>>

    @Insert
    suspend fun insertPairs(pairs: List<LocalizationPairEntity>)

    @Update
    suspend fun updatePair(pair: LocalizationPairEntity)

    @Query("DELETE FROM localization_pairs WHERE localization_id = :localizationId")
    suspend fun deletePairs(localizationId: String)

    @Query("DELETE FROM localization_pairs WHERE id = :id")
    suspend fun deletePair(id: String)

    /** Los puntos GNSS no editados del proyecto con su posición en el SC sin localizar, también los de la papelera (S039/RF-17, RF-20). */
    @Query(
        "SELECT id, measured_easting, measured_northing, measured_height FROM points " +
            "WHERE project_id = :projectId AND edited = 0 AND origin IN ('MEASURED', 'STAKED') AND measured_easting IS NOT NULL " +
            "AND measured_northing IS NOT NULL",
    )
    suspend fun gnssPoints(projectId: String): List<GnssPointRow>

    /** Reescribe las coordenadas mostradas de un punto con su localización (S039/RF-17, RF-19); la medida no se toca. */
    @Query(
        "UPDATE points SET easting = :easting, northing = :northing, height = :height, localization_id = :localizationId " +
            "WHERE id = :id",
    )
    suspend fun setLocalized(id: String, easting: Double, northing: Double, height: Double?, localizationId: String?)

    @Query(
        "SELECT COUNT(*) FROM points WHERE project_id = :projectId AND edited = 0 AND origin IN ('MEASURED', 'STAKED') " +
            "AND measured_easting IS NOT NULL AND deleted_at IS NULL",
    )
    suspend fun gnssCount(projectId: String): Int
}

@Dao
interface LocalizedCrsDao {
    @Insert
    suspend fun insert(crs: LocalizedCrsEntity)

    @Query("DELETE FROM localized_crs WHERE id = :id")
    suspend fun delete(id: String)

    @Query("SELECT * FROM localized_crs WHERE id = :id")
    suspend fun byId(id: String): LocalizedCrsEntity?

    @Query("SELECT * FROM localized_crs WHERE name = :name COLLATE NOCASE")
    suspend fun byName(name: String): LocalizedCrsEntity?

    @Query("SELECT * FROM localized_crs ORDER BY name COLLATE NOCASE")
    fun all(): Flow<List<LocalizedCrsEntity>>
}
