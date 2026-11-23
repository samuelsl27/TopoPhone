package es.topophone.app.ui.map

import es.topophone.app.ui.alignment.AlignmentScene
import es.topophone.app.ui.cad.DesignScene
import es.topophone.core.geodesy.GeoBox
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

    /**
     * Las capas WMS del proyecto, ya ordenadas, sobre el mapa base y debajo del Catastro, las líneas, los puntos y el rover; sin capas
     * visibles, un plan vacío. Cambiar el plan no toca el mapa base ni el Catastro (S036/RF-22, RF-29, RF-33).
     */
    fun showWmsLayers(plan: WmsLayerPlan)

    /** Las líneas y los polígonos del proyecto, ya como GeoJSON; nada sin líneas (S025/RF-22). */
    fun showLines(scene: LineScene?)

    /** El id de cada línea del proyecto que el usuario toca en el mapa, si no toca un punto (S025/RF-22). */
    val lineClicks: Flow<String>

    /** El dibujo importado de un DXF en la vista de ahora; nada sin dibujo (S040/RF-24). */
    fun showDesign(scene: DesignScene?)

    /** Los ejes visibles con el activo destacado y sus marcas de PK en la vista de ahora; nada sin ejes (S049/RF-17). */
    fun showAlignments(scene: AlignmentScene?)

    /** El replanteo: el punto de diseño, la punta y la línea entre ellos; nada sin replanteo (S018/RF-14). */
    fun showStakeout(marker: StakeoutMarker?)

    /** La base con su símbolo, o nada sin posición de la base (S015/RF-19). */
    fun showBase(position: GeoPoint?)

    /** El punto central del proyecto con su símbolo y su texto, o nada sin punto central (S006/RF-36). */
    fun showCentralPoint(position: GeoPoint?, label: String)

    /** Lleva el mapa a un encuadre (S009/RF-19, RF-21). */
    fun moveTo(viewport: MapViewport)

    /** España entera, con Canarias (S009/RF-21). */
    fun showSpain()

    /** Lo que se ve ahora en el mapa, en grados, o null si el mapa aún no se ha dibujado: es el encuadre que se puede descargar (S037/RF-2). */
    fun visibleBounds(): GeoBox?

    /**
     * Píxeles que tapan los paneles por abajo. El centro del mapa, donde se sigue al rover, pasa al resto de la
     * pantalla, así el rover queda a la vista con el recolector abierto (S010/RF-1).
     */
    fun setBottomInset(px: Int)

    /**
     * El replanteo: lleva el mapa al centro, el zoom y el rumbo de [camera] con una transición corta, respetando el hueco de los paneles
     * (S060/RF-25). El rumbo es el que queda hacia arriba, en grados desde el norte.
     */
    fun follow(camera: FollowCamera)

    /** Vuelve a poner el norte arriba, al cerrar el replanteo (S060/RF-25). */
    fun resetBearing()

    /** El lado corto de la parte visible del mapa, sin el hueco de los paneles, en dp: con él se calcula el zoom del replanteo (S060/RF-25). */
    val visibleShortSideDp: Double

    /** El id de cada punto que el usuario toca en el mapa (S011/RF-5). */
    val pointClicks: Flow<String>

    /** Cada vez que el usuario mantiene pulsado un punto del mapa, con el píxel y la zona visible (S036/RF-34). */
    val longPresses: Flow<MapPress>

    /** Marca el punto de una pulsación larga mientras el menú del mapa está abierto; null quita la marca (S036/RF-34, RF-35). */
    fun showPressMarker(position: GeoPoint?)

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
