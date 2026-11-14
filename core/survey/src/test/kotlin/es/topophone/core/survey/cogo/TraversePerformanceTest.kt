package es.topophone.core.survey.cogo

import es.topophone.core.model.cogo.LegKind
import es.topophone.core.model.cogo.TraverseLeg
import es.topophone.core.model.geometry.DistanceMode
import es.topophone.core.model.project.ProjectCrs
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import java.util.Random
import kotlin.system.measureNanoTime
import org.junit.jupiter.api.Test

/** Una poligonal de 200 tramos se recalcula en menos de 100 ms (S038, requisitos no funcionales; RNF-T13). */
class TraversePerformanceTest {

    private val crs = ProjectCrs.ETRS89_UTM30
    private val start = CogoFixtures.points(crs).getValue("P1")
    private val reference = CogoFixtures.points(crs).getValue("P6")

    private fun legs(count: Int): List<TraverseLeg> {
        val random = Random(38)
        val kinds = LegKind.entries
        return List(count) {
            val kind = kinds[random.nextInt(kinds.size)]
            val value = if (kind.name.startsWith("DEFLECTION")) random.nextDouble() * Math.PI else random.nextDouble() * 2 * Math.PI
            TraverseLeg(kind, value, 5.0 + random.nextDouble() * 80.0, random.nextDouble() * 2 - 1)
        }
    }

    private fun bestOfMillis(mode: DistanceMode, legs: List<TraverseLeg>): Double {
        fun once(): Double = measureNanoTime {
            // Se mide lo que hace falta para recalcular: la escala del primer punto y toda la poligonal con su cierre.
            val scale = CogoScale.forFirst(start, crs, mode)
            Traverse.compute(start, reference, legs, scale, CogoPoint(start.easting, start.northing, start.height))
        } / 1e6
        repeat(3) { once() } // calentamiento de la JVM
        return (1..7).minOf { once() }
    }

    @Test
    fun `S038 RNF-T13 una poligonal de 200 tramos se recalcula en menos de 100 ms en cuadricula y en terreno`() {
        val legs = legs(200)

        val grid = bestOfMillis(DistanceMode.GRID, legs)
        val ground = bestOfMillis(DistanceMode.GROUND, legs)

        (grid < 100.0) shouldBe true
        (ground < 100.0) shouldBe true
    }

    @Test
    fun `S038 RNF-T13 los 200 tramos se calculan todos y dan cierre`() {
        val legs = legs(200)

        val result = Traverse.compute(start, reference, legs, CogoScale.forFirst(start, crs, DistanceMode.GROUND), start)

        result.stoppedAt.shouldBeNull()
        result.steps.size shouldBe 200
        result.closure shouldNotBe null
    }
}
