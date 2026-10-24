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

    // S019: la cota usada, las tolerancias y si cumple, frente a fixtures/replanteo/replanteo-informe.csv (pyproj).
    private class Stake(val c: List<String>) {
        private fun number(i: Int) = c[i].toDouble()
        private fun optional(i: Int) = c[i].takeIf { it.isNotEmpty() }?.toDouble()

        val case get() = "${c[0]}/${c[3]}"
        val design get() = PointCoordinates(GeodeticPosition(number(6), number(7), number(8)), optional(9), optional(10), optional(11))
        val measured get() = PointCoordinates(GeodeticPosition(number(12), number(13), number(14)), optional(15), optional(16), number(17))
        val used get() = optional(19)
        val toleranceH get() = optional(20)
        val toleranceV get() = optional(21)
        val dNorth get() = number(22)
        val dEast get() = number(23)
        val dHorizontal get() = number(24)
        val dHeight get() = optional(25)
        val within get() = c[26].takeIf { it.isNotEmpty() }?.let { it == "1" }
    }

    private val stakes = SurveyFixtures.rows("replanteo-informe.csv", folder = "replanteo").map(::Stake)

    private fun ref(stake: Stake) = StakeoutDiff.ref(
        "id-${stake.case}",
        stake.case,
        stake.design,
        stake.measured,
        elevationM = stake.used,
        toleranceHM = stake.toleranceH,
        toleranceVM = stake.toleranceV,
    )

    @Test
    fun `S019 RF-13 las diferencias de Norte, Este y distancia son las de pyproj a menos de 1 mm`() {
        stakes.size shouldBe 30
        stakes.forEach { stake ->
            val ref = ref(stake)

            ref.dNorthM shouldBe (stake.dNorth plusOrMinus 0.001)
            ref.dEastM shouldBe (stake.dEast plusOrMinus 0.001)
            ref.dHorizontalM shouldBe (stake.dHorizontal plusOrMinus 0.001)
        }
    }

    @Test
    fun `S019 RF-13 la diferencia de cota es la medida menos la cota usada a menos de 1 mm`() {
        stakes.forEach { stake ->
            val ref = ref(stake)

            ref.designElevationM shouldBe stake.used
            if (stake.dHeight == null) ref.dHeightM.shouldBeNull() else ref.dHeightM!! shouldBe (stake.dHeight!! plusOrMinus 0.001)
        }
        stakes.count { it.dHeight == null } shouldBe 3 // P4, sin altura de diseno ni cota escrita, en cada proyecto
    }

    @Test
    fun `S019 RF-14 dentro de la tolerancia coincide con el calculo aparte y es nulo sin tolerancias`() {
        stakes.forEach { stake ->
            val ref = ref(stake)

            ref.withinTolerance shouldBe stake.within
            ref.toleranceHM shouldBe stake.toleranceH
            ref.toleranceVM shouldBe stake.toleranceV
        }
        stakes.mapNotNull { it.within }.toSet() shouldBe setOf(true, false)
    }

    @Test
    fun `S019 RF-13 sin pasar la cota usada se mide contra la altura del punto de diseno como en S018`() {
        val measured = coordinates(440_290.47805, 4_474_257.35189, 648.17)

        val ref = StakeoutDiff.ref("design-1", "12", design, measured)

        ref.designElevationM shouldBe 648.19
        ref.dHeightM!! shouldBe (-0.02 plusOrMinus 1e-9)
        ref.toleranceHM.shouldBeNull()
        ref.toleranceVM.shouldBeNull()
        ref.withinTolerance.shouldBeNull()
    }

    @Test
    fun `S019 RF-13 la cota escrita sustituye a la del punto y el diseno se guarda tal como era`() {
        val measured = coordinates(440_290.47805, 4_474_257.35189, 647.0)

        val ref = StakeoutDiff.ref("design-1", "12", design, measured, elevationM = 646.9)

        ref.designElevationM shouldBe 646.9
        ref.dHeightM!! shouldBe (0.1 plusOrMinus 1e-9)
        ref.design shouldBe design
        ref.design.height shouldBe 648.19
    }

    @Test
    fun `S019 RF-13 una cota escrita da diferencia de cota aunque el punto de diseno no tenga altura`() {
        val noHeight = design.copy(height = null)
        val measured = coordinates(440_290.0, 4_474_257.0, 250.2)

        StakeoutDiff.ref("d", "1", noHeight, measured, elevationM = 250.0).dHeightM!! shouldBe (0.2 plusOrMinus 1e-9)
        StakeoutDiff.ref("d", "1", noHeight, measured, elevationM = null).dHeightM.shouldBeNull()
        StakeoutDiff.ref("d", "1", noHeight, measured).designElevationM.shouldBeNull()
    }

    @Test
    fun `S019 RF-14 dentro exige la horizontal y, si hay cota, la vertical`() {
        StakeoutDiff.within(0.020, 0.010, 0.025, 0.03) shouldBe true
        StakeoutDiff.within(0.030, 0.010, 0.025, 0.03) shouldBe false // fuera en horizontal
        StakeoutDiff.within(0.020, 0.045, 0.025, 0.03) shouldBe false // fuera en vertical
        StakeoutDiff.within(0.020, -0.045, 0.025, 0.03) shouldBe false // terraplen fuera
        StakeoutDiff.within(0.020, null, 0.025, 0.03) shouldBe true // sin cota, solo la horizontal
        StakeoutDiff.within(0.030, null, 0.025, 0.03) shouldBe false
    }

    @Test
    fun `S019 RF-14 los limites exactos estan dentro`() {
        StakeoutDiff.within(0.25, 0.25, 0.25, 0.25) shouldBe true
        StakeoutDiff.within(0.25, -0.25, 0.25, 0.25) shouldBe true
        StakeoutDiff.within(0.2500001, 0.0, 0.25, 0.25) shouldBe false
        StakeoutDiff.within(0.0, 0.2500001, 0.25, 0.25) shouldBe false
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
