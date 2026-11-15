package es.topophone.app.receiver

import es.topophone.app.ui.projects.ProjectCenterProvider
import es.topophone.core.model.project.CentralPoint
import es.topophone.data.project.ProjectRepository
import javax.inject.Inject
import kotlinx.coroutines.flow.first

/** El proyecto más reciente y su centro, para el origen por defecto del receptor virtual (S022/RF-13). */
data class VirtualProject(val name: String, val center: CentralPoint?)

/** De dónde sale el origen por defecto del receptor virtual: el centro del proyecto más recientemente abierto (S006/RF-37). */
interface VirtualOrigins {
    /** El proyecto abierto más recientemente y su centro (null si no tiene); null si no hay ningún proyecto. */
    suspend fun latestProject(): VirtualProject?

    /** Sin proyectos: en los sitios que no eligen un escenario. */
    object None : VirtualOrigins {
        override suspend fun latestProject(): VirtualProject? = null
    }
}

class RepositoryVirtualOrigins @Inject constructor(private val projects: ProjectRepository, private val centers: ProjectCenterProvider) :
    VirtualOrigins {
    override suspend fun latestProject(): VirtualProject? {
        // La búsqueda sin texto devuelve todos los proyectos del abierto más recientemente al más antiguo (S006/RF-1, RF-4).
        val latest = projects.search("").first().firstOrNull()?.project ?: return null
        return VirtualProject(latest.name, centers.center(latest))
    }
}
