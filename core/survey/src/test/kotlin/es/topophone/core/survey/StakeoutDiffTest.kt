package es.topophone.core.survey

import es.topophone.core.model.point.GeodeticPosition
import es.topophone.core.model.point.PointCoordinates
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** Lo que se guarda con un punto replanteado: lo medido menos el diseño, sin redondear (S018/RF-28). */
class StakeoutDiffTest {

    private fun coordinates(east: Double?, north: Double?, height: Double?, lat: Double = 40.4168, lon: Double = -3.7038) =
        PointCoordinates(GeodeticPosition(lat, lon, height?.plus(51.0)), east, north, height)

    private val design = coordinates(440_290.45805, 4_474_257.38189, 648.19)

    @Test
    fun `S018 RF-28 las diferencias son medido menos diseno en Este, Norte y cota`() {
        val measured = coordinates(440_290.47805, 4_474_257.35189, 648.17)

        val ref = StakeoutDiff.ref("design-1", "12", design, measured)

        ref.dEastM shouldBe (0.02 plusOrMinus 1e-9)
        ref.dNorthM shouldBe (-0.03 plusOrMinus 1e-9)
        ref.dHeightM!! shouldBe (-0.02 plusOrMinus 1e-9)
        ref.dHorizontalM shouldBe (Math.hypot(0.02, 0.03) plusOrMinus 1e-9)
    }

    @Test
    fun `S018 RF-28 la referencia lleva el diseno por valor tal como era`() {
        val ref = StakeoutDiff.ref("design-1", "12", design, coordinates(440_290.0, 4_474_257.0, 648.0))

        ref.designId shouldBe "design-1"
        ref.designName shouldBe "12"
        ref.design shouldBe design
    }

    @Test
    fun `S018 RF-28 las diferencias no se redondean`() {
        val measured = coordinates(440_290.458051234567, 4_474_257.381889999, 648.190000123)

        val ref = StakeoutDiff.ref("d", "1", design, measured)

        ref.dEastM shouldBe (measured.easting!! - design.easting!!)
        ref.dNorthM shouldBe (measured.northing!! - design.northing!!)
        ref.dHeightM shouldBe (measured.height!! - design.height!!)
    }

    @Test
    fun `S018 RF-28 sin altura en el diseno o en lo medido no hay diferencia de cota`() {
        StakeoutDiff.ref("d", "1", design.copy(height = null), coordinates(440_291.0, 4_474_258.0, 648.0)).dHeightM.shouldBeNull()
        StakeoutDiff.ref("d", "1", design, coordinates(440_291.0, 4_474_258.0, null)).dHeightM.shouldBeNull()
    }

    @Test
    fun `S018 RF-28 con el mismo punto todo vale cero`() {
        val ref = StakeoutDiff.ref("d", "1", design, design)

        ref.dEastM shouldBe 0.0
        ref.dNorthM shouldBe 0.0
        ref.dHorizontalM shouldBe 0.0
        ref.dHeightM shouldBe 0.0
    }

    @Test
    fun `S018 RF-28 en un SC geografico son las del plano local del punto medido`() {
        val geoDesign = coordinates(null, null, 12.0, lat = 41.3874, lon = 2.16869)
        val measured = coordinates(null, null, 12.0, lat = 41.3874, lon = 2.1686)

        val ref = StakeoutDiff.ref("d", "1", geoDesign, measured)

        // Lo medido está al oeste del diseño unos 7,5 m: medido menos diseño sale negativo en Este.
        ref.dEastM shouldBe (-7.5 plusOrMinus 0.05)
        ref.dNorthM shouldBe (0.0 plusOrMinus 0.01)
    }
}
