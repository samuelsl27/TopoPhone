package es.topophone.core.geodesy

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import kotlin.math.cos
import kotlin.math.sin
import org.junit.jupiter.api.Test

/** Plano tangente local frente a la tubería `cart` + `topocentric` de pyproj (S016/RF-13; constitución nº 2). */
class LocalTangentPlaneTest {

    private val vectors = GeodesyFixtures.rows("punta_jalon.csv")

    /** Desplazamiento de ETC_rev_3 de la antena a la punta: Δ = −H·(sen θ·sen α, sen θ·cos α, cos θ). */
    private fun delta(row: List<String>): Enu {
        val theta = Math.toRadians(row[3].toDouble())
        val alpha = Math.toRadians(row[4].toDouble())
        val h = row[5].toDouble()
        return Enu(-h * sin(theta) * sin(alpha), -h * sin(theta) * cos(alpha), -h * cos(theta))
    }

    private fun antenna(row: List<String>) = GeodeticPoint(row[0].toDouble(), row[1].toDouble(), row[2].toDouble())

    @Test
    fun `S016 RF-13 el desplazamiento coincide con pyproj a menos de 1 mm`() {
        vectors.size shouldBeGreaterThan 60

        val failures = vectors.mapNotNull { row ->
            val d = delta(row)
            val tip = LocalTangentPlane.offset(antenna(row), d.east, d.north, d.up)
            val expected = Ecef.fromGeodetic(row[6].toDouble(), row[7].toDouble(), row[8].toDouble())
            val error = Ecef.fromGeodetic(tip.latitudeDeg, tip.longitudeDeg, tip.ellipsoidalHeightM).distanceTo(expected)
            if (error > 0.001) "(${row.take(6)}): ${error * 1000} mm" else null
        }

        failures.shouldBeEmpty()
    }

    @Test
    fun `S016 RF-13 la diferencia ENU deshace el desplazamiento a menos de 0,1 mm`() {
        vectors.forEach { row ->
            val d = delta(row)
            val origin = antenna(row)

            val back = LocalTangentPlane.between(origin, LocalTangentPlane.offset(origin, d.east, d.north, d.up))

            back.east shouldBe (d.east plusOrMinus 1e-4)
            back.north shouldBe (d.north plusOrMinus 1e-4)
            back.up shouldBe (d.up plusOrMinus 1e-4)
        }
    }

    @Test
    fun `S016 RF-7 la diferencia ENU entre las puntas de pyproj es la de sus desplazamientos`() {
        // Dos filas del mismo origen: la diferencia entre sus puntas es la de sus desplazamientos.
        val madrid = vectors.filter { it[0] == "40.4168" && it[1] == "-3.7038" }
        madrid.size shouldBeGreaterThan 2
        val (a, b) = madrid[1] to madrid[3]
        val tipA = GeodeticPoint(a[6].toDouble(), a[7].toDouble(), a[8].toDouble())
        val tipB = GeodeticPoint(b[6].toDouble(), b[7].toDouble(), b[8].toDouble())

        val enu = LocalTangentPlane.between(tipA, tipB)

        val (da, db) = delta(a) to delta(b)
        enu.east shouldBe ((db.east - da.east) plusOrMinus 1e-3)
        enu.north shouldBe ((db.north - da.north) plusOrMinus 1e-3)
        enu.horizontalM shouldBe (Math.hypot(db.east - da.east, db.north - da.north) plusOrMinus 1e-3)
    }

    @Test
    fun `S016 RF-13 subir y bajar por la normal no cambia la latitud ni la longitud`() {
        val origin = GeodeticPoint(28.27223, -16.64251, 3000.0)

        val below = LocalTangentPlane.offset(origin, 0.0, 0.0, -1.934)

        below.latitudeDeg shouldBe (origin.latitudeDeg plusOrMinus 1e-11)
        below.longitudeDeg shouldBe (origin.longitudeDeg plusOrMinus 1e-11)
        below.ellipsoidalHeightM shouldBe (2998.066 plusOrMinus 1e-6)
    }
}
