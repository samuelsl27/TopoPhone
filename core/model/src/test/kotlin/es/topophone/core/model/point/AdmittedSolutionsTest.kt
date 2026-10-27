package es.topophone.core.model.point

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** Los tipos de solución admitidos y su efecto en el control de precisión (S047/RF-1 a RF-4, RF-6, RF-7). */
class AdmittedSolutionsTest {

    @Test
    fun `S047 RF-2 por defecto solo se admite FIX`() {
        PrecisionControl().admitted shouldBe AdmittedSolutions.FIX_ONLY
        AdmittedSolutions.FIX_ONLY.types shouldBe setOf(SolutionType.FIX)
        AdmittedSolutions.FIX_ONLY.onlyFix shouldBe true
        AdmittedSolutions.ALL.types shouldBe setOf(SolutionType.FIX, SolutionType.FLOAT, SolutionType.SINGLE)
        AdmittedSolutions.ALL.onlyFix shouldBe false
    }

    @Test
    fun `S047 los tipos van de mejor a peor y el mejor admitido es el primero de ellos`() {
        SolutionType.entries shouldBe listOf(SolutionType.FIX, SolutionType.FLOAT, SolutionType.SINGLE)
        AdmittedSolutions.of(setOf(SolutionType.SINGLE, SolutionType.FLOAT)).best shouldBe SolutionType.FLOAT
        AdmittedSolutions.ALL.best shouldBe SolutionType.FIX
    }

    @Test
    fun `S047 RF-3 sin ningun tipo se queda en solo FIX`() {
        AdmittedSolutions.of(emptySet()) shouldBe AdmittedSolutions.FIX_ONLY
    }

    @Test
    fun `S047 RF-3 desmarcar el ultimo tipo no se permite`() {
        AdmittedSolutions.FIX_ONLY.toggle(SolutionType.FIX) shouldBe AdmittedSolutions.Toggle.LastOne
        AdmittedSolutions.of(setOf(SolutionType.FLOAT)).toggle(SolutionType.FLOAT) shouldBe AdmittedSolutions.Toggle.LastOne
    }

    @Test
    fun `S047 RF-3 desmarcar uno de varios lo quita`() {
        val result = AdmittedSolutions.ALL.toggle(SolutionType.FLOAT)
        result shouldBe AdmittedSolutions.Toggle.Changed(AdmittedSolutions.of(setOf(SolutionType.FIX, SolutionType.SINGLE)))
    }

    @Test
    fun `S047 RF-4 marcar FLOAT o SINGLE pide confirmacion y no cambia nada hasta aceptarla`() {
        AdmittedSolutions.FIX_ONLY.toggle(SolutionType.FLOAT) shouldBe AdmittedSolutions.Toggle.NeedsConfirmation(SolutionType.FLOAT)
        AdmittedSolutions.FIX_ONLY.toggle(SolutionType.SINGLE) shouldBe AdmittedSolutions.Toggle.NeedsConfirmation(SolutionType.SINGLE)
        AdmittedSolutions.FIX_ONLY.types shouldBe setOf(SolutionType.FIX)
        AdmittedSolutions.FIX_ONLY.with(SolutionType.FLOAT).types shouldBe setOf(SolutionType.FIX, SolutionType.FLOAT)
    }

    @Test
    fun `S047 RF-4 marcar FIX no pide confirmacion`() {
        val onlyFloat = AdmittedSolutions.of(setOf(SolutionType.FLOAT))
        onlyFloat.toggle(SolutionType.FIX) shouldBe
            AdmittedSolutions.Toggle.Changed(AdmittedSolutions.of(setOf(SolutionType.FIX, SolutionType.FLOAT)))
    }

    @Test
    fun `S047 RF-5 se codifica y se decodifica ignorando lo desconocido`() {
        AdmittedSolutions.ALL.encode() shouldBe "FIX,FLOAT,SINGLE"
        AdmittedSolutions.decode("FIX,FLOAT") shouldBe AdmittedSolutions.of(setOf(SolutionType.FIX, SolutionType.FLOAT))
        AdmittedSolutions.decode("FLOAT,DGPS, xx") shouldBe AdmittedSolutions.of(setOf(SolutionType.FLOAT))
        AdmittedSolutions.decode("DGPS") shouldBe AdmittedSolutions.FIX_ONLY
        AdmittedSolutions.decode("") shouldBe AdmittedSolutions.FIX_ONLY
        AdmittedSolutions.decode(null) shouldBe null
    }

    @Test
    fun `S047 RF-6 el control solo deja pasar los tipos admitidos`() {
        val onlyFloat = PrecisionControl(admitted = AdmittedSolutions.of(setOf(SolutionType.FLOAT)))
        onlyFloat.check(SolutionType.FLOAT, 0.1, 0.1).shouldBeEmpty()
        onlyFloat.check(SolutionType.FIX, 0.1, 0.1) shouldBe setOf(PrecisionFailure.NOT_FIX)
        onlyFloat.check(SolutionType.SINGLE, 0.1, 0.1) shouldBe setOf(PrecisionFailure.NOT_FIX)
    }

    @Test
    fun `S047 RF-7 una solucion sin tipo no se admite nunca`() {
        PrecisionControl(admitted = AdmittedSolutions.ALL).check(null, 0.1, 0.1) shouldBe setOf(PrecisionFailure.NOT_FIX)
    }

    @Test
    fun `S047 RF-7 el tipo no admitido se suma a los limites incumplidos`() {
        val control = PrecisionControl(horizontalLimitM = 0.02, verticalLimitM = 0.03)
        control.check(SolutionType.FLOAT, horizontalM = 0.05, verticalM = 0.04) shouldBe
            setOf(PrecisionFailure.NOT_FIX, PrecisionFailure.HORIZONTAL, PrecisionFailure.VERTICAL)
    }

    @Test
    fun `S047 RF-15 la calidad de un punto guarda los tipos admitidos y las descartadas por tipo, y los puntos de antes no los tienen`() {
        val old = PointQuality("FIX", 24, 0.6, 1.0, 0.01, 0.02, MeasurementMode.INSTANT, 1, 0, fromReplay = false)
        old.admitted shouldBe null
        old.epochsDiscardedByType shouldBe null
        val now = old.copy(admitted = AdmittedSolutions.ALL, epochsDiscardedByType = 7)
        now.admitted shouldBe AdmittedSolutions.ALL
        now.epochsDiscardedByType shouldBe 7
    }
}
