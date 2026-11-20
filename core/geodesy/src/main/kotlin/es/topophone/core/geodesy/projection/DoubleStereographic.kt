package es.topophone.core.geodesy.projection

import es.topophone.core.geodesy.Ellipsoid
import es.topophone.core.geodesy.GeoPoint
import es.topophone.core.geodesy.PointFactors
import kotlin.math.abs
import kotlin.math.asinh
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.atanh
import kotlin.math.cos
import kotlin.math.cosh
import kotlin.math.sin
import kotlin.math.sinh
import kotlin.math.sqrt
import kotlin.math.tan
import kotlin.math.tanh

/**
 * Estereográfica oblicua o doble estereográfica (EPSG 9809) sobre cualquier elipsoide (S032/RF-10; plan A4; GN 7-2 §3.3.7): el elipsoide pasa
 * a una esfera conforme de radio R (Gauss) y de ahí a un plano por una estereográfica. Los parámetros llegan ya en metros.
 *
 * La latitud conforme de la esfera de Gauss es `sinχ = tanh(u)` con `u = n(ψ − ψ₀) + atanh(sinφ₀/n)` y ψ la latitud isométrica: así no hay
 * constante `c` ni cocientes que pierdan cifras cerca de los polos.
 */
internal object DoubleStereographic {

    /** Un punto tan cerca de la antípoda del origen que el plano se va al infinito: sin imagen (RF-19). */
    private const val ANTIPODE_TOLERANCE = 1e-10

    /** cosχ por debajo de esto (relativo) es el polo: la inversa lo devuelve sin pasar por una latitud infinita. */
    private const val POLE_TOLERANCE = 1e-12

    fun create(ellipsoid: Ellipsoid, parameters: ProjectionParameters): MapProjection {
        require(parameters is ObliqueStereographic) { "${parameters.method} no es una Estereográfica oblicua" }
        return Projection(ellipsoid, parameters)
    }

    private class Projection(ellipsoid: Ellipsoid, parameters: ObliqueStereographic) : MapProjection {

        private val conformal = ConformalLatitude(ellipsoid)
        private val a = ellipsoid.semiMajorAxisM
        private val e2 = ellipsoid.eccentricitySquared
        private val longitudeOfOriginDeg = parameters.longitudeOfOriginDeg
        private val falseEastingM = parameters.falseEasting
        private val falseNorthingM = parameters.falseNorthing

        /** n: relación entre la longitud de la esfera de Gauss y la del elipsoide. */
        private val n: Double

        /** `2·R·k₀`: el radio de la esfera de Gauss por el factor de escala del origen y por 2 de la estereográfica. */
        private val twoRk0: Double
        private val psi0: Double

        /** u₀: el valor de u en el origen (u = atanh(sinχ)). */
        private val u0: Double
        private val sinChi0: Double
        private val cosChi0: Double

        init {
            val phi0 = Math.toRadians(parameters.latitudeOfOriginDeg)
            val sinPhi0 = sin(phi0)
            val cosPhi0 = cos(phi0)
            n = sqrt(1 + e2 * cosPhi0 * cosPhi0 * cosPhi0 * cosPhi0 / (1 - e2))
            // R = √(ρ₀ν₀) = a√(1 − e²) / (1 − e²sin²φ₀)
            val radius = a * sqrt(1 - e2) / (1 - e2 * sinPhi0 * sinPhi0)
            twoRk0 = 2 * radius * parameters.scaleFactor
            psi0 = asinh(conformal.tauPrime(tan(phi0)))
            sinChi0 = sinPhi0 / n
            u0 = atanh(sinChi0)
            cosChi0 = 1 / cosh(u0)
        }

        override fun forward(latitudeDeg: Double, longitudeDeg: Double): ProjectedPoint? {
            if (!latitudeDeg.isFinite() || !longitudeDeg.isFinite() || abs(latitudeDeg) > 90.0) return null
            val u = u(latitudeDeg)
            val sinChi = tanh(u)
            val cosChi = 1 / cosh(u)
            val delta = n * Math.toRadians(wrap(longitudeDeg - longitudeOfOriginDeg))
            val cosDelta = cos(delta)
            val b = 1 + sinChi * sinChi0 + cosChi * cosChi0 * cosDelta
            if (!b.isFinite() || b < ANTIPODE_TOLERANCE) return null
            val easting = falseEastingM + twoRk0 * cosChi * sin(delta) / b
            val northing = falseNorthingM + twoRk0 * (sinChi * cosChi0 - cosChi * sinChi0 * cosDelta) / b
            return if (easting.isFinite() && northing.isFinite()) ProjectedPoint(easting, northing) else null
        }

        override fun inverse(eastingM: Double, northingM: Double): GeoPoint? {
            if (!eastingM.isFinite() || !northingM.isFinite()) return null
            val x = (eastingM - falseEastingM) / twoRk0
            val y = (northingM - falseNorthingM) / twoRk0
            val r2 = x * x + y * y
            val d = 1 + r2
            // Estereográfica inversa sobre la esfera de Gauss (Snyder 21-15 con c = 2·atan r).
            val numerator = (1 - r2) * sinChi0 + 2 * y * cosChi0
            val cosChiTimesD = sqrt(maxOf(0.0, (d - numerator) * (d + numerator)))
            val delta = atan2(2 * x, (1 - r2) * cosChi0 - 2 * y * sinChi0)
            val longitude = wrap(longitudeOfOriginDeg + Math.toDegrees(delta) / n)
            // Un polo tiene imagen, pero su longitud no está definida: se devuelve la que da el plano.
            if (cosChiTimesD <= POLE_TOLERANCE * d) return GeoPoint(if (numerator > 0) 90.0 else -90.0, longitude)
            val u = asinh(numerator / cosChiTimesD)
            val psi = psi0 + (u - u0) / n
            val latitude = Math.toDegrees(atan(conformal.tau(sinh(psi))))
            return if (latitude.isFinite() && longitude.isFinite()) GeoPoint(latitude, longitude) else null
        }

        override fun factors(latitudeDeg: Double, longitudeDeg: Double): PointFactors? {
            error("S032 T12: factor de escala y convergencia de la Estereográfica oblicua")
        }

        /** u = atanh(sinχ) de una latitud geodésica en grados. */
        private fun u(latitudeDeg: Double): Double {
            val psi = asinh(conformal.tauPrime(tan(Math.toRadians(latitudeDeg))))
            return n * (psi - psi0) + u0
        }

        private fun wrap(longitudeDeg: Double): Double {
            if (longitudeDeg > -180.0 && longitudeDeg <= 180.0) return longitudeDeg
            val wrapped = ((longitudeDeg + 180.0) % 360.0 + 360.0) % 360.0 - 180.0
            return if (wrapped == -180.0) 180.0 else wrapped
        }
    }
}
