package es.topophone.core.geodesy.projection

import es.topophone.core.geodesy.Ellipsoid
import es.topophone.core.geodesy.GeodesyFixtures
import es.topophone.core.geodesy.Utm
import es.topophone.core.geodesy.WebMercator
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThan
import kotlin.math.abs
import org.junit.jupiter.api.Test

/**
 * S032 no cambia ningún resultado de la UTM (S008) ni de Web Mercator (S009): siguen dando lo de siempre frente a los vectores de pyproj de
 * esas specs (S032/RF-21).
 */
class ExistingProjectionsUnchangedTest {

    @Test
    fun `S032 RF-21 la UTM sobre ETRS89 sigue coincidiendo con pyproj a menos de 1 mm`() {
        val vectors = GeodesyFixtures.rows("utm_etrs89.csv")
        vectors.size shouldBeGreaterThan 100

        val failures = vectors.mapNotNull { row ->
            val utm = Utm.forward(row[0].toDouble(), row[1].toDouble(), zone = row[2].toInt())
            val dE = abs(utm.easting - row[3].toDouble())
            val dN = abs(utm.northing - row[4].toDouble())
            if (dE > 0.001 || dN > 0.001) "(${row[0]}, ${row[1]}): ΔE=${dE * 1000} mm, ΔN=${dN * 1000} mm" else null
        }

        withClue(failures.joinToString("\n")) { failures.shouldBeEmpty() }
    }

    @Test
    fun `S032 RF-21 el factor de escala y la convergencia de la UTM siguen coincidiendo con pyproj`() {
        val vectors = GeodesyFixtures.rows("factores_tm.csv")
        vectors.size shouldBeGreaterThan 200

        val failures = vectors.mapNotNull { row ->
            val ellipsoid = if (row[0] == "GRS80") Ellipsoid.GRS80 else Ellipsoid.INTERNATIONAL_1924
            val factors = Utm.factors(row[1].toDouble(), row[2].toDouble(), row[3].toInt(), ellipsoid)
            val dk = abs(factors.scale - row[4].toDouble())
            val dGammaArcSeconds = abs(Math.toDegrees(factors.convergenceRad) - row[5].toDouble()) * 3600.0
            if (dk > 1e-9 || dGammaArcSeconds > 0.01) "(${row.take(4)}): Δk=$dk, Δγ=$dGammaArcSeconds″" else null
        }

        withClue(failures.joinToString("\n")) { failures.shouldBeEmpty() }
    }

    @Test
    fun `S032 RF-21 Web Mercator sigue coincidiendo con pyproj a menos de 1 mm y la vuelta a 1e-8 grados`() {
        val vectors = GeodesyFixtures.rows("web_mercator.csv")
        vectors.size shouldBeGreaterThan 50

        val failures = vectors.mapNotNull { row ->
            val (x, y) = WebMercator.forward(row[0].toDouble(), row[1].toDouble())
            val back = WebMercator.inverse(row[2].toDouble(), row[3].toDouble())
            val dx = abs(x - row[2].toDouble())
            val dy = abs(y - row[3].toDouble())
            val dLat = abs(back.latitudeDeg - row[0].toDouble())
            val dLon = abs(back.longitudeDeg - row[1].toDouble())
            if (dx > 0.001 ||
                dy > 0.001 ||
                dLat > 1e-8 ||
                dLon > 1e-8
            ) {
                "(${row[0]}, ${row[1]}): Δx=$dx Δy=$dy Δφ=$dLat Δλ=$dLon"
            } else {
                null
            }
        }

        withClue(failures.joinToString("\n")) { failures.shouldBeEmpty() }
    }
}
