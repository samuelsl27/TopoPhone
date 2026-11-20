package es.topophone.core.geodesy.projection

import es.topophone.core.geodesy.Ellipsoid
import es.topophone.core.geodesy.GeoPoint
import es.topophone.core.geodesy.PointFactors
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sign
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Cónica Conforme de Lambert 1SP y 2SP (EPSG 9801 y 9802; GN 7-2 §3.3.1.1 y §3.3.1.2) sobre cualquier elipsoide (S032/RF-1; plan A1).
 * Los parámetros llegan ya en metros ([inMetres]) y las longitudes se cuentan desde el meridiano de origen del SC: de eso se ocupa
 * [ProjectionFactory].
 */
internal object LambertConic {

    fun create(ellipsoid: Ellipsoid, parameters: ProjectionParameters): MapProjection = when (parameters) {
        is LambertConic1Sp -> oneStandardParallel(ellipsoid, parameters)
        is LambertConic2Sp -> twoStandardParallels(ellipsoid, parameters)
        else -> error("${parameters.method} no es una Lambert")
    }

    private fun oneStandardParallel(ellipsoid: Ellipsoid, p: LambertConic1Sp): MapProjection {
        val phi0 = Math.toRadians(p.latitudeOfOriginDeg)
        val conformal = ConformalLatitude(ellipsoid)
        val n = sin(phi0)
        val m0 = m(ellipsoid, phi0)
        val t0 = conformal.t(conformal.tauPrime(tan(phi0)))
        // r = a·k₀·F·tⁿ con F = m₀/(n·t₀ⁿ); en el origen, r_F = a·k₀·m₀/n.
        val aF = ellipsoid.semiMajorAxisM * p.scaleFactor * m0 / (n * t0.pow(n))
        return Projection(ellipsoid, conformal, p.longitudeOfOriginDeg, n, aF, aF * t0.pow(n), p.falseEasting, p.falseNorthing)
    }

    private fun twoStandardParallels(ellipsoid: Ellipsoid, p: LambertConic2Sp): MapProjection {
        val phiF = Math.toRadians(p.latitudeOfFalseOriginDeg)
        val phi1 = Math.toRadians(p.standardParallel1Deg)
        val phi2 = Math.toRadians(p.standardParallel2Deg)
        val conformal = ConformalLatitude(ellipsoid)
        val m1 = m(ellipsoid, phi1)
        val t1 = conformal.t(conformal.tauPrime(tan(phi1)))
        val n = if (abs(p.standardParallel1Deg - p.standardParallel2Deg) < EQUAL_PARALLELS_DEG) {
            sin(phi1)
        } else {
            val m2 = m(ellipsoid, phi2)
            val t2 = conformal.t(conformal.tauPrime(tan(phi2)))
            (ln(m1) - ln(m2)) / (ln(t1) - ln(t2))
        }
        val aF = ellipsoid.semiMajorAxisM * m1 / (n * t1.pow(n))
        val tF = conformal.t(conformal.tauPrime(tan(phiF)))
        return Projection(
            ellipsoid,
            conformal,
            p.longitudeOfFalseOriginDeg,
            n,
            aF,
            aF * tF.pow(n),
            p.eastingAtFalseOrigin,
            p.northingAtFalseOrigin,
        )
    }

    /** `m = cosφ / √(1 − e²sin²φ)`. */
    private fun m(ellipsoid: Ellipsoid, phi: Double): Double = cos(phi) / sqrt(1 - ellipsoid.eccentricitySquared * sin(phi) * sin(phi))

    private const val EQUAL_PARALLELS_DEG = 1e-12

    /**
     * @param aF `a·F` (con el factor de escala en el 1SP): `r = aF·tⁿ`.
     * @param rF el radio en el origen: `aF·t_Fⁿ`.
     */
    private class Projection(
        ellipsoid: Ellipsoid,
        private val conformal: ConformalLatitude,
        private val longitudeOfOriginDeg: Double,
        private val n: Double,
        private val aF: Double,
        private val rF: Double,
        private val falseEastingM: Double,
        private val falseNorthingM: Double,
    ) : MapProjection {

        private val coneSign = sign(n)

        override fun forward(latitudeDeg: Double, longitudeDeg: Double): ProjectedPoint? {
            if (!latitudeDeg.isFinite() || !longitudeDeg.isFinite() || abs(latitudeDeg) > 90.0) return null
            val r = if (abs(latitudeDeg) == 90.0) {
                // El vértice del cono (r = 0) está en el polo del signo de n; el otro polo queda en el infinito: sin imagen.
                if (sign(latitudeDeg) != coneSign) return null
                0.0
            } else {
                aF * conformal.t(conformal.tauPrime(tan(Math.toRadians(latitudeDeg)))).pow(n)
            }
            val theta = n * Math.toRadians(wrap(longitudeDeg - longitudeOfOriginDeg))
            return ProjectedPoint(falseEastingM + r * sin(theta), falseNorthingM + rF - r * cos(theta))
        }

        override fun inverse(eastingM: Double, northingM: Double): GeoPoint? {
            if (!eastingM.isFinite() || !northingM.isFinite()) return null
            val dx = eastingM - falseEastingM
            val dy = rF - (northingM - falseNorthingM)
            val rPrime = coneSign * hypot(dx, dy)
            if (rPrime == 0.0) return GeoPoint(coneSign * 90.0, longitudeOfOriginDeg)
            val thetaPrime = atan2(coneSign * dx, coneSign * dy)
            val tPrime = (rPrime / aF).pow(1.0 / n)
            val latitude = if (tPrime == 0.0) coneSign * 90.0 else Math.toDegrees(atan(conformal.tau(conformal.tauPrimeFromT(tPrime))))
            val longitude = wrap(longitudeOfOriginDeg + Math.toDegrees(thetaPrime / n))
            return GeoPoint(latitude, longitude)
        }

        /** Pendiente de la tarea T4: el factor de escala y la convergencia. */
        override fun factors(latitudeDeg: Double, longitudeDeg: Double): PointFactors? = null

        private fun wrap(longitudeDeg: Double): Double {
            if (longitudeDeg > -180.0 && longitudeDeg <= 180.0) return longitudeDeg
            val wrapped = ((longitudeDeg + 180.0) % 360.0 + 360.0) % 360.0 - 180.0
            return if (wrapped == -180.0) 180.0 else wrapped
        }
    }
}
