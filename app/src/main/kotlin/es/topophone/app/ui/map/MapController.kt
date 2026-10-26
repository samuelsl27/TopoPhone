package es.topophone.app.ui.map

import es.topophone.app.ui.cad.DesignScene
import es.topophone.core.geodesy.GeoPoint
import es.topophone.core.model.map.MapSettings
import es.topophone.core.model.map.MapViewport
import es.topophone.core.model.project.LinearUnit
import kotlinx.coroutines.flow.Flow
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

    /** El rover y su círculo de precisión, o nada sin posición reciente (S009/RF-16 a RF-18). */
    fun showRover(marker: RoverMarker?)

    /** Puntos del proyecto con su nombre (S010/RF-20). */
    fun showPoints(points: List<MapPoint>)

    /** El dibujo importado de un DXF en la vista de ahora; nada sin dibujo (S040/RF-24). */
    fun showDesign(scene: DesignScene?)

    /** El replanteo: el punto de diseño, la punta y la línea entre ellos; nada sin replanteo (S018/RF-14). */
    fun showStakeout(marker: StakeoutMarker?)

    /** La base con su símbolo, o nada sin posición de la base (S015/RF-19). */
    fun showBase(position: GeoPoint?)

    /** Lleva el mapa a un encuadre (S009/RF-19, RF-21). */
    fun moveTo(viewport: MapViewport)

    /** España entera, con Canarias (S009/RF-21). */
    fun showSpain()

    /**
     * Píxeles que tapan los paneles por abajo. El centro del mapa, donde se sigue al rover, pasa al resto de la
     * pantalla, así el rover queda a la vista con el recolector abierto (S010/RF-1).
     */
    fun setBottomInset(px: Int)

    /** El id de cada punto que el usuario toca en el mapa (S011/RF-5). */
    val pointClicks: Flow<String>

    /** Cada toque en el mapa que no cae en un punto del proyecto, con la tolerancia de un dedo en metros (S040/RF-29). */
    val mapTaps: Flow<MapTap>

    /** Cada vez que el usuario empieza a mover el mapa con el dedo (S009/RF-20). */
    val userGestures: Flow<Unit>

    /** Centro y zoom del mapa mientras se mueve, para la barra de escala (S009/RF-10); null hasta que se ve. */
    val camera: StateFlow<MapCamera?>
}

/**
 * El rover en el mapa: posición de la GGA sin redondear, precisión horizontal de la GST (null sin ella) y
 * color de su solución (S009/RF-16, RF-17).
 */
data class RoverMarker(val position: GeoPoint, val accuracyM: Double?, val colorArgb: Int)

/**
 * El replanteo en el mapa: el punto de diseño, la punta del jalón si hay guía y, con [line], la línea entre ellos, que solo se
 * dibuja mientras queda a más de 40 cm (S018/RF-14). Con una línea del dibujo, [chosen] es esa línea, que se dibuja más gruesa
 * que las demás (S041/RF-5). Sin [design] la guía de la línea está apagada y solo se ve la línea elegida.
 */
data class StakeoutMarker(val design: GeoPoint?, val tip: GeoPoint?, val line: Boolean, val chosen: List<GeoPoint> = emptyList())

/** Un toque en el mapa: dónde, y a cuántos metros equivalen 24 dp en ese punto para acertar con el dedo (S040/RF-29). */
data class MapTap(val latitudeDeg: Double, val longitudeDeg: Double, val toleranceM: Double)

/** Centro del mapa en geográficas y zoom de MapLibre (teselas de 512 dp). */
data class MapCamera(val latitudeDeg: Double, val longitudeDeg: Double, val zoom: Double)

/** Ancho máximo de la barra de escala (S009/RF-10). */
const val SCALE_BAR_MAX_DP = 100f
