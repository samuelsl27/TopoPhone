package es.topophone.data.wms

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
 * Un servicio WMS de la biblioteca del móvil (S036/RF-3, RF-4, RF-15). [address] es la dirección normalizada con sus parámetros y
 * [addressKey] lo que distingue un servicio de otro: dos direcciones equivalentes tienen la misma, y no hay dos servicios con la misma. Los
 * formatos son listas separadas por `|`. [presetKey] es la clave del preset de donde viene, o null si lo escribió el usuario.
 */
@Entity(tableName = "wms_services", indices = [Index(value = ["address_key"], unique = true)])
data class WmsServiceEntity(
    @PrimaryKey val id: String,
    val name: String,
    val address: String,
    @ColumnInfo(name = "address_key") val addressKey: String,
    val title: String,
    val version: String,
    val attribution: String,
    @ColumnInfo(name = "preset_key") val presetKey: String?,
    @ColumnInfo(name = "request_mode") val requestMode: String,
    @ColumnInfo(name = "map_formats") val mapFormats: String,
    @ColumnInfo(name = "info_formats") val infoFormats: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

/**
 * Una capa del árbol que leyó el GetCapabilities de un servicio, en el orden del documento (S036/RF-3, RF-4). El árbol se rehace con
 * [ordinal] y [depth]. Se borra con su servicio.
 */
@Entity(
    tableName = "wms_layers",
    primaryKeys = ["service_id", "ordinal"],
    foreignKeys = [
        ForeignKey(entity = WmsServiceEntity::class, parentColumns = ["id"], childColumns = ["service_id"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index(value = ["service_id", "name"])],
)
data class WmsLayerEntity(
    @ColumnInfo(name = "service_id") val serviceId: String,
    val ordinal: Int,
    val depth: Int,
    val name: String?,
    val title: String,
    val summary: String,
    val transparent: Boolean,
    val queryable: Boolean,
    @ColumnInfo(name = "mercator_code") val mercatorCode: String?,
)

/**
 * Una capa de un servicio añadida a un proyecto (S036/RF-18 a RF-24). [position] 0 es la de arriba; [opacity] va de 0 a 100 de 5 en 5. Una
 * capa no se repite en su proyecto, y se borra con el proyecto o con su servicio.
 */
@Entity(
    tableName = "project_wms_layers",
    foreignKeys = [
        ForeignKey(entity = ProjectEntity::class, parentColumns = ["id"], childColumns = ["project_id"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = WmsServiceEntity::class, parentColumns = ["id"], childColumns = ["service_id"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index(value = ["project_id", "service_id", "layer_name"], unique = true), Index(value = ["service_id"])],
)
data class ProjectWmsLayerEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "project_id") val projectId: String,
    @ColumnInfo(name = "service_id") val serviceId: String,
    @ColumnInfo(name = "layer_name") val layerName: String,
    val position: Int,
    val visible: Boolean,
    val opacity: Int,
    @ColumnInfo(name = "added_at") val addedAt: Long,
)

@Dao
interface WmsDao {
    @Insert
    suspend fun insertService(service: WmsServiceEntity)

    @Insert
    suspend fun insertLayers(layers: List<WmsLayerEntity>)

    @Insert
    suspend fun insertProjectLayer(layer: ProjectWmsLayerEntity)

    /** Los servicios de la biblioteca por nombre, sin distinguir mayúsculas. */
    @Query("SELECT * FROM wms_services ORDER BY name COLLATE NOCASE, id")
    fun services(): Flow<List<WmsServiceEntity>>

    @Query("SELECT * FROM wms_layers WHERE service_id = :serviceId ORDER BY ordinal")
    suspend fun layersOf(serviceId: String): List<WmsLayerEntity>

    @Query("SELECT * FROM project_wms_layers WHERE project_id = :projectId ORDER BY position")
    fun projectLayers(projectId: String): Flow<List<ProjectWmsLayerEntity>>
}
