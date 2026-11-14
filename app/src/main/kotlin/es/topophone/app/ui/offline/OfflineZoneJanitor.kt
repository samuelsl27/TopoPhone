package es.topophone.app.ui.offline

import es.topophone.core.model.log.AppLog
import es.topophone.core.model.log.LogCategory
import es.topophone.data.project.ProjectRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** Los identificadores de todos los proyectos que existen, también los de la papelera, que conserva sus zonas (S037/RF-28). */
fun interface ProjectIds {
    fun existing(): Flow<Set<String>>
}

/** Los proyectos de todas las carpetas más los de la papelera, con lo que ya ofrece [ProjectRepository]: S006 no se toca. */
@Singleton
class RepositoryProjectIds @Inject constructor(private val projects: ProjectRepository) : ProjectIds {
    @OptIn(ExperimentalCoroutinesApi::class)
    override fun existing(): Flow<Set<String>> = projects.folders().flatMapLatest { folders ->
        val active = (
            listOf<String?>(null) + folders.map {
                it.id
            }
            ).map { folder -> projects.projects(folder).map { list -> list.map { it.id } } }
        combine(active + projects.trash().map { list -> list.map { it.project.id } }) { lists -> lists.flatMap { it }.toSet() }
    }
}

/**
 * Borra las zonas sin conexión de los proyectos que ya no existen (S037/RF-28, plan A7). Un proyecto borrado definitivamente (a mano o al cumplir sus 30 días
 * en la papelera) desaparece de [ProjectIds]; entonces sus regiones se borran, y se hace también al abrir la app. Mientras el proyecto esté en la papelera, sus
 * zonas se conservan. No toca el código de S006: observa lo que ya ofrece. Las regiones que no son de TopoPhone no se tocan.
 */
@Singleton
class OfflineZoneJanitor @Inject constructor(
    private val store: OfflineRegionStore,
    private val projects: ProjectIds,
    private val log: AppLog,
) {

    fun start(scope: CoroutineScope): Job = scope.launch {
        projects.existing().distinctUntilChanged().collect { existing ->
            // Un fallo de MapLibre o de la base no debe tumbar la app: se anota y se vuelve a intentar con el siguiente cambio.
            runCatching {
                sweep(existing)
            }.onFailure { log.error(LogCategory.APP, "No se pudieron barrer las zonas sin conexión: ${it.message}") }
        }
    }

    /** Borra las zonas cuyo proyecto no está en [existing] y devuelve cuántas zonas (no regiones) ha quitado. */
    suspend fun sweep(existing: Set<String>): Int {
        val orphans = store.list().filter { region -> region.meta?.let { it.projectId !in existing } == true }
        orphans.forEach { store.delete(it.id) }
        val zones = orphans.mapNotNull { it.meta }.distinctBy { it.zoneId }
        zones.forEach { log.info(LogCategory.APP, ZoneLog.deleted(it.name)) }
        return zones.size
    }
}
