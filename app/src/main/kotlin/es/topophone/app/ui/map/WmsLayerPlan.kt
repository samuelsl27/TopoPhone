package es.topophone.app.ui.map

import es.topophone.core.formats.wms.WmsPresets
import es.topophone.core.formats.wms.WmsRequests
import es.topophone.core.model.wms.WmsAddress
import es.topophone.core.model.wms.WmsAddressResult
import es.topophone.core.model.wms.WmsRequestMode
import es.topophone.core.model.wms.WmsService
import es.topophone.data.wms.ProjectLayerView

/** Cómo llega al mapa una capa WMS del proyecto (S036/RF-17, RF-29). */
sealed interface WmsLayerSource {
    /** Por teselas: MapLibre rellena [template] con el cuadro envolvente en EPSG:3857 de cada tesela de 256 px (A3 del plan). */
    data class Tiles(val template: String) : WmsLayerSource

    /**
     * En «una imagen»: una sola petición de la zona visible cuando el mapa se detiene (A4 del plan); lleva lo que hace falta para
     * armarla con [WmsRequests.image].
     */
    data class Image(val address: WmsAddress, val version: String, val layerName: String, val code: String, val format: String) :
        WmsLayerSource
}

/** Una capa que se dibuja: [id] es el de la capa del proyecto, que da nombre a su fuente y a su capa del mapa. [opacity] va de 0 a 1. */
data class WmsPlanLayer(val id: String, val opacity: Float, val source: WmsLayerSource, val attribution: String)

/**
 * Lo que el mapa dibuja de las capas WMS del proyecto (S036/RF-17, RF-20 a RF-22, RF-29, RF-30): solo las visibles y disponibles, **de la
 * última a la primera** de la lista, de modo que el mapa las pone una a una justo debajo de la capa ancla y la primera acaba encima de las
 * demás (A5 del plan). Es puro y comparable: el mapa solo rehace las capas WMS si el plan cambia (RF-33).
 */
data class WmsLayerPlan(val layers: List<WmsPlanLayer>) {
    /** Hay alguna capa en «una imagen», que se pide aparte al detenerse el mapa. */
    val hasImages: Boolean get() = layers.any { it.source is WmsLayerSource.Image }

    companion object {
        val EMPTY = WmsLayerPlan(emptyList())

        /** El plan de las capas del proyecto en [views], que da el repositorio de arriba abajo. */
        fun of(views: List<ProjectLayerView>): WmsLayerPlan {
            val layers = views.sortedBy { it.layer.position }.filter { it.layer.visible }.mapNotNull(::layer).asReversed()
            return if (layers.isEmpty()) EMPTY else WmsLayerPlan(layers)
        }

        private fun layer(view: ProjectLayerView): WmsPlanLayer? {
            val code = view.info?.mercatorCode ?: return null // No disponible (RF-27) o sin Web Mercator (RF-12): no se pide.
            val service = view.service
            val address = (WmsAddress.parse(service.address) as? WmsAddressResult.Valid)?.address ?: return null
            val format = format(service)
            val name = view.layer.layerName
            val source = when (service.requestMode) {
                WmsRequestMode.TILES -> WmsLayerSource.Tiles(WmsRequests.tileTemplate(address, service.version, name, code, format))
                WmsRequestMode.IMAGE -> WmsLayerSource.Image(address, service.version, name, code, format)
            }
            return WmsPlanLayer(view.layer.id, view.layer.opacity / PERCENT, source, attribution(service))
        }

        /**
         * El formato que el preset prefiere (`image/png` en un servicio propio) y, si el servicio no lo ofrece, el primero que anuncie: la
         * ortofoto de GRAFCAN solo da JPEG y su mapa solo PNG (A3 del plan). Sin lista de formatos se pide el preferido.
         */
        private fun format(service: WmsService): String {
            val wanted = service.presetKey?.let(WmsPresets::byKey)?.preferredFormat ?: DEFAULT_FORMAT
            val offered = service.mapFormats
            return offered.firstOrNull { it.equals(wanted, ignoreCase = true) } ?: offered.firstOrNull() ?: wanted
        }

        /** La del preset o la que declara el servicio y, si no hay, su título y, si tampoco, el nombre que le dio el usuario (RF-30). */
        private fun attribution(service: WmsService): String =
            service.presetKey?.let(WmsPresets::byKey)?.attribution?.takeIf { it.isNotBlank() }
                ?: service.attribution.takeIf { it.isNotBlank() }
                ?: service.title.takeIf { it.isNotBlank() }
                ?: service.name

        private const val PERCENT = 100f
        private const val DEFAULT_FORMAT = "image/png"
    }
}
