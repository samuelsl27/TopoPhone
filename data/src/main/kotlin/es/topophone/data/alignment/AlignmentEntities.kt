package es.topophone.data.alignment

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
 * Un eje en un proyecto (S049, plan: modelo de datos). La planta y la rasante van en un bloque binario cada una dentro de la fila
 * ([geometry] y [profile], el formato de `AlignmentCodec`), así que importar es una sola inserción y es atómica (RNF-T6). Se borra con
 * el proyecto. [source] es `LANDXML`, `DRAWING`, `CSV` o `MANUAL`; [fileName] es NULL si se definió a mano. El PK inicial, la longitud y la
 * extensión están fuera de los bloques para listar y dibujar sin decodificarlos. [approximated] es el eje guardado como rectas cortas
 * por venir de otro sistema (decisión D2).
 */
@Entity(
    tableName = "alignments",
    foreignKeys = [
        ForeignKey(entity = ProjectEntity::class, parentColumns = ["id"], childColumns = ["project_id"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index("project_id")],
)
data class AlignmentEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "project_id") val projectId: String,
    val name: String,
    @ColumnInfo(name = "file_name") val fileName: String?,
    @ColumnInfo(name = "imported_at_ms") val importedAtMs: Long,
    val source: String,
    @ColumnInfo(name = "start_chainage") val startChainage: Double,
    @ColumnInfo(name = "length_m") val lengthM: Double,
    @ColumnInfo(defaultValue = "1") val visible: Boolean,
    @ColumnInfo(name = "min_e") val minEasting: Double,
    @ColumnInfo(name = "max_e") val maxEasting: Double,
    @ColumnInfo(name = "min_n") val minNorthing: Double,
    @ColumnInfo(name = "max_n") val maxNorthing: Double,
    @ColumnInfo(defaultValue = "0") val approximated: Boolean,
    val geometry: ByteArray,
    val profile: ByteArray?,
) {
    override fun equals(other: Any?): Boolean = other is AlignmentEntity &&
        key() == other.key() &&
        geometry.contentEquals(other.geometry) &&
        profile.contentEquals(other.profile)

    /** Todo menos los bloques binarios, que se comparan por su contenido. */
    private fun key() = listOf(
        id, projectId, name, fileName, importedAtMs, source, startChainage, lengthM, visible, minEasting, maxEasting, minNorthing,
        maxNorthing, approximated,
    )

    override fun hashCode(): Int = 31 * id.hashCode() + geometry.contentHashCode()
}

/** Un eje sin sus bloques binarios: lo que necesita la lista (S049/RF-14, RF-19). */
data class AlignmentRow(
    val id: String,
    @ColumnInfo(name = "project_id") val projectId: String,
    val name: String,
    @ColumnInfo(name = "file_name") val fileName: String?,
    @ColumnInfo(name = "imported_at_ms") val importedAtMs: Long,
    val source: String,
    @ColumnInfo(name = "start_chainage") val startChainage: Double,
    @ColumnInfo(name = "length_m") val lengthM: Double,
    val visible: Boolean,
    @ColumnInfo(name = "min_e") val minEasting: Double,
    @ColumnInfo(name = "max_e") val maxEasting: Double,
    @ColumnInfo(name = "min_n") val minNorthing: Double,
    @ColumnInfo(name = "max_n") val maxNorthing: Double,
    val approximated: Boolean,
    @ColumnInfo(name = "has_profile") val hasProfile: Boolean,
)

@Dao
interface AlignmentDao {
    @Insert
    suspend fun insert(alignment: AlignmentEntity)

    @Query("DELETE FROM alignments WHERE id = :id")
    suspend fun delete(id: String)

    @Query("UPDATE alignments SET name = :name WHERE id = :id")
    suspend fun rename(id: String, name: String)

    @Query("UPDATE alignments SET visible = :visible WHERE id = :id")
    suspend fun setVisible(id: String, visible: Boolean)

    @Query(
        "SELECT id, project_id, name, file_name, imported_at_ms, source, start_chainage, length_m, visible, min_e, max_e, min_n, max_n, " +
            "approximated, profile IS NOT NULL AS has_profile FROM alignments WHERE project_id = :projectId ORDER BY imported_at_ms, id",
    )
    fun rows(projectId: String): Flow<List<AlignmentRow>>

    @Query("SELECT * FROM alignments WHERE id = :id")
    suspend fun byId(id: String): AlignmentEntity?

    @Query("SELECT * FROM alignments WHERE project_id = :projectId ORDER BY imported_at_ms, id")
    fun all(projectId: String): Flow<List<AlignmentEntity>>

    @Query("SELECT name FROM alignments WHERE project_id = :projectId")
    suspend fun names(projectId: String): List<String>

    @Query("SELECT COUNT(*) FROM alignments WHERE project_id = :projectId")
    suspend fun count(projectId: String): Int

    @Query("SELECT project_id FROM alignments WHERE id = :id")
    suspend fun projectOf(id: String): String?

    @Query("SELECT active_alignment_id FROM projects WHERE id = :projectId")
    suspend fun activeId(projectId: String): String?

    @Query("SELECT active_alignment_id FROM projects WHERE id = :projectId")
    fun activeAlignmentId(projectId: String): Flow<String?>

    @Query("UPDATE projects SET active_alignment_id = :alignmentId WHERE id = :projectId")
    suspend fun setActive(projectId: String, alignmentId: String?)

    @Query("UPDATE projects SET active_alignment_id = NULL WHERE active_alignment_id = :alignmentId")
    suspend fun clearActive(alignmentId: String)
}
