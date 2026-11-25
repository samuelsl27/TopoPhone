package es.topophone.core.survey

import es.topophone.core.model.surface.ElevationRamp
import es.topophone.core.model.surface.SurfaceExtent
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test

/** Contorno o malla según los triángulos que caen en la vista (S061/RF-6, RF-7, RF-10, plan A3). */
class SurfaceViewSelectorTest {

    private val e0 = 440_000.0
    private val n0 = 4_474_000.0

    /** Una malla de [nx] × [ny] celdas de 2 m con dos triángulos cada una, repartida en teselas por [TinTiler]. */
    private fun grid(nx: Int, ny: Int, maxTriangles: Int = 1_000): List<TinCell> {
        val w = nx + 1
        val e = DoubleArray(w * (ny + 1)) { e0 + (it % w) * 2.0 }
        val n = DoubleArray(w * (ny + 1)) { n0 + (it / w) * 2.0 }
        val z = DoubleArray(w * (ny + 1)) { 600.0 + (it % w) * 0.1 }
        val t = IntArray(nx * ny * 6)
        var k = 0
        for (j in 0 until ny) {
            for (i in 0 until nx) {
                val a = j * w + i
                for (v in intArrayOf(a, a + 1, a + w + 1, a, a + w + 1, a + w)) t[k++] = v
            }
        }
        return TinTiler.tile(e, n, z, t, maxTriangles = maxTriangles)
    }

    private fun viewOf(nx: Int, ny: Int) = SurfaceExtent(e0, e0 + nx * 2.0, n0, n0 + ny * 2.0)

    private val ramp = ElevationRamp(600.0, 620.0)

    @Test
    fun `S061 RF-7 con 20000 triangulos en la vista se dibuja la malla sin repetir ninguno`() {
        val cells = grid(100, 100)
        (cells.size > 1) shouldBe true

        val view = SurfaceViewSelector.tin(cells.map { it.tile }, viewOf(100, 100), ramp)

        view.shouldBeInstanceOf<TinView.Mesh>().triangles.count shouldBe 20_000
    }

    @Test
    fun `S061 RF-6 con mas de 20000 triangulos en la vista solo el contorno`() {
        val cells = grid(100, 101)

        SurfaceViewSelector.tin(cells.map { it.tile }, viewOf(100, 101), ramp) shouldBe TinView.OutlineOnly
    }

    @Test
    fun `S061 RF-7 solo se cuentan los de la vista pero se dibujan los de la vista ampliada`() {
        val cells = grid(200, 100) // 40.000 triángulos; la vista es casi la mitad izquierda
        val half = SurfaceExtent(e0, e0 + 199.0, n0, n0 + 200.0)

        val mesh = SurfaceViewSelector.tin(cells.map { it.tile }, half, ramp).shouldBeInstanceOf<TinView.Mesh>()

        // En la vista caen 100 columnas de celdas (20.000 triángulos, que no pasan del límite); con la vista ampliada 49,75 m
        // a la derecha se dibujan 125 columnas.
        mesh.triangles.count shouldBe 25_000
    }

    @Test
    fun `S061 RF-10 cada triangulo lleva el color de su cota media`() {
        val cells = grid(2, 1)

        val mesh = SurfaceViewSelector.tin(cells.map { it.tile }, viewOf(2, 1), ramp).shouldBeInstanceOf<TinView.Mesh>().triangles

        for (k in 0 until mesh.count) {
            val meanE = (mesh.easting[3 * k] + mesh.easting[3 * k + 1] + mesh.easting[3 * k + 2]) / 3
            val meanZ = 600.0 + (meanE - e0) / 2.0 * 0.1
            mesh.argb[k] shouldBe ramp.argb(meanZ)
        }
    }

    @Test
    fun `S061 RF-6 con la suma del indice por encima de 60000 no se lee ningun bloque`() {
        val index = listOf(TileBox(viewOf(10, 10), 40_000), TileBox(viewOf(10, 10), 30_000))

        SurfaceViewSelector.tilesToRead(index, viewOf(10, 10)).shouldBeNull()
    }

    @Test
    fun `S061 RF-7 se leen solo las teselas que cortan la vista ampliada`() {
        val near = TileBox(SurfaceExtent(e0 + 21.0, e0 + 30.0, n0, n0 + 20.0), 500) // a 1 m: dentro del 25 % de 20 m
        val far = TileBox(SurfaceExtent(e0 + 100.0, e0 + 120.0, n0, n0 + 20.0), 500)
        val inside = TileBox(viewOf(5, 5), 500)

        SurfaceViewSelector.tilesToRead(listOf(far, inside, near), viewOf(10, 10)).shouldNotBeNull() shouldBe listOf(1, 2)
    }
}
