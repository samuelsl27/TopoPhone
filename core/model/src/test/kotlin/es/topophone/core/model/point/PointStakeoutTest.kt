package es.topophone.core.model.point

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** El punto replanteado: origen, referencia al diseño y diferencias (S018/RF-27, RF-28, RF-32, RF-33). */
class PointStakeoutTest {

    private val ground = GeodeticPosition(40.4168, -3.7038, 699.423)
    private val coordinates = PointCoordinates(ground, 440290.45805, 4474257.38189, 648.19)
    private val measurement = Measurement(
        antenna = GeodeticPosition(40.4168, -3.7038, 701.357),
        poleHeightM = 1.8,
        quality = PointQuality("FIX", 28, 0.5, 1.0, 0.01, 0.015, MeasurementMode.INSTANT, 1, 0, fromReplay = false),
        rawNmea = "",
        outOfTolerance = emptySet(),
        measuredFromMs = 1_000,
        measuredToMs = 1_200,
        coordinates = coordinates,
    )
    private val design = PointCoordinates(GeodeticPosition(40.41680018, -3.7038, 699.428), 440290.45821, 4474257.40188, 648.195)
    private val ref = StakeoutRef(
        designId = "design-1",
        designName = "12",
        design = design,
        dEastM = -0.00016,
        dNorthM = -0.01999,
        dHorizontalM = 0.01999,
        dHeightM = -0.005,
    )

    private fun point(staked: StakeoutRef?) = Point.measured("p1", "proj", "12_stkd", "esquina", measurement, 5_000, staked)

    @Test
    fun `S018 RF-27 un punto medido con referencia al diseno es un punto replanteado`() {
        val point = point(ref)

        point.origin shouldBe PointOrigin.STAKED
        point.staked shouldBe ref
        point.measurement.shouldNotBeNull() shouldBe measurement
        point.coordinates shouldBe coordinates
    }

    @Test
    fun `S018 RF-27 sin referencia el punto medido sigue siendo MEASURED y sin replanteo`() {
        val point = Point.measured("p1", "proj", "1", "", measurement, 5_000)

        point.origin shouldBe PointOrigin.MEASURED
        point.staked.shouldBeNull()
    }

    @Test
    fun `S018 RF-27 el medido y el replanteado tienen medida y el manual no`() {
        PointOrigin.MEASURED.hasMeasurement shouldBe true
        PointOrigin.STAKED.hasMeasurement shouldBe true
        PointOrigin.MANUAL.hasMeasurement shouldBe false
    }

    @Test
    fun `S018 RF-28 la referencia guarda el diseno por valor y las diferencias sin redondear`() {
        ref.design.easting shouldBe 440290.45821
        ref.dEastM shouldBe -0.00016
        ref.dNorthM shouldBe -0.01999
        ref.dHorizontalM shouldBe 0.01999
        ref.dHeightM shouldBe -0.005
        ref.copy(dHeightM = null).dHeightM.shouldBeNull()
    }

    @Test
    fun `S019 RF-13 una referencia sin los datos nuevos no tiene cota usada ni tolerancias ni veredicto`() {
        ref.designElevationM.shouldBeNull()
        ref.toleranceHM.shouldBeNull()
        ref.toleranceVM.shouldBeNull()
        ref.withinTolerance.shouldBeNull()
    }

    @Test
    fun `S019 RF-13 la referencia guarda la cota usada y las dos tolerancias sin redondear`() {
        val checked = ref.copy(designElevationM = 698.25, toleranceHM = 0.025, toleranceVM = 0.03, withinTolerance = true)

        checked.designElevationM shouldBe 698.25
        checked.toleranceHM shouldBe 0.025
        checked.toleranceVM shouldBe 0.03
        checked.withinTolerance shouldBe true
        checked.dHeightM shouldBe -0.005
        point(checked).staked shouldBe checked
    }

    @Test
    fun `S019 RF-26 sin tolerancias guardadas el replanteo esta sin evaluar`() {
        ref.notEvaluated shouldBe true
        ref.copy(toleranceHM = 0.025, toleranceVM = 0.03, withinTolerance = false).notEvaluated shouldBe false
        ref.copy(toleranceHM = 0.025, toleranceVM = 0.03, withinTolerance = true).notEvaluated shouldBe false
    }

    @Test
    fun `S019 RF-14 un replanteo sin cota tiene dentro de la tolerancia solo por la horizontal`() {
        val noElevation = ref.copy(
            dHeightM = null,
            designElevationM = null,
            toleranceHM = 0.025,
            toleranceVM = 0.03,
            withinTolerance = true,
        )

        noElevation.hasElevation shouldBe false
        noElevation.notEvaluated shouldBe false
        ref.hasElevation shouldBe true
    }

    @Test
    fun `S018 RF-32 el resumen de un punto replanteado lleva su origen y sin replanteos por defecto`() {
        val summary = point(ref).summary()

        summary.origin shouldBe PointOrigin.STAKED
        summary.stakeCount shouldBe 0
    }

    @Test
    fun `S018 RF-33 el resumen puede llevar cuantas veces se ha replanteado el punto`() {
        val summary = Point.measured("p2", "proj", "12", "", measurement, 5_000).summary().copy(stakeCount = 3)

        summary.stakeCount shouldBe 3
    }
}
