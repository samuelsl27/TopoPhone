package es.topophone.core.geodesy.projection

import es.topophone.core.geodesy.Ellipsoid
import es.topophone.core.geodesy.GeoPoint
import es.topophone.core.geodesy.PointFactors
import kotlin.math.abs
import kotlin.math.asinh
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sinh
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Mercator A (con factor de escala en el ecuador, EPSG 9804) y B (con un paralelo estándar de escala verdadera, EPSG 9805) sobre cualquier
 * elipsoide (S032/RF-4, RF-5; plan A2; GN 7-2 §3.5.1). Los parámetros llegan ya en metros y las longitudes se cuentan desde el meridiano de
 * origen del SC: de eso se ocupa [ProjectionFactory].
 *
 * El Norte es `FN + a·k₀·ψ` con ψ la latitud isométrica, que es `asinh(τ′)` con τ′ = tan χ de la latitud conforme.
 */
internal object Mercator {

    fun create(ellipsoid: Ellipsoid, parameters: ProjectionParameters): MapProjection = when (parameters) {
        is MercatorA -> Projection(
            ellipsoid,
            parameters.longitudeOfOriginDeg,
            parameters.scaleFactor,
            parameters.falseEasting,
            parameters.falseNorthing,
        )
        is MercatorB -> {
            // k₀ = cosφ₁ / √(1 − e²sin²φ₁): el factor de escala del ecuador que da escala 1 en el paralelo estándar.
            val phi1 = Math.toRadians(parameters.standardParallelDeg)
            val k0 = cos(phi1) / sqrt(1 - ellipsoid.eccentricitySquared * sin(phi1) * sin(phi1))
            Projection(ellipsoid, parameters.longitudeOfOriginDeg, k0, parameters.falseEasting, parameters.falseNorthing)
        }
        else -> error("${parameters.method} no es una Mercator")
    }

    private class Projection(
        ellipsoid: Ellipsoid,
        private val longitudeOfOriginDeg: Double,
        private val k0: Double,
        private val falseEastingM: Double,
        private val falseNorthingM: Double,
    ) : MapProjection {

        private val conformal = ConformalLatitude(ellipsoid)
        private val a = ellipsoid.semiMajorAxisM
        private val e2 = ellipsoid.eccentricitySquared

        override fun forward(latitudeDeg: Double, longitudeDeg: Double): ProjectedPoint? {
            // Los polos quedan en el infinito: sin imagen (RF-19).
            if (!latitudeDeg.isFinite() || !longitudeDeg.isFinite() || abs(latitudeDeg) >= 90.0) return null
            val psi = asinh(conformal.tauPrime(tan(Math.toRadians(latitudeDeg))))
            val easting = falseEastingM + a * k0 * Math.toRadians(wrap(longitudeDeg - longitudeOfOriginDeg))
            return ProjectedPoint(easting, falseNorthingM + a * k0 * psi)
        }

        override fun inverse(eastingM: Double, northingM: Double): GeoPoint? {
            if (!eastingM.isFinite() || !northingM.isFinite()) return null
            val psi = (northingM - falseNorthingM) / (a * k0)
            val latitude = Math.toDegrees(atan(conformal.tau(sinh(psi))))
            val longitude = wrap(longitudeOfOriginDeg + Math.toDegrees((eastingM - falseEastingM) / (a * k0)))
            return if (latitude.isFinite() && longitude.isFinite()) GeoPoint(latitude, longitude) else null
        }

        /** `k = k₀·√(1 − e²sin²φ)/cosφ` y convergencia nula: los meridianos son paralelos entre sí (S032/RF-5). */
        override fun factors(latitudeDeg: Double, longitudeDeg: Double): PointFactors? {
            if (!latitudeDeg.isFinite() || !longitudeDeg.isFinite() || abs(latitudeDeg) >= 90.0) return null
            val phi = Math.toRadians(latitudeDeg)
            return PointFactors(k0 * sqrt(1 - e2 * sin(phi) * sin(phi)) / cos(phi), 0.0)
        }

        private fun wrap(longitudeDeg: Double): Double {
            if (longitudeDeg > -180.0 && longitudeDeg <= 180.0) return longitudeDeg
            val wrapped = ((longitudeDeg + 180.0) % 360.0 + 360.0) % 360.0 - 180.0
            return if (wrapped == -180.0) 180.0 else wrapped
        }
    }
}
