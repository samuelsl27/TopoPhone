package es.topophone.core.survey.cogo

import es.topophone.core.model.cogo.IntersectionSide
import es.topophone.core.model.geometry.DistanceMode
import es.topophone.core.model.project.ProjectCrs
import es.topophone.core.survey.cogo.CogoFixtures.LENGTH_TOLERANCE_M
import io.kotest.assertions.withClue
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.math.PI
import org.junit.jupiter.api.Test

/** Intersecciones de dos direcciones y de una distancia con una dirección (S038/RF-15, RF-16, RF-17). */
class IntersectionLinesTest {

    private val grid = CogoScale.forFirst(CogoPoint(0.0, 0.0), ProjectCrs.ETRS89_GEOGRAPHIC, DistanceMode.GRID)
    private val origin = CogoPoint(0.0, 0.0)

    private fun solutions(result: IntersectionResult) = result.shouldBeInstanceOf<IntersectionResult.Solutions>().list

    private fun check(case: IntersectionCase, result: IntersectionResult) {
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

    @Test
    fun `S038 RF-15 direccion-direccion coincide con la referencia en todos los sistemas y modos`() {
        val cases = IntersectionFixtures.cases("bb")
        cases.size shouldBe 3 * 2 * 11 // 5 fijos y 6 aleatorios por sistema y modo

        for (case in cases) {
            check(case, Intersection.directionDirection(case.first, case.second, case.value1, case.value2, case.scale))
        }
    }

    @Test
    fun `S038 RF-16 distancia-direccion coincide con la referencia en todos los sistemas y modos`() {
        val cases = IntersectionFixtures.cases("db")
        cases.size shouldBe 3 * 2 * 10 // 4 fijos y 6 aleatorios por sistema y modo

        for (case in cases) {
            check(case, Intersection.distanceDirection(case.first, case.second, case.value1, case.value2, case.scale))
        }
    }

    @Test
    fun `S038 RF-15 dos direcciones que se cortan dan la solucion con la distancia desde cada punto`() {
        // Desde (0,0) al nordeste y desde (100,0) al noroeste se cruzan en (50, 50).
        val found = solutions(Intersection.directionDirection(origin, CogoPoint(100.0, 0.0), PI / 4, 7 * PI / 4, grid)).single()

        found.side shouldBe IntersectionSide.SINGLE
        found.easting shouldBe (50.0 plusOrMinus 1e-9)
        found.northing shouldBe (50.0 plusOrMinus 1e-9)
        found.distance1 shouldBe (Math.hypot(50.0, 50.0) plusOrMinus 1e-9)
        found.distance2 shouldBe (Math.hypot(50.0, 50.0) plusOrMinus 1e-9)
        found.behind shouldBe false
    }

    @Test
    fun `S038 RF-15 si la solucion queda detras de un punto la distancia es negativa y se marca`() {
        // Desde (0,0) hacia el sudoeste: la recta pasa por (50, 50) pero detrás del punto.
        val found = solutions(Intersection.directionDirection(origin, CogoPoint(100.0, 0.0), 5 * PI / 4, 7 * PI / 4, grid)).single()

        found.easting shouldBe (50.0 plusOrMinus 1e-9)
        found.northing shouldBe (50.0 plusOrMinus 1e-9)
        (found.distance1 < 0.0) shouldBe true
        (found.distance2 > 0.0) shouldBe true
        found.behind shouldBe true
    }

    @Test
    fun `S038 RF-17 direcciones paralelas o coincidentes no dan solucion`() {
        fun reason(second: CogoPoint, a1: Double, a2: Double) =
            Intersection.directionDirection(origin, second, a1, a2, grid).shouldBeInstanceOf<IntersectionResult.NoSolution>().reason

        reason(CogoPoint(100.0, 0.0), 0.7, 0.7) shouldBe NoSolutionReason.PARALLEL
        reason(CogoPoint(100.0, 0.0), 0.7, 0.7 + PI) shouldBe NoSolutionReason.PARALLEL // opuestas
        reason(CogoPoint(100 * Math.sin(0.7), 100 * Math.cos(0.7)), 0.7, 0.7) shouldBe NoSolutionReason.PARALLEL // sobre la misma recta
    }

    @Test
    fun `S038 RF-16 distancia-direccion da dos soluciones ordenadas por su distancia al segundo punto`() {
        // Circunferencia de radio 50 en el origen y recta hacia el este a 30 m al norte: corta en x = -40 y x = 40.
        val found = solutions(Intersection.distanceDirection(origin, CogoPoint(-100.0, 30.0), 50.0, PI / 2, grid))

        found.map { it.side } shouldBe listOf(IntersectionSide.FIRST, IntersectionSide.SECOND)
        found[0].easting shouldBe (-40.0 plusOrMinus 1e-9)
        found[1].easting shouldBe (40.0 plusOrMinus 1e-9)
        found.map { it.northing }.forEach { it shouldBe (30.0 plusOrMinus 1e-9) }
        found[0].distance2 shouldBe (60.0 plusOrMinus 1e-9)
        found[1].distance2 shouldBe (140.0 plusOrMinus 1e-9)
        found.forEach { it.distance1 shouldBe 50.0 }
    }

    @Test
    fun `S038 RF-16 una solucion detras del segundo punto lleva distancia negativa y se marca`() {
        // La recta sale de (-100, 30) hacia el oeste y los cortes (x = -40 y x = 40) quedan detrás de ese punto.
        val found = solutions(Intersection.distanceDirection(origin, CogoPoint(-100.0, 30.0), 50.0, 3 * PI / 2, grid))

        found[0].distance2 shouldBe (-140.0 plusOrMinus 1e-9)
        found[1].distance2 shouldBe (-60.0 plusOrMinus 1e-9)
        found.all { it.behind } shouldBe true
    }

    @Test
    fun `S038 RF-16 distancia-direccion tangente da una sola solucion y la recta que no llega no da ninguna`() {
        val tangent = solutions(Intersection.distanceDirection(origin, CogoPoint(-100.0, 50.0), 50.0, PI / 2, grid)).single()
        val missing = Intersection.distanceDirection(origin, CogoPoint(-100.0, 60.0), 50.0, PI / 2, grid)

        tangent.side shouldBe IntersectionSide.SINGLE
        tangent.easting shouldBe (0.0 plusOrMinus 1e-9)
        tangent.northing shouldBe (50.0 plusOrMinus 1e-9)
        missing.shouldBeInstanceOf<IntersectionResult.NoSolution>().reason shouldBe NoSolutionReason.LINE_MISSES
    }
}
