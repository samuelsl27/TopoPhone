package es.topophone.core.geodesy

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import kotlin.math.abs
import kotlin.math.sqrt
import org.junit.jupiter.api.Test

/**
 * UTM sobre cualquier elipsoide, factor de escala y convergencia de meridianos frente a pyproj
 * (S008/RF-1, RF-13, RF-14; constitución nº 2).
 */
class UtmFactorsTest {

    private fun ellipsoid(name: String) = when (name) {
        "GRS80" -> Ellipsoid.GRS80
        "intl" -> Ellipsoid.INTERNATIONAL_1924
        else -> error("elipsoide $name")
    }

    @Test
    fun `S008 RF-1 REGCAN95 en los husos 27 y 28 coincide con pyproj a menos de 1 mm`() {
        val vectors = GeodesyFixtures.rows("utm_regcan95.csv")
        vectors.size shouldBeGreaterThan 100

        val failures = vectors.mapNotNull { row ->
            val utm = Utm.forward(row[0].toDouble(), row[1].toDouble(), zone = row[2].toInt())
            val dE = abs(utm.easting - row[3].toDouble())
            val dN = abs(utm.northing - row[4].toDouble())
            if (dE > 0.001 || dN > 0.001) "(${row[0]}, ${row[1]}): ΔE=${dE * 1000} mm, ΔN=${dN * 1000} mm" else null
        }

        failures.shouldBeEmpty()
    }

    @Test
    fun `S008 RF-1 la UTM sobre el elipsoide internacional de 1924 (ED50) coincide con pyproj a menos de 1 mm`() {
        val vectors = GeodesyFixtures.rows("ed50_utm.csv").filter { it[2].isNotEmpty() }
        vectors.size shouldBeGreaterThan 150

        val failures = vectors.mapNotNull { row ->
            val utm = Utm.forward(row[2].toDouble(), row[3].toDouble(), zone = row[4].toInt(), ellipsoid = Ellipsoid.INTERNATIONAL_1924)
            val dE = abs(utm.easting - row[5].toDouble())
            val dN = abs(utm.northing - row[6].toDouble())
            if (dE > 0.001 || dN > 0.001) "(${row[2]}, ${row[3]}): ΔE=${dE * 1000} mm, ΔN=${dN * 1000} mm" else null
        }

        failures.shouldBeEmpty()
    }

    @Test
    fun `S008 RF-13 RF-14 el factor de escala y la convergencia coinciden con pyproj, tambien con huso forzado`() {
        val vectors = GeodesyFixtures.rows("factores_tm.csv")
        vectors.size shouldBeGreaterThan 200

        val failures = vectors.mapNotNull { row ->
            val factors = Utm.factors(row[1].toDouble(), row[2].toDouble(), row[3].toInt(), ellipsoid(row[0]))
            val dk = abs(factors.scale - row[4].toDouble())
            val dGammaArcSec = abs(Math.toDegrees(factors.convergenceRad) - row[5].toDouble()) * 3600
            if (dk > 1e-9 || dGammaArcSec > 0.01) "(${row.take(4)}): Δk=$dk, Δγ=$dGammaArcSec″" else null
        }

        failures.shouldBeEmpty()
    }

    @Test
    fun `S008 RF-13 en el meridiano central el factor de escala es 0,9996 y la convergencia 0`() {
        val factors = Utm.factors(40.0, -3.0, 30)

        factors.scale shouldBe (0.9996 plusOrMinus 1e-12)
        factors.convergenceRad shouldBe (0.0 plusOrMinus 1e-15)
    }

    @Test
    fun `S008 RF-15 el radio medio de Gauss es la media geometrica de los radios de curvatura`() {
        val a = Ellipsoid.GRS80.semiMajorAxisM
        val e2 = Ellipsoid.GRS80.eccentricitySquared
        val sin2 = Math.sin(Math.toRadians(40.0)).let { it * it }
        val meridian = a * (1 - e2) / Math.pow(1 - e2 * sin2, 1.5)
        val primeVertical = a / sqrt(1 - e2 * sin2)

        Geodesy.gaussMeanRadius(40.0, Ellipsoid.GRS80) shouldBe (sqrt(meridian * primeVertical) plusOrMinus 1e-6)
        // En el ecuador, √(a²(1−e²)) = b·a/b… = a·√(1−e²).
        Geodesy.gaussMeanRadius(0.0, Ellipsoid.GRS80) shouldBe (a * sqrt(1 - e2) plusOrMinus 1e-6)
    }

    @Test
    fun `S008 RF-1 el elipsoide internacional de 1924 es el de ED50`() {
        Ellipsoid.INTERNATIONAL_1924.semiMajorAxisM shouldBe 6_378_388.0
        Ellipsoid.INTERNATIONAL_1924.inverseFlattening shouldBe 297.0
    }
}
