package es.topophone.core.survey

import es.topophone.core.geodesy.Ed50
import es.topophone.core.geodesy.Ellipsoid
import es.topophone.core.geodesy.Utm
import es.topophone.core.model.project.GeodeticDatum
import es.topophone.core.model.project.ProjectCrs
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Dónde cae un dibujo respecto al proyecto (S040/RF-9, RF-10): las geográficas de un punto en el sistema del proyecto y la
 * distancia entre dos puntos, para avisar si el centro del dibujo queda a más de [FAR_KM] del proyecto.
 */
object CadPlacement {
    /** A más de esta distancia entre los centros se avisa antes de importar (RF-10). */
    const val FAR_KM = 100.0

    private const val EARTH_RADIUS_KM = 6371.0088

    /**
     * Latitud y longitud ETRS89 o REGCAN95 del punto (Este, Norte) del sistema [crs]; en un sistema en geográficas, (Norte, Este)
     * son la latitud y la longitud. null si es ED50 y cae fuera de la rejilla.
     */
    fun geographic(crs: ProjectCrs, easting: Double, northing: Double): Pair<Double, Double>? {
        val zone = crs.utmZone ?: return northing to easting
        val ellipsoid = if (crs.datum == GeodeticDatum.ED50) Ellipsoid.INTERNATIONAL_1924 else Ellipsoid.GRS80
        val point = Utm.inverse(easting, northing, zone, ellipsoid)
        return if (crs.datum ==
            GeodeticDatum.ED50
        ) {
            Ed50.toEtrs89(point.latitudeDeg, point.longitudeDeg)
        } else {
            point.latitudeDeg to point.longitudeDeg
        }
    }

    /** Distancia sobre la esfera media entre dos puntos, en km (haversine): de sobra para un aviso de cientos de km. */
    fun distanceKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val p1 = Math.toRadians(lat1)
        val p2 = Math.toRadians(lat2)
        val dLat = p2 - p1
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) + cos(p1) * cos(p2) * sin(dLon / 2) * sin(dLon / 2)
        return 2 * EARTH_RADIUS_KM * asin(sqrt(a))
    }

    /** Si la distancia [km] pide confirmación; null (no se pudo calcular) no la pide. */
    fun isFar(km: Double?): Boolean = km != null && km > FAR_KM
}
