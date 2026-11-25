package es.topophone.core.survey

import es.topophone.core.model.project.HeightSystem
import es.topophone.core.model.surface.ElevationRamp
import es.topophone.core.model.surface.RasterInfo
import es.topophone.core.model.surface.RasterTile
import es.topophone.core.model.surface.RasterValueType
import es.topophone.core.model.surface.SurfaceExtent
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test

/** Recuadro o imagen de celdas según las celdas del ráster que caen en la vista (S061/RF-8, RF-9, RF-10, plan A4). */
class RasterViewSelectorTest {

    /** 300 × 200 celdas de 1 m: los bordes van de 440.000 a 440.300 en X y de 4.474.000 a 4.474.200 en Y. */
    private val info =
        RasterInfo(300, 200, 440_000.5, 4_474_199.5, 1.0, 1.0, RasterValueType.FLOAT32, HeightSystem.ORTHOMETRIC, 59_700, 300)

    /** Cota 600 + columna / 10, sin dato en la fila 5 y sin el bloque de la columna de bloques 2. */
    private fun tile(tc: Int, tr: Int): RasterTile? {
        if (tc == 2) return null
        val values = FloatArray(RasterTile.CELLS) { k ->
            val col = tc * RasterTile.EDGE + k % RasterTile.EDGE
            val row = tr * RasterTile.EDGE + k / RasterTile.EDGE
            if (row == 5) Float.NaN else (600.0 + col / 10.0).toFloat()
        }
        return RasterTile.ofFloats(values)
    }

    private val ramp = ElevationRamp(600.0, 630.0)

    @Test
    fun `S061 RF-9 con pocas celdas en la vista se dibuja la imagen de la vista ampliada`() {
        val view = SurfaceExtent(440_050.0, 440_099.5, 4_474_100.5, 4_474_149.5) // columnas 50 a 99 y filas 50 a 99

        val image = RasterViewSelector.raster(info, view, ramp, maxCells = 2_500) { tc, tr -> tile(tc, tr) }
            .shouldBeInstanceOf<RasterView.Image>()

        val range = RasterViewSelector.cells(info, SurfaceViewSelector.enlarge(view))!!
        image.width shouldBe range.lastColumn - range.firstColumn + 1
        image.height shouldBe range.lastRow - range.firstRow + 1
        // Esquinas en los bordes de las celdas: arriba a la izquierda, arriba a la derecha, abajo a la derecha, abajo a la izquierda.
        image.cornerX.toList() shouldBe
            listOf(440_000.0 + range.firstColumn, 440_001.0 + range.lastColumn, 440_001.0 + range.lastColumn, 440_000.0 + range.firstColumn)
        image.cornerY.toList() shouldBe
            listOf(4_474_200.0 - range.firstRow, 4_474_200.0 - range.firstRow, 4_474_199.0 - range.lastRow, 4_474_199.0 - range.lastRow)
        val col = 70
        val row = 80
        image.argb[(row - range.firstRow) * image.width + (col - range.firstColumn)] shouldBe
            ramp.argb((600.0 + col / 10.0).toFloat().toDouble())
    }

    @Test
    fun `S061 RF-8 con mas celdas que el limite solo el recuadro partido en 32 tramos por lado`() {
        val view = SurfaceExtent(440_050.0, 440_100.0, 4_474_100.0, 4_474_150.0) // 51 × 51 celdas

        val extent = RasterViewSelector.raster(info, view, ramp, maxCells = 2_500) { tc, tr -> tile(tc, tr) }
            .shouldBeInstanceOf<RasterView.ExtentOnly>()

        extent.x.size shouldBe 128
        extent.x.min() shouldBe 440_000.0
        extent.x.max() shouldBe 440_300.0
        extent.y.min() shouldBe 4_474_000.0
        extent.y.max() shouldBe 4_474_200.0
    }

    @Test
    fun `S061 RF-9 las celdas sin dato y los bloques que faltan quedan transparentes`() {
        val image = RasterViewSelector.raster(info, SurfaceExtent(440_000.0, 440_299.0, 4_474_000.0, 4_474_199.0), ramp) { tc, tr ->
            tile(tc, tr)
        }.shouldBeInstanceOf<RasterView.Image>()

        image.width shouldBe 300
        image.argb[5 * 300 + 10] shouldBe 0 // fila 5 sin dato
        image.argb[20 * 300 + 260] shouldBe 0 // columna 260, del bloque 2 que no está
        image.argb[20 * 300 + 10] shouldBe ramp.argb((600.0 + 1.0).toFloat().toDouble())
    }

    @Test
    fun `S061 RF-9 una vista que no corta el raster no dibuja nada`() {
        RasterViewSelector.raster(info, SurfaceExtent(441_000.0, 441_100.0, 4_474_000.0, 4_474_100.0), ramp) { tc, tr -> tile(tc, tr) }
            .shouldBeNull()
    }
}
