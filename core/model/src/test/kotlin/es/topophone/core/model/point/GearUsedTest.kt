package es.topophone.core.model.point

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** El jalón y la antena con los que se midió un punto (S046/RF-24). */
class GearUsedTest {

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
    fun `S046 RF-24 una medida de antes de S046 no tiene equipo`() {
        measurement().gear.shouldBeNull()
    }

    @Test
    fun `S046 RF-24 la medida guarda una copia del jalon y de la antena sin redondear`() {
        val gear = GearUsed("Jalón corto", PoleKind.SLANT, 1.95, "Reach RS4/RS4 Pro", 0.095, 2.044076)
        val saved = measurement().copy(poleHeightM = 1.949076, gear = gear)

        saved.gear shouldBe gear
        saved.gear!!.poleEnteredM shouldBe 1.95
        saved.gear!!.antennaOffsetM shouldBe 0.095
        saved.poleHeightM shouldBe 1.949076
    }

    @Test
    fun `S046 RF-16 los tipos de medida son vertical e inclinada`() {
        PoleKind.entries shouldBe listOf(PoleKind.VERTICAL, PoleKind.SLANT)
    }
}
