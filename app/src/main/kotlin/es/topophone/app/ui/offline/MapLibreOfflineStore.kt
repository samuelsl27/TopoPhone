package es.topophone.app.ui.offline

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import es.topophone.app.ui.map.MapEnvironment
import es.topophone.core.formats.offline.ZoneMetaJson
import es.topophone.core.geodesy.GeoBox
import es.topophone.core.model.offline.ZoneMeta
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.offline.OfflineManager
import org.maplibre.android.offline.OfflineRegion
import org.maplibre.android.offline.OfflineRegionError
import org.maplibre.android.offline.OfflineRegionStatus
import org.maplibre.android.offline.OfflineTilePyramidRegionDefinition

/**
 * Las regiones sin conexión de MapLibre (S037, plan «Estructura de módulos»): una zona son tantas regiones como fuentes, con los metadatos de la zona en
 * cada una. Todo se hace en el hilo principal, que es el que usa MapLibre, y sus devoluciones se esperan con corrutinas. Se probó en T1 con un arnés
 * temporal; la comprobación en el emulador está en la demo de T22.
 *
 * MapLibre solo entrega los cambios de una región mientras está activa, salvo que se pida lo contrario: aquí siempre se piden, para ver también cuándo
 * una región vuelve a inactiva al terminar.
 */
@Singleton
class MapLibreOfflineStore @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val environment: MapEnvironment,
) : OfflineRegionStore {

    /**
     * La región de MapLibre, su progreso y los errores que ha dado desde que se activó. Solo se toca desde el hilo principal. El progreso empieza vacío y solo
     * pasa a null cuando la región se borra: con null al principio, `observe` terminaba al instante y el descargador no veía avanzar nada (visto en el emulador, T20).
     */
    private class Handle(val region: OfflineRegion) {
        val progress = MutableStateFlow<RegionProgress?>(RegionProgress.EMPTY)
        var failures = 0L
    }

    private val handles = HashMap<Long, Handle>()

    private val manager: OfflineManager
        get() {
            environment.ensureInitialized()
            return OfflineManager.getInstance(context)
        }

    /** MapLibre pide `@2x` en los símbolos con una relación de píxeles mayor que 1: la región debe pedir lo mismo que el mapa vivo. */
    private val pixelRatio: Float get() = context.resources.displayMetrics.density

    override suspend fun create(definition: RegionDefinition, meta: ZoneMeta): RegionId = onMain {
        val box = definition.box
        val tilePyramid = OfflineTilePyramidRegionDefinition(
            definition.styleUrl,
            LatLngBounds.from(box.north, box.east, box.south, box.west),
            definition.minMapZoom,
            definition.maxMapZoom,
            pixelRatio,
        )
        val region = suspendCancellableCoroutine<OfflineRegion> { cont ->
            manager.createOfflineRegion(
                tilePyramid,
                ZoneMetaJson.encode(meta),
                object : OfflineManager.CreateOfflineRegionCallback {
                    override fun onCreate(offlineRegion: OfflineRegion) = cont.resume(offlineRegion)

                    override fun onError(error: String) = cont.resumeWithException(IOException(error))
                },
            )
        }
        attach(region)
        RegionId(region.id)
    }

    override suspend fun list(): List<StoredRegion> = onMain {
        val regions = suspendCancellableCoroutine<Array<OfflineRegion>> { cont ->
            manager.listOfflineRegions(
                object : OfflineManager.ListOfflineRegionsCallback {
                    override fun onList(offlineRegions: Array<OfflineRegion>?) = cont.resume(offlineRegions ?: emptyArray())

                    override fun onError(error: String) = cont.resumeWithException(IOException(error))
                },
            )
        }
        regions.mapNotNull { region ->
            val box = boxOf(region) ?: return@mapNotNull null
            val styleUrl = region.definition.styleURL ?: return@mapNotNull null
            val handle = attach(region)
            val status = statusOf(region)
            if (status != null) handle.progress.value = progressOf(status, handle.failures)
            StoredRegion(
                RegionId(region.id),
                RegionDefinition(styleUrl, box, region.definition.minZoom, region.definition.maxZoom),
                ZoneMetaJson.decode(region.metadata),
                handle.progress.value ?: RegionProgress.EMPTY,
            )
        }
    }

    override fun observe(id: RegionId): Flow<RegionProgress> = flow {
        val handle = onMain { handleOf(id) } ?: return@flow
        emitAll(handle.progress.takeWhile { it != null }.map { checkNotNull(it) })
    }

    override suspend fun setActive(id: RegionId, active: Boolean) {
        onMain {
            val handle = handleOf(id)
            if (handle != null) {
                if (active) {
                    handle.failures = 0
                    handle.progress.value = handle.progress.value?.copy(failures = 0)
                }
                handle.region.setDownloadState(if (active) OfflineRegion.STATE_ACTIVE else OfflineRegion.STATE_INACTIVE)
            }
        }
    }

    override suspend fun invalidate(id: RegionId) {
        onMain {
            val handle = handleOf(id)
            if (handle != null) {
                suspendCancellableCoroutine<Unit> { cont ->
                    handle.region.invalidate(
                        object : OfflineRegion.OfflineRegionInvalidateCallback {
                            override fun onInvalidate() = cont.resume(Unit)

                            override fun onError(error: String) = cont.resumeWithException(IOException(error))
                        },
                    )
                }
                val status = statusOf(handle.region)
                if (status != null) handle.progress.value = progressOf(status, handle.failures)
            }
        }
    }

    override suspend fun delete(id: RegionId) {
        onMain {
            val handle = handleOf(id)
            if (handle != null) {
                suspendCancellableCoroutine<Unit> { cont ->
                    handle.region.delete(
                        object : OfflineRegion.OfflineRegionDeleteCallback {
                            override fun onDelete() = cont.resume(Unit)

                            override fun onError(error: String) = cont.resumeWithException(IOException(error))
                        },
                    )
                }
                handles.remove(id.value)
                handle.progress.value = null
            }
        }
    }

    override suspend fun updateMeta(id: RegionId, meta: ZoneMeta) {
        onMain {
            val handle = handleOf(id)
            if (handle != null) {
                suspendCancellableCoroutine<Unit> { cont ->
                    handle.region.updateMetadata(
                        ZoneMetaJson.encode(meta),
                        object : OfflineRegion.OfflineRegionUpdateMetadataCallback {
                            override fun onUpdate(metadata: ByteArray) = cont.resume(Unit)

                            override fun onError(error: String) = cont.resumeWithException(IOException(error))
                        },
                    )
                }
            }
        }
    }

    private suspend fun <T> onMain(block: suspend () -> T): T = withContext(Dispatchers.Main.immediate) { block() }

    /** El manejador de una región ya conocida o, si es la primera vez en esta ejecución, el que da MapLibre; null si no existe. */
    private suspend fun handleOf(id: RegionId): Handle? {
        handles[id.value]?.let { return it }
        val region = suspendCancellableCoroutine<OfflineRegion?> { cont ->
            manager.getOfflineRegion(
                id.value,
                object : OfflineManager.GetOfflineRegionCallback {
                    override fun onRegion(offlineRegion: OfflineRegion) = cont.resume(offlineRegion)

                    override fun onRegionNotFound() = cont.resume(null)

                    override fun onError(error: String) = cont.resumeWithException(IOException(error))
                },
            )
        } ?: return null
        return attach(region)
    }

    private fun attach(region: OfflineRegion): Handle = handles.getOrPut(region.id) {
        Handle(region).also { handle ->
            region.setDeliverInactiveMessages(true)
            region.setObserver(
                object : OfflineRegion.OfflineRegionObserver {
                    override fun onStatusChanged(status: OfflineRegionStatus) {
                        handle.progress.value = progressOf(status, handle.failures)
                    }

                    override fun onError(error: OfflineRegionError) {
                        handle.failures++
                        handle.progress.value = handle.progress.value?.copy(failures = handle.failures)
                    }

                    override fun mapboxTileCountLimitExceeded(limit: Long) = Unit
                },
            )
        }
    }

    private suspend fun statusOf(region: OfflineRegion): OfflineRegionStatus? = suspendCancellableCoroutine { cont ->
        region.getStatus(
            object : OfflineRegion.OfflineRegionStatusCallback {
                override fun onStatus(status: OfflineRegionStatus?) = cont.resume(status)

                override fun onError(error: String?) = cont.resume(null)
            },
        )
    }

    private fun progressOf(status: OfflineRegionStatus, failures: Long) = RegionProgress(
        state = if (status.downloadState == OfflineRegion.STATE_ACTIVE) RegionDownloadState.ACTIVE else RegionDownloadState.INACTIVE,
        completedResources = status.completedResourceCount,
        requiredResources = status.requiredResourceCount,
        precise = status.isRequiredResourceCountPrecise,
        completedTiles = status.completedTileCount,
        bytes = status.completedResourceSize,
        failures = failures,
        complete = status.isComplete,
    )

    /** La caja de una región; null si no cabe en una [GeoBox] (por ejemplo, de otro uso de MapLibre que cruza el antimeridiano). */
    private fun boxOf(region: OfflineRegion): GeoBox? {
        val bounds = region.definition.bounds ?: return null
        return runCatching {
            GeoBox(bounds.southWest.latitude, bounds.southWest.longitude, bounds.northEast.latitude, bounds.northEast.longitude)
        }.getOrNull()
    }
}
