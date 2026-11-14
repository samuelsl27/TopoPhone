package es.topophone.app.ui.offline

import es.topophone.core.geodesy.GeoBox
import es.topophone.core.model.offline.ZoneMeta
import kotlinx.coroutines.flow.Flow

/** El identificador que da MapLibre a una región sin conexión. */
@JvmInline
value class RegionId(val value: Long)

/** Si MapLibre está descargando una región o no (`OfflineRegion.STATE_ACTIVE` e `INACTIVE`). */
enum class RegionDownloadState { ACTIVE, INACTIVE }

/**
 * Cómo va una región (S037/RF-15, RF-19). [requiredResources] cuenta, además de las teselas, el estilo y, en los vectoriales, los símbolos y los glifos;
 * [precise] dice si ya se conoce el total. [failures] son los errores que ha dado la región desde que se activó: MapLibre reintenta sin límite, así
 * que el número solo crece mientras falla (plan A5).
 */
data class RegionProgress(
    val state: RegionDownloadState,
    val completedResources: Long,
    val requiredResources: Long,
    val precise: Boolean,
    val completedTiles: Long,
    val bytes: Long,
    val failures: Long,
    val complete: Boolean,
) {
    /** De 0 a 1 lo que lleva descargado; 0 mientras no se sabe el total. */
    val fraction: Float
        get() = if (requiredResources <= 0) 0f else (completedResources.toFloat() / requiredResources).coerceIn(0f, 1f)

    companion object {
        val EMPTY = RegionProgress(RegionDownloadState.INACTIVE, 0, 0, false, 0, 0, 0, false)
    }
}

/** Lo que fija la región al crearla: el estilo, la caja y los zooms del mapa entre los que se descarga (plan, «Modelo de datos»). */
data class RegionDefinition(val styleUrl: String, val box: GeoBox, val minMapZoom: Double, val maxMapZoom: Double)

/** Una región de la base sin conexión; [meta] es null si no es de TopoPhone (otro uso de MapLibre) o su JSON no se puede leer. */
data class StoredRegion(val id: RegionId, val definition: RegionDefinition, val meta: ZoneMeta?, val progress: RegionProgress)

/**
 * Las regiones sin conexión de MapLibre (S037, plan «Contratos»): una zona son tantas regiones como fuentes. Todo ocurre en el hilo principal que usa MapLibre
 * y no bloquea más de 16 ms (RNF-T13). Una región nueva está inactiva (MapLibre las crea así) y también lo está tras reiniciar la app.
 */
interface OfflineRegionStore {
    suspend fun create(definition: RegionDefinition, meta: ZoneMeta): RegionId

    suspend fun list(): List<StoredRegion>

    /** El progreso de la región ahora y cada vez que cambia; termina si la región se borra. */
    fun observe(id: RegionId): Flow<RegionProgress>

    /** Empieza o pausa la descarga: lo ya guardado se conserva y reanudar solo pide lo que falta. */
    suspend fun setActive(id: RegionId, active: Boolean)

    /** Marca lo guardado como caducado para que la siguiente descarga lo revalide y sustituya lo que cambió (S037/RF-26). */
    suspend fun invalidate(id: RegionId)

    suspend fun delete(id: RegionId)

    suspend fun updateMeta(id: RegionId, meta: ZoneMeta)
}
