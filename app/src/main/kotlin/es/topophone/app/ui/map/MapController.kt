package es.topophone.app.ui.map

import es.topophone.core.model.map.MapSettings

/**
 * El mapa del proyecto visto desde la pantalla, sin depender de MapLibre (ADR-9). La implementación es
 * [MapLibreMapController]; los tests de la interfaz usan un doble.
 */
interface MapController {
    /** Mapa base y Catastro (S009/RF-6, RF-7). */
    fun setSettings(settings: MapSettings)
}
