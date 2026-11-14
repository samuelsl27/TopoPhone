package es.topophone.app.ui.offline

import es.topophone.core.geodesy.DetailZoom
import es.topophone.core.geodesy.GeoBox
import es.topophone.core.geodesy.TileCover
import es.topophone.core.model.offline.DownloadableSource
import es.topophone.core.model.offline.OfflineLimits
import es.topophone.core.model.offline.SourceKind

/** Qué red hay para descargar (S037/RF-13, RF-14): ninguna con internet, una sin tarifa de datos o datos móviles. Nunca la Wi-Fi del receptor (RNF-T4). */
enum class NetworkKind { NONE, UNMETERED, MOBILE }

/** El almacenamiento: el espacio libre del móvil, lo que ya ocupan los mapas sin conexión y el límite de S037/RF-31. */
data class StorageState(val freeBytes: Long, val usedBytes: Long, val limitBytes: Long)

/** Los tamaños medios de una tesela por tipo de fuente, medidos en T1 en el centro de Madrid y redondeados por arriba (plan, A4). */
object TileSizes {
    private const val KB = 1024L
    private const val MB = 1_000_000L

    const val PNOA_BYTES = 25 * KB
    const val WMS_JPEG_BYTES = 25 * KB
    const val WMS_PNG_BYTES = 60 * KB
    const val IGN_BYTES = 45 * KB
    const val OPENFREEMAP_BYTES = 150 * KB

    /** Lo que baja una vez cada estilo vectorial: sus símbolos y glifos. */
    const val IGN_STYLE_BYTES = 65 * MB / 10
    const val OPENFREEMAP_STYLE_BYTES = 7 * MB
}

/** Lo estimado de una fuente: el número exacto de teselas (RF-11) y su tamaño estimado. */
data class SourceEstimate(val source: DownloadableSource, val tiles: Long, val estimatedBytes: Long)

/** Por qué una descarga no puede empezar (RF-9, RF-12, RF-13). */
sealed interface EstimateBlock {
    data class OpenFreeMapLimit(val tiles: Long, val bytes: Long) : EstimateBlock

    data class NoSpace(val missingBytes: Long) : EstimateBlock

    data class OverLimit(val missingBytes: Long) : EstimateBlock

    data object NoInternet : EstimateBlock
}

/** Qué hay que confirmar antes de empezar (RF-10, RF-14). */
sealed interface EstimateConfirmation {
    data object MobileData : EstimateConfirmation

    data class OwnWms(val serviceName: String) : EstimateConfirmation
}

data class Estimate(val sources: List<SourceEstimate>, val blocks: List<EstimateBlock>, val confirmations: List<EstimateConfirmation>) {
    val totalTiles: Long get() = sources.sumOf { it.tiles }
    val totalBytes: Long get() = sources.sumOf { it.estimatedBytes }
    val canStart: Boolean get() = blocks.isEmpty()
}

/** La previsión de una descarga y sus comprobaciones previas (S037/RF-9 a RF-14, plan A4). */
object DownloadEstimate {
    /**
     * @param box la caja que se descarga (de `AreaBounds`) y [maxMapZoom] el zoom máximo del nivel de detalle (de `DetailZoom`).
     * @param ownWmsNames el nombre de los servicios WMS añadidos por el usuario (los que no son un preset), por identificador (RF-10).
     * @param wmsFormats el formato de imagen de cada capa WMS por clave de fuente; sin formato se estima en PNG, que pesa más.
     */
    @Suppress("LongParameterList")
    fun of(
        box: GeoBox,
        maxMapZoom: Double,
        sources: List<DownloadableSource>,
        ownWmsNames: Map<String, String>,
        wmsFormats: Map<String, String>,
        storage: StorageState,
        network: NetworkKind,
    ): Estimate {
        val estimates = sources.map { source ->
            val min = DetailZoom.minMapZoom(source, maxMapZoom)
            val tiles = TileCover.count(box, min, maxMapZoom, source.tileSources)
            SourceEstimate(source, tiles, bytesOf(source, tiles, wmsFormats[source.key]))
        }
        val total = estimates.sumOf { it.estimatedBytes }
        val blocks = buildList {
            if (network == NetworkKind.NONE) add(EstimateBlock.NoInternet)
            estimates.firstOrNull { it.source.kind == SourceKind.OPENFREEMAP }?.let {
                if (OfflineLimits.exceedsOpenFreeMapLimit(
                        it.tiles,
                        it.estimatedBytes,
                    )
                ) {
                    add(EstimateBlock.OpenFreeMapLimit(it.tiles, it.estimatedBytes))
                }
            }
            val needed = total + OfflineLimits.FREE_SPACE_RESERVE_BYTES
            if (storage.freeBytes < needed) add(EstimateBlock.NoSpace(needed - storage.freeBytes))
            if (storage.usedBytes + total > storage.limitBytes) add(EstimateBlock.OverLimit(storage.usedBytes + total - storage.limitBytes))
        }
        val confirmations = buildList {
            if (network == NetworkKind.MOBILE && OfflineLimits.needsMobileConfirmation(total)) add(EstimateConfirmation.MobileData)
            sources.filter { it.kind == SourceKind.WMS }
                .mapNotNull { ownWmsNames[it.serviceId] }
                .distinct()
                .forEach { add(EstimateConfirmation.OwnWms(it)) }
        }
        return Estimate(estimates, blocks, confirmations)
    }

    private fun bytesOf(source: DownloadableSource, tiles: Long, wmsFormat: String?): Long = when (source.kind) {
        SourceKind.PNOA -> tiles * TileSizes.PNOA_BYTES
        SourceKind.IGN_VECTOR -> tiles * TileSizes.IGN_BYTES + TileSizes.IGN_STYLE_BYTES
        SourceKind.OPENFREEMAP -> tiles * TileSizes.OPENFREEMAP_BYTES + TileSizes.OPENFREEMAP_STYLE_BYTES
        SourceKind.WMS -> tiles * if (wmsFormat == JPEG) TileSizes.WMS_JPEG_BYTES else TileSizes.WMS_PNG_BYTES
    }

    private const val JPEG = "image/jpeg"
}
