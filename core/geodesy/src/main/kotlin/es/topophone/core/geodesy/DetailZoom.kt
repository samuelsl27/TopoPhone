package es.topophone.core.geodesy

import es.topophone.core.model.offline.DetailLevel
import es.topophone.core.model.offline.DownloadableSource
import es.topophone.core.model.offline.TileKind

/**
 * Los zooms del mapa entre los que se descarga una fuente de una zona sin conexión (S037/RF-8, plan A3). MapLibre da un solo par de zooms a cada región,
 * y cada fuente de teselas del estilo lo acota después a su propio `minzoom` y `maxzoom`.
 */
object DetailZoom {
    /** Las fuentes solo ráster bajan 10 niveles por debajo del máximo, para no descargar el mundo: por debajo sirve la caché de S009 (plan, D3). */
    const val RASTER_LEVELS = 10

    /** El zoom máximo de un nivel: el de su escala nominal en la latitud del centro de la zona, o el zoom máximo del mapa en «Máximo». */
    fun maxMapZoom(level: DetailLevel, latitudeDeg: Double): Double =
        level.scaleDenominator?.let { WebMercator.zoomForScale(it, latitudeDeg) } ?: WebMercator.MAX_ZOOM

    /** El zoom mínimo de una fuente: 0 si alguna de sus teselas es vectorial (el IGN trae además un relieve ráster) y [RASTER_LEVELS] menos que el máximo si no. */
    fun minMapZoom(source: DownloadableSource, maxMapZoom: Double): Double =
        if (source.tileSources.any { it.kind == TileKind.VECTOR }) 0.0 else (maxMapZoom - RASTER_LEVELS).coerceAtLeast(0.0)
}
