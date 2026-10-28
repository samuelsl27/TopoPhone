package es.topophone.core.survey

import es.topophone.core.model.surface.RasterTile
import es.topophone.core.model.surface.RasterValueType
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** Cortar una banda de un ráster en bloques de 128 × 128 celdas (S045/RF-14, plan A2). */
class RasterTilerTest {

    private val edge = RasterTile.EDGE

    private fun band(rows: Int, columns: Int) = DoubleArray(rows * columns) { 600.0 + it / 7.0 }

    @Test
    fun `S045 RF-14 una banda de 40 por 36 celdas cabe en un bloque y el resto del bloque no tiene dato`() {
        val values = band(36, 40)

        val cuts = RasterTiler.cut(0, 36, 40, values, RasterValueType.FLOAT64)

        cuts.size shouldBe 1
        cuts[0].tileColumn shouldBe 0
        cuts[0].tileRow shouldBe 0
        val tile = cuts[0].tile
        tile.valueType shouldBe RasterValueType.FLOAT64
        for (row in 0 until 36) for (col in 0 until 40) tile.valueAt(col, row) shouldBe values[row * 40 + col]
        tile.valueAt(40, 0).isNaN() shouldBe true
        tile.valueAt(0, 36).isNaN() shouldBe true
        tile.valueAt(edge - 1, edge - 1).isNaN() shouldBe true
    }

    @Test
    fun `S045 RF-14 una banda ancha da un bloque por cada 128 columnas con el ultimo a medias`() {
        val values = band(edge, 300)

        val cuts = RasterTiler.cut(2 * edge, edge, 300, values, RasterValueType.FLOAT64)

        cuts.map { it.tileColumn } shouldBe listOf(0, 1, 2)
        cuts.map { it.tileRow } shouldBe listOf(2, 2, 2)
        cuts[1].tile.valueAt(0, 0) shouldBe values[edge]
        cuts[2].tile.valueAt(43, edge - 1) shouldBe values[(edge - 1) * 300 + 2 * edge + 43]
        cuts[2].tile.valueAt(44, 0).isNaN() shouldBe true
    }

    @Test
    fun `S045 RF-14 las celdas sin dato siguen siendo NaN en el bloque`() {
        val values = band(4, 6).also { it[8] = Double.NaN }

        val tile = RasterTiler.cut(0, 4, 6, values, RasterValueType.FLOAT32)[0].tile

        tile.valueAt(2, 1).isNaN() shouldBe true
        tile.valueAt(3, 1).isNaN() shouldBe false
    }

    @Test
    fun `S045 RF-14 float32 guarda el valor como float y float64 sin redondear`() {
        val values = doubleArrayOf(610.0189819335938, 600.123456789012, 1e-9, -9999.5)

        val f32 = RasterTiler.cut(0, 1, 4, values, RasterValueType.FLOAT32)[0].tile
        val f64 = RasterTiler.cut(0, 1, 4, values, RasterValueType.FLOAT64)[0].tile

        f32.valueAt(0, 0) shouldBe 610.0189819335938 // ya es un float
        f32.valueAt(1, 0) shouldBe 600.123456789012.toFloat().toDouble()
        f64.valueAt(1, 0) shouldBe 600.123456789012
        f64.valueAt(2, 0) shouldBe 1e-9
    }

    @Test
    fun `S045 RF-14 una banda que no empieza en una fila multiplo de 128 o es demasiado alta no se corta`() {
        shouldThrow<IllegalArgumentException> { RasterTiler.cut(5, 4, 6, band(4, 6), RasterValueType.FLOAT32) }
        shouldThrow<IllegalArgumentException> { RasterTiler.cut(0, edge + 1, 2, band(edge + 1, 2), RasterValueType.FLOAT32) }
        shouldThrow<IllegalArgumentException> { RasterTiler.cut(0, 0, 2, band(0, 2), RasterValueType.FLOAT32) }
        shouldThrow<IllegalArgumentException> { RasterTiler.cut(0, 4, 6, band(2, 6), RasterValueType.FLOAT32) }
    }

    @Test
    fun `S045 RF-14 un raster leido entero y cortado conserva cada celda`() {
        val raster = RasterTestFiles.load("esquina.asc")
        val columns = raster.header.columns

        val cuts = RasterTiler.cut(0, raster.header.rows, columns, raster.values, RasterValueType.FLOAT64)

        cuts.size shouldBe 1
        for (row in 0 until raster.header.rows) {
            for (col in 0 until columns) {
                val expected = raster.value(col, row)
                val actual = cuts[0].tile.valueAt(col, row)
                if (expected.isNaN()) actual.isNaN() shouldBe true else actual shouldBe expected
            }
        }
    }
}
