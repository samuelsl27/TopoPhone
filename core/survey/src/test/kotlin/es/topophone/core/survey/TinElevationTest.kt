package es.topophone.core.survey

import es.topophone.core.model.surface.SurfaceExtent
import es.topophone.core.model.surface.SurfaceTile
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** La cota de una superficie en una posición frente a la referencia calculada aparte (S042/RF-12, RF-22, RF-23). */
class TinElevationTest {

    /** La cota con teselas y directorio, como la dará la app. */
    private fun heightAt(cells: List<TinCell>, e: Double, n: Double): Double? {
        val directory = TinDirectory(cells.map { it.region })
        return directory.candidates(e, n).asList().firstNotNullOfOrNull { TinElevation.at(cells[it].tile, e, n) }
    }

    private fun cellsOf(maxTriangles: Int = TinTiler.MAX_TRIANGLES): List<TinCell> {
        val s = SurfaceFixtures.terrain()
        return TinTiler.tile(s.easting, s.northing, s.height, s.triangles, maxTriangles)
    }

    @Test
    fun `S042 RF-22 la cota dentro de un triangulo coincide con la referencia a menos de 1 mm`() {
        val cells = cellsOf()
        val inside = SurfaceFixtures.queries().filter { it.kind == "dentro" }

        inside.size shouldBe 7
        for (q in inside) heightAt(cells, q.easting, q.northing)!! shouldBe (q.height!! plusOrMinus 1e-3)
    }

    @Test
    fun `S042 RF-22 la cota sobre una arista y sobre un vertice coincide con la referencia`() {
        val cells = cellsOf()

        for (q in SurfaceFixtures.queries().filter { it.kind == "arista" || it.kind == "vertice" }) {
            heightAt(cells, q.easting, q.northing)!! shouldBe (q.height!! plusOrMinus 1e-3)
        }
    }

    @Test
    fun `S042 RF-22 la cota de un vertice es exactamente la del vertice`() {
        val s = SurfaceFixtures.terrain()
        val cells = cellsOf()

        for (i in listOf(0, 6, 17, 24, 41)) heightAt(cells, s.easting[i], s.northing[i])!! shouldBe (s.height[i] plusOrMinus 1e-9)
    }

    @Test
    fun `S042 RF-23 en un hueco y fuera del contorno no hay cota`() {
        val cells = cellsOf()
        val none = SurfaceFixtures.queries().filter { it.kind == "hueco" || it.kind == "fuera" }

        none.size shouldBe 7
        for (q in none) heightAt(cells, q.easting, q.northing).shouldBeNull()
    }

    @Test
    fun `S042 RF-12 el hueco es un hueco con la superficie repartida en muchas teselas`() {
        val cells = cellsOf(maxTriangles = 4)

        (cells.size > 4) shouldBe true
        for (q in SurfaceFixtures.queries()) {
            val z = heightAt(cells, q.easting, q.northing)
            if (q.height == null) z.shouldBeNull() else z!! shouldBe (q.height plusOrMinus 1e-3)
        }
    }

    @Test
    fun `S042 RF-22 la cota es lineal dentro del triangulo y no un plano para toda la superficie`() {
        val tile = SurfaceTile(
            doubleArrayOf(0.0, 10.0, 0.0),
            doubleArrayOf(0.0, 0.0, 10.0),
            doubleArrayOf(100.0, 110.0, 120.0),
            intArrayOf(0, 1, 2),
        )

        TinElevation.at(tile, 0.0, 0.0)!! shouldBe (100.0 plusOrMinus 1e-12)
        TinElevation.at(tile, 5.0, 0.0)!! shouldBe (105.0 plusOrMinus 1e-12)
        TinElevation.at(tile, 0.0, 5.0)!! shouldBe (110.0 plusOrMinus 1e-12)
        TinElevation.at(tile, 5.0, 5.0)!! shouldBe (115.0 plusOrMinus 1e-12)
        TinElevation.at(tile, 2.0, 3.0)!! shouldBe (100.0 + 2.0 + 6.0 plusOrMinus 1e-12)
    }

    @Test
    fun `S042 RF-22 una posicion fuera del triangulo no tiene cota y un triangulo degenerado no cuenta`() {
        val tile = SurfaceTile(
            doubleArrayOf(0.0, 10.0, 0.0, 5.0, 10.0),
            doubleArrayOf(0.0, 0.0, 10.0, 5.0, 10.0),
            doubleArrayOf(100.0, 110.0, 120.0, 0.0, 0.0),
            intArrayOf(0, 1, 2, 0, 3, 4),
        )

        TinElevation.at(tile, 9.0, 9.0).shouldBeNull()
        TinElevation.at(tile, -0.5, 1.0).shouldBeNull()
        TinElevation.at(tile, 6.0, 6.0).shouldBeNull()
    }

    @Test
    fun `S042 RF-22 el directorio da las teselas de una celda y dos en un borde compartido`() {
        val directory = TinDirectory(
            listOf(SurfaceExtent(0.0, 10.0, 0.0, 10.0), SurfaceExtent(10.0, 20.0, 0.0, 10.0), SurfaceExtent(0.0, 10.0, 10.0, 20.0)),
        )

        directory.candidates(5.0, 5.0).toList() shouldBe listOf(0)
        directory.candidates(10.0, 5.0).toList() shouldBe listOf(0, 1)
        directory.candidates(10.0, 10.0).toList() shouldBe listOf(0, 1, 2)
        directory.candidates(30.0, 5.0).toList() shouldBe emptyList()
        directory.near(10.0, 5.0, 1.0).toList() shouldBe listOf(0, 1)
        directory.near(5.0, 5.0, 100.0).toList() shouldBe listOf(0, 1, 2)
    }
}
