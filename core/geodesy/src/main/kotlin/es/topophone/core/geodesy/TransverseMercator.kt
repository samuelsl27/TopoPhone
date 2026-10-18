package es.topophone.core.geodesy

import kotlin.math.abs
import kotlin.math.asinh
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.atanh
import kotlin.math.cos
import kotlin.math.cosh
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.sinh
import kotlin.math.sqrt
import kotlin.math.tan

/** Elipsoide de revolución. */
data class Ellipsoid(val semiMajorAxisM: Double, val inverseFlattening: Double) {
    val flattening: Double get() = 1.0 / inverseFlattening
    val eccentricitySquared: Double get() = flattening * (2.0 - flattening)
    val eccentricity: Double get() = sqrt(eccentricitySquared)

    companion object {
        /** GRS 1980, el de ETRS89 y REGCAN95. */
        val GRS80 = Ellipsoid(semiMajorAxisM = 6_378_137.0, inverseFlattening = 298.257222101)

        /** Internacional de 1924 (Hayford), el de ED50 (S008/RF-1). */
        val INTERNATIONAL_1924 = Ellipsoid(semiMajorAxisM = 6_378_388.0, inverseFlattening = 297.0)
    }
}

/** Factor de escala de la proyección y convergencia de meridianos en un punto (S008/RF-13, RF-14). */
data class PointFactors(val scale: Double, val convergenceRad: Double)

object Geodesy {
    /** Radio medio de Gauss √(M·N) en la latitud dada (S008/RF-15). */
    fun gaussMeanRadius(latitudeDeg: Double, ellipsoid: Ellipsoid): Double {
        val sinPhi = sin(Math.toRadians(latitudeDeg))
        val e2 = ellipsoid.eccentricitySquared
        return ellipsoid.semiMajorAxisM * sqrt(1 - e2) / (1 - e2 * sinPhi * sinPhi)
    }

    /** Radio de curvatura del meridiano M en la latitud [phiRad] (S009/RF-17). */
    fun meridianRadius(phiRad: Double, ellipsoid: Ellipsoid = Ellipsoid.GRS80): Double {
        val e2 = ellipsoid.eccentricitySquared
        val w = 1 - e2 * sin(phiRad) * sin(phiRad)
        return ellipsoid.semiMajorAxisM * (1 - e2) / (w * sqrt(w))
    }

    /** Radio de curvatura del primer vertical N en la latitud [phiRad] (S009/RF-12, RF-17). */
    fun primeVerticalRadius(phiRad: Double, ellipsoid: Ellipsoid = Ellipsoid.GRS80): Double =
        ellipsoid.semiMajorAxisM / sqrt(1 - ellipsoid.eccentricitySquared * sin(phiRad) * sin(phiRad))
}

/**
 * Proyección transversa de Mercator por las series de Krüger hasta orden 6 (Karney 2011),
 * equivalente al `etmerc` de PROJ: error < 1 µm hasta ±3° del meridiano central.
 * Latitud de origen 0 (la de UTM); otras latitudes de origen llegan en S031.
 */
class TransverseMercator(
    private val ellipsoid: Ellipsoid,
    centralMeridianDeg: Double,
    private val scaleFactor: Double,
    private val falseEastingM: Double,
    private val falseNorthingM: Double,
) {
    private val lambda0 = Math.toRadians(centralMeridianDeg)
    private val n = ellipsoid.flattening / (2.0 - ellipsoid.flattening)
    private val rectifyingRadius: Double
    private val alpha: DoubleArray
    private val beta: DoubleArray

    init {
        val n2 = n * n
        val n3 = n2 * n
        val n4 = n3 * n
        val n5 = n4 * n
        val n6 = n5 * n
        rectifyingRadius = ellipsoid.semiMajorAxisM / (1 + n) * (1 + n2 / 4 + n4 / 64 + n6 / 256)
        alpha = doubleArrayOf(
            n / 2 - 2.0 / 3 * n2 + 5.0 / 16 * n3 + 41.0 / 180 * n4 - 127.0 / 288 * n5 + 7891.0 / 37800 * n6,
            13.0 / 48 * n2 - 3.0 / 5 * n3 + 557.0 / 1440 * n4 + 281.0 / 630 * n5 - 1983433.0 / 1935360 * n6,
            61.0 / 240 * n3 - 103.0 / 140 * n4 + 15061.0 / 26880 * n5 + 167603.0 / 181440 * n6,
            49561.0 / 161280 * n4 - 179.0 / 168 * n5 + 6601661.0 / 7257600 * n6,
            34729.0 / 80640 * n5 - 3418889.0 / 1995840 * n6,
            212378941.0 / 319334400 * n6,
        )
        // Karney 2011, ecuación 36: los coeficientes de la serie inversa (S011/RF-17).
        beta = doubleArrayOf(
            n / 2 - 2.0 / 3 * n2 + 37.0 / 96 * n3 - 1.0 / 360 * n4 - 81.0 / 512 * n5 + 96199.0 / 604800 * n6,
            1.0 / 48 * n2 + 1.0 / 15 * n3 - 437.0 / 1440 * n4 + 46.0 / 105 * n5 - 1118711.0 / 3870720 * n6,
            17.0 / 480 * n3 - 37.0 / 840 * n4 - 209.0 / 4480 * n5 + 5569.0 / 90720 * n6,
            4397.0 / 161280 * n4 - 11.0 / 504 * n5 - 830251.0 / 7257600 * n6,
            4583.0 / 161280 * n5 - 108847.0 / 3991680 * n6,
            20648693.0 / 638668800 * n6,
        )
    }

    /** Geográficas (grados) → (Este, Norte) en metros. */
    fun forward(latitudeDeg: Double, longitudeDeg: Double): Pair<Double, Double> {
        val c = Conformal(latitudeDeg, longitudeDeg)
        var xi = c.xiPrime
        var eta = c.etaPrime
        for (j in 1..alpha.size) {
            val twoJ = 2.0 * j
            xi += alpha[j - 1] * sin(twoJ * c.xiPrime) * cosh(twoJ * c.etaPrime)
            eta += alpha[j - 1] * cos(twoJ * c.xiPrime) * sinh(twoJ * c.etaPrime)
        }
        val k = scaleFactor * rectifyingRadius
        return (falseEastingM + k * eta) to (falseNorthingM + k * xi)
    }

    /**
     * (Este, Norte) en metros → geográficas (grados): la inversa de [forward] con la serie β de Krüger y la latitud
     * por Newton desde la conforme (Karney 2011, ecuaciones 11, 15 y 19 a 21) (S011/RF-17).
     */
    fun inverse(eastingM: Double, northingM: Double): GeoPoint {
        val k = scaleFactor * rectifyingRadius
        val xi = (northingM - falseNorthingM) / k
        val eta = (eastingM - falseEastingM) / k
        var xiPrime = xi
        var etaPrime = eta
        for (j in 1..beta.size) {
            val twoJ = 2.0 * j
            xiPrime -= beta[j - 1] * sin(twoJ * xi) * cosh(twoJ * eta)
            etaPrime -= beta[j - 1] * cos(twoJ * xi) * sinh(twoJ * eta)
        }
        val tauPrime = sin(xiPrime) / sqrt(sinh(etaPrime) * sinh(etaPrime) + cos(xiPrime) * cos(xiPrime))
        val dLambda = atan2(sinh(etaPrime), cos(xiPrime))
        return GeoPoint(Math.toDegrees(atan(tauFromConformal(tauPrime))), Math.toDegrees(lambda0 + dLambda))
    }

    /** τ = tan φ desde τ' = tan χ por Newton (Karney 2011, ecuaciones 19 a 21); converge en dos o tres pasos. */
    private fun tauFromConformal(tauPrime: Double): Double {
        val e2 = ellipsoid.eccentricitySquared
        var tau = tauPrime / (1 - e2)
        repeat(MAX_NEWTON_STEPS) {
            val tp = conformalTau(tau)
            val step = (tauPrime - tp) * (1 + (1 - e2) * tau * tau) / ((1 - e2) * sqrt(1 + tp * tp) * sqrt(1 + tau * tau))
            tau += step
            if (abs(step) <= NEWTON_TOLERANCE * maxOf(1.0, abs(tau))) return tau
        }
        return tau
    }

    /** τ' = tan χ de la latitud conforme, sin pérdida de precisión cerca del polo (Karney 2011, ecuación 7). */
    private fun conformalTau(tau: Double): Double {
        val e = ellipsoid.eccentricity
        val sigma = sinh(e * atanh(e * tau / sqrt(1 + tau * tau)))
        return tau * sqrt(1 + sigma * sigma) - sigma * sqrt(1 + tau * tau)
    }

    /**
     * Factor de escala y convergencia de meridianos (Karney 2011, ecuaciones 13 y 14), con las mismas
     * series que [forward] (S008/RF-13, RF-14). La convergencia es positiva al este del meridiano central.
     */
    fun factors(latitudeDeg: Double, longitudeDeg: Double): PointFactors {
        val c = Conformal(latitudeDeg, longitudeDeg)
        var p = 1.0
        var q = 0.0
        for (j in 1..alpha.size) {
            val twoJ = 2.0 * j
            p += twoJ * alpha[j - 1] * cos(twoJ * c.xiPrime) * cosh(twoJ * c.etaPrime)
            q += twoJ * alpha[j - 1] * sin(twoJ * c.xiPrime) * sinh(twoJ * c.etaPrime)
        }
        val gamma = atan2(c.tauPrime * tan(c.dLambda), sqrt(1 + c.tauPrime * c.tauPrime)) + atan2(q, p)
        val e2 = ellipsoid.eccentricitySquared
        val sinPhi = sin(c.phi)
        val kPrime = sqrt(1 - e2 * sinPhi * sinPhi) * sqrt(1 + c.tau * c.tau) /
            sqrt(c.tauPrime * c.tauPrime + cos(c.dLambda) * cos(c.dLambda))
        val kSecond = rectifyingRadius / ellipsoid.semiMajorAxisM * sqrt(p * p + q * q)
        return PointFactors(scaleFactor * kPrime * kSecond, gamma)
    }

    /** Latitud conforme (tangente, sin pérdida de precisión cerca del polo) y coordenadas esféricas ξ', η'. */
    private inner class Conformal(latitudeDeg: Double, longitudeDeg: Double) {
        val phi = Math.toRadians(latitudeDeg)
        val dLambda = Math.toRadians(longitudeDeg) - lambda0
        val tau = tan(phi)
        val tauPrime: Double
        val xiPrime: Double
        val etaPrime: Double

        init {
            tauPrime = conformalTau(tau)
            xiPrime = atan2(tauPrime, cos(dLambda))
            etaPrime = asinh(sin(dLambda) / sqrt(tauPrime * tauPrime + cos(dLambda) * cos(dLambda)))
        }
    }

    private companion object {
        const val MAX_NEWTON_STEPS = 6
        const val NEWTON_TOLERANCE = 1e-14
    }
}

/** Coordenadas UTM del hemisferio norte. */
data class UtmCoordinate(val zone: Int, val easting: Double, val northing: Double)

/** UTM norte sobre GRS80 (ETRS89, REGCAN95) o sobre otro elipsoide, como el de ED50 (S002/RF-36, S008/RF-1). */
object Utm {
    private const val SCALE = 0.9996
    private const val FALSE_EASTING = 500_000.0
    private val projections = HashMap<Pair<Int, Ellipsoid>, TransverseMercator>()

    /** Huso por la longitud: ⌊(λ + 180)/6⌋ + 1. */
    fun zoneFor(longitudeDeg: Double): Int = (floor((longitudeDeg + 180.0) / 6.0).toInt() % 60) + 1

    fun centralMeridianDeg(zone: Int): Double = 6.0 * zone - 183.0

    fun forward(
        latitudeDeg: Double,
        longitudeDeg: Double,
        zone: Int = zoneFor(longitudeDeg),
        ellipsoid: Ellipsoid = Ellipsoid.GRS80,
    ): UtmCoordinate {
        val (easting, northing) = projection(zone, ellipsoid).forward(latitudeDeg, longitudeDeg)
        return UtmCoordinate(zone, easting, northing)
    }

    /** Geográficas de (Este, Norte) en el huso y el elipsoide del proyecto (S011/RF-17). */
    fun inverse(eastingM: Double, northingM: Double, zone: Int, ellipsoid: Ellipsoid = Ellipsoid.GRS80): GeoPoint =
        projection(zone, ellipsoid).inverse(eastingM, northingM)

    /** Factor de escala y convergencia en el huso dado, aunque el punto caiga en otro (S008/RF-13, RF-14). */
    fun factors(latitudeDeg: Double, longitudeDeg: Double, zone: Int, ellipsoid: Ellipsoid = Ellipsoid.GRS80): PointFactors =
        projection(zone, ellipsoid).factors(latitudeDeg, longitudeDeg)

    private fun projection(zone: Int, ellipsoid: Ellipsoid): TransverseMercator = synchronized(projections) {
        projections.getOrPut(zone to ellipsoid) { TransverseMercator(ellipsoid, centralMeridianDeg(zone), SCALE, FALSE_EASTING, 0.0) }
    }
}
