package es.topophone.core.survey

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** Unir las teselas de una superficie para calcular su contorno (S061/RF-6, plan A2). */
class TileMergerTest {

    /** Una malla de [side] × [side] celdas de 2 m sin la celda del centro, repartida en teselas pequeñas que repiten triángulos. */
    private fun grid(side: Int): Triple<DoubleArray, DoubleArray, IntArray> {
        val w = side + 1
        val e = DoubleArray(w * w) { 440_000.0 + (it % w) * 2.0 }
        val n = DoubleArray(w * w) { 4_474_000.0 + (it / w) * 2.0 }
        val t = ArrayList<Int>()
        for (j in 0 until side) {
            for (i in 0 until side) {
                if (i == side / 2 && j == side / 2) continue
                val a = j * w + i
                t += listOf(a, a + 1, a + w + 1, a, a + w + 1, a + w)
            }
        }
        return Triple(e, n, t.toIntArray())
    }

    @Test
    fun `S061 RF-6 las teselas unidas dan los mismos triangulos sin repetir y el mismo contorno`() {
        val (e, n, t) = grid(20)
        val z = DoubleArray(e.size) { 600.0 + it * 0.001 }
        val tiles = TinTiler.tile(e, n, z, t, maxTriangles = 50).map { it.tile }
        (tiles.sumOf { it.triangleCount } > t.size / 3) shouldBe true // hay triángulos en varias teselas

        val merged = TileMerger.merge(tiles)

        merged.triangleCount shouldBe t.size / 3
        merged.easting.size shouldBe e.size
        TinOutline.rings(merged.easting, merged.northing, merged.triangles).rings.map {
            it.exterior to it.vertexCount
        }.sortedBy { it.second } shouldBe
            TinOutline.rings(e, n, t).rings.map { it.exterior to it.vertexCount }.sortedBy { it.second }
    }

    @Test
    fun `S061 RF-6 las cotas de los vertices unidos son las de las teselas`() {
        val (e, n, t) = grid(4)
        val z = DoubleArray(e.size) { 600.0 + it * 0.123 }
        val tiles = TinTiler.tile(e, n, z, t, maxTriangles = 4).map { it.tile }

        val merged = TileMerger.merge(tiles)

        for (v in merged.easting.indices) {
            val original = e.indices.first { e[it] == merged.easting[v] && n[it] == merged.northing[v] }
            merged.height[v] shouldBe z[original]
        }
    }

    @Test
    fun `S061 RF-6 sin teselas no hay malla`() {
        TileMerger.merge(emptyList()).triangleCount shouldBe 0
    }
}
