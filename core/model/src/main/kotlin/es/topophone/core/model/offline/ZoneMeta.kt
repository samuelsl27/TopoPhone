package es.topophone.core.model.offline

/**
 * Lo que cada región sin conexión de MapLibre guarda en sus metadatos (S037, plan «Modelo de datos»): una zona son tantas regiones como fuentes, todas con el
 * mismo [zoneId]. El JSON lo escribe y lee `core:formats` (`ZoneMetaJson`). La caja, el zoom y el estilo los guarda la propia región.
 *
 * @property tiles el número exacto de teselas de la fuente (RF-11) y [estimatedBytes] su tamaño estimado.
 * @property downloadedAtMs null hasta que la región se completa; se conserva si una actualización se interrumpe (RF-26).
 * @property failed las teselas que no llegaron tras los reintentos (RF-19).
 */
data class ZoneMeta(
    val zoneId: String,
    val projectId: String,
    val name: String,
    val source: DownloadableSource,
    val area: OfflineArea,
    val detail: DetailLevel,
    val tiles: Long,
    val estimatedBytes: Long,
    val downloadedAtMs: Long?,
    val failed: Long,
) {
    companion object {
        /** La versión del JSON de los metadatos. */
        const val VERSION = 1
    }
}
