package es.topophone.data.surface

import es.topophone.core.model.surface.Surface
import kotlinx.coroutines.flow.first

/** Lo que necesita el replanteo de una superficie al abrirse (S062/RF-6): dejarla activa y visible y saber cuál es. */
fun interface SurfaceStakeStore {
    /** Deja [surfaceId] como la activa del proyecto [projectId] y visible en el mapa, y la devuelve; null si no existe. */
    suspend fun prepare(projectId: String, surfaceId: String): Surface?

    companion object {
        /** Sin superficies: para los tests y las vistas previas. */
        val NONE = SurfaceStakeStore { _, _ -> null }

        fun of(repository: SurfaceRepository) = SurfaceStakeStore { projectId, surfaceId ->
            val surface = repository.surfaces(projectId).first().firstOrNull { it.id == surfaceId } ?: return@SurfaceStakeStore null
            if (repository.activeSurfaceId(projectId).first() != surfaceId) repository.activate(projectId, surfaceId)
            if (!surface.visible) repository.setVisible(surfaceId, true)
            surface.copy(visible = true)
        }
    }
}
