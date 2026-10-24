package es.topophone.app.ui.map

import es.topophone.core.geodesy.GeoPoint
import es.topophone.core.geodesy.WebMercator
import es.topophone.core.model.point.PointOrigin
import es.topophone.core.model.point.PointSummary

/** Un punto del proyecto en el mapa: el punto del suelo, su nombre y si está fuera de tolerancia (S010/RF-20). */
data class MapPoint(
    val id: String,
    val name: String,
    val position: GeoPoint,
    val outOfTolerance: Boolean,
    /** Un punto replanteado, con su propio símbolo (S018/RF-34). */
    val staked: Boolean = false,
)

/** Puntos del proyecto para el mapa (S010/RF-20, RF-21). */
object MapPoints {
    /**
     * Escala desde la que se ve el nombre de los puntos, para que no se amontonen. Algo más lejos que la de seguir
     * al rover (1:2.000), para que siguiéndolo los nombres no aparezcan y desaparezcan según la latitud.
     */
    private const val LABEL_SCALE = 2_500.0

    /** Los puntos que se ven en el mapa: los ocultos no van (S011/RF-4). */
    fun from(points: List<PointSummary>): List<MapPoint> = points.filterNot { it.hidden }.map { point ->
        MapPoint(
            id = point.id,
            name = point.name,
            position = GeoPoint(point.latitudeDeg, point.longitudeDeg),
            outOfTolerance = point.outOfTolerance,
            staked = point.origin == PointOrigin.STAKED, // S018/RF-34
        )
    }

    /** Zoom de 1:2.500 en la latitud media de los puntos; null sin puntos. */
    fun labelMinZoom(points: List<MapPoint>): Double? =
        points.takeIf { it.isNotEmpty() }?.let { WebMercator.zoomForScale(LABEL_SCALE, it.map { p -> p.position.latitudeDeg }.average()) }
}
