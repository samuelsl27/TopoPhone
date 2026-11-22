package es.topophone.app.ui.projects

import es.topophone.core.model.project.CentralPoint
import es.topophone.core.model.project.Project
import es.topophone.core.model.project.ProjectCenter
import es.topophone.data.map.MapViewportRepository
import es.topophone.data.point.PointRepository
import javax.inject.Inject
import kotlinx.coroutines.flow.first

/**
 * El único sitio que calcula el centro de un proyecto (S006/RF-37): su punto central, si no el centro de sus puntos y, si
 * no, el de su último encuadre. Sin ninguno de los tres no tiene centro. Lo usan el aviso de distancia de S040/S042 y,
 * después, la zona de importación de S048.
 */
class ProjectCenterProvider @Inject constructor(private val points: PointRepository, private val viewports: MapViewportRepository) {
    /**
     * El centro de un proyecto que quizá todavía no existe: [central] si se ha fijado y, si no, el de los puntos o el encuadre del proyecto [projectId] si ya
     * está guardado (S058/RF-3: el origen del plano de terreno).
     */
    suspend fun centerOf(projectId: String?, central: CentralPoint?): CentralPoint? {
        central?.let { return it }
        projectId ?: return null
        val grounds = points.points(projectId).first().map {
            CentralPoint(it.coordinates.ground.latitudeDeg, it.coordinates.ground.longitudeDeg)
        }
        return ProjectCenter.of(null, grounds, viewports.viewport(projectId))
    }

    /** La altura elipsoidal media de los puntos del proyecto [projectId] que la tienen, o `null` (S058/RF-3: el factor propuesto). */
    suspend fun meanEllipsoidalHeight(projectId: String?): Double? {
        projectId ?: return null
        val heights = points.points(projectId).first().mapNotNull { it.coordinates.ground.ellipsoidalHeightM }
        return heights.takeIf { it.isNotEmpty() }?.average()
    }

    suspend fun center(project: Project): CentralPoint? {
        // Con punto central no hace falta leer nada más.
        project.centralPoint?.let { return it }
        val grounds = points.points(project.id).first().map {
            CentralPoint(it.coordinates.ground.latitudeDeg, it.coordinates.ground.longitudeDeg)
        }
        return ProjectCenter.of(null, grounds, viewports.viewport(project.id))
    }
}
