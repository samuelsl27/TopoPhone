package es.topophone.data.wms

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Embedded
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

    @Query("SELECT * FROM project_wms_layers WHERE id = :id")
    suspend fun projectLayer(id: String): ProjectWmsLayerEntity?

    @Query("SELECT * FROM project_wms_layers WHERE project_id = :projectId ORDER BY position, added_at, id")
    suspend fun projectLayersNow(projectId: String): List<ProjectWmsLayerEntity>

    @Query("SELECT COUNT(*) FROM project_wms_layers WHERE project_id = :projectId")
    suspend fun countProjectLayers(projectId: String): Int

    @Query("SELECT COUNT(*) FROM project_wms_layers WHERE project_id = :projectId AND service_id = :serviceId AND layer_name = :name")
    suspend fun countProjectLayer(projectId: String, serviceId: String, name: String): Int

    @Query("SELECT * FROM wms_layers WHERE service_id = :serviceId AND name = :name ORDER BY ordinal")
    suspend fun layersNamed(serviceId: String, name: String): List<WmsLayerEntity>

    /** Baja un puesto todas las capas del proyecto, para poner una nueva arriba. */
    @Query("UPDATE project_wms_layers SET position = position + 1 WHERE project_id = :projectId")
    suspend fun shiftDown(projectId: String)

    @Query("UPDATE project_wms_layers SET visible = :visible WHERE id = :id")
    suspend fun setVisible(id: String, visible: Boolean): Int

    @Query("UPDATE project_wms_layers SET opacity = :opacity WHERE id = :id")
    suspend fun setOpacity(id: String, opacity: Int): Int

    @Query("UPDATE project_wms_layers SET position = :position WHERE id = :id")
    suspend fun setPosition(id: String, position: Int)

    @Query("DELETE FROM project_wms_layers WHERE id = :id")
    suspend fun deleteProjectLayer(id: String)

    /**
     * Las capas del proyecto de arriba abajo con su servicio y lo que éste dice de ellas: una sola fila por capa, la que ofrece Web Mercator
     * si el nombre sale más de una vez; `l_*` es null si ya no está o ya no se puede pedir. Se vuelve a emitir al cambiar cualquiera de las
     * tres tablas (S036/RF-6, RF-27).
     */
    @Query(
        "SELECT pl.id, pl.project_id, pl.service_id, pl.layer_name, pl.position, pl.visible, pl.opacity, pl.added_at, " +
            "s.id AS s_id, s.name AS s_name, s.address AS s_address, s.address_key AS s_address_key, s.title AS s_title, " +
            "s.version AS s_version, s.attribution AS s_attribution, s.preset_key AS s_preset_key, s.request_mode AS s_request_mode, " +
            "s.map_formats AS s_map_formats, s.info_formats AS s_info_formats, s.created_at AS s_created_at, " +
            "s.updated_at AS s_updated_at, " +
            "l.service_id AS l_service_id, l.ordinal AS l_ordinal, l.depth AS l_depth, l.name AS l_name, l.title AS l_title, " +
            "l.summary AS l_summary, l.transparent AS l_transparent, l.queryable AS l_queryable, l.mercator_code AS l_mercator_code " +
            "FROM project_wms_layers pl JOIN wms_services s ON s.id = pl.service_id " +
            "LEFT JOIN wms_layers l ON l.service_id = pl.service_id AND l.ordinal = (SELECT MIN(x.ordinal) FROM wms_layers x " +
            "WHERE x.service_id = pl.service_id AND x.name = pl.layer_name AND x.mercator_code IS NOT NULL) " +
            "WHERE pl.project_id = :projectId ORDER BY pl.position, pl.added_at, pl.id",
    )
    fun projectLayerViews(projectId: String): Flow<List<ProjectLayerJoin>>
}

/** Una capa de un proyecto con su servicio y, si sigue pudiéndose pedir, lo que el servicio dice de ella (S036/RF-27). */
data class ProjectLayerJoin(
    @Embedded val layer: ProjectWmsLayerEntity,
    @Embedded(prefix = "s_") val service: WmsServiceEntity,
    @Embedded(prefix = "l_") val info: WmsLayerEntity?,
)

data class UseRow(val projectId: String, val projectName: String, val layers: Int)

data class MissingRow(val projectId: String, val projectName: String, val layerName: String)
