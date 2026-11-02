package es.topophone.core.model.alignment.slope

import es.topophone.core.model.alignment.section.HeightKind
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** Los tipos de los taludes (S051/RF-1, RF-2, RF-15). */
class SlopeModelTest {
    private val rule = SlopeRule(SlopeSide.RIGHT, 100.0, 200.0, cutRatio = 1.5, fillRatio = 2.0)

    @Test
    fun `S051 RF-1 el desplazamiento de la izquierda es negativo y el de la derecha positivo`() {
        SlopeSide.LEFT.sign shouldBe -1
        SlopeSide.RIGHT.sign shouldBe 1
    }

    @Test
    fun `S051 RF-1 una regla incluye sus dos extremos y no lo de fuera`() {
        rule.contains(100.0) shouldBe true
        rule.contains(200.0) shouldBe true
        rule.contains(99.999) shouldBe false
        rule.contains(200.001) shouldBe false
    }

    @Test
    fun `S051 RF-1 la relacion depende del tipo de talud`() {
        rule.ratioOf(SlopeKind.CUT) shouldBe 1.5
        rule.ratioOf(SlopeKind.FILL) shouldBe 2.0
    }

    @Test
    fun `S051 RF-2 una regla sin codigo ni borde manual usa el exterior de la seccion`() {
        rule.edgeCode shouldBe null
        rule.manualEdge shouldBe null
        rule.copy(manualEdge = ManualEdge(3.5, -0.07, HeightKind.RELATIVE)).manualEdge?.heightKind shouldBe HeightKind.RELATIVE
    }

    @Test
    fun `S051 RF-15 el talud de un punto replanteado lleva el tipo la relacion la altura y la estaca`() {
        val info = SlopeStakeInfo(SlopeKind.CUT, SlopeSide.LEFT, 1.5, 2.4, -0.012)

        info.stakeOffsetM shouldBe 0.0
        info.copy(stakeOffsetM = 1.0).stakeOffsetM shouldBe 1.0
        info.dHeightM shouldBe -0.012
    }
}
