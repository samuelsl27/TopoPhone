package es.topophone.app.ui.map

import es.topophone.core.geodesy.WebMercator
import es.topophone.core.model.map.Basemap
import es.topophone.core.model.map.MapSettings

/** Opciones fijas del mapa del proyecto. */
object MapOptions {
    /** S009/RF-11: norte arriba; los gestos no giran ni inclinan el mapa. */
    const val ROTATE_GESTURES = false
    const val TILT_GESTURES = false

    /** S009/RF-12: hasta que la barra de escala baja a 1 m o menos. */
    const val MAX_ZOOM = WebMercator.MAX_ZOOM

    /** S009/RF-13: caché del mapa base ya visto; MapLibre descarta lo usado hace más tiempo. */
    const val AMBIENT_CACHE_BYTES = 200L * 1024 * 1024

    /** S009/RF-9: la atribución la pone la app, con el texto que pide cada servicio, y siempre a la vista. */
    const val MAPLIBRE_ATTRIBUTION = false
}

/** Fuente de datos que hay que atribuir (RNF-T14). */
enum class AttributionSource { IGN, OPENFREEMAP, CADASTRE }

/** Fuentes a la vista con los ajustes del mapa (S009/RF-9). */
object MapAttribution {
    fun sources(settings: MapSettings): List<AttributionSource> {
        val basemap = when (settings.basemap) {
            Basemap.IGN_VECTOR, Basemap.PNOA_ORTHOPHOTO -> listOf(AttributionSource.IGN)
            Basemap.OSM_VECTOR -> listOf(AttributionSource.OPENFREEMAP)
            Basemap.NONE -> emptyList()
        }
        return if (settings.cadastre) basemap + AttributionSource.CADASTRE else basemap
    }
}
