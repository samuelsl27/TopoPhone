package es.topophone.app.ui.map

import es.topophone.core.geodesy.GeoPoint
import es.topophone.core.geodesy.WebMercator
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max

/** Lo que tiene que hacer la cámara en un replanteo: centro en el jalón, zoom y rumbo del mapa en grados (S060/RF-25). */
data class FollowCamera(val latitudeDeg: Double, val longitudeDeg: Double, val zoom: Double, val bearingDeg: Double)

/**
 * La cámara que sigue al jalón en el replanteo, sin Android para poder probarla (S060/RF-25, RF-26):
 * - centra el mapa en el jalón;
 * - acerca el zoom para que el destino y el jalón quepan con un margen del 25 %, sin pasar de 3 m de lado visible, y solo lo cambia si
 *   la diferencia es de más de 0,3 niveles, para que el mapa no «respire»;
 * - gira el mapa hacia la dirección de marcha, solo si cambia más de 5°; sin rumbo, el norte arriba;
 * - da como mucho 5 órdenes por segundo (S060/RNF-T13);
 * - un gesto del usuario la suelta ([onUserGesture]) y [recenter] la recupera.
 */
class StakeoutCameraPolicy(private val minIntervalMs: Long = MIN_INTERVAL_MS) {
    var following = true
        private set

    private var lastZoom: Double? = null
    private var lastBearing: Double? = null
    private var lastOrderMs: Long? = null

    /** El usuario ha movido o acercado el mapa a mano: deja de seguir al jalón (S060/RF-26). */
    fun onUserGesture() {
        following = false
    }

    /** «Recentrar»: vuelve a seguir al jalón y la próxima posición mueve la cámara sin esperar al ritmo (S060/RF-26). */
    fun recenter() {
        following = true
        lastOrderMs = null
        lastZoom = null
    }

    /** Al abrir o cerrar un replanteo se empieza de cero. */
    fun reset() {
        following = true
        lastZoom = null
        lastBearing = null
        lastOrderMs = null
    }

    /**
     * La orden de cámara para la posición de ahora, o null si no hay que moverla: no se sigue, o la última orden fue hace menos de
     * [minIntervalMs]. [shortSideDp] es el lado corto de la parte visible del mapa; [headingRad] el rumbo de marcha o null.
     */
    fun next(tip: GeoPoint, design: GeoPoint?, headingRad: Double?, shortSideDp: Double, nowMs: Long): FollowCamera? {
        if (!following) return null
        val last = lastOrderMs
        if (last != null && nowMs - last < minIntervalMs) return null
        val zoom = pickZoom(tip, design, shortSideDp)
        val bearing = pickBearing(headingRad)
        lastOrderMs = nowMs
        lastZoom = zoom
        lastBearing = bearing
        return FollowCamera(tip.latitudeDeg, tip.longitudeDeg, zoom, bearing)
    }

    private fun pickZoom(tip: GeoPoint, design: GeoPoint?, shortSideDp: Double): Double {
        val sideM = if (design == null) DEFAULT_SIDE_M else max(2 * distanceM(tip, design) * (1 + MARGIN), MIN_SIDE_M)
        val wanted = zoomForSide(tip.latitudeDeg, sideM, shortSideDp)
        val previous = lastZoom
        return if (previous == null || abs(wanted - previous) > ZOOM_STEP) wanted else previous
    }

    private fun pickBearing(headingRad: Double?): Double {
        if (headingRad == null) return 0.0
        val wanted = (Math.toDegrees(headingRad) % FULL_TURN + FULL_TURN) % FULL_TURN
        val previous = lastBearing ?: return wanted
        val diff = abs(((wanted - previous + HALF_TURN) % FULL_TURN + FULL_TURN) % FULL_TURN - HALF_TURN)
        return if (diff > BEARING_STEP_DEG) wanted else previous
    }

    /** El zoom con el que el lado corto de [shortSideDp] cubre [sideM] metros en esa latitud, entre 1 y el máximo del mapa. */
    private fun zoomForSide(latitudeDeg: Double, sideM: Double, shortSideDp: Double): Double {
        val metresPerDp = sideM / shortSideDp
        val atZoomZero = WebMercator.metresPerPixel(latitudeDeg, 0.0)
        return (ln(atZoomZero / metresPerDp) / ln(2.0)).coerceIn(MIN_ZOOM, WebMercator.MAX_ZOOM)
    }

    /** Distancia en metros en el plano local, de sobra para los pocos metros de un replanteo. */
    private fun distanceM(a: GeoPoint, b: GeoPoint): Double {
        val dNorth = Math.toRadians(b.latitudeDeg - a.latitudeDeg) * WebMercator.RADIUS_M
        val dEast =
            Math.toRadians(b.longitudeDeg - a.longitudeDeg) * WebMercator.RADIUS_M *
                cos(Math.toRadians((a.latitudeDeg + b.latitudeDeg) / 2))
        return hypot(dNorth, dEast)
    }

    companion object {
        /** Como mucho 5 órdenes por segundo (S060/RNF-T13). */
        const val MIN_INTERVAL_MS = 200L

        /** El margen que se deja alrededor del destino (S060/RF-25). */
        const val MARGIN = 0.25

        /** El zoom no se acerca más de 3 m de lado visible (S060/RF-25). */
        const val MIN_SIDE_M = 3.0

        /** Sin destino, el lado visible. */
        const val DEFAULT_SIDE_M = 20.0

        const val ZOOM_STEP = 0.3
        const val BEARING_STEP_DEG = 5.0
        private const val MIN_ZOOM = 1.0
        private const val FULL_TURN = 360.0
        private const val HALF_TURN = 180.0
    }
}
