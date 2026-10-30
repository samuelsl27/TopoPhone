package es.topophone.core.model.point

import es.topophone.core.model.project.CorrectionsDatum
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** El desplazamiento de base y lo que un punto guarda de él (S020). */
class BaseShiftTest {

    private fun measurement() = Measurement(
        antenna = GeodeticPosition(40.0, -3.0, 700.0),
        poleHeightM = 1.8,
        quality = PointQuality("FIX", 20, 0.6, 1.0, 0.01, 0.02, MeasurementMode.INSTANT, 1, 0, fromReplay = false),
        rawNmea = "",
        outOfTolerance = emptySet(),
        measuredFromMs = 0,
        measuredToMs = 0,
        coordinates = PointCoordinates(GeodeticPosition(40.0, -3.0, 698.066), null, null, null),
    )

    @Test
    fun `S020 RF-5 el modulo del vector es la raiz de la suma de cuadrados`() {
        BaseShiftVector(3.0, 4.0, 12.0).moduleM shouldBe (13.0 plusOrMinus 1e-12)
        BaseShiftVector(0.0, 0.0, 0.0).moduleM shouldBe 0.0
    }

    @Test
    fun `S020 RF-5 hasta 10 m no pide confirmacion`() {
        BaseShiftLimits.classify(0.0) shouldBe BaseShiftClass.OK
        BaseShiftLimits.classify(10.0) shouldBe BaseShiftClass.OK
    }

    @Test
    fun `S020 RF-5 de mas de 10 m a 1000 m pide una confirmacion aparte`() {
        BaseShiftLimits.classify(10.01) shouldBe BaseShiftClass.NEEDS_CONFIRMATION
        BaseShiftLimits.classify(1_000.0) shouldBe BaseShiftClass.NEEDS_CONFIRMATION
    }

    @Test
    fun `S020 RF-5 por encima de 1000 m no se permite`() {
        BaseShiftLimits.classify(1_000.1) shouldBe BaseShiftClass.REJECTED
    }

    @Test
    fun `S020 RF-8 una medida de antes de S020 no tiene desplazamiento`() {
        measurement().shift.shouldBeNull()
    }

    @Test
    fun `S020 RF-8 la medida guarda el vector, el punto conocido y la punta sin desplazar`() {
        val applied = AppliedShift(
            shiftId = "s1",
            knownName = "V-12",
            vector = BaseShiftVector(1.0, -2.0, 0.5),
            unshiftedGround = GeodeticPosition(40.0, -3.0, 698.066),
        )

        measurement().copy(shift = applied).shift shouldBe applied
    }

    @Test
    fun `S020 RF-12 un desplazamiento nuevo es un calculo guardado, activo o no`() {
        val shift = BaseShift(
            id = "s1",
            projectId = "p1",
            createdAtMs = 1L,
            fromReplay = false,
            knownName = "V-12",
            knownPointId = null,
            knownEasting = 440_290.0,
            knownNorthing = 4_474_257.0,
            knownHeightM = null,
            measured = GeodeticPosition(40.4, -3.7, 651.3),
            epochsUsed = 60,
            vector = BaseShiftVector(-2.1, -2.0, 0.5),
            horizontalOnly = true,
            correctionsDatum = CorrectionsDatum.ETRS89,
            active = false,
            base = null,
        )

        shift.copy(active = true).active shouldBe true
        shift.knownHeightM.shouldBeNull()
        shift.horizontalOnly shouldBe true
    }
}
