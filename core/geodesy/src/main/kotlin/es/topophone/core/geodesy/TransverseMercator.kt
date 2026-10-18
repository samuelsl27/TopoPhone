package es.topophone.core.geodesy

import kotlin.math.asinh
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
            val e = ellipsoid.eccentricity
            val sigma = sinh(e * atanh(e * tau / sqrt(1 + tau * tau)))
            tauPrime = tau * sqrt(1 + sigma * sigma) - sigma * sqrt(1 + tau * tau)
            xiPrime = atan2(tauPrime, cos(dLambda))
            etaPrime = asinh(sin(dLambda) / sqrt(tauPrime * tauPrime + cos(dLambda) * cos(dLambda)))
        }
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

    /** Factor de escala y convergencia en el huso dado, aunque el punto caiga en otro (S008/RF-13, RF-14). */
    fun factors(latitudeDeg: Double, longitudeDeg: Double, zone: Int, ellipsoid: Ellipsoid = Ellipsoid.GRS80): PointFactors =
        projection(zone, ellipsoid).factors(latitudeDeg, longitudeDeg)

    private fun projection(zone: Int, ellipsoid: Ellipsoid): TransverseMercator = synchronized(projections) {
        projections.getOrPut(zone to ellipsoid) { TransverseMercator(ellipsoid, centralMeridianDeg(zone), SCALE, FALSE_EASTING, 0.0) }
    }
}
