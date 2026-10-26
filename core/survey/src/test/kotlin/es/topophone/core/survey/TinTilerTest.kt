package es.topophone.core.survey

import es.topophone.core.model.surface.SurfaceTileCodec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import kotlin.random.Random
import org.junit.jupiter.api.Test

/** El reparto de los triángulos de una superficie en teselas con un árbol cuaternario (S042, plan A3). */
class TinTilerTest {

    private fun heightAt(cells: List<TinCell>, e: Double, n: Double): Double? {
        val directory = TinDirectory(cells.map { it.region })
        return directory.candidates(e, n).asList().firstNotNullOfOrNull { TinElevation.at(cells[it].tile, e, n) }
    }

    @Test
    fun `S042 RF-22 una superficie pequena cabe en una sola tesela con la extension entera`() {
        val s = SurfaceFixtures.terrain()

        val cells = TinTiler.tile(s.easting, s.northing, s.height, s.triangles)

        cells.size shouldBe 1
        cells.single().tile.triangleCount shouldBe 58
        cells.single().region.minEasting shouldBe s.easting.min()
        cells.single().region.maxNorthing shouldBe s.northing.max()
    }

    @Test
    fun `S042 RF-22 sin triangulos no hay teselas y los vertices sueltos no entran`() {
        TinTiler.tile(doubleArrayOf(1.0), doubleArrayOf(1.0), doubleArrayOf(1.0), IntArray(0)) shouldBe emptyList()
        val cells = TinTiler.tile(
            doubleArrayOf(0.0, 10.0, 0.0, 99.0),
            doubleArrayOf(0.0, 0.0, 10.0, 99.0),
            doubleArrayOf(1.0, 2.0, 3.0, 4.0),
            intArrayOf(0, 1, 2),
        )

        cells.single().tile.vertexCount shouldBe 3
    }

    @Test
    fun `S042 RF-22 una hoja no pasa del maximo de triangulos y todo triangulo esta en alguna hoja`() {
        val s = SurfaceFixtures.terrain()

        val cells = TinTiler.tile(s.easting, s.northing, s.height, s.triangles, maxTriangles = 6)

        (cells.size > 1) shouldBe true
        cells.all { it.tile.triangleCount <= 6 } shouldBe true
        (cells.sumOf { it.tile.triangleCount } >= s.triangles.size / 3) shouldBe true
        // Cada triángulo del original aparece en al menos una hoja (se reconoce por la suma de sus tres cotas).
        val inTiles = cells.flatMap { c -> (0 until c.tile.triangleCount).map { heightKey(c.tile, it) } }.toSet()
        for (t in 0 until s.triangles.size / 3) {
            val key = (0 until 3).sumOf { s.height[s.triangles[3 * t + it]] + 3 * s.easting[s.triangles[3 * t + it]] }
            (key in inTiles) shouldBe true
        }
    }

    private fun heightKey(tile: es.topophone.core.model.surface.SurfaceTile, t: Int) =
        (0 until 3).sumOf { tile.height[tile.triangles[3 * t + it]] + 3 * tile.easting[tile.triangles[3 * t + it]] }

    @Test
    fun `S042 RF-22 con muchas hojas la cota es la misma que con una sola`() {
        val s = SurfaceFixtures.terrain()
        val one = TinTiler.tile(s.easting, s.northing, s.height, s.triangles)
        val many = TinTiler.tile(s.easting, s.northing, s.height, s.triangles, maxTriangles = 3)
        val random = Random(7)

        repeat(2_000) {
            val e = random.nextDouble(s.easting.min() - 5, s.easting.max() + 5)
            val n = random.nextDouble(s.northing.min() - 5, s.northing.max() + 5)
            val expected = heightAt(one, e, n)
            val got = heightAt(many, e, n)
            if (expected == null) (got == null) shouldBe true else got!! shouldBe (expected plusOrMinus 1e-9)
        }
    }

    @Test
    fun `S042 RF-22 un triangulo grande que cruza hojas esta en todas las que toca`() {
        val e = DoubleArray(3 + 40) { if (it < 3) doubleArrayOf(0.0, 1000.0, 0.0)[it] else (it - 3) * 25.0 }
        val n = DoubleArray(3 + 40) { if (it < 3) doubleArrayOf(0.0, 0.0, 1000.0)[it] else 10.0 }
        val z = DoubleArray(3 + 40) { 100.0 }
        val small = (0 until 12).flatMap { k -> listOf(3 + k, 4 + k, 5 + k) }.toIntArray()

        val cells = TinTiler.tile(e, n, z, intArrayOf(0, 1, 2) + small, maxTriangles = 4)

        val withBig = cells.count { 1000.0 in it.tile.easting.toList() }
        (withBig > 1) shouldBe true
        heightAt(cells, 900.0, 5.0) shouldBe (100.0 plusOrMinus 1e-9)
    }

    @Test
    fun `S042 RF-22 una hoja que pesa mas de lo permitido se parte aunque tenga pocos triangulos`() {
        val s = SurfaceFixtures.terrain()

        val cells = TinTiler.tile(s.easting, s.northing, s.height, s.triangles, maxTileBytes = 900)

        (cells.size > 1) shouldBe true
        cells.all { SurfaceTileCodec.encodedSize(it.tile) <= 900 } shouldBe true
        SurfaceFixtures.queries().forEach { q ->
            val z = heightAt(cells, q.easting, q.northing)
            if (q.height == null) (z == null) shouldBe true else z!! shouldBe (q.height plusOrMinus 1e-3)
        }
    }

    @Test
    fun `S042 RF-22 triangulos amontonados en el mismo sitio se reparten en varias teselas con la misma celda`() {
        val count = 1_000
        val e = doubleArrayOf(0.0, 10.0, 0.0)
        val n = doubleArrayOf(0.0, 0.0, 10.0)
        val z = doubleArrayOf(1.0, 2.0, 3.0)

        val cells = TinTiler.tile(e, n, z, IntArray(3 * count) { it % 3 }, maxTriangles = 100)

        cells.size shouldBe 10
        cells.map { it.region }.toSet().size shouldBe 1
        cells.sumOf { it.tile.triangleCount } shouldBe count
    }

    @Test
    fun `S042 RF-22 una superficie grande aleatoria da las mismas cotas que recorrer todos los triangulos`() {
        val random = Random(11)
        val side = 120
        val e = DoubleArray((side + 1) * (side + 1))
        val n = DoubleArray(e.size)
        val z = DoubleArray(e.size)
        for (j in 0..side) {
            for (i in 0..side) {
                val k = j * (side + 1) + i
                e[k] = 440000.0 + i * 10 + random.nextDouble(-3.0, 3.0)
                n[k] = 4474000.0 + j * 10 + random.nextDouble(-3.0, 3.0)
                z[k] = 600.0 + random.nextDouble(0.0, 20.0)
            }
        }
        val triangles = ArrayList<Int>()
        for (j in 0 until side) {
            for (i in 0 until side) {
                val a = j * (side + 1) + i
                triangles += listOf(a, a + 1, a + side + 2, a, a + side + 2, a + side + 1)
            }
        }
        val cells = TinTiler.tile(e, n, z, triangles.toIntArray())
        val all = es.topophone.core.model.surface.SurfaceTile(e, n, z, triangles.toIntArray())

        (cells.size > 1) shouldBe true
        cells.all { it.tile.triangleCount <= TinTiler.MAX_TRIANGLES } shouldBe true
        repeat(1_000) {
            val x = random.nextDouble(440000.0, 441200.0)
            val y = random.nextDouble(4474000.0, 4475200.0)
            val expected = TinElevation.at(all, x, y)
            val got = heightAt(cells, x, y)
            if (expected == null) (got == null) shouldBe true else got!! shouldBe (expected plusOrMinus 1e-9)
        }
    }
}
