package es.topophone.app.ui.offline

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import es.topophone.core.model.log.AppLog
import es.topophone.core.model.log.LogCategory
import es.topophone.core.model.offline.DetailLevel
import es.topophone.core.model.offline.DownloadableSource
import es.topophone.core.model.offline.OfflineLimits
import es.topophone.core.model.offline.SourceState
import es.topophone.core.model.offline.ZoneMeta
import es.topophone.core.model.offline.ZoneState
import es.topophone.data.offline.OfflinePreferences
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Una zona sin conexión en la lista (S037/RF-24): lo que se ve de ella, sacado de las regiones de sus fuentes. */
data class ZoneItem(
    val zoneId: String,
    val projectId: String,
    val name: String,
    val sources: List<DownloadableSource>,
    val detail: DetailLevel,
    /** El tamaño real, no el estimado: lo que ocupan sus regiones ahora. */
    val sizeBytes: Long,
    /** La fecha de descarga: la de la fuente más antigua, o null si alguna no se ha descargado. */
    val downloadedAtMs: Long?,
    val state: ZoneState,
    /** De 0 a 1: lo que lleva descargado una zona incompleta. */
    val fraction: Float,
)

data class OfflineZonesUiState(
    val loaded: Boolean = false,
    /** Las zonas del proyecto, de la más reciente a la más antigua. */
    val zones: List<ZoneItem> = emptyList(),
    /** Lo que ocupan todas las zonas sin conexión de la app, de todos los proyectos (RF-24). */
    val totalBytes: Long = 0,
    val limitBytes: Long = OfflineLimits.DEFAULT_STORAGE_LIMIT_BYTES,
    /** La zona que el usuario ha pedido borrar y está esperando confirmación (RF-27). */
    val pendingDelete: ZoneItem? = null,
    val download: DownloadState = DownloadState(),
)

/**
 * Las zonas sin conexión del proyecto (S037/RF-24 a RF-27, RF-31): la lista con su estado, la ocupación total y el límite, y renombrar, actualizar,
 * reanudar y borrar con confirmación. Sale de las regiones de MapLibre; se vuelve a leer cuando cambia la descarga y después de cada orden.
 */
@HiltViewModel
class OfflineZonesViewModel @Inject constructor(
    private val store: OfflineRegionStore,
    private val downloader: OfflineDownloader,
    private val preferences: OfflinePreferences,
    private val log: AppLog,
) : ViewModel() {
    private val mutableState = MutableStateFlow(OfflineZonesUiState())
    val state: StateFlow<OfflineZonesUiState> = mutableState.asStateFlow()

    private var projectId: String? = null
    private var watching: Job? = null

    /** Abre las zonas del proyecto [projectId] y sigue la descarga. */
    fun open(projectId: String) {
        this.projectId = projectId
        watching?.cancel()
        watching = viewModelScope.launch {
            reload()
            var lastKey: Any? = null
            downloader.state.collect { download ->
                mutableState.update { it.copy(download = download) }
                // Solo cuando cambia la fase, la zona o el estado de alguna fuente: el progreso de cada tesela no relee las regiones.
                val key = Triple(download.phase, download.zoneId, download.sources.map { it.state })
                if (key != lastKey) {
                    lastKey = key
                    reload()
                }
            }
        }
    }

    fun rename(zoneId: String, name: String) {
        val clean = name.trim()
        if (clean.isEmpty()) return
        viewModelScope.launch {
            regionsOf(zoneId).forEach { region -> region.meta?.let { store.updateMeta(region.id, it.copy(name = clean)) } }
            reload()
        }
    }

    fun update(zoneId: String) {
        viewModelScope.launch {
            downloader.update(zoneId)
            reload()
        }
    }

    /** Sigue con una zona incompleta; es también el «Reintentar» de las fuentes incompletas (RF-19). */
    fun resume(zoneId: String) {
        viewModelScope.launch {
            downloader.resume(zoneId)
            reload()
        }
    }

    fun requestDelete(zoneId: String) {
        mutableState.update { state -> state.copy(pendingDelete = state.zones.firstOrNull { it.zoneId == zoneId }) }
    }

    fun dismissDelete() {
        mutableState.update { it.copy(pendingDelete = null) }
    }

    /** Borra la zona pendiente: si es la que se está descargando, cancela la descarga (RF-27). */
    fun confirmDelete() {
        val zone = mutableState.value.pendingDelete ?: return
        mutableState.update { it.copy(pendingDelete = null) }
        viewModelScope.launch {
            if (downloader.state.value.zoneId == zone.zoneId && downloader.state.value.phase != DownloadPhase.IDLE) {
                downloader.cancel()
            } else {
                regionsOf(zone.zoneId).forEach { store.delete(it.id) }
                log.info(LogCategory.APP, ZoneLog.deleted(zone.name))
            }
            reload()
        }
    }

    /** El límite de almacenamiento de los mapas sin conexión, acotado de 200 MB a 10 GB (RF-31). */
    fun setLimit(bytes: Long) {
        viewModelScope.launch {
            val saved = preferences.settings.first().copy(storageLimitBytes = bytes).sanitized()
            preferences.save(saved)
            mutableState.update { it.copy(limitBytes = saved.storageLimitBytes) }
        }
    }

    private suspend fun regionsOf(zoneId: String) = store.list().filter { it.meta?.zoneId == zoneId }

    private suspend fun reload() {
        val project = projectId ?: return
        val regions = store.list().filter { it.meta != null }
        val download = downloader.state.value
        val zones = regions.groupBy { checkNotNull(it.meta).zoneId }
            .map { (zoneId, group) ->
                group.sortedBy { it.id.value }.let { sorted ->
                    sorted.first().id.value to
                        item(zoneId, sorted, download)
                }
            }
            .filter { (_, zone) -> zone.projectId == project }
            .sortedByDescending { (firstRegion, _) -> firstRegion }
            .map { (_, zone) -> zone }
        val limit = preferences.settings.first().storageLimitBytes
        mutableState.update {
            it.copy(
                loaded = true,
                zones = zones,
                totalBytes = regions.sumOf { r ->
                    r.progress.bytes
                },
                limitBytes = limit,
                download = download,
            )
        }
    }

    /** Una fuente está completa si tiene fecha de descarga (también durante una actualización, que sigue sirviendo con lo anterior); si no, lo que dice el descargador. */
    private fun sourceState(meta: ZoneMeta, index: Int, download: DownloadState, live: Boolean): SourceState = when {
        meta.downloadedAtMs != null -> SourceState.COMPLETE
        live -> download.sources.getOrNull(index)?.state ?: SourceState.INCOMPLETE
        else -> SourceState.INCOMPLETE
    }

    private fun item(zoneId: String, group: List<StoredRegion>, download: DownloadState): ZoneItem {
        val metas = group.map { checkNotNull(it.meta) }
        val live = download.zoneId == zoneId && download.phase != DownloadPhase.IDLE
        val states = metas.mapIndexed { index, meta -> sourceState(meta, index, download, live) }
        val dates = metas.map { it.downloadedAtMs }
        return ZoneItem(
            zoneId = zoneId,
            projectId = metas.first().projectId,
            name = metas.first().name,
            sources = metas.map { it.source },
            detail = metas.first().detail,
            sizeBytes = group.sumOf { it.progress.bytes },
            downloadedAtMs = if (dates.all { it != null }) dates.filterNotNull().min() else null,
            state = ZoneState.of(states, updating = live && download.updating && download.phase == DownloadPhase.RUNNING),
            fraction = group.map { it.progress.fraction }.average().toFloat(),
        )
    }
}
