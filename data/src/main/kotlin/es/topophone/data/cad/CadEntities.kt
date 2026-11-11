package es.topophone.data.cad

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import es.topophone.data.project.ProjectEntity

/**
 * Un DXF importado en un proyecto (S040). Se borra con el proyecto. [status] es `IMPORTING` mientras se escribe y `READY` cuando
 * está entero; solo un dibujo `READY` existe para el resto de la app (S040/RF-12, RNF-T6). [hasElevations] es la cota del dibujo
 * entero (RF-8, RF-22).
 */
@Entity(
    tableName = "cad_drawings",
    foreignKeys = [
        ForeignKey(entity = ProjectEntity::class, parentColumns = ["id"], childColumns = ["project_id"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index("project_id")],
)
data class CadDrawingEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "project_id") val projectId: String,
    @ColumnInfo(name = "file_name") val fileName: String,
    @ColumnInfo(name = "imported_at_ms") val importedAtMs: Long,
    @ColumnInfo(name = "source_crs") val sourceCrs: String,
    @ColumnInfo(name = "source_unit") val sourceUnit: String,
    @ColumnInfo(name = "use_elevations") val useElevations: Boolean,
    @ColumnInfo(name = "has_elevations") val hasElevations: Boolean,
    val incomplete: Boolean,
    val status: String,
    @ColumnInfo(name = "min_e") val minEasting: Double?,
    @ColumnInfo(name = "max_e") val maxEasting: Double?,
    @ColumnInfo(name = "min_n") val minNorthing: Double?,
    @ColumnInfo(name = "max_n") val maxNorthing: Double?,
    /** `DXF`, `GEOPACKAGE` o `SHAPEFILE` (S048/RF-26, S030/RF-26). */
    @ColumnInfo(defaultValue = "'DXF'") val origin: String = "DXF",
)

/** Una capa de diseño: una capa del DXF de un solo tipo, con su color y su interruptor (S040/RF-26, RF-27). */
@Entity(
    tableName = "cad_layers",
    foreignKeys = [
        ForeignKey(entity = CadDrawingEntity::class, parentColumns = ["id"], childColumns = ["drawing_id"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index("drawing_id")],
)
data class CadLayerEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "drawing_id") val drawingId: String,
    val name: String,
    val kind: String,
    @ColumnInfo(name = "color_argb") val colorArgb: Int,
    @ColumnInfo(name = "auto_contrast") val autoContrast: Boolean,
    val visible: Boolean,
    @ColumnInfo(name = "entity_count") val entityCount: Int,
    val position: Int,
    /** La tabla del GeoPackage de la que viene la capa y su campo de nombre; NULL en un dibujo de DXF (S048/RF-21). */
    @ColumnInfo(name = "source_table") val sourceTable: String? = null,
    @ColumnInfo(name = "name_field") val nameField: String? = null,
)

/** Un trozo de una capa: unos cientos de elementos de la misma zona en un bloque binario de hasta 256 kB (S040, plan). */
@Entity(
    tableName = "cad_chunks",
    foreignKeys = [
        ForeignKey(entity = CadLayerEntity::class, parentColumns = ["id"], childColumns = ["layer_id"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index("layer_id")],
)
data class CadChunkEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "layer_id") val layerId: String,
    @ColumnInfo(name = "min_e") val minEasting: Double,
    @ColumnInfo(name = "max_e") val maxEasting: Double,
    @ColumnInfo(name = "min_n") val minNorthing: Double,
    @ColumnInfo(name = "max_n") val maxNorthing: Double,
    @ColumnInfo(name = "entity_count") val entityCount: Int,
    val data: ByteArray,
) {
    override fun equals(other: Any?): Boolean = other is CadChunkEntity && key() == other.key() && data.contentEquals(other.data)

    /** Todo menos el bloque binario, que se compara por su contenido. */
    private fun key() = listOf(id, layerId, minEasting, maxEasting, minNorthing, maxNorthing, entityCount)

    override fun hashCode(): Int = 31 * id.hashCode() + data.contentHashCode()
}

/**
 * Los atributos de los objetos de un trozo de un dibujo de GeoPackage, en un solo bloque en el mismo orden que sus elementos (S048/RF-20).
 * Se lee solo al tocar un objeto, así dibujar el mapa no paga por ellos. Se borra con su trozo.
 */
@Entity(
    tableName = "cad_chunk_attributes",
    foreignKeys = [
        ForeignKey(entity = CadChunkEntity::class, parentColumns = ["id"], childColumns = ["chunk_id"], onDelete = ForeignKey.CASCADE),
    ],
)
data class CadChunkAttributesEntity(@PrimaryKey @ColumnInfo(name = "chunk_id") val chunkId: Long, val data: ByteArray) {
    override fun equals(other: Any?): Boolean =
        other is CadChunkAttributesEntity && chunkId == other.chunkId && data.contentEquals(other.data)

    override fun hashCode(): Int = 31 * chunkId.hashCode() + data.contentHashCode()
}
