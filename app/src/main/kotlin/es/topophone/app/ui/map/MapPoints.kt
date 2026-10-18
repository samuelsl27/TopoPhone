package es.topophone.app.ui.map

import es.topophone.core.geodesy.GeoPoint
import es.topophone.core.geodesy.WebMercator
import es.topophone.core.model.point.CollectedPoint

/** Un punto del proyecto en el mapa: el punto del suelo, su nombre y si está fuera de tolerancia (S010/RF-20). */
data class MapPoint(val id: String, val name: String, val position: GeoPoint, val outOfTolerance: Boolean)

/** Puntos del proyecto para el mapa (S010/RF-20, RF-21). */
object MapPoints {
    /** Escala desde la que se ve el nombre de los puntos, para que no se amontonen. */
    private const val LABEL_SCALE = 2_000.0

    fun from(points: List<CollectedPoint>): List<MapPoint> = points.map { point ->
        MapPoint(point.id, point.name, GeoPoint(point.ground.latitudeDeg, point.ground.longitudeDeg), point.outOfTolerance.isNotEmpty())
    }

    /** Zoom de 1:2.000 en la latitud media de los puntos; null sin puntos. */
    fun labelMinZoom(points: List<MapPoint>): Double? =
        points.takeIf { it.isNotEmpty() }?.let { WebMercator.zoomForScale(LABEL_SCALE, it.map { p -> p.position.latitudeDeg }.average()) }
}
