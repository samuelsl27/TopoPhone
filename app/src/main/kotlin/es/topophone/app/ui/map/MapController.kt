package es.topophone.app.ui.map

import es.topophone.core.model.map.MapSettings
import es.topophone.core.model.project.LinearUnit
import kotlinx.coroutines.flow.StateFlow

/**
 * El mapa del proyecto visto desde la pantalla, sin depender de MapLibre (ADR-9). La implementación es
 * [MapLibreMapController]; los tests de la interfaz usan un doble.
 */
interface MapController {
    /** Mapa base y Catastro (S009/RF-6, RF-7). */
    fun setSettings(settings: MapSettings)

    /** Unidad de la barra de escala, que marca también la separación de la cuadrícula (S009/RF-10, RF-14). */
    fun setLinearUnit(unit: LinearUnit)

    /** Centro y zoom del mapa mientras se mueve, para la barra de escala (S009/RF-10); null hasta que se ve. */
    val camera: StateFlow<MapCamera?>
}

/** Centro del mapa en geográficas y zoom de MapLibre (teselas de 512 dp). */
data class MapCamera(val latitudeDeg: Double, val longitudeDeg: Double, val zoom: Double)

/** Ancho máximo de la barra de escala (S009/RF-10). */
const val SCALE_BAR_MAX_DP = 100f
