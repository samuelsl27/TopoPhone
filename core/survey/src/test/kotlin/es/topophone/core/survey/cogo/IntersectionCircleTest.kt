package es.topophone.core.survey.cogo

import es.topophone.core.model.cogo.IntersectionSide
import es.topophone.core.model.geometry.DistanceMode
import es.topophone.core.model.project.ProjectCrs
import es.topophone.core.survey.cogo.CogoFixtures.LENGTH_TOLERANCE_M
import io.kotest.assertions.withClue
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test

/** Intersección de dos distancias: las dos soluciones a izquierda y derecha, tangentes y sin solución (S038/RF-13, RF-14, RF-17). */
class IntersectionCircleTest {

    private val grid = CogoScale.forFirst(CogoPoint(0.0, 0.0), ProjectCrs.ETRS89_GEOGRAPHIC, DistanceMode.GRID)
    private val origin = CogoPoint(0.0, 0.0)

    private fun solutions(result: IntersectionResult) = result.shouldBeInstanceOf<IntersectionResult.Solutions>().list

    @Test
    fun `S038 RF-14 las soluciones y los motivos coinciden con la referencia en todos los sistemas y modos`() {
        val cases = IntersectionFixtures.cases("dd")
        cases.size shouldBe 3 * 2 * 12 // 6 fijos y 6 aleatorios por sistema y modo

        for (case in cases) {
            val result = Intersection.distanceDistance(case.first, case.second, case.value1, case.value2, case.scale)

            withClue(case.label) {
                if (case.count == 0) {
                    result.shouldBeInstanceOf<IntersectionResult.NoSolution>().reason.name shouldBe case.reason
                } else {
                    val found = solutions(result)
                    found.size shouldBe case.count
                    found.zip(case.solutions).forEach { (solution, expected) ->
                        solution.side.name shouldBe expected.label
                        solution.easting shouldBe (expected.easting plusOrMinus LENGTH_TOLERANCE_M)
                        solution.northing shouldBe (expected.northing plusOrMinus LENGTH_TOLERANCE_M)
                        solution.distance1 shouldBe (expected.t1 plusOrMinus LENGTH_TOLERANCE_M)
                        solution.distance2 shouldBe (expected.t2 plusOrMinus LENGTH_TOLERANCE_M)
                    }
                }
            }
        }
    }

    @Test
    fun `S038 RF-14 dos circunferencias que se cortan dan una solucion a la izquierda y otra a la derecha del sentido de A a B`() {
        // A en el origen, B al este: la izquierda del sentido A a B es el norte.
        val found = solutions(Intersection.distanceDistance(origin, CogoPoint(100.0, 0.0), 60.0, 80.0, grid))

        found.map { it.side } shouldBe listOf(IntersectionSide.LEFT, IntersectionSide.RIGHT)
        found[0].easting shouldBe (36.0 plusOrMinus 1e-9)
        found[0].northing shouldBe (48.0 plusOrMinus 1e-9)
        found[1].easting shouldBe (36.0 plusOrMinus 1e-9)
        found[1].northing shouldBe (-48.0 plusOrMinus 1e-9)
    }

    @Test
    fun `S038 RF-14 con las circunferencias tangentes hay una sola solucion`() {
        val outside = solutions(Intersection.distanceDistance(origin, CogoPoint(100.0, 0.0), 40.0, 60.0, grid)).single()
        val inside = solutions(Intersection.distanceDistance(origin, CogoPoint(100.0, 0.0), 150.0, 50.0, grid)).single()
        val nearlyTangent = solutions(Intersection.distanceDistance(origin, CogoPoint(100.0, 0.0), 40.0, 60.0 - 5e-7, grid)).single()

        outside.side shouldBe IntersectionSide.SINGLE
        outside.easting shouldBe (40.0 plusOrMinus 1e-9)
        outside.northing shouldBe (0.0 plusOrMinus 1e-9)
        inside.easting shouldBe (150.0 plusOrMinus 1e-9) // tangentes por dentro, más allá de B
        nearlyTangent.side shouldBe IntersectionSide.SINGLE
    }

    @Test
    fun `S038 RF-17 circunferencias que no se cortan, concentricas o una dentro de otra no dan solucion y se dice por que`() {
        fun reason(r1: Double, r2: Double, b: CogoPoint = CogoPoint(100.0, 0.0)) =
            Intersection.distanceDistance(origin, b, r1, r2, grid).shouldBeInstanceOf<IntersectionResult.NoSolution>().reason

        reason(30.0, 30.0) shouldBe NoSolutionReason.TOO_FAR
        reason(40.0, 60.0 - 1e-5) shouldBe NoSolutionReason.TOO_FAR
        reason(300.0, 100.0) shouldBe NoSolutionReason.TOO_CLOSE
        reason(10.0, 50.0, b = CogoPoint(10.0, 0.0)) shouldBe NoSolutionReason.TOO_CLOSE
        reason(50.0, 70.0, b = CogoPoint(0.0, 0.0)) shouldBe NoSolutionReason.CONCENTRIC
        reason(50.0, 50.0, b = CogoPoint(0.0, 0.0)) shouldBe NoSolutionReason.CONCENTRIC
    }

    @Test
    fun `S038 RF-5 en terreno las distancias escritas son de terreno y se pasan a cuadricula con el factor del primer punto`() {
        val case = IntersectionFixtures.cases("dd").first { it.name == "dd_general" && it.mode == DistanceMode.GROUND }
        val gridCase = IntersectionFixtures.cases("dd").first {
            it.name == "dd_general" &&
                it.mode == DistanceMode.GRID &&
                it.crs == case.crs
        }

        val ground = solutions(Intersection.distanceDistance(case.first, case.second, case.value1, case.value2, case.scale))
        val plain =
            solutions(Intersection.distanceDistance(gridCase.first, gridCase.second, gridCase.value1, gridCase.value2, gridCase.scale))

        ground[0].easting shouldBe (plain[0].easting plusOrMinus LENGTH_TOLERANCE_M)
        (ground[0].distance1 > plain[0].distance1) shouldBe true
    }
}
