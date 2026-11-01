package es.topophone.data.section

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import es.topophone.data.alignment.AlignmentEntity
import kotlinx.coroutines.flow.Flow

/**
 * Una sección tipo de un eje (S050, plan: modelo de datos), aplicada entre [fromChainage] y [toChainage]. Los tramos de cada lado van
 * en un bloque binario ([geometry], el formato de `SectionCodec`). [gradeCode] es NULL si el punto de rasante es el eje. Se borra con
 * el eje (RF-8).
 */
@Entity(
    tableName = "section_templates",
    foreignKeys = [
        ForeignKey(entity = AlignmentEntity::class, parentColumns = ["id"], childColumns = ["alignment_id"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index("alignment_id")],
)
data class SectionTemplateEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "alignment_id") val alignmentId: String,
    @ColumnInfo(name = "from_chainage") val fromChainage: Double,
    @ColumnInfo(name = "to_chainage") val toChainage: Double,
    @ColumnInfo(name = "grade_code") val gradeCode: String?,
    val geometry: ByteArray,
    @ColumnInfo(name = "created_at_ms") val createdAtMs: Long,
) {
    override fun equals(other: Any?): Boolean = other is SectionTemplateEntity &&
        id == other.id &&
        alignmentId == other.alignmentId &&
        fromChainage == other.fromChainage &&
        toChainage == other.toChainage &&
        gradeCode == other.gradeCode &&
        createdAtMs == other.createdAtMs &&
        geometry.contentEquals(other.geometry)

    override fun hashCode(): Int = 31 * id.hashCode() + geometry.contentHashCode()
}

/**
 * Una sección explícita de un eje, de diseño o de terreno (S050, plan: modelo de datos), en el PK [chainage]. [kind] es `DESIGN` o
 * `GROUND` y [heightKind] `RELATIVE` o `ABSOLUTE`; los puntos van en un bloque binario ([points], el formato de `SectionCodec`).
 */
@Entity(
    tableName = "section_profiles",
    foreignKeys = [
        ForeignKey(entity = AlignmentEntity::class, parentColumns = ["id"], childColumns = ["alignment_id"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index("alignment_id")],
)
data class SectionProfileEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "alignment_id") val alignmentId: String,
    val kind: String,
    val chainage: Double,
    @ColumnInfo(name = "height_kind") val heightKind: String,
    val points: ByteArray,
    @ColumnInfo(name = "created_at_ms") val createdAtMs: Long,
) {
    override fun equals(other: Any?): Boolean = other is SectionProfileEntity &&
        id == other.id &&
        alignmentId == other.alignmentId &&
        kind == other.kind &&
        chainage == other.chainage &&
        heightKind == other.heightKind &&
        createdAtMs == other.createdAtMs &&
        points.contentEquals(other.points)

    override fun hashCode(): Int = 31 * id.hashCode() + points.contentHashCode()
}

/** Una fila de la tabla de peralte de un eje (S050/RF-6): sin PK repetido por eje (índice único). Pendientes en fracción. */
@Entity(
    tableName = "superelevation_rows",
    foreignKeys = [
        ForeignKey(entity = AlignmentEntity::class, parentColumns = ["id"], childColumns = ["alignment_id"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index("alignment_id", "chainage", unique = true)],
)
data class SuperelevationRowEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "alignment_id") val alignmentId: String,
    val chainage: Double,
    @ColumnInfo(name = "left_slope") val leftSlope: Double,
    @ColumnInfo(name = "right_slope") val rightSlope: Double,
    @ColumnInfo(name = "created_at_ms") val createdAtMs: Long,
)

@Dao
interface SectionDao {
    @Insert
    suspend fun insertTemplate(template: SectionTemplateEntity)

    @Insert
    suspend fun insertProfile(profile: SectionProfileEntity)

    @Insert
    suspend fun insertRow(row: SuperelevationRowEntity)

    @Query("DELETE FROM section_templates WHERE id = :id")
    suspend fun deleteTemplate(id: String)

    @Query("DELETE FROM section_profiles WHERE id = :id")
    suspend fun deleteProfile(id: String)

    @Query("DELETE FROM superelevation_rows WHERE id = :id")
    suspend fun deleteRow(id: String)

    @Query("DELETE FROM section_profiles WHERE alignment_id = :alignmentId AND kind = :kind")
    suspend fun deleteProfilesOfKind(alignmentId: String, kind: String)

    @Query("SELECT * FROM section_templates WHERE alignment_id = :alignmentId ORDER BY from_chainage, rowid")
    fun templates(alignmentId: String): Flow<List<SectionTemplateEntity>>

    @Query("SELECT * FROM section_profiles WHERE alignment_id = :alignmentId ORDER BY chainage, rowid")
    fun profiles(alignmentId: String): Flow<List<SectionProfileEntity>>

    @Query("SELECT * FROM superelevation_rows WHERE alignment_id = :alignmentId ORDER BY chainage")
    fun rows(alignmentId: String): Flow<List<SuperelevationRowEntity>>

    @Query("SELECT * FROM section_templates WHERE alignment_id = :alignmentId ORDER BY from_chainage, rowid")
    suspend fun templatesNow(alignmentId: String): List<SectionTemplateEntity>

    @Query("SELECT * FROM section_profiles WHERE alignment_id = :alignmentId ORDER BY chainage, rowid")
    suspend fun profilesNow(alignmentId: String): List<SectionProfileEntity>

    @Query("SELECT * FROM superelevation_rows WHERE alignment_id = :alignmentId ORDER BY chainage")
    suspend fun rowsNow(alignmentId: String): List<SuperelevationRowEntity>

    @Query("SELECT COUNT(*) FROM section_templates WHERE alignment_id = :alignmentId")
    suspend fun templateCount(alignmentId: String): Int

    @Query("SELECT COUNT(*) FROM section_profiles WHERE alignment_id = :alignmentId")
    suspend fun profileCount(alignmentId: String): Int

    @Query("SELECT COUNT(*) FROM superelevation_rows WHERE alignment_id = :alignmentId")
    suspend fun rowCount(alignmentId: String): Int
}
