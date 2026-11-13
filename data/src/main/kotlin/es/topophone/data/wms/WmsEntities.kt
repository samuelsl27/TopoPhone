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

    @Query("SELECT * FROM wms_services WHERE id = :id")
    suspend fun service(id: String): WmsServiceEntity?

    @Query("SELECT * FROM wms_services WHERE address_key = :key")
    suspend fun serviceByKey(key: String): WmsServiceEntity?

    @Query("DELETE FROM wms_layers WHERE service_id = :serviceId")
    suspend fun deleteLayers(serviceId: String)

    @Query("DELETE FROM wms_services WHERE id = :id")
    suspend fun deleteService(id: String)

    @Query("UPDATE wms_services SET name = :name WHERE id = :id")
    suspend fun rename(id: String, name: String): Int

    @Query("UPDATE wms_services SET request_mode = :mode WHERE id = :id")
    suspend fun setRequestMode(id: String, mode: String): Int

    @Query(
        "UPDATE wms_services SET title = :title, version = :version, attribution = :attribution, map_formats = :mapFormats, " +
            "info_formats = :infoFormats, updated_at = :updatedAt WHERE id = :id",
    )
    suspend fun updateRead(
        id: String,
        title: String,
        version: String,
        attribution: String,
        mapFormats: String,
        infoFormats: String,
        updatedAt: Long,
    )

    /** Los proyectos activos con capas del servicio y cuántas (S036/RF-7). */
    @Query(
        "SELECT p.id AS projectId, p.name AS projectName, COUNT(*) AS layers FROM project_wms_layers l " +
            "JOIN projects p ON p.id = l.project_id WHERE l.service_id = :serviceId AND p.deleted_at IS NULL " +
            "GROUP BY p.id, p.name ORDER BY p.name COLLATE NOCASE, p.id",
    )
    suspend fun uses(serviceId: String): List<UseRow>

    /** Las capas de proyectos activos que ya no están entre las capas con nombre y con Web Mercator del servicio (S036/RF-6, RF-27). */
    @Query(
        "SELECT p.id AS projectId, p.name AS projectName, l.layer_name AS layerName FROM project_wms_layers l " +
            "JOIN projects p ON p.id = l.project_id WHERE l.service_id = :serviceId AND p.deleted_at IS NULL AND l.layer_name NOT IN " +
            "(SELECT name FROM wms_layers WHERE service_id = :serviceId AND name IS NOT NULL AND mercator_code IS NOT NULL) " +
            "ORDER BY p.name COLLATE NOCASE, p.id, l.position",
    )
    suspend fun missing(serviceId: String): List<MissingRow>
}

data class UseRow(val projectId: String, val projectName: String, val layers: Int)

data class MissingRow(val projectId: String, val projectName: String, val layerName: String)
