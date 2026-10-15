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
        val phi = Math.toRadians(latitudeDeg)
        val dLambda = Math.toRadians(longitudeDeg) - lambda0
        val e = ellipsoid.eccentricity

        // Latitud conforme (tangente), sin pérdida de precisión cerca del polo.
        val tau = tan(phi)
        val sigma = sinh(e * atanh(e * tau / sqrt(1 + tau * tau)))
        val tauPrime = tau * sqrt(1 + sigma * sigma) - sigma * sqrt(1 + tau * tau)

        val xiPrime = atan2(tauPrime, cos(dLambda))
        val etaPrime = asinh(sin(dLambda) / sqrt(tauPrime * tauPrime + cos(dLambda) * cos(dLambda)))

        var xi = xiPrime
        var eta = etaPrime
        for (j in 1..alpha.size) {
            val twoJ = 2.0 * j
            xi += alpha[j - 1] * sin(twoJ * xiPrime) * cosh(twoJ * etaPrime)
            eta += alpha[j - 1] * cos(twoJ * xiPrime) * sinh(twoJ * etaPrime)
        }
        val k = scaleFactor * rectifyingRadius
        return (falseEastingM + k * eta) to (falseNorthingM + k * xi)
    }
}

/** Coordenadas UTM del hemisferio norte. */
data class UtmCoordinate(val zone: Int, val easting: Double, val northing: Double)

/** UTM norte sobre ETRS89/GRS80 (S002/RF-36). */
object Utm {
    private const val SCALE = 0.9996
    private const val FALSE_EASTING = 500_000.0
    private val projections = HashMap<Int, TransverseMercator>()

    /** Huso por la longitud: ⌊(λ + 180)/6⌋ + 1. */
    fun zoneFor(longitudeDeg: Double): Int = (floor((longitudeDeg + 180.0) / 6.0).toInt() % 60) + 1

    fun centralMeridianDeg(zone: Int): Double = 6.0 * zone - 183.0

    fun forward(latitudeDeg: Double, longitudeDeg: Double, zone: Int = zoneFor(longitudeDeg)): UtmCoordinate {
        val projection = synchronized(projections) {
            projections.getOrPut(zone) { TransverseMercator(Ellipsoid.GRS80, centralMeridianDeg(zone), SCALE, FALSE_EASTING, 0.0) }
        }
        val (easting, northing) = projection.forward(latitudeDeg, longitudeDeg)
        return UtmCoordinate(zone, easting, northing)
    }
}
