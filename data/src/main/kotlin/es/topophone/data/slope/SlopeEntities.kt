package es.topophone.data.slope

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
 * Los taludes de un lado de un eje entre dos PK (S051, plan: modelo de datos), con el borde manual si no se usa la sección de S050
 * ([manualOffset], [manualHeight] y [manualHeightKind] son NULL a la vez). Las relaciones son metros horizontales por metro vertical.
 * Se borra con el eje (RF-4).
 */
@Entity(
    tableName = "slope_rules",
    foreignKeys = [
        ForeignKey(entity = AlignmentEntity::class, parentColumns = ["id"], childColumns = ["alignment_id"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index("alignment_id")],
)
data class SlopeRuleEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "alignment_id") val alignmentId: String,
    val side: String,
    @ColumnInfo(name = "from_chainage") val fromChainage: Double,
    @ColumnInfo(name = "to_chainage") val toChainage: Double,
    @ColumnInfo(name = "cut_ratio") val cutRatio: Double,
    @ColumnInfo(name = "fill_ratio") val fillRatio: Double,
    @ColumnInfo(name = "edge_code") val edgeCode: String?,
    @ColumnInfo(name = "manual_offset") val manualOffset: Double?,
    @ColumnInfo(name = "manual_height") val manualHeight: Double?,
    @ColumnInfo(name = "manual_height_kind") val manualHeightKind: String?,
    @ColumnInfo(name = "created_at_ms") val createdAtMs: Long,
)

@Dao
interface SlopeDao {
    @Insert
    suspend fun insert(rule: SlopeRuleEntity)

    @Query("DELETE FROM slope_rules WHERE id = :id")
    suspend fun delete(id: String)

    @Query("SELECT * FROM slope_rules WHERE alignment_id = :alignmentId ORDER BY side, from_chainage, rowid")
    fun rules(alignmentId: String): Flow<List<SlopeRuleEntity>>

    @Query("SELECT * FROM slope_rules WHERE alignment_id = :alignmentId ORDER BY side, from_chainage, rowid")
    suspend fun rulesNow(alignmentId: String): List<SlopeRuleEntity>

    @Query("SELECT COUNT(*) FROM slope_rules WHERE alignment_id = :alignmentId")
    suspend fun count(alignmentId: String): Int
}
