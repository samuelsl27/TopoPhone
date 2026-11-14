package es.topophone.app.ui.offline

import es.topophone.app.ui.projects.ProjectCenterProvider
import es.topophone.core.model.map.Basemap
import es.topophone.core.model.project.CentralPoint
import es.topophone.data.map.MapPreferences
import es.topophone.data.point.PointRepository
import es.topophone.data.project.ProjectRepository
import es.topophone.data.wms.ProjectLayerView
import es.topophone.data.wms.ProjectLayersRepository
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first

/** Lo que el plan de una zona lee del proyecto y de los ajustes del mapa (S037/RF-2, RF-5, RF-6, RF-8); los tests lo sustituyen por un doble. */
interface ZoneInputs {
    /** El centro del proyecto (S006/RF-37), o null si no tiene. */
    suspend fun center(projectId: String): CentralPoint?

    /** Los puntos del proyecto, sin redondear. */
    suspend fun points(projectId: String): List<CentralPoint>

    /** Las capas WMS del proyecto, de arriba abajo. */
    suspend fun wmsLayers(projectId: String): List<ProjectLayerView>

    /** El mapa base elegido (S009/RF-8). */
    suspend fun basemap(): Basemap
}

@Singleton
class RepositoryZoneInputs @Inject constructor(
    private val projects: ProjectRepository,
    private val centers: ProjectCenterProvider,
    private val points: PointRepository,
    private val layers: ProjectLayersRepository,
    private val map: MapPreferences,
) : ZoneInputs {
    override suspend fun center(projectId: String): CentralPoint? = projects.project(projectId)?.let { centers.center(it) }

    override suspend fun points(projectId: String): List<CentralPoint> =
        points.points(projectId).first().map { CentralPoint(it.coordinates.ground.latitudeDeg, it.coordinates.ground.longitudeDeg) }

    override suspend fun wmsLayers(projectId: String): List<ProjectLayerView> = layers.layers(projectId).first()

    override suspend fun basemap(): Basemap = map.settings.first().basemap
}

/** El reloj y los identificadores de las zonas, para que los tests los fijen. */
interface ZoneClock {
    fun nowMs(): Long

    fun newId(): String
}

@Singleton
class SystemZoneClock @Inject constructor() : ZoneClock {
    override fun nowMs(): Long = System.currentTimeMillis()

    override fun newId(): String = UUID.randomUUID().toString()
}
