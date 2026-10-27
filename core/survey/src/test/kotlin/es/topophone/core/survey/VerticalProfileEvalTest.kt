package es.topophone.core.survey

import es.topophone.core.model.alignment.VerticalCurve
import es.topophone.core.model.alignment.VerticalProfile
import es.topophone.core.model.alignment.VerticalVertex
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** La cota y la pendiente de la rasante frente a `fixtures/ejes/` (S049/RF-18, RF-20, constitución nº 2: ≤ 1 mm). */
class VerticalProfileEvalTest {

    @Test
    fun `S049 RF-20 la cota y la pendiente de cada PK esperado coinciden a menos de 1 mm`() {
        var checked = 0
        for (p in AlignmentFixtures.points().filter { it.elevation != null }) {
            val eval = VerticalProfileEval(AlignmentFixtures.profileOf(p.axis).shouldNotBeNull())
            val value = eval.at(p.chainage).shouldNotBeNull()
            value.elevation shouldBe (p.elevation!! plusOrMinus 1e-6)
            value.slopePercent shouldBe (p.slopePct!! plusOrMinus 1e-6)
            checked++
        }
        (checked > 90) shouldBe true
    }

    @Test
    fun `S049 RF-20 en el acuerdo parabolico y en el circular la rasante es continua con su pendiente`() {
        val eval = VerticalProfileEval(AlignmentFixtures.profileOf("Eje A").shouldNotBeNull())
        // Acuerdos: parábola de 100 m en el PK 150 (100 a 200) y círculo de R 2000 en el 300 (hacia 278 a 322).
        for (edge in listOf(100.0, 200.0, 300.0 - 21.66, 300.0 + 21.66)) {
            val before = eval.at(edge - 0.01).shouldNotBeNull()
            val after = eval.at(edge + 0.01).shouldNotBeNull()
            after.elevation shouldBe (before.elevation plusOrMinus 0.01)
            after.slope shouldBe (before.slope plusOrMinus 0.001)
        }
    }

    @Test
    fun `S049 RF-20 sin acuerdo la rasante es la recta entre vertices`() {
        val eval =
            VerticalProfileEval(VerticalProfile(listOf(VerticalVertex(0.0, 10.0), VerticalVertex(100.0, 12.0), VerticalVertex(300.0, 8.0))))

        eval.at(50.0).shouldNotBeNull().elevation shouldBe (11.0 plusOrMinus 1e-12)
        eval.at(50.0).shouldNotBeNull().slopePercent shouldBe (2.0 plusOrMinus 1e-12)
        eval.at(100.0).shouldNotBeNull().elevation shouldBe (12.0 plusOrMinus 1e-12)
        eval.at(200.0).shouldNotBeNull().slopePercent shouldBe (-2.0 plusOrMinus 1e-12)
    }

    @Test
    fun `S049 RF-21 un PK fuera de la rasante no tiene cota`() {
        val eval = VerticalProfileEval(AlignmentFixtures.profileOf("Eje B").shouldNotBeNull())

        eval.at(1000.0).shouldBeNull()
        eval.at(1381.0).shouldBeNull()
        eval.at(1000.5).shouldNotBeNull().elevation shouldBe (50.0 plusOrMinus 1e-9)
        eval.at(1380.5).shouldNotBeNull().elevation shouldBe (49.0 plusOrMinus 1e-9)
    }

    @Test
    fun `S049 RF-10 una rasante con PK repetidos no se puede evaluar`() {
        val repeated = VerticalProfile(listOf(VerticalVertex(0.0, 1.0), VerticalVertex(0.0, 2.0), VerticalVertex(5.0, 3.0)))

        shouldThrow<IllegalArgumentException> { VerticalProfileEval(repeated) }
    }

    @Test
    fun `S049 RF-20 una parabola en cresta pasa por debajo del vertice`() {
        val crest = VerticalProfile(
            listOf(VerticalVertex(0.0, 0.0), VerticalVertex(100.0, 5.0, VerticalCurve.Parabola(40.0)), VerticalVertex(200.0, 0.0)),
        )

        // En el vértice una parábola de 40 m con Δg = −0,1 baja L·Δg/8 = 0,5 m.
        VerticalProfileEval(crest).at(100.0).shouldNotBeNull().elevation shouldBe (4.5 plusOrMinus 1e-9)
    }
}
