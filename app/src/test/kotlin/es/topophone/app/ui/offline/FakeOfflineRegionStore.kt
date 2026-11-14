package es.topophone.app.ui.offline

import es.topophone.core.model.offline.ZoneMeta
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.takeWhile

/**
 * Las regiones de MapLibre para los tests (S037): guarda lo que se crea y deja que el test haga avanzar la descarga con [progress], [fail] y [finish]. Las
 * llamadas quedan en [calls], con sus argumentos, en el orden en que llegaron.
 */
class FakeOfflineRegionStore : OfflineRegionStore {
    private class Entry(var definition: RegionDefinition, var meta: ZoneMeta?, val progress: MutableStateFlow<RegionProgress?>)

    private val regions = LinkedHashMap<Long, Entry>()
    private var nextId = 1L
    val calls = mutableListOf<String>()

    /** Las regiones que tiene ahora, en el orden de creación. */
    val ids: List<RegionId> get() = regions.keys.map { RegionId(it) }

    override suspend fun create(definition: RegionDefinition, meta: ZoneMeta): RegionId {
        val id = nextId++
        regions[id] = Entry(definition, meta, MutableStateFlow(RegionProgress.EMPTY))
        calls += "create:$id"
        return RegionId(id)
    }

    /** Una región que no es de TopoPhone, sin metadatos legibles. */
    fun addForeign(definition: RegionDefinition): RegionId {
        val id = nextId++
        regions[id] = Entry(definition, null, MutableStateFlow(RegionProgress.EMPTY))
        return RegionId(id)
    }

    override suspend fun list(): List<StoredRegion> =
        regions.map { (id, e) -> StoredRegion(RegionId(id), e.definition, e.meta, e.progress.value ?: RegionProgress.EMPTY) }

    override fun observe(id: RegionId): Flow<RegionProgress> {
        val entry = regions[id.value] ?: return emptyFlow()
        return entry.progress.asStateFlow().takeWhile { it != null }.map { checkNotNull(it) }
    }

    override suspend fun setActive(id: RegionId, active: Boolean) {
        calls += "setActive:${id.value}:$active"
        update(id) { it.copy(state = if (active) RegionDownloadState.ACTIVE else RegionDownloadState.INACTIVE) }
    }

    override suspend fun invalidate(id: RegionId) {
        calls += "invalidate:${id.value}"
        update(id) { it.copy(complete = false) }
    }

    override suspend fun delete(id: RegionId) {
        calls += "delete:${id.value}"
        regions.remove(id.value)?.progress?.value = null
    }

    override suspend fun updateMeta(id: RegionId, meta: ZoneMeta) {
        calls += "updateMeta:${id.value}"
        regions[id.value]?.meta = meta
    }

    /** La región ahora, con sus metadatos. */
    fun meta(id: RegionId): ZoneMeta? = regions[id.value]?.meta

    fun progressOf(id: RegionId): RegionProgress = regions.getValue(id.value).progress.value ?: RegionProgress.EMPTY

    /** Hace avanzar la descarga: [completed] de [required] recursos, de ellos [tiles] teselas, con [bytes] ya guardados. */
    fun progress(id: RegionId, completed: Long, required: Long, tiles: Long = completed, bytes: Long = completed * 1_000) {
        update(id) {
            it.copy(completedResources = completed, requiredResources = required, precise = true, completedTiles = tiles, bytes = bytes)
        }
    }

    /** Suma [count] errores, como los reintentos de MapLibre ante un 500. */
    fun fail(id: RegionId, count: Long = 1) = update(id) { it.copy(failures = it.failures + count) }

    /** Termina la descarga: todo guardado y la región vuelve a inactiva, como en MapLibre. */
    fun finish(id: RegionId) = update(id) {
        it.copy(state = RegionDownloadState.INACTIVE, completedResources = it.requiredResources, complete = true)
    }

    private fun update(id: RegionId, change: (RegionProgress) -> RegionProgress) {
        val entry = regions[id.value] ?: return
        entry.progress.value = change(entry.progress.value ?: RegionProgress.EMPTY)
    }
}
