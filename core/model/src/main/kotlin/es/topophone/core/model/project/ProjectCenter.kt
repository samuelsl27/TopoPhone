package es.topophone.core.model.project

import es.topophone.core.model.map.MapViewport

/** El punto central de un proyecto: latitud y longitud del suelo sin redondear (S006/RF-32). */
data class CentralPoint(val latitudeDeg: Double, val longitudeDeg: Double)

/** El centro del proyecto, calculado en un único sitio (S006/RF-37). */
object ProjectCenter {
    /**
     * El punto central si lo hay; si no, la media de los [points]; si no, el centro del [viewport]; y si no hay
     * ninguno de los tres, `null`.
     */
    fun of(central: CentralPoint?, points: List<CentralPoint>, viewport: MapViewport?): CentralPoint? = when {
        central != null -> central
        points.isNotEmpty() -> CentralPoint(points.map { it.latitudeDeg }.average(), points.map { it.longitudeDeg }.average())
        viewport != null -> CentralPoint(viewport.latitudeDeg, viewport.longitudeDeg)
        else -> null
    }
}
