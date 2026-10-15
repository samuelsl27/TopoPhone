package es.topophone.core.geodesy

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import kotlin.math.abs
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

/** UTM ETRS89 frente a pyproj/PROJ (S002/RF-36, S002/RF-37; constitución nº 2: ≤ 1 mm). */
class UtmTest {

    private val toleranceM = 0.001

    @Test
    fun `S002 RF-36 las coordenadas UTM coinciden con pyproj a menos de 1 mm en los husos 29, 30 y 31`() {
        val vectors = GeodesyFixtures.rows("utm_etrs89.csv")
        vectors.size shouldBeGreaterThan 300

        val failures = vectors.mapNotNull { row ->
            val (lat, lon) = row[0].toDouble() to row[1].toDouble()
            val utm = Utm.forward(lat, lon)
            val dE = abs(utm.easting - row[3].toDouble())
            val dN = abs(utm.northing - row[4].toDouble())
            when {
                utm.zone != row[2].toInt() -> "huso ${utm.zone} ≠ ${row[2]} en ($lat, $lon)"
                dE > toleranceM || dN > toleranceM -> "($lat, $lon): ΔE=${dE * 1000} mm, ΔN=${dN * 1000} mm"
                else -> null
            }
        }

        failures.shouldBeEmpty()
    }

    @ParameterizedTest
    @CsvSource(
        "-11.9999999, 29",
        "-9.0, 29",
        "-6.0000001, 29",
        "-6.0, 30",
        "-3.7038, 30",
        "-0.0000001, 30",
        "0.0, 31",
        "4.5, 31",
        "5.9999999, 31",
        "-15.4, 28",
        "6.0, 32",
    )
    fun `S002 RF-36 el huso es el que corresponde a la longitud`(longitude: Double, expected: Int) {
        Utm.zoneFor(longitude) shouldBe expected
    }

    @Test
    fun `S002 RF-37 el meridiano central da Este 500000 m y el ecuador Norte 0`() {
        val utm = Utm.forward(0.0, -3.0)

        utm.easting shouldBe 500_000.0
        abs(utm.northing) shouldBe 0.0
    }
}
