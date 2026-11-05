package es.topophone.core.survey.geometry

import es.topophone.core.survey.geometry.GeometryFixtures.number
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** Perímetro, área y cruces en cuadrícula frente a pyproj y a un cálculo aparte (S026/RF-1, RF-6). */
class GeometryAreaTest {

    private fun info(case: String): GeometryInfo = (
        GeometryCalculator.of(
            GeometryFixtures.vertices(case),
            GeometryFixtures.closed(case),
            SegmentScale.Grid,
        ) as GeometryResult.Info
        ).info

    @Test
    fun `S026 RF-1 el perimetro y el area de cada poligono coinciden con la referencia`() {
        for (case in listOf("concavo", "cruces", "parcela")) {
            val total = GeometryFixtures.total(case, "grid")
            val result = info(case)
            result.perimeter!! shouldBe (total.number("perimetro") plusOrMinus 1e-6)
            result.perimeter!! shouldBe (result.length2D plusOrMinus 1e-9)
            result.area!! shouldBe (total.number("area") plusOrMinus 1e-6)
        }
    }

    @Test
    fun `S026 RF-1 el area del poligono concavo es la del dibujo`() {
        info("concavo").area!! shouldBe (20000.0 plusOrMinus 1e-6)
    }

    @Test
    fun `S026 RF-1 el area no depende del sentido ni del vertice de partida`() {
        val vertices = GeometryFixtures.vertices("parcela")
        val area = info("parcela").area!!
        val reversed = (GeometryCalculator.of(vertices.reversed(), true, SegmentScale.Grid) as GeometryResult.Info).info.area!!
        val rotated = (
            GeometryCalculator.of(
                vertices.drop(2) + vertices.take(2),
                true,
                SegmentScale.Grid,
            ) as GeometryResult.Info
            ).info.area!!
        reversed shouldBe (area plusOrMinus 1e-6)
        rotated shouldBe (area plusOrMinus 1e-6)
    }

    @Test
    fun `S026 RF-6 con cruces avisa y da el area con signo de la referencia`() {
        val result = info("cruces")
        result.crosses shouldBe true
        // El lazo: dos triangulos de sentidos opuestos que se restan.
        result.area!! shouldBe (GeometryFixtures.total("cruces", "grid").number("area") plusOrMinus 1e-6)
        info("concavo").crosses shouldBe false
        info("parcela").crosses shouldBe false
    }

    @Test
    fun `S026 RF-1 una linea abierta no tiene cruces ni area`() {
        info("quebrada").crosses shouldBe false
        info("quebrada").area shouldBe null
    }

    @Test
    fun `S026 RF-1 el area conserva el milimetro cuadrado con valores grandes de Este y Norte`() {
        val square = listOf(
            GeometryVertex(easting = 500000.0, northing = 4500000.0),
            GeometryVertex(easting = 500100.0, northing = 4500000.0),
            GeometryVertex(easting = 500100.0, northing = 4500100.0),
            GeometryVertex(easting = 500000.0, northing = 4500100.0),
        )
        (GeometryCalculator.of(square, true, SegmentScale.Grid) as GeometryResult.Info).info.area!! shouldBe (10000.0 plusOrMinus 1e-9)
    }
}
