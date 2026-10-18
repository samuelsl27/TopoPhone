package es.topophone.core.geodesy

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import kotlin.math.abs
import kotlin.math.cos
import org.junit.jupiter.api.Test

/** ETRS89 → ED50 con la rejilla oficial del IGN (EPSG:15932) frente a PROJ (S008/RF-7, RF-8; constitución nº 2). */
class Ed50Test {

    private val rows = GeodesyFixtures.rows("ed50_utm.csv")

    /** 1″ de latitud ≈ 30,87 m; de longitud, por el coseno de la latitud. */
    private fun metres(dLatDeg: Double, dLonDeg: Double, latDeg: Double): Pair<Double, Double> =
        dLatDeg * 111_120.0 to dLonDeg * 111_120.0 * cos(Math.toRadians(latDeg))

    @Test
    fun `S008 RF-7 las geograficas ED50 coinciden con las de PROJ a menos de 1 mm`() {
        val inside = rows.filter { it[2].isNotEmpty() && it[4] == "30" }
        inside.size shouldBeGreaterThan 50

        val failures = inside.mapNotNull { row ->
            val lat = row[0].toDouble()
            val ed50 = Ed50.fromEtrs89(lat, row[1].toDouble()) ?: return@mapNotNull "(${row[0]}, ${row[1]}) sin ED50"
            val (dN, dE) = metres(ed50.first - row[2].toDouble(), ed50.second - row[3].toDouble(), lat)
            if (abs(dN) > 0.001 || abs(dE) > 0.001) "(${row[0]}, ${row[1]}): ΔN=${dN * 1000} mm ΔE=${dE * 1000} mm" else null
        }

        failures.shouldBeEmpty()
    }

    @Test
    fun `S008 RF-7 ED50 en UTM 29, 30 y 31 coincide con PROJ a menos de 1 mm`() {
        val inside = rows.filter { it[2].isNotEmpty() }
        inside.size shouldBeGreaterThan 150

        val failures = inside.mapNotNull { row ->
            val ed50 = Ed50.fromEtrs89(row[0].toDouble(), row[1].toDouble()) ?: return@mapNotNull "(${row[0]}, ${row[1]}) sin ED50"
            val utm = Utm.forward(ed50.first, ed50.second, zone = row[4].toInt(), ellipsoid = Ellipsoid.INTERNATIONAL_1924)
            val dE = abs(utm.easting - row[5].toDouble())
            val dN = abs(utm.northing - row[6].toDouble())
            if (dE > 0.001 || dN > 0.001) "(${row[0]}, ${row[1]}) huso ${row[4]}: ΔE=${dE * 1000} mm ΔN=${dN * 1000} mm" else null
        }

        failures.shouldBeEmpty()
    }

    @Test
    fun `S008 RF-7 ida y vuelta ETRS89 a ED50 a ETRS89 queda a menos de 0,1 mm`() {
        val failures = rows.filter { it[2].isNotEmpty() && it[4] == "30" }.mapNotNull { row ->
            val (lat, lon) = row[0].toDouble() to row[1].toDouble()
            val ed50 = Ed50.fromEtrs89(lat, lon).shouldNotBeNull()
            val back = Ed50.toEtrs89(ed50.first, ed50.second).shouldNotBeNull()
            val (dN, dE) = metres(back.first - lat, back.second - lon, lat)
            if (abs(dN) > 0.0001 || abs(dE) > 0.0001) "($lat, $lon): ΔN=${dN * 1000} mm ΔE=${dE * 1000} mm" else null
        }

        failures.shouldBeEmpty()
    }

    @Test
    fun `S008 RF-8 fuera de la rejilla no hay ED50 y no se extrapola`() {
        val outside = rows.filter { it[2].isEmpty() }
        outside.size shouldBeGreaterThan 0

        outside.forEach { row -> Ed50.fromEtrs89(row[0].toDouble(), row[1].toDouble()).shouldBeNull() }
        Ed50.fromEtrs89(28.1, -15.4).shouldBeNull() // Canarias
    }
}
