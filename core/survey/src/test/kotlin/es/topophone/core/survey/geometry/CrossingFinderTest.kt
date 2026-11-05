package es.topophone.core.survey.geometry

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** Detección de cruces de un polígono (S026/RF-6). */
class CrossingFinderTest {

    private fun crosses(points: List<Pair<Double, Double>>, closed: Boolean = true) =
        CrossingFinder.crosses(points.map { it.first }.toDoubleArray(), points.map { it.second }.toDoubleArray(), closed)

    @Test
    fun `S026 RF-6 un cuadrado no se cruza`() {
        crosses(listOf(0.0 to 0.0, 10.0 to 0.0, 10.0 to 10.0, 0.0 to 10.0)) shouldBe false
    }

    @Test
    fun `S026 RF-6 un lazo en ocho se cruza`() {
        crosses(listOf(0.0 to 0.0, 10.0 to 10.0, 10.0 to 0.0, 0.0 to 10.0)) shouldBe true
    }

    @Test
    fun `S026 RF-6 un poligono concavo no se cruza`() {
        crosses(listOf(0.0 to 0.0, 20.0 to 0.0, 20.0 to 5.0, 10.0 to 5.0, 10.0 to 15.0, 0.0 to 15.0)) shouldBe false
    }

    @Test
    fun `S026 RF-6 los segmentos vecinos que comparten un vertice no cuentan, tampoco el cierre con el primero`() {
        crosses(listOf(0.0 to 0.0, 10.0 to 0.0, 5.0 to 8.0)) shouldBe false
    }

    @Test
    fun `S026 RF-6 una poligonal abierta tambien detecta que se corta a si misma`() {
        crosses(listOf(0.0 to 0.0, 10.0 to 10.0, 10.0 to 0.0, 0.0 to 10.0), closed = false) shouldBe true
        crosses(listOf(0.0 to 0.0, 10.0 to 0.0, 10.0 to 10.0, 0.0 to 10.0), closed = false) shouldBe false
    }

    @Test
    fun `S026 RF-6 el cierre que corta a un segmento intermedio es un cruce`() {
        crosses(listOf(0.0 to 0.0, 10.0 to 0.0, 10.0 to 10.0, 5.0 to -5.0)) shouldBe true
    }

    @Test
    fun `S026 RF-6 un trazado de miles de vertices sin cruces se revisa sin recorrer todos los pares`() {
        val n = 20000
        val points = (0 until n).map { it.toDouble() to (if (it % 2 == 0) 0.0 else 1.0) }
        val start = System.nanoTime()
        crosses(points, closed = false) shouldBe false
        val elapsedMs = (System.nanoTime() - start) / 1_000_000
        (elapsedMs < 1000) shouldBe true
    }
}
