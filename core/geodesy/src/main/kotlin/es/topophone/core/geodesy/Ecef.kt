package es.topophone.core.geodesy

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Punto en geográficas con altura elipsoidal: grados y metros. */
data class GeodeticPoint(val latitudeDeg: Double, val longitudeDeg: Double, val ellipsoidalHeightM: Double)

/**
 * Coordenadas cartesianas geocéntricas (ECEF), en metros: la posición de la base en los mensajes RTCM 1005/1006 y la
 * forma de medir la línea base (S015/RF-13, RF-14).
 */
data class Ecef(val x: Double, val y: Double, val z: Double) {

    /** Distancia en línea recta, en metros. */
    fun distanceTo(other: Ecef): Double {
        val dx = x - other.x
        val dy = y - other.y
        val dz = z - other.z
        return sqrt(dx * dx + dy * dy + dz * dz)
    }

    /**
     * Geográficas sobre [ellipsoid] (EPSG GN 7-2, método 9602): la latitud se itera con la altura hasta que cambia
     * menos de 1·10⁻¹² rad; cerca de los polos la altura se saca de Z.
     */
    fun toGeodetic(ellipsoid: Ellipsoid = Ellipsoid.GRS80): GeodeticPoint {
        val e2 = ellipsoid.eccentricitySquared
        val p = sqrt(x * x + y * y)
        val lambda = atan2(y, x)
        var phi = atan2(z, p * (1 - e2))
        var h = 0.0
        for (iteration in 1..MAX_ITERATIONS) {
            val n = Geodesy.primeVerticalRadius(phi, ellipsoid)
            h = height(p, phi, n, e2)
            val next = atan2(z, p * (1 - e2 * n / (n + h)))
            val converged = abs(next - phi) < TOLERANCE_RAD
            phi = next
            if (converged) break
        }
        h = height(p, phi, Geodesy.primeVerticalRadius(phi, ellipsoid), e2)
        return GeodeticPoint(Math.toDegrees(phi), Math.toDegrees(lambda), h)
    }

    private fun height(p: Double, phi: Double, n: Double, e2: Double): Double {
        val cosPhi = cos(phi)
        return if (abs(cosPhi) > POLAR_COS) p / cosPhi - n else z / sin(phi) - n * (1 - e2)
    }

    companion object {
        private const val MAX_ITERATIONS = 10
        private const val TOLERANCE_RAD = 1e-12

        /** Por debajo de este coseno (a menos de unos 2,6° del polo) la altura se calcula con Z. */
        private const val POLAR_COS = 0.045

        /** Geocéntricas de un punto en geográficas sobre [ellipsoid] (EPSG GN 7-2, método 9602). */
        fun fromGeodetic(
            latitudeDeg: Double,
            longitudeDeg: Double,
            ellipsoidalHeightM: Double,
            ellipsoid: Ellipsoid = Ellipsoid.GRS80,
        ): Ecef {
            val phi = Math.toRadians(latitudeDeg)
            val lambda = Math.toRadians(longitudeDeg)
            val n = Geodesy.primeVerticalRadius(phi, ellipsoid)
            val r = (n + ellipsoidalHeightM) * cos(phi)
            return Ecef(r * cos(lambda), r * sin(lambda), (n * (1 - ellipsoid.eccentricitySquared) + ellipsoidalHeightM) * sin(phi))
        }
    }
}
