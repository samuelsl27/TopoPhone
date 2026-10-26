package es.topophone.core.model.point

import es.topophone.core.model.cad.CadEntityKind
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** Los modos de línea, el origen del punto de diseño y lo que guarda un replanteo de un dibujo (S041/RF-3, RF-27, RF-28, RF-29). */
class LineStakeoutTypesTest {

    private val design = PointCoordinates(GeodeticPosition(40.4168, -3.7038, 699.423), 440290.45805, 4474257.38189, 648.19)

    private fun ref(source: StakeSource = StakeSource.ProjectPoint) =
        StakeoutRef("design-1", "12", design, -0.0001, -0.0199, 0.0199, -0.005, source = source)

    @Test
    fun `S041 RF-3 hay cinco modos de linea en el orden en que se ofrecen`() {
        LineStakeoutMode.entries.map { it.name } shouldBe listOf("BY_SEGMENTS", "TO_LINE", "ALONG", "SEGMENT_AB", "INTERVALS")
    }

    @Test
    fun `S041 RF-3 el modo de la primera vez es A la linea y se reconoce por su nombre`() {
        LineStakeoutMode.DEFAULT shouldBe LineStakeoutMode.TO_LINE
        LineStakeoutMode.fromName("INTERVALS") shouldBe LineStakeoutMode.INTERVALS
        LineStakeoutMode.fromName("raro") shouldBe LineStakeoutMode.TO_LINE
        LineStakeoutMode.fromName(null) shouldBe LineStakeoutMode.TO_LINE
    }

    @Test
    fun `S041 RF-6 y RF-20 solo por segmentos e intervalos replantean un punto fijo`() {
        LineStakeoutMode.entries.filter { it.fixedPoint } shouldBe listOf(LineStakeoutMode.BY_SEGMENTS, LineStakeoutMode.INTERVALS)
    }

    @Test
    fun `S041 RF-28 un replanteo de antes no tiene origen de dibujo ni datos de linea`() {
        val plain = ref()

        plain.source shouldBe StakeSource.ProjectPoint
        plain.mode.shouldBeNull()
        plain.chainageM.shouldBeNull()
        plain.lateralOffsetM.shouldBeNull()
        plain.dLateralM.shouldBeNull()
        plain.fromDrawing shouldBe false
    }

    @Test
    fun `S041 RF-28 y RF-29 el replanteo de un elemento guarda dibujo capa tipo modo PK desplazamiento y error lateral`() {
        val source = StakeSource.CadElement("calle.dxf", "Eje", CadEntityKind.POLYLINE)

        val staked = ref(source).copy(mode = LineStakeoutMode.ALONG, chainageM = 45.0, lateralOffsetM = -1.5, dLateralM = 0.012)

        staked.fromDrawing shouldBe true
        (staked.source as StakeSource.CadElement).layerName shouldBe "Eje"
        staked.chainageM shouldBe 45.0
        staked.dLateralM shouldBe 0.012
        staked shouldBe staked.copy()
    }

    @Test
    fun `S041 RF-27 el nombre de un punto replanteado de una capa es capa_n_stkd con el siguiente numero libre`() {
        StakeoutNames.nextForLayer("Pozos", emptySet()) shouldBe "Pozos_1_stkd"
        StakeoutNames.nextForLayer("Pozos", setOf("Pozos_1_stkd")) shouldBe "Pozos_2_stkd"
        StakeoutNames.nextForLayer("Pozos", setOf("Pozos_1_stkd", "Pozos_3_stkd")) shouldBe "Pozos_2_stkd"
        StakeoutNames.nextForLayer("  Eje ", setOf("Pozos_1_stkd")) shouldBe "Eje_1_stkd"
        StakeoutNames.nextForLayer("Pozos", setOf("Pozos_stkd", "Pozos")) shouldBe "Pozos_1_stkd"
    }

    @Test
    fun `S041 RF-21 hay un motivo para cada entrada que no se aplica`() {
        LineInputError.entries.map { it.name } shouldBe listOf("NOT_A_NUMBER", "OUT_OF_LINE", "SAME_PK", "INTERVAL_NOT_POSITIVE")
    }
}
