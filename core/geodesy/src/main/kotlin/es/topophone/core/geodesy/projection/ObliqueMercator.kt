package es.topophone.core.geodesy.projection

import es.topophone.core.geodesy.Ellipsoid
import es.topophone.core.geodesy.GeoPoint
import es.topophone.core.geodesy.PointFactors
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Hotine Oblique Mercator, variantes A (falsos en el origen natural, EPSG 9812) y B (Este y Norte en el centro de la proyección, EPSG 9815)
 * sobre cualquier elipsoide (S032/RF-7; plan A3; GN 7-2 §3.5.2.1). Los parámetros llegan ya en metros.
 *
 * La línea inicial es una línea geodésica con azimut αc en el centro de la proyección; (u, v) son las coordenadas a lo largo y a través de ella
 * y el ángulo de rectificación γc las gira a (Este, Norte). La variante B resta además `uc`, el valor de u en el centro.
 */
internal object ObliqueMercator {

    /** Un punto tan cerca de los polos de la proyección (a 90° de la línea central) que `v` se va al infinito, como en PROJ. */
    private const val POLE_TOLERANCE = 1e-10

    fun create(ellipsoid: Ellipsoid, parameters: ProjectionParameters): MapProjection = when (parameters) {
        is ObliqueMercatorA -> Projection(
            ellipsoid,
            Centre(
                parameters.latitudeOfCentreDeg,
                parameters.longitudeOfCentreDeg,
                parameters.azimuthDeg,
                parameters.rectifiedGridAngleDeg,
                parameters.scaleFactor,
            ),
            parameters.falseEasting,
            parameters.falseNorthing,
            offsetFromCentre = false,
        )
        is ObliqueMercatorB -> Projection(
            ellipsoid,
            Centre(
                parameters.latitudeOfCentreDeg,
                parameters.longitudeOfCentreDeg,
                parameters.azimuthDeg,
                parameters.rectifiedGridAngleDeg,
                parameters.scaleFactor,
            ),
            parameters.eastingAtCentre,
            parameters.northingAtCentre,
            offsetFromCentre = true,
        )
        else -> error("${parameters.method} no es una Oblique Mercator")
    }

    private data class Centre(
        val latitudeDeg: Double,
        val longitudeDeg: Double,
        val azimuthDeg: Double,
        val rectifiedGridAngleDeg: Double,
        val scaleFactor: Double,
    )

    private class Projection(
        ellipsoid: Ellipsoid,
        centre: Centre,
        private val eastingM: Double,
        private val northingM: Double,
        offsetFromCentre: Boolean,
    ) : MapProjection {

        private val conformal = ConformalLatitude(ellipsoid)
        private val a = ellipsoid.semiMajorAxisM

        private val b: Double
        private val scaleA: Double
        private val h: Double
        private val cosGamma0: Double
        private val sinGamma0: Double
        private val cosGammaC = cos(Math.toRadians(centre.rectifiedGridAngleDeg))
        private val sinGammaC = sin(Math.toRadians(centre.rectifiedGridAngleDeg))

        /** Longitud (grados) donde la línea inicial cruza el ecuador de la proyección: el origen de las longitudes de la fórmula. */
        private val lambda0Deg: Double

        /** `uc`: u en el centro de la proyección; la variante A no lo resta. */
        private val centreU: Double

        init {
            val e2 = ellipsoid.eccentricitySquared
            val phiC = Math.toRadians(centre.latitudeDeg)
            val sinPhiC = sin(phiC)
            val cosPhiC = cos(phiC)
            b = sqrt(1 + e2 * cosPhiC.pow(4) / (1 - e2))
            scaleA = a * b * centre.scaleFactor * sqrt(1 - e2) / (1 - e2 * sinPhiC * sinPhiC)
            val t0 = conformal.t(conformal.tauPrime(tan(phiC)))
            // D² < 1 (centro en el ecuador o muy cerca) se toma como 1.
            val d = maxOf(1.0, b * sqrt(1 - e2) / (cosPhiC * sqrt(1 - e2 * sinPhiC * sinPhiC)))
            val rootD = sqrt(d * d - 1)
            val f = if (centre.latitudeDeg < 0) d - rootD else d + rootD
            h = f * t0.pow(b)
            val g = (f - 1 / f) / 2
            val alpha = Math.toRadians(centre.azimuthDeg)
            val gamma0 = asin(sin(alpha) / d)
            cosGamma0 = cos(gamma0)
            sinGamma0 = sin(gamma0)
            lambda0Deg = centre.longitudeDeg - Math.toDegrees(asin(g * tan(gamma0)) / b)
            // uc es la u del propio centro (S = G, B·(λc − λ0) = asin(G·tanγ0)): así el centro cae siempre en el Este y el Norte dados, sea cual
            // sea el azimut. Es la `uc` de GN 7-2 sin su caso aparte de 90° ni el signo de la latitud, que salen solos del signo de G.
            val gTanGamma0 = g * tan(gamma0)
            val centreAlong = scaleA / b * atan2(g / cosGamma0, sqrt(maxOf(0.0, 1 - gTanGamma0 * gTanGamma0)))
            centreU = if (offsetFromCentre) centreAlong else 0.0
        }

        override fun forward(latitudeDeg: Double, longitudeDeg: Double): ProjectedPoint? {
            if (!latitudeDeg.isFinite() || !longitudeDeg.isFinite() || abs(latitudeDeg) > 90.0) return null
            val t = conformal.t(conformal.tauPrime(tan(Math.toRadians(latitudeDeg))))
            val q = h / t.pow(b)
            val s = (q - 1 / q) / 2
            val tt = (q + 1 / q) / 2
            val bl = b * Math.toRadians(wrap(longitudeDeg - lambda0Deg))
            val sinBl = sin(bl)
            val u = (-sinBl * cosGamma0 + s * sinGamma0) / tt
            // |U| = 1: el punto está a 90° de la línea central y v se va al infinito (RF-19).
            if (!u.isFinite() || 1 - abs(u) < POLE_TOLERANCE) return null
            val v = scaleA / (2 * b) * ln((1 - u) / (1 + u))
            val along = scaleA / b * atan2(s * cosGamma0 + sinBl * sinGamma0, cos(bl)) - centreU
            val easting = v * cosGammaC + along * sinGammaC + eastingM
            val northing = along * cosGammaC - v * sinGammaC + northingM
            return if (easting.isFinite() && northing.isFinite()) ProjectedPoint(easting, northing) else null
        }

        override fun inverse(eastingM: Double, northingM: Double): GeoPoint? {
            if (!eastingM.isFinite() || !northingM.isFinite()) return null
            val dx = eastingM - this.eastingM
            val dy = northingM - this.northingM
            val v = dx * cosGammaC - dy * sinGammaC
            val u = dy * cosGammaC + dx * sinGammaC + centreU
            val q = exp(-b * v / scaleA)
            val s = (q - 1 / q) / 2
            val tt = (q + 1 / q) / 2
            val vq = sin(b * u / scaleA)
            val uq = (vq * cosGamma0 + s * sinGamma0) / tt
            if (!uq.isFinite()) return null
            // |U′| = 1 es uno de los polos de la Tierra: tiene imagen, pero su longitud no está definida (como PROJ, la de la línea inicial).
            if (1 - abs(uq) < POLE_TOLERANCE) return GeoPoint(if (uq > 0) 90.0 else -90.0, wrap(lambda0Deg))
            val tPrime = (h / sqrt((1 + uq) / (1 - uq))).pow(1 / b)
            val latitude = Math.toDegrees(atan(conformal.tau(conformal.tauPrimeFromT(tPrime))))
            val longitude = wrap(lambda0Deg - Math.toDegrees(atan2(s * cosGamma0 - vq * sinGamma0, cos(b * u / scaleA)) / b))
            return if (latitude.isFinite() && longitude.isFinite()) GeoPoint(latitude, longitude) else null
        }

        override fun factors(latitudeDeg: Double, longitudeDeg: Double): PointFactors? {
            error("S032 T9: factor de escala y convergencia de OM")
        }

        private fun wrap(longitudeDeg: Double): Double {
            if (longitudeDeg > -180.0 && longitudeDeg <= 180.0) return longitudeDeg
            val wrapped = ((longitudeDeg + 180.0) % 360.0 + 360.0) % 360.0 - 180.0
            return if (wrapped == -180.0) 180.0 else wrapped
        }
    }
}
