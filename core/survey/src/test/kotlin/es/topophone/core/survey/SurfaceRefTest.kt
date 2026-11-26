package es.topophone.core.survey

import es.topophone.core.model.point.GeodeticPosition
import es.topophone.core.model.point.PointCoordinates
import es.topophone.core.model.point.StakeSource
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** Lo que se guarda con un punto replanteado contra la superficie solo en cota (S062/RF-17, RF-18). */
class SurfaceRefTest {

    private fun measured(height: Double?) =
        PointCoordinates(GeodeticPosition(40.4168, -3.7038, height?.plus(51.0)), 440_123.456, 4_474_321.987, height)

    @Test
    fun `S062 RF-17 el diseno es la posicion medida con la cota de diseno y sin diferencias en planta`() {
        val ref = StakeoutDiff.surfaceRef("p-1", "explanacion", measured(612.388), designElevationM = 612.44, toleranceVM = 0.03)

        ref.designId shouldBe "p-1"
        ref.designName shouldBe "explanacion"
        ref.source shouldBe StakeSource.Surface("explanacion")
        ref.fromSurfaceOnly shouldBe true
        ref.design.easting shouldBe 440_123.456
        ref.design.northing shouldBe 4_474_321.987
        ref.design.height shouldBe 612.44
        ref.designElevationM shouldBe 612.44
        listOf(ref.dEastM, ref.dNorthM, ref.dHorizontalM) shouldBe listOf(0.0, 0.0, 0.0)
        ref.toleranceHM.shouldBeNull()
    }

    @Test
    fun `S062 RF-17 la diferencia es medido menos diseno con terraplen negativo y desmonte positivo`() {
        // Medido más bajo que el diseño: hay que rellenar (terraplén) y la diferencia sale negativa.
        StakeoutDiff.surfaceRef("p", "s", measured(612.388), 612.44, 0.03).dHeightM!! shouldBe (-0.052 plusOrMinus 1e-9)
        // Medido más alto: hay que excavar (desmonte) y sale positiva.
        StakeoutDiff.surfaceRef("p", "s", measured(612.5), 612.44, 0.03).dHeightM!! shouldBe (0.06 plusOrMinus 1e-9)
    }

    @Test
    fun `S062 RF-18 cumple solo con la tolerancia vertical y el limite cuenta como dentro`() {
        StakeoutDiff.surfaceRef("p", "s", measured(612.388), 612.44, 0.03).withinTolerance shouldBe false
        StakeoutDiff.surfaceRef("p", "s", measured(612.46), 612.44, 0.03).withinTolerance shouldBe true
        StakeoutDiff.surfaceRef("p", "s", measured(612.5), 612.5, 0.03).withinTolerance shouldBe true
    }

    @Test
    fun `S062 RF-17 sin altura medida no hay diferencia ni veredicto`() {
        val ref = StakeoutDiff.surfaceRef("p", "s", measured(null), 612.44, 0.03)

        ref.dHeightM.shouldBeNull()
        ref.withinTolerance.shouldBeNull()
    }
}
