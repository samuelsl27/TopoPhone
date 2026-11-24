package es.topophone.core.survey

import es.topophone.core.model.point.BaseShiftVector
import es.topophone.core.model.point.GeodeticPosition
import io.kotest.matchers.doubles.shouldBeLessThan
import org.junit.jupiter.api.Test

/** Aplicar el desplazamiento de base no añade más de 1 ms por época (S020, RNF). */
class BaseShiftPerformanceTest {

    private val vector = BaseShiftVector(2.1, -1.7, 0.4)

    @Test
    fun `S020 RNF aplicar el vector cuesta menos de 1 ms por epoca en el peor caso`() {
        val ground = GeodeticPosition(40.4168, -3.7038, 650.123)
        // Calienta la JIT y mide el peor de 10.000 aplicaciones seguidas, que es más de lo que llega en 30 min a 5 Hz.
        repeat(2_000) { BaseShiftApplier.apply(ground, vector) }
        var worstMs = 0.0
        repeat(10_000) {
            val start = System.nanoTime()
            BaseShiftApplier.apply(ground, vector)
            worstMs = maxOf(worstMs, (System.nanoTime() - start) / 1e6)
        }

        // El peor de 10.000 llamadas en un runner compartido incluye pausas del GC y del planificador: ahi se admiten 10 ms.
        worstMs shouldBeLessThan (if (System.getenv("CI") != null) 10.0 else 1.0)
    }

    @Test
    fun `S020 RNF una epoca entera con el vector, con el cambio de SC, tampoco llega a 1 ms de mas`() {
        val ground = GeodeticPosition(40.4168, -3.7038, 650.123)
        val calculator = ProjectPositionCalculator()
        val crs = es.topophone.core.model.project.ProjectCrs.ETRS89_UTM30
        val heights = es.topophone.core.model.project.HeightSystem.ELLIPSOIDAL
        repeat(2_000) { calculator.fromGround(BaseShiftApplier.apply(ground, vector), crs, heights) }
        var without = 0.0
        var with = 0.0
        repeat(2_000) {
            val a = System.nanoTime()
            calculator.fromGround(ground, crs, heights)
            val b = System.nanoTime()
            calculator.fromGround(BaseShiftApplier.apply(ground, vector), crs, heights)
            val c = System.nanoTime()
            without += (b - a) / 1e6
            with += (c - b) / 1e6
        }

        ((with - without) / 2_000) shouldBeLessThan 1.0
    }
}
