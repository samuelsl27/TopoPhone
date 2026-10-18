package es.topophone.core.geodesy

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

/**
 * Círculo de precisión del rover como polígono geográfico sobre GRS80, para que el mapa lo escale solo
 * con el zoom (S009/RF-17). Desarrollo de segundo orden del problema directo: error < 0,01 mm hasta
 * 100 m de radio frente a la geodésica.
 */
object AccuracyCircle {
    /**
     * [segments] vértices a [radiusM] metros de [center], desde el norte y en sentido horario. El anillo
     * no se cierra: el primer vértice no se repite al final.
     */
    fun polygon(center: GeoPoint, radiusM: Double, segments: Int = 64): List<GeoPoint> {
        require(radiusM >= 0.0) { "El radio no puede ser negativo: $radiusM" }
        require(segments >= 3) { "Hacen falta al menos 3 vértices: $segments" }
        val phi0 = Math.toRadians(center.latitudeDeg)
        return List(segments) { k ->
            val azimuth = 2 * PI * k / segments
            val northM = radiusM * cos(azimuth)
            val eastM = radiusM * sin(azimuth)
            // Radios en la latitud media; el paralelo se curva hacia el ecuador (término en east²).
            val phiMid = phi0 + northM / (2 * Geodesy.meridianRadius(phi0))
            val m = Geodesy.meridianRadius(phiMid)
            val n = Geodesy.primeVerticalRadius(phiMid)
            val phi = phi0 + northM / m - eastM * eastM * tan(phiMid) / (2 * m * n)
            GeoPoint(Math.toDegrees(phi), center.longitudeDeg + Math.toDegrees(eastM / (n * cos(phiMid))))
        }
    }
}
