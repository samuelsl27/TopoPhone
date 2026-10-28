package es.topophone.core.model.point

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** Lo que guarda un replanteo por PK y una medida de "Analizar" (S054/RF-10, RF-13). */
class AxisStakeoutTypesTest {

    private val design = PointCoordinates(GeodeticPosition(40.4168, -3.7038, 699.423), 440290.45805, 4474257.38189, 648.19)

    private fun ref(source: StakeSource = StakeSource.ProjectPoint) =
        StakeoutRef("axis:Eje A:150000", "Eje A", design, -0.0001, -0.0199, 0.0199, -0.005, source = source)

    @Test
    fun `S054 RF-10 un replanteo de antes no tiene eje ni espesor ni diferencia de PK`() {
        val plain = ref()

        plain.fromAlignment shouldBe false
        plain.thicknessM.shouldBeNull()
        plain.dChainageM.shouldBeNull()
    }

    @Test
    fun `S054 RF-10 el replanteo por PK guarda el eje el PK el desplazamiento el espesor y las diferencias`() {
        val staked = ref(StakeSource.Alignment("Eje A")).copy(
            chainageM = 150.0,
            lateralOffsetM = 3.5,
            dLateralM = 0.012,
            dChainageM = -0.034,
            thicknessM = 0.1,
        )

        staked.fromAlignment shouldBe true
        staked.fromDrawing shouldBe false
        (staked.source as StakeSource.Alignment).alignmentName shouldBe "Eje A"
        staked.mode.shouldBeNull()
        staked.dChainageM shouldBe -0.034
        staked.thicknessM shouldBe 0.1
        staked shouldBe staked.copy()
    }

    @Test
    fun `S054 RF-13 la referencia al eje de una medida lleva la diferencia de cota y contra que se midio`() {
        val plain = AxisRef("Eje A", 150.0, 3.5)
        plain.dHeightM.shouldBeNull()
        plain.heightRef.shouldBeNull()

        val analysed = AxisRef("Eje A", 150.0, 3.5, dHeightM = -0.12, heightRef = AxisHeightRef.PROFILE)

        analysed.dHeightM shouldBe -0.12
        analysed.heightRef shouldBe AxisHeightRef.PROFILE
        AxisHeightRef.entries.map { it.name } shouldBe listOf("PROFILE", "SURFACE")
    }

    @Test
    fun `S054 RF-13 una medida desde Analizar es un punto medido con su referencia al eje y sin replanteo`() {
        val axis = AxisRef("Eje A", 150.0, 3.5, dHeightM = 0.02, heightRef = AxisHeightRef.SURFACE)

        val measurement = Measurement(
            antenna = GeodeticPosition(40.4168, -3.7038, 701.357),
            poleHeightM = 1.8,
            quality = PointQuality("FIX", 28, 0.5, 1.0, 0.01, 0.015, MeasurementMode.INSTANT, 1, 0, fromReplay = false),
            rawNmea = "",
            outOfTolerance = emptySet(),
            measuredFromMs = 1_000,
            measuredToMs = 1_200,
            coordinates = design,
        )

        val point = Point.measured("p1", "proj", "1", "", measurement, createdAtMs = 1L, onAxis = axis)

        point.onAxis shouldBe axis
        point.origin shouldBe PointOrigin.MEASURED
        point.staked.shouldBeNull()
    }

    @Test
    fun `S054 RF-10 el nombre de un punto replanteado de un eje es eje_n_stkd con el siguiente numero libre`() {
        StakeoutNames.nextForLayer("Eje A", emptySet()) shouldBe "Eje A_1_stkd"
        StakeoutNames.nextForLayer("Eje A", setOf("Eje A_1_stkd")) shouldBe "Eje A_2_stkd"
    }
}
