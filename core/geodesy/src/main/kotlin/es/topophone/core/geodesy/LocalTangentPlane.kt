package es.topophone.core.geodesy

import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/** Desplazamiento en el plano tangente local de un punto, en metros: este, norte y arriba (por la normal). */
data class Enu(val east: Double, val north: Double, val up: Double) {
    /** En planta. */
    val horizontalM: Double get() = hypot(east, north)
}

/**
 * Plano tangente local (ENU) de un punto sobre el elipsoide: la conversión topocéntrica de EPSG GN 7-2 (métodos
 * 9836 y 9837) y la de ETC_rev_3 para la punta del jalón (S016/RF-13). La rotación se aplica a las geocéntricas.
 */
object LocalTangentPlane {

    /** El punto desplazado [east], [north] y [up] metros en el plano tangente local de [origin]. */
    fun offset(origin: GeodeticPoint, east: Double, north: Double, up: Double, ellipsoid: Ellipsoid = Ellipsoid.GRS80): GeodeticPoint {
        val r = Rotation(origin)
        val start = Ecef.fromGeodetic(origin.latitudeDeg, origin.longitudeDeg, origin.ellipsoidalHeightM, ellipsoid)
        val dx = -r.sinLambda * east - r.sinPhi * r.cosLambda * north + r.cosPhi * r.cosLambda * up
        val dy = r.cosLambda * east - r.sinPhi * r.sinLambda * north + r.cosPhi * r.sinLambda * up
        val dz = r.cosPhi * north + r.sinPhi * up
        return Ecef(start.x + dx, start.y + dy, start.z + dz).toGeodetic(ellipsoid)
    }

    /** Dónde está [to] en el plano tangente local de [from] (la rotación traspuesta de [offset]). */
    fun between(from: GeodeticPoint, to: GeodeticPoint, ellipsoid: Ellipsoid = Ellipsoid.GRS80): Enu {
        val r = Rotation(from)
        val a = Ecef.fromGeodetic(from.latitudeDeg, from.longitudeDeg, from.ellipsoidalHeightM, ellipsoid)
        val b = Ecef.fromGeodetic(to.latitudeDeg, to.longitudeDeg, to.ellipsoidalHeightM, ellipsoid)
        val dx = b.x - a.x
        val dy = b.y - a.y
        val dz = b.z - a.z
        return Enu(
            east = -r.sinLambda * dx + r.cosLambda * dy,
            north = -r.sinPhi * r.cosLambda * dx - r.sinPhi * r.sinLambda * dy + r.cosPhi * dz,
            up = r.cosPhi * r.cosLambda * dx + r.cosPhi * r.sinLambda * dy + r.sinPhi * dz,
        )
    }

    private class Rotation(origin: GeodeticPoint) {
        private val phi = Math.toRadians(origin.latitudeDeg)
        private val lambda = Math.toRadians(origin.longitudeDeg)
        val sinPhi = sin(phi)
        val cosPhi = cos(phi)
        val sinLambda = sin(lambda)
        val cosLambda = cos(lambda)
    }
}
