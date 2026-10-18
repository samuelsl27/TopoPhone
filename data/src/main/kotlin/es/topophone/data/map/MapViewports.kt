package es.topophone.data.map

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert
import es.topophone.core.model.map.MapViewport
import es.topophone.data.db.TopoPhoneDatabase
import es.topophone.data.project.ProjectEntity

/** Último encuadre del mapa de un proyecto; se borra con el proyecto (S009/RF-21). */
@Entity(
    tableName = "map_viewports",
    foreignKeys = [
        ForeignKey(entity = ProjectEntity::class, parentColumns = ["id"], childColumns = ["project_id"], onDelete = ForeignKey.CASCADE),
    ],
)
data class MapViewportEntity(
    @PrimaryKey @ColumnInfo(name = "project_id") val projectId: String,
    val latitude: Double,
    val longitude: Double,
    val zoom: Double,
)

@Dao
interface MapViewportDao {
    @Query("SELECT * FROM map_viewports WHERE project_id = :projectId")
    suspend fun byProject(projectId: String): MapViewportEntity?

    @Upsert
    suspend fun upsert(viewport: MapViewportEntity)
}

/** Encuadre del mapa de cada proyecto (S009/RF-21). */
interface MapViewportRepository {
    suspend fun viewport(projectId: String): MapViewport?

    suspend fun save(projectId: String, viewport: MapViewport)
}

class RoomMapViewportRepository(database: TopoPhoneDatabase) : MapViewportRepository {
    private val dao = database.mapViewports()

    override suspend fun viewport(projectId: String): MapViewport? = dao.byProject(projectId)?.let {
        MapViewport(it.latitude, it.longitude, it.zoom)
    }

    override suspend fun save(projectId: String, viewport: MapViewport) =
        dao.upsert(MapViewportEntity(projectId, viewport.latitudeDeg, viewport.longitudeDeg, viewport.zoom))
}
