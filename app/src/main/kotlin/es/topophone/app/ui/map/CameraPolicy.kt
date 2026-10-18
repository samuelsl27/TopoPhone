package es.topophone.app.ui.map

import es.topophone.core.geodesy.GeoPoint
import es.topophone.core.geodesy.WebMercator
import es.topophone.core.model.map.MapViewport

/** Lo que tiene que hacer la cámara del mapa (S009/RF-19 a RF-21). */
sealed interface CameraCommand {
    data class MoveTo(val viewport: MapViewport) : CameraCommand

    /** España entera, con Canarias (S009/RF-21). */
    data object ShowSpain : CameraCommand
}

/**
 * Seguimiento del rover, sin Android para poder probarlo (S009/RF-19 a RF-21):
 * - al abrir sin posición, el último encuadre del proyecto o España entera;
 * - con la primera posición, o al pulsar "Centrar en el rover", sigue al rover y se acerca a 1:2.000 si
 *   estaba más lejos;
 * - mientras lo sigue, cada posición mueve el centro sin cambiar el zoom;
 * - un gesto del usuario lo deja libre.
 */
class CameraPolicy {
    var following = false
        private set

    /** Ya se ha colocado el mapa (encuadre de apertura o rover): desde aquí su encuadre se guarda. */
    var placed = false
        private set

    private var centeredOnRover = false

    fun onOpen(saved: MapViewport?): CameraCommand {
        placed = true
        return saved?.let { CameraCommand.MoveTo(it) } ?: CameraCommand.ShowSpain
    }

    /** Una posición nueva del rover; [camera] es la cámara actual. Null si el mapa no se mueve. */
    fun onPosition(position: GeoPoint, camera: MapCamera?): CameraCommand? {
        if (!centeredOnRover) return onCenterPressed(position, camera)
        if (!following) return null
        return CameraCommand.MoveTo(MapViewport(position.latitudeDeg, position.longitudeDeg, camera?.zoom ?: followZoom(position)))
    }

    fun onCenterPressed(position: GeoPoint, camera: MapCamera?): CameraCommand {
        following = true
        centeredOnRover = true
        placed = true
        val zoom = maxOf(camera?.zoom ?: 0.0, followZoom(position))
        return CameraCommand.MoveTo(MapViewport(position.latitudeDeg, position.longitudeDeg, zoom))
    }

    fun onUserGesture() {
        following = false
    }

    /** Ya hay rover centrado: la apertura no debe llevar el mapa a otro sitio. */
    val hasRover: Boolean get() = centeredOnRover

    private fun followZoom(position: GeoPoint) = WebMercator.zoomForScale(FOLLOW_SCALE, position.latitudeDeg)

    companion object {
        /** Escala a la que se acerca el mapa al centrar en el rover (S009/RF-19). */
        const val FOLLOW_SCALE = 2_000.0

        /** España entera: Península, Baleares, Canarias, Ceuta y Melilla (S009/RF-21). */
        const val SPAIN_SOUTH = 27.6
        const val SPAIN_WEST = -18.2
        const val SPAIN_NORTH = 43.8
        const val SPAIN_EAST = 4.4
    }
}
