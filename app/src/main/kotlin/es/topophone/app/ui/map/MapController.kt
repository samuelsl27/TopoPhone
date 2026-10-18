package es.topophone.app.ui.map

import es.topophone.core.model.map.MapSettings
import es.topophone.core.model.project.LinearUnit

/**
 * El mapa del proyecto visto desde la pantalla, sin depender de MapLibre (ADR-9). La implementación es
 * [MapLibreMapController]; los tests de la interfaz usan un doble.
 */
interface MapController {
    /** Mapa base y Catastro (S009/RF-6, RF-7). */
    fun setSettings(settings: MapSettings)

    /** Unidad de la barra de escala, que marca también la separación de la cuadrícula (S009/RF-10, RF-14). */
    fun setLinearUnit(unit: LinearUnit)
}

/** Ancho máximo de la barra de escala (S009/RF-10). */
const val SCALE_BAR_MAX_DP = 100f
