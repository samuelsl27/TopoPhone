package es.topophone.core.survey.localization

import es.topophone.core.model.localization.LocalizationParams
import es.topophone.core.model.localization.VerticalKind
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test

/** Localizar y deslocalizar: la ida y vuelta cierra a menos de 0,1 mm (S039/RF-17, RF-18; constitución nº 2) y 50 pares se calculan en 100 ms (RNF-T13). */
class LocalizationApplierTest {

    private val plane = LocalizationParams(
        centreE = 441026.87,
        centreN = 4472941.74,
        knownCentreE = 426377.54,
        knownCentreN = 4472546.97,
        rotationRad = 0.0035,
        scale = 1.00002,
        hasHorizontal = true,
        verticalKind = VerticalKind.PLANE,
        dh = -7.37,
        slopeE = 3e-6,
        slopeN = -2e-6,
        verticalE = 441026.0,
        verticalN = 4472941.0,
    )

    @Test
    fun `S039 RF-17 localizar y deslocalizar cierra a menos de 0,1 mm con un plano`() {
        val samples = listOf(Triple(441000.0, 4472900.0, 650.0), Triple(445000.0, 4479000.0, 12.5), Triple(430000.0, 4465000.0, -3.0))
        for ((e, n, h) in samples) {
            val (le, ln) = LocalizationApplier.horizontal(plane, e, n)
            val lh = LocalizationApplier.height(plane, e, n, h)
            val (be, bn) = LocalizationApplier.delocalizeHorizontal(plane, le, ln)
            val bh = LocalizationApplier.delocalizeHeight(plane, be, bn, lh)

            be shouldBe (e plusOrMinus 1e-4)
            bn shouldBe (n plusOrMinus 1e-4)
            bh shouldBe (h plusOrMinus 1e-4)
        }
    }

    @Test
    fun `S039 RF-17 una localizacion sin horizontal ni vertical deja las coordenadas como estaban`() {
        val id = LocalizationParams()

        LocalizationApplier.horizontal(id, 1.0, 2.0) shouldBe (1.0 to 2.0)
        LocalizationApplier.height(id, 1.0, 2.0, 3.0) shouldBe 3.0
        LocalizationApplier.delocalizeHorizontal(id, 1.0, 2.0) shouldBe (1.0 to 2.0)
    }

    @Test
    fun `S039 RF-17 una traslacion y un desplazamiento son exactos en los dos sentidos`() {
        val shift = LocalizationParams(
            centreE = 10.0,
            centreN = 20.0,
            knownCentreE = 15.0,
            knownCentreN = 17.0,
            hasHorizontal = true,
            verticalKind = VerticalKind.OFFSET,
            dh = 0.5,
        )

        LocalizationApplier.horizontal(shift, 100.0, 200.0) shouldBe (105.0 to 197.0)
        LocalizationApplier.height(shift, 100.0, 200.0, 30.0) shouldBe 30.5
        LocalizationApplier.delocalizeHorizontal(shift, 105.0, 197.0) shouldBe (100.0 to 200.0)
        LocalizationApplier.delocalizeHeight(shift, 100.0, 200.0, 30.5) shouldBe 30.0
    }

    @Test
    fun `S039 RNF-T13 calcular con 50 pares tarda menos de 100 ms`() {
        val case = LocalizationFixtures.cases.first { it.name == "c2_utm30_con_ruido" }
        val fifty = List(50) { i ->
            val base = case.pairs[i % case.pairs.size]
            val dE = i * 13.0
            val dN = i * -7.0
            base.copy(id = "q$i", scE = base.scE + dE, scN = base.scN + dN, knownE = base.knownE + dE, knownN = base.knownN + dN)
        }
        repeat(50) { LocalizationSolver.solve(fifty, false) }

        val start = System.nanoTime()
        val outcome = LocalizationSolver.solve(fifty, false)
        val elapsedMs = (System.nanoTime() - start) / 1e6

        outcome.shouldBeInstanceOf<LocalizationOutcome.Solved>()
        (elapsedMs < 100.0) shouldBe true
    }
}
