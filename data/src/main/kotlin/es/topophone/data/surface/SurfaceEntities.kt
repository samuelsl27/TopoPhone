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
    /** `TIN` (un LandXML, S042) o `RASTER` (un ráster de cotas, S045); las columnas siguientes son solo del ráster. */
    @ColumnInfo(defaultValue = "TIN") val kind: String = "TIN",
    @ColumnInfo(name = "height_system") val heightSystem: String? = null,
    @ColumnInfo(name = "value_type") val valueType: String? = null,
    @ColumnInfo(name = "column_count") val columnCount: Int? = null,
    @ColumnInfo(name = "row_count") val rowCount: Int? = null,
    @ColumnInfo(name = "origin_x") val originX: Double? = null,
    @ColumnInfo(name = "origin_y") val originY: Double? = null,
    @ColumnInfo(name = "step_x") val stepX: Double? = null,
    @ColumnInfo(name = "step_y") val stepY: Double? = null,
    @ColumnInfo(name = "data_cells") val dataCells: Long? = null,
    @ColumnInfo(name = "nodata_cells") val noDataCells: Long? = null,
)

/**
 * Un bloque de 128 × 128 celdas de un ráster de cotas, en la columna [tileColumn] y la fila [tileRow] de la cuadrícula de bloques
 * (S045, plan «Room v22»): [data] es el bloque binario de `RasterTileCodec`, de hasta 128 kB. Se borra con su superficie.
 */
@Entity(
    tableName = "raster_tiles",
    foreignKeys = [
        ForeignKey(entity = SurfaceEntity::class, parentColumns = ["id"], childColumns = ["surface_id"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index(value = ["surface_id", "tile_col", "tile_row"], unique = true)],
)
data class RasterTileEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "surface_id") val surfaceId: String,
    @ColumnInfo(name = "tile_col") val tileColumn: Int,
    @ColumnInfo(name = "tile_row") val tileRow: Int,
    val data: ByteArray,
) {
    override fun equals(other: Any?): Boolean = other is RasterTileEntity &&
        id == other.id &&
        surfaceId == other.surfaceId &&
        tileColumn == other.tileColumn &&
        tileRow == other.tileRow &&
        data.contentEquals(other.data)

    override fun hashCode(): Int = 31 * (31 * id.hashCode() + surfaceId.hashCode()) + data.contentHashCode()
}

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

    @Insert
    suspend fun insertRasterTiles(tiles: List<RasterTileEntity>)

    @Query(
        "SELECT data FROM raster_tiles WHERE surface_id = :surfaceId AND tile_col = :tileColumn AND tile_row = :tileRow " +
            "AND surface_id IN (SELECT id FROM surfaces WHERE status = 'READY')",
    )
    suspend fun rasterTile(surfaceId: String, tileColumn: Int, tileRow: Int): ByteArray?

    @Query("SELECT COUNT(*) FROM raster_tiles WHERE surface_id = :surfaceId")
    suspend fun rasterTileCount(surfaceId: String): Int

    @Query(
        "UPDATE surfaces SET status = 'READY', min_e = :minE, max_e = :maxE, min_n = :minN, max_n = :maxN, min_z = :minZ, max_z = :maxZ, " +
            "data_cells = :dataCells, nodata_cells = :noDataCells WHERE id = :surfaceId",
    )
    suspend fun markRasterReady(
        surfaceId: String,
        minE: Double,
        maxE: Double,
        minN: Double,
        maxN: Double,
        minZ: Double,
        maxZ: Double,
        dataCells: Long,
        noDataCells: Long,
    )

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

    @Query("SELECT project_id FROM surfaces WHERE id = :surfaceId")
    suspend fun projectOf(surfaceId: String): String?

    @Query("SELECT active_surface_id FROM projects WHERE id = :projectId")
    suspend fun activeId(projectId: String): String?

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
