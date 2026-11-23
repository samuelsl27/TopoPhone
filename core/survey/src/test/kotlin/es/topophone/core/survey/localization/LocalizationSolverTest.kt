package es.topophone.core.survey.localization

import es.topophone.core.model.localization.LocalizationPair
import es.topophone.core.model.localization.VerticalKind
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test

/** El cálculo completo de la localización: parte horizontal y vertical, residuos y avisos (S039/RF-7 a RF-13). */
class LocalizationSolverTest {

    private val points = listOf(
        441000.0 to 4472900.0,
        441800.0 to 4473500.0,
        440300.0 to 4473100.0,
        441400.0 to 4472200.0,
        440900.0 to 4473900.0,
    )

    /** Pares con un desplazamiento horizontal fijo y la altura `h + 0,25 + 3e-6·E − 2e-6·N` (un plano). */
    private fun pairs(count: Int, vertical: Boolean = true, heights: Boolean = true): List<LocalizationPair> =
        points.take(count).mapIndexed { i, (e, n) ->
            val h = 650.0 + i
            LocalizationPair(
                "p$i", "id$i", "P$i", e, n, if (heights) h else null,
                e + 100.0, n - 50.0, if (heights) h + 0.25 + 3e-6 * (e - 441000.0) - 2e-6 * (n - 4472900.0) else null,
                useVertical = vertical,
            )
        }

    private fun solved(list: List<LocalizationPair>, fixScale: Boolean = false) =
        LocalizationSolver.solve(list, fixScale).shouldBeInstanceOf<LocalizationOutcome.Solved>().solution

    @Test
    fun `S039 RF-9 con uno o dos pares verticales es un desplazamiento con la media de las diferencias`() {
        val one = solved(pairs(1))
        one.params.verticalKind shouldBe VerticalKind.OFFSET
        one.params.dh shouldBe (0.25 plusOrMinus 1e-12)

        val two = solved(pairs(2))
        two.params.verticalKind shouldBe VerticalKind.OFFSET
        two.params.dh shouldBe ((0.25 + 0.25 + 3e-6 * 800.0 - 2e-6 * 600.0) / 2 plusOrMinus 1e-12)
    }

    @Test
    fun `S039 RF-9 con tres o mas pares no alineados es un plano y no deja residuo vertical`() {
        for (count in 3..5) {
            val s = solved(pairs(count))

            s.params.verticalKind shouldBe VerticalKind.PLANE
            s.params.slopeE shouldBe (3e-6 plusOrMinus 1e-12)
            s.params.slopeN shouldBe (-2e-6 plusOrMinus 1e-12)
            s.verticalAligned shouldBe false
            s.rmsVertical!! shouldBe (0.0 plusOrMinus 1e-9)
        }
    }

    @Test
    fun `S039 RF-9 tres pares verticales alineados se quedan en un desplazamiento y lo avisan`() {
        val aligned = listOf(0.0, 100.0, 250.0).mapIndexed { i, d ->
            LocalizationPair("a$i", "i$i", "A$i", 441000.0 + d, 4472900.0 + d, 650.0, 441100.0 + d, 4472850.0 + d, 650.0 + 0.1 * (i + 1))
        }

        val s = solved(aligned)

        s.params.verticalKind shouldBe VerticalKind.OFFSET
        s.params.slopeE shouldBe 0.0
        s.verticalAligned shouldBe true
    }

    @Test
    fun `S039 RF-5 excluir la componente vertical de un par la quita del ajuste y de los residuos`() {
        val list = pairs(4).mapIndexed { i, p -> if (i == 3) p.copy(useVertical = false) else p }

        val s = solved(list)

        s.residuals[3].vertical shouldBe null
        s.residuals[3].horizontal!! shouldBe (0.0 plusOrMinus 1e-9)
        s.params.verticalKind shouldBe VerticalKind.PLANE
    }

    @Test
    fun `S039 RF-9 sin ninguna altura no hay parte vertical`() {
        val s = solved(pairs(3, heights = false))

        s.params.verticalKind shouldBe VerticalKind.NONE
        s.rmsVertical shouldBe null
    }

    @Test
    fun `S039 RF-10 el RMS y el par con mayor residuo salen de los residuos`() {
        val list = pairs(4).mapIndexed { i, p -> if (i == 2) p.copy(knownE = p.knownE + 0.05) else p }

        val s = solved(list)

        s.worstPairId shouldBe "p2"
        s.rmsHorizontal!! shouldBe (s.residuals.sumOf { (it.horizontal!!) * (it.horizontal!!) } / 4).let { Math.sqrt(it) }
    }

    @Test
    fun `S039 RF-12 sin pares con componente activa no se calcula`() {
        LocalizationSolver.solve(emptyList(), false) shouldBe LocalizationOutcome.Failed(LocalizationFailure.NO_PAIRS)
        LocalizationSolver.solve(pairs(3).map { it.copy(useHorizontal = false, useVertical = false) }, false) shouldBe
            LocalizationOutcome.Failed(LocalizationFailure.NO_PAIRS)
        LocalizationSolver.solve(pairs(3).map { it.copy(confirmed = false) }, false) shouldBe
            LocalizationOutcome.Failed(LocalizationFailure.NO_PAIRS)
    }

    @Test
    fun `S039 RF-12 dos pares con el mismo punto GNSS son degenerados`() {
        val a = pairs(1)[0]
        val b = a.copy(id = "b", pointId = "other", pointName = "B", knownE = 1.0)

        LocalizationSolver.solve(listOf(a, b), false) shouldBe LocalizationOutcome.Failed(LocalizationFailure.DEGENERATE)
    }

    @Test
    fun `S039 RF-8 la escala fija deja la escala en 1 y los residuos muestran la deformacion`() {
        val scaled = points.take(4).mapIndexed { i, (e, n) ->
            val knownE = 441000.0 + (e - 441000.0) * 1.0005
            val knownN = 4472900.0 + (n - 4472900.0) * 1.0005
            LocalizationPair("s$i", "i$i", "S$i", e, n, null, knownE, knownN, null)
        }

        val free = solved(scaled)
        val fixed = solved(scaled, fixScale = true)

        free.params.scale shouldBe (1.0005 plusOrMinus 1e-12)
        free.rmsHorizontal!! shouldBe (0.0 plusOrMinus 1e-9)
        fixed.params.scale shouldBe 1.0
        (fixed.rmsHorizontal!! > 0.1) shouldBe true
    }

    @Test
    fun `S039 RF-13 los umbrales destacan el residuo alto y la escala fuera de limite`() {
        val list = pairs(4).mapIndexed { i, p -> if (i == 1) p.copy(knownN = p.knownN + 0.20) else p }
        val s = solved(list)

        val warnings = s.assess(residualLimitM = 0.03, scaleLimit = 0.001)

        warnings.highPairIds.contains("p1") shouldBe true
        warnings.hasWarnings shouldBe true
        val clean = solved(pairs(4)).assess(0.03, 0.001)
        clean.hasWarnings shouldBe false
        val scaled = solved(
            pairs(4).map {
                it.copy(
                    knownE = 441000.0 + (it.scE - 441000.0) * 1.002,
                    knownN =
                    4472900.0 + (it.scN - 4472900.0) * 1.002,
                )
            },
        )
        scaled.assess(0.03, 0.001).scaleOutOfLimit shouldBe true
    }
}
