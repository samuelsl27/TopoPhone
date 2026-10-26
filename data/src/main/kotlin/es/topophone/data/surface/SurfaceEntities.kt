package es.topophone.data.surface

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
 * Una superficie importada de un LandXML en un proyecto (S042). Se borra con el proyecto. [status] es `IMPORTING` mientras se
 * escribe y `READY` cuando está entera; solo una superficie `READY` existe para el resto de la app (S042/RF-11, RNF-T6).
 * [sourceCrs] y [sourceUnit] son lo que el usuario eligió al importar (RF-6, RF-7).
 */
@Entity(
    tableName = "surfaces",
    foreignKeys = [
        ForeignKey(entity = ProjectEntity::class, parentColumns = ["id"], childColumns = ["project_id"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index("project_id")],
)
data class SurfaceEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "project_id") val projectId: String,
    val name: String,
    @ColumnInfo(name = "file_name") val fileName: String,
    @ColumnInfo(name = "imported_at_ms") val importedAtMs: Long,
    @ColumnInfo(name = "source_crs") val sourceCrs: String,
    @ColumnInfo(name = "source_unit") val sourceUnit: String,
    val status: String,
    @ColumnInfo(name = "min_e") val minEasting: Double?,
    @ColumnInfo(name = "max_e") val maxEasting: Double?,
    @ColumnInfo(name = "min_n") val minNorthing: Double?,
    @ColumnInfo(name = "max_n") val maxNorthing: Double?,
    @ColumnInfo(name = "min_z") val minHeight: Double?,
    @ColumnInfo(name = "max_z") val maxHeight: Double?,
    @ColumnInfo(name = "vertex_count") val vertexCount: Int,
    @ColumnInfo(name = "triangle_count") val triangleCount: Int,
    @ColumnInfo(name = "hole_count") val holeCount: Int,
)

/**
 * Una tesela de una superficie: su celda y su bloque binario de hasta 256 kB, autosuficiente (S042, plan A3). Varias teselas pueden
 * tener la misma celda.
 */
@Entity(
    tableName = "surface_tiles",
    foreignKeys = [
        ForeignKey(entity = SurfaceEntity::class, parentColumns = ["id"], childColumns = ["surface_id"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index("surface_id")],
)
data class SurfaceTileEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "surface_id") val surfaceId: String,
    @ColumnInfo(name = "min_e") val minEasting: Double,
    @ColumnInfo(name = "max_e") val maxEasting: Double,
    @ColumnInfo(name = "min_n") val minNorthing: Double,
    @ColumnInfo(name = "max_n") val maxNorthing: Double,
    @ColumnInfo(name = "triangle_count") val triangleCount: Int,
    val data: ByteArray,
) {
    override fun equals(other: Any?): Boolean = other is SurfaceTileEntity && key() == other.key() && data.contentEquals(other.data)

    /** Todo menos el bloque binario, que se compara por su contenido. */
    private fun key() = listOf(id, surfaceId, minEasting, maxEasting, minNorthing, maxNorthing, triangleCount)

    override fun hashCode(): Int = 31 * id.hashCode() + data.contentHashCode()
}

/** La celda de una tesela sin su bloque: lo que se carga al activar una superficie (S042, plan A4). */
data class SurfaceTileCell(
    val id: Long,
    @ColumnInfo(name = "min_e") val minEasting: Double,
    @ColumnInfo(name = "max_e") val maxEasting: Double,
    @ColumnInfo(name = "min_n") val minNorthing: Double,
    @ColumnInfo(name = "max_n") val maxNorthing: Double,
    @ColumnInfo(name = "triangle_count") val triangleCount: Int,
)

@Dao
interface SurfaceDao {
    @Insert
    suspend fun insertSurface(surface: SurfaceEntity)

    @Insert
    suspend fun insertTiles(tiles: List<SurfaceTileEntity>)

    @Query(
        "UPDATE surfaces SET status = 'READY', min_e = :minE, max_e = :maxE, min_n = :minN, max_n = :maxN, min_z = :minZ, max_z = :maxZ, " +
            "vertex_count = :vertices, triangle_count = :triangles, hole_count = :holes WHERE id = :surfaceId",
    )
    suspend fun markReady(
        surfaceId: String,
        minE: Double,
        maxE: Double,
        minN: Double,
        maxN: Double,
        minZ: Double,
        maxZ: Double,
        vertices: Int,
        triangles: Int,
        holes: Int,
    )

    @Query("DELETE FROM surfaces WHERE id = :surfaceId")
    suspend fun deleteSurface(surfaceId: String)

    @Query("DELETE FROM surfaces WHERE status != 'READY'")
    suspend fun deleteUnfinished(): Int

    @Query("SELECT * FROM surfaces WHERE project_id = :projectId AND status = 'READY' ORDER BY imported_at_ms, id")
    fun readySurfaces(projectId: String): Flow<List<SurfaceEntity>>

    @Query("SELECT * FROM surfaces WHERE id = :surfaceId AND status = 'READY'")
    suspend fun readySurface(surfaceId: String): SurfaceEntity?

    @Query("SELECT COUNT(*) FROM surfaces WHERE project_id = :projectId AND status = 'READY'")
    suspend fun readyCount(projectId: String): Int

    @Query("SELECT id, min_e, max_e, min_n, max_n, triangle_count FROM surface_tiles WHERE surface_id = :surfaceId ORDER BY id")
    suspend fun cells(surfaceId: String): List<SurfaceTileCell>

    @Query("SELECT * FROM surface_tiles WHERE id = :tileId")
    suspend fun tile(tileId: Long): SurfaceTileEntity?

    @Query("SELECT active_surface_id FROM projects WHERE id = :projectId")
    fun activeSurfaceId(projectId: String): Flow<String?>

    @Query("SELECT vertical_offset_m FROM projects WHERE id = :projectId")
    fun verticalOffset(projectId: String): Flow<Double?>

    @Query("UPDATE projects SET active_surface_id = :surfaceId WHERE id = :projectId")
    suspend fun setActive(projectId: String, surfaceId: String?)

    @Query("UPDATE projects SET active_surface_id = NULL WHERE active_surface_id = :surfaceId")
    suspend fun clearActive(surfaceId: String)

    @Query("UPDATE projects SET vertical_offset_m = :offsetM WHERE id = :projectId")
    suspend fun setVerticalOffset(projectId: String, offsetM: Double)
}
