package es.topophone.core.survey

import es.topophone.core.model.point.GeodeticPosition
import es.topophone.core.model.point.PointCoordinates
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlin.math.PI
import org.junit.jupiter.api.Test

/**
 * La guía del replanteo: distancia horizontal, diferencias de Norte y Este, rumbo al punto, adelante y derecha y diferencia
 * de cota (S018/RF-6, RF-7, RF-12), frente a `fixtures/replanteo/` calculado con pyproj (constitución nº 2).
 */
class StakeoutGeometryTest {

    private class Row(val c: List<String>) {
        val case get() = c[0]
        val geographic get() = c[1] == "GEOGRAFICO"
        private fun number(i: Int) = c[i].toDouble()
        private fun optional(i: Int) = c[i].takeIf { it.isNotEmpty() }?.toDouble()

        fun coordinates(lat: Int, lon: Int, h: Int, east: Int, north: Int) =
            PointCoordinates(GeodeticPosition(number(lat), number(lon), number(h)), optional(east), optional(north), number(h))

        val design get() = coordinates(2, 3, 4, 8, 9)
        val tip get() = coordinates(5, 6, 7, 10, 11)
        val heading get() = Math.toRadians(number(12))
        val distance get() = number(13)
        val dEast get() = number(14)
        val dNorth get() = number(15)
        val bearingDeg get() = optional(16)
        val ahead get() = number(17)
        val right get() = number(18)
        val dHeight get() = number(19)
    }

    private val rows = SurveyFixtures.rows("replanteo-geometria.csv", folder = "replanteo").map(::Row)

    @Test
    fun `S018 RF-6 la distancia y las diferencias de Norte y Este son las de pyproj a menos de 1 mm`() {
        rows.size shouldBe 30
        rows.forEach { row ->
            val g = StakeoutGeometry.guidance(row.design, row.tip, row.heading)

            g.distanceM shouldBe (row.distance plusOrMinus 0.001)
            g.dEastM shouldBe (row.dEast plusOrMinus 0.001)
            g.dNorthM shouldBe (row.dNorth plusOrMinus 0.001)
        }
    }

    @Test
    fun `S018 RF-7 el rumbo al punto es el de pyproj a menos de 0,001 grados y no existe con distancia cero`() {
        rows.forEach { row ->
            val g = StakeoutGeometry.guidance(row.design, row.tip, row.heading)
            val expected = row.bearingDeg

            if (expected == null) {
                g.bearingRad.shouldBeNull()
            } else {
                Math.toDegrees(g.bearingRad.shouldNotBeNull()) shouldBe (expected plusOrMinus 0.001)
            }
        }
    }

    @Test
    fun `S018 RF-7 adelante y derecha salen del rumbo del usuario`() {
        rows.forEach { row ->
            val g = StakeoutGeometry.guidance(row.design, row.tip, row.heading)

            g.aheadM.shouldNotBeNull() shouldBe (row.ahead plusOrMinus 0.001)
            g.rightM.shouldNotBeNull() shouldBe (row.right plusOrMinus 0.001)
        }
    }

    @Test
    fun `S018 RF-12 la diferencia de cota es la de la punta menos la del diseno`() {
        rows.forEach { row ->
            StakeoutGeometry.guidance(row.design, row.tip, row.heading).dHeightM.shouldNotBeNull() shouldBe (row.dHeight plusOrMinus 1e-9)
        }
    }

    @Test
    fun `S018 RF-12 sin altura en el diseno o en la punta no hay diferencia de cota`() {
        val design = PointCoordinates(GeodeticPosition(40.0, -3.0, 600.0), 440000.0, 4430000.0, 550.0)
        val tip = PointCoordinates(GeodeticPosition(40.0, -3.0, 600.0), 440001.0, 4430001.0, 550.5)

        StakeoutGeometry.guidance(design.copy(height = null), tip, null).dHeightM.shouldBeNull()
        StakeoutGeometry.guidance(design, tip.copy(height = null), null).dHeightM.shouldBeNull()
        StakeoutGeometry.guidance(design, tip, null).dHeightM.shouldNotBeNull() shouldBe (0.5 plusOrMinus 1e-12)
    }

    @Test
    fun `S018 RF-7 sin rumbo del usuario no hay adelante ni derecha pero si distancia y diferencias`() {
        val design = PointCoordinates(GeodeticPosition(40.0, -3.0, 0.0), 100.0, 200.0, null)
        val tip = PointCoordinates(GeodeticPosition(40.0, -3.0, 0.0), 97.0, 196.0, null)

        val g = StakeoutGeometry.guidance(design, tip, headingRad = null)

        g.distanceM shouldBe (5.0 plusOrMinus 1e-12)
        g.dEastM shouldBe (3.0 plusOrMinus 1e-12)
        g.dNorthM shouldBe (4.0 plusOrMinus 1e-12)
        g.aheadM.shouldBeNull()
        g.rightM.shouldBeNull()
    }

    @Test
    fun `S018 RF-7 el rumbo se normaliza entre 0 y 360 grados y adelante se mide hacia donde mira el usuario`() {
        val tip = PointCoordinates(GeodeticPosition(40.0, -3.0, 0.0), 100.0, 100.0, null)
        // El diseño al oeste de la punta: rumbo 270°.
        val west = PointCoordinates(GeodeticPosition(40.0, -3.0, 0.0), 90.0, 100.0, null)

        val facingNorth = StakeoutGeometry.guidance(west, tip, headingRad = 0.0)
        val facingWest = StakeoutGeometry.guidance(west, tip, headingRad = 1.5 * PI)

        Math.toDegrees(facingNorth.bearingRad.shouldNotBeNull()) shouldBe (270.0 plusOrMinus 1e-9)
        facingNorth.aheadM.shouldNotBeNull() shouldBe (0.0 plusOrMinus 1e-9)
        facingNorth.rightM.shouldNotBeNull() shouldBe (-10.0 plusOrMinus 1e-9) // a la izquierda
        facingWest.aheadM.shouldNotBeNull() shouldBe (10.0 plusOrMinus 1e-9) // de frente
        facingWest.rightM.shouldNotBeNull() shouldBe (0.0 plusOrMinus 1e-9)
    }

    @Test
    fun `S018 RF-6 en un SC geografico las diferencias son las del plano local de la punta`() {
        val tip = PointCoordinates(GeodeticPosition(41.3874, 2.1686, 12.345), null, null, 12.345)
        val design = PointCoordinates(GeodeticPosition(41.3874, 2.16869, 12.345), null, null, 12.345)

        val g = StakeoutGeometry.guidance(design, tip, headingRad = 0.0)

        // 0,00009° de longitud a 41,4° son unos 7,5 m al este y nada al norte.
        g.dEastM shouldBe (7.5 plusOrMinus 0.05)
        g.dNorthM shouldBe (0.0 plusOrMinus 0.01)
        Math.toDegrees(g.bearingRad.shouldNotBeNull()) shouldBe (90.0 plusOrMinus 0.01)
    }
}
