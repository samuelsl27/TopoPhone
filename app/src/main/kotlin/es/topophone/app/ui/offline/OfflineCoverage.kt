package es.topophone.app.ui.offline

import es.topophone.app.ui.map.MapCamera
import es.topophone.core.geodesy.GeoBox
import es.topophone.core.model.map.Basemap
import es.topophone.core.model.offline.SourceKind
import es.topophone.core.model.wms.WmsRequestMode
import es.topophone.data.wms.ProjectLayerView
import kotlin.math.log2

/**
 * Lo que el mapa sabe de una región sin conexión para decidir si la cubre (S037/RF-20, RF-21): su caja, la fuente, el último zoom del mapa del que guarda detalle
 * y si está completa. Una fuente completa es la que tiene fecha de descarga; una a medias se guarda pero no cubre.
 */
data class ZoneCoverage(val box: GeoBox, val sourceKey: String, val storedMaxMapZoom: Double, val complete: Boolean) {
    fun contains(latitudeDeg: Double, longitudeDeg: Double): Boolean =
        latitudeDeg in box.south..box.north && longitudeDeg in box.west..box.east

    companion object {
        /** La cobertura de una región, o null si no es de TopoPhone (no tiene metadatos que se entiendan). */
        fun of(region: StoredRegion): ZoneCoverage? {
            val meta = region.meta ?: return null
            // Una ráster de 256 px pide un nivel de tesela más que el zoom del mapa, así que sirve un zoom menos que su `maxzoom`.
            val spec = meta.source.tileSources.first()
            val servedZoom = spec.maxZoom - log2(TILE_BASE / spec.tileSize)
            return ZoneCoverage(
                box = region.definition.box,
                sourceKey = meta.source.key,
                storedMaxMapZoom = minOf(region.definition.maxMapZoom, servedZoom),
                complete = meta.downloadedAtMs != null,
            )
        }

        private const val TILE_BASE = 512.0
    }
}

/** Si el centro del mapa está cubierto y si se acerca más de lo guardado (S037/RF-20, RF-21). */
data class CoverageStatus(val covered: Boolean, val beyondDetail: Boolean)

/** Las comprobaciones de cobertura: puras, para probarlas sin MapLibre. */
object OfflineCoverage {
    /**
     * El mapa está cubierto si **todas** las fuentes que dibuja ([drawn]) tienen una zona completa cuya caja contiene el centro de la cámara; sin ninguna fuente
     * descargable dibujada no hay nada cubierto. Se acerca más de lo guardado si, cubierto, el zoom pasa del último nivel de la fuente que guarda menos detalle
     * (de varias zonas de una fuente vale la que guarda más).
     */
    fun status(zones: List<ZoneCoverage>, drawn: Set<String>, camera: MapCamera): CoverageStatus {
        if (drawn.isEmpty()) return CoverageStatus(covered = false, beyondDetail = false)
        val perSource = drawn.map { key ->
            zones.filter { it.complete && it.sourceKey == key && it.contains(camera.latitudeDeg, camera.longitudeDeg) }
        }
        if (perSource.any { it.isEmpty() }) return CoverageStatus(covered = false, beyondDetail = false)
        val storedZoom = perSource.minOf { covering -> covering.maxOf { it.storedMaxMapZoom } }
        return CoverageStatus(covered = true, beyondDetail = camera.zoom > storedZoom)
    }

    /** Las fuentes descargables que el mapa dibuja: el mapa base elegido y las capas WMS por teselas visibles y disponibles (el Catastro y las de una imagen, no). */
    fun drawnKeys(basemap: Basemap, layers: List<ProjectLayerView>): Set<String> {
        val base = when (basemap) {
            Basemap.IGN_VECTOR -> SourceKind.IGN_VECTOR.key
            Basemap.PNOA_ORTHOPHOTO -> SourceKind.PNOA.key
            Basemap.OSM_VECTOR -> SourceKind.OPENFREEMAP.key
            Basemap.NONE -> null
        }
        val wms = layers.filter { it.layer.visible && it.service.requestMode == WmsRequestMode.TILES && it.info?.mercatorCode != null }
            .map { "wms:${it.layer.serviceId}:${it.layer.layerName}" }
        return (listOfNotNull(base) + wms).toSet()
    }
}
