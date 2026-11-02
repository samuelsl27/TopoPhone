package es.topophone.core.survey

import es.topophone.core.model.alignment.section.HeightKind
import es.topophone.core.model.alignment.slope.ManualEdge
import es.topophone.core.model.alignment.slope.SlopeIssue
import es.topophone.core.model.alignment.slope.SlopeRule
import es.topophone.core.model.alignment.slope.SlopeSide
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** Los taludes que no se guardan, con el motivo (S051/RF-3). */
class SlopeValidatorTest {
    private fun rule(
        side: SlopeSide = SlopeSide.RIGHT,
        from: Double = 0.0,
        to: Double = 100.0,
        cut: Double = 1.5,
        fill: Double = 2.0,
        manual: ManualEdge? = null,
    ) = SlopeRule(side, from, to, cut, fill, manualEdge = manual)

    private fun check(rule: SlopeRule, others: List<SlopeRule> = emptyList()) = SlopeValidator.validate(rule, 0.0, 420.0, others)

    @Test
    fun `S051 RF-3 un talud correcto se acepta`() {
        check(rule()).shouldBeNull()
    }

    @Test
    fun `S051 RF-3 una pendiente cero negativa o que no es un numero se rechaza con el lado`() {
        check(rule(cut = 0.0)) shouldBe SlopeIssue.InvalidRatio(SlopeSide.RIGHT, 0.0)
        check(rule(fill = -2.0)) shouldBe SlopeIssue.InvalidRatio(SlopeSide.RIGHT, -2.0)
        check(rule(side = SlopeSide.LEFT, cut = Double.NaN)).let {
            (it as SlopeIssue.InvalidRatio).side shouldBe SlopeSide.LEFT
        }
        check(rule(fill = Double.POSITIVE_INFINITY)).let { (it is SlopeIssue.InvalidRatio) shouldBe true }
    }

    @Test
    fun `S051 RF-3 dos rangos que se pisan en el mismo lado se rechazan y en lados distintos o tocandose no`() {
        val other = rule(from = 0.0, to = 100.0)

        check(rule(from = 50.0, to = 150.0), listOf(other)) shouldBe SlopeIssue.OverlappingRanges(SlopeSide.RIGHT, 50.0, 150.0)
        check(rule(side = SlopeSide.LEFT, from = 50.0, to = 150.0), listOf(other)).shouldBeNull()
        check(rule(from = 100.0, to = 200.0), listOf(other)).shouldBeNull()
    }

    @Test
    fun `S051 RF-3 un rango con el final antes del inicio o fuera del eje se rechaza`() {
        check(rule(from = 80.0, to = 20.0)) shouldBe SlopeIssue.ReversedRange(80.0, 20.0)
        check(rule(from = -1.0)) shouldBe SlopeIssue.ChainageOutsideAxis(-1.0)
        check(rule(to = 500.0)) shouldBe SlopeIssue.ChainageOutsideAxis(500.0)
    }

    @Test
    fun `S051 RF-2 un borde manual que no es un numero se rechaza`() {
        check(rule(manual = ManualEdge(3.5, Double.NaN, HeightKind.ABSOLUTE))) shouldBe SlopeIssue.InvalidManualEdge
        check(rule(manual = ManualEdge(Double.POSITIVE_INFINITY, 1.0, HeightKind.RELATIVE))) shouldBe SlopeIssue.InvalidManualEdge
        check(rule(manual = ManualEdge(3.5, -0.07, HeightKind.RELATIVE))).shouldBeNull()
    }

    @Test
    fun `S051 RF-2 el borde manual de la derecha tiene que quedar a la derecha del eje y el de la izquierda a la izquierda`() {
        check(rule(manual = ManualEdge(-3.5, 0.0, HeightKind.RELATIVE))) shouldBe SlopeIssue.InvalidManualEdge
        check(rule(side = SlopeSide.LEFT, manual = ManualEdge(3.5, 0.0, HeightKind.RELATIVE))) shouldBe SlopeIssue.InvalidManualEdge
        check(rule(side = SlopeSide.LEFT, manual = ManualEdge(-3.5, 0.0, HeightKind.RELATIVE))).shouldBeNull()
    }
}
