package es.topophone.core.survey

import es.topophone.core.model.surface.ElevationRamp
import es.topophone.core.model.surface.RasterInfo
import es.topophone.core.model.surface.RasterTile
import es.topophone.core.model.surface.SurfaceExtent
import kotlin.math.floor

/** Qué se dibuja de un ráster visible (S061/RF-8, RF-9), en el sistema de coordenadas del ráster. */
sealed interface RasterView {
    /** Demasiadas celdas en la vista: solo el recuadro de su extensión, con cada lado partido en [SIDE_STEPS] tramos. */
    class ExtentOnly(val x: DoubleArray, val y: DoubleArray) : RasterView

    /**
     * Una imagen de una celda por píxel, de [width] × [height], fila a fila de arriba abajo, con el color de cada celda y
     * transparente sin dato. [cornerX] y [cornerY] son las cuatro esquinas de la imagen: arriba a la izquierda, arriba a la
     * derecha, abajo a la derecha y abajo a la izquierda (el orden de una imagen del mapa).
     */
    class Image(val width: Int, val height: Int, val argb: IntArray, val cornerX: DoubleArray, val cornerY: DoubleArray) : RasterView

    companion object {
        const val SIDE_STEPS = 32
    }
}

/**
 * Decide qué se dibuja de un ráster según las celdas que caen en la vista (S061, plan A4). Las celdas se cuentan con la aritmética
 * de la malla, sin leer nada; con más de [MAX_CELLS] se dibuja el recuadro y si no, una imagen de las celdas de la vista ampliada
 * un 25 % ([SurfaceViewSelector.VIEW_MARGIN]).
 */
object RasterViewSelector {
    const val MAX_CELLS = 1_000_000L

    /** Las columnas y filas de celdas, ambas incluidas, que corta un recuadro en el sistema del ráster. */
    data class CellRange(val firstColumn: Int, val lastColumn: Int, val firstRow: Int, val lastRow: Int) {
        val cellCount: Long get() = (lastColumn - firstColumn + 1).toLong() * (lastRow - firstRow + 1)
    }

    /** Las celdas que corta [view] (en el sistema del ráster); null si no corta ninguna. */
    fun cells(info: RasterInfo, view: SurfaceExtent): CellRange? {
        val left = info.originX - info.stepX / 2
        val top = info.originY + info.stepY / 2
        val c0 = floor((view.minEasting - left) / info.stepX)
        val c1 = floor((view.maxEasting - left) / info.stepX)
        val r0 = floor((top - view.maxNorthing) / info.stepY)
        val r1 = floor((top - view.minNorthing) / info.stepY)
        if (c1 < 0 || r1 < 0 || c0 > info.columns - 1 || r0 > info.rows - 1) return null
        return CellRange(
            c0.coerceAtLeast(0.0).toInt(),
            c1.coerceAtMost(info.columns - 1.0).toInt(),
            r0.coerceAtLeast(0.0).toInt(),
            r1.coerceAtMost(info.rows - 1.0).toInt(),
        )
    }

    /**
     * Lo que se dibuja del ráster [info] en la [view] (en el sistema del ráster), con los colores de [ramp]; [tile] da el bloque
     * de una columna y una fila de bloques, o null si no está. Null si la vista no corta el ráster.
     */
    fun raster(
        info: RasterInfo,
        view: SurfaceExtent,
        ramp: ElevationRamp,
        maxCells: Long = MAX_CELLS,
        tile: (tileColumn: Int, tileRow: Int) -> RasterTile?,
    ): RasterView? {
        val inView = cells(info, view) ?: return null
        if (inView.cellCount > maxCells) return extentOnly(info)
        val range = cells(info, SurfaceViewSelector.enlarge(view)) ?: inView
        val width = range.lastColumn - range.firstColumn + 1
        val height = range.lastRow - range.firstRow + 1
        val argb = IntArray(width * height)
        val cache = HashMap<Long, RasterTile?>()
        for (j in 0 until height) {
            val row = range.firstRow + j
            for (i in 0 until width) {
                val col = range.firstColumn + i
                val tc = col / RasterTile.EDGE
                val tr = row / RasterTile.EDGE
                val block = cache.getOrPut((tc.toLong() shl 32) or tr.toLong()) { tile(tc, tr) } ?: continue
                argb[j * width + i] = ramp.argb(block.valueAt(col % RasterTile.EDGE, row % RasterTile.EDGE))
            }
        }
        val x0 = info.originX + (range.firstColumn - 0.5) * info.stepX
        val x1 = info.originX + (range.lastColumn + 0.5) * info.stepX
        val y0 = info.originY - (range.firstRow - 0.5) * info.stepY
        val y1 = info.originY - (range.lastRow + 0.5) * info.stepY
        return RasterView.Image(width, height, argb, doubleArrayOf(x0, x1, x1, x0), doubleArrayOf(y0, y0, y1, y1))
    }

    /** El recuadro de la extensión de las celdas, con cada lado partido en [RasterView.SIDE_STEPS] tramos, en sentido antihorario. */
    fun extentOnly(info: RasterInfo): RasterView.ExtentOnly {
        val x0 = info.originX - info.stepX / 2
        val x1 = info.originX + (info.columns - 0.5) * info.stepX
        val y1 = info.originY + info.stepY / 2
        val y0 = info.originY - (info.rows - 0.5) * info.stepY
        val steps = RasterView.SIDE_STEPS
        val corners = listOf(x0 to y0, x1 to y0, x1 to y1, x0 to y1)
        val x = DoubleArray(4 * steps)
        val y = DoubleArray(4 * steps)
        for (side in 0 until 4) {
            val (ax, ay) = corners[side]
            val (bx, by) = corners[(side + 1) % 4]
            for (s in 0 until steps) {
                val f = s.toDouble() / steps
                x[side * steps + s] = ax + (bx - ax) * f
                y[side * steps + s] = ay + (by - ay) * f
            }
        }
        return RasterView.ExtentOnly(x, y)
    }
}
