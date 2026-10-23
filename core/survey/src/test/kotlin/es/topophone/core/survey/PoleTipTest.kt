package es.topophone.core.survey

import es.topophone.core.geodesy.Ecef
import es.topophone.core.model.point.GeodeticPosition
import es.topophone.core.model.point.PoleVector
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlin.math.cos
import kotlin.math.sin
import org.junit.jupiter.api.Test

/** La punta del jalón desde la antena con la inclinación del ETC, frente a pyproj (S016/RF-13, RF-27; constitución nº 2). */
class PoleTipTest {

    private val vectors = SurveyFixtures.rows("punta_jalon.csv")

    private fun pole(row: List<String>): PoleVector {
        val theta = Math.toRadians(row[3].toDouble())
        val alpha = Math.toRadians(row[4].toDouble())
        return PoleVector(sin(theta) * sin(alpha), sin(theta) * cos(alpha), cos(theta))
    }

    private fun antenna(row: List<String>, withHeight: Boolean = true) =
        GeodeticPosition(row[0].toDouble(), row[1].toDouble(), if (withHeight) row[2].toDouble() else null)

    /** Distancia en metros entre dos posiciones a la misma altura (la de pyproj si [h] no es null). */
    private fun distance(a: GeodeticPosition, b: GeodeticPosition, h: Double) =
        Ecef.fromGeodetic(a.latitudeDeg, a.longitudeDeg, a.ellipsoidalHeightM ?: h)
            .distanceTo(Ecef.fromGeodetic(b.latitudeDeg, b.longitudeDeg, b.ellipsoidalHeightM ?: h))

    @Test
    fun `S016 RF-13 la punta coincide con pyproj a menos de 1 mm`() {
        vectors.size shouldBeGreaterThan 60

        val failures = vectors.mapNotNull { row ->
            val tip = PoleTip.fromAntenna(antenna(row), pole(row), antennaHeightM = row[5].toDouble())
            val expected = GeodeticPosition(row[6].toDouble(), row[7].toDouble(), row[8].toDouble())
            val error = distance(tip, expected, expected.ellipsoidalHeightM!!)
            if (error > 0.001) "(${row.take(6)}): ${error * 1000} mm" else null
        }

        failures.shouldBeEmpty()
    }

    @Test
    fun `S016 RF-13 con 0 grados la punta es la del jalon vertical`() {
        val antenna = GeodeticPosition(40.4168, -3.7038, 700.123)
        val antennaHeight = VerticalPole.antennaHeightM(1.8, ReceiverModel.RS3)

        val tip = PoleTip.fromAntenna(antenna, PoleVector.VERTICAL, antennaHeight)

        tip.latitudeDeg shouldBe (antenna.latitudeDeg plusOrMinus 1e-11)
        tip.longitudeDeg shouldBe (antenna.longitudeDeg plusOrMinus 1e-11)
        tip.ellipsoidalHeightM.shouldNotBeNull() shouldBe ((700.123 - antennaHeight) plusOrMinus 1e-4)
    }

    @Test
    fun `S016 RF-27 sin altura la punta sale solo en planta`() {
        // Usar h = 0 desvía la planta H·sen θ·h/R: con jalones normales y hasta 2000 m, menos de 1 mm.
        val usable = vectors.filter { it[2].toDouble() <= 2_000.0 && it[5].toDouble() <= 3.0 }
        usable.size shouldBeGreaterThan 20

        val failures = usable.mapNotNull { row ->
            val tip = PoleTip.fromAntenna(antenna(row, withHeight = false), pole(row), antennaHeightM = row[5].toDouble())
            tip.ellipsoidalHeightM.shouldBeNull()
            val expected = GeodeticPosition(row[6].toDouble(), row[7].toDouble(), null)
            val error = distance(tip, expected, row[8].toDouble())
            if (error > 0.001) "(${row.take(6)}): ${error * 1000} mm" else null
        }

        failures.shouldBeEmpty()
    }

    @Test
    fun `S016 RF-25 a lo largo del jalon se sube o se baja la distancia pedida`() {
        val row = vectors.first { it[3] == "30.0" && it[4] == "180.0" }
        val antennaHeight = row[5].toDouble()
        val tip = PoleTip.fromAntenna(antenna(row), pole(row), antennaHeight)

        // De la punta, subir el jalón entero devuelve la antena.
        val back = PoleTip.alongPole(tip, pole(row), antennaHeight)

        distance(back, antenna(row), 0.0) shouldBe (0.0 plusOrMinus 1e-4)
    }
}
