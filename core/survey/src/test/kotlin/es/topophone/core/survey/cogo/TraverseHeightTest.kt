package es.topophone.core.survey.cogo

import es.topophone.core.model.cogo.LegKind
import es.topophone.core.model.cogo.TraverseLeg
import es.topophone.core.model.geometry.DistanceMode
import es.topophone.core.model.project.ProjectCrs
import es.topophone.core.survey.cogo.CogoFixtures.LENGTH_TOLERANCE_M
import io.kotest.assertions.withClue
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** La altura de los puntos de una poligonal: solo con altura de inicio y desnivel escrito en todos los tramos (S038/RF-11). */
class TraverseHeightTest {

    private val grid = CogoScale.forFirst(CogoPoint(0.0, 0.0), ProjectCrs.ETRS89_GEOGRAPHIC, DistanceMode.GRID)

    private fun leg(dh: Double?) = TraverseLeg(LegKind.AZIMUTH, 0.0, 10.0, dh)

    @Test
    fun `S038 RF-11 las alturas de la referencia salen del inicio y los desniveles en todos los sistemas y modos`() {
        TraverseFixtures.cases.size shouldBe 3 * 2 * 11 // 8 fijas y 3 aleatorias por sistema y modo

        for (case in TraverseFixtures.cases) {
            val result = Traverse.compute(case.start, case.reference, case.legs, case.scale)

            withClue(case.label) {
                result.steps.zip(case.expected).forEach { (step, expected) ->
                    if (expected.third == null) {
                        step.height.shouldBeNull()
                    } else {
                        step.height!! shouldBe (expected.third!! plusOrMinus LENGTH_TOLERANCE_M)
                    }
                }
            }
        }
    }

    @Test
    fun `S038 RF-11 un desnivel positivo o negativo se suma a la altura del punto anterior`() {
        val result = Traverse.compute(CogoPoint(0.0, 0.0, height = 650.0), null, listOf(leg(2.5), leg(-4.0), leg(0.0)), grid)

        result.steps.map { it.height } shouldBe listOf(652.5, 648.5, 648.5)
    }

    @Test
    fun `S038 RF-11 un desnivel vacio no se toma por cero y ese punto y los siguientes quedan sin altura`() {
        val result = Traverse.compute(CogoPoint(0.0, 0.0, height = 650.0), null, listOf(leg(1.0), leg(null), leg(2.0)), grid)

        result.steps.map { it.height } shouldBe listOf(651.0, null, null)
    }

    @Test
    fun `S038 RF-11 si el punto de inicio no tiene altura ningun punto la tiene aunque haya desniveles`() {
        val result = Traverse.compute(CogoPoint(0.0, 0.0), null, listOf(leg(1.0), leg(2.0)), grid)

        result.steps.map { it.height } shouldBe listOf(null, null)
    }
}
