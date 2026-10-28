package es.topophone.core.survey

import es.topophone.core.model.cad.CadIgnoredReason
import es.topophone.core.model.cad.CadSourceCrs
import es.topophone.core.model.project.LinearUnit
import es.topophone.core.model.project.ProjectCrs
import es.topophone.core.model.surface.RasterTile
import es.topophone.core.model.surface.RasterValueType
import es.topophone.core.model.surface.SurfaceExtent

/** Un bloque de un ráster con su posición en la cuadrícula de bloques (S045, plan A2). */
class RasterTileCut(val tileColumn: Int, val tileRow: Int, val tile: RasterTile)

/**
 * Corta una banda de un ráster en bloques de [RasterTile.EDGE] × [RasterTile.EDGE] celdas (S045/RF-14, plan A2). La banda es un
 * trozo de hasta 128 filas que empieza en una fila múltiplo de 128; las celdas del bloque que quedan fuera del ráster son `NaN`.
 */
object RasterTiler {
    /**
     * Los bloques de la banda de [rows] filas desde la fila [firstRow], con [columns] celdas por fila en [values], de oeste a este.
     * Con [RasterValueType.FLOAT32] cada celda se guarda como `Float` y con [RasterValueType.FLOAT64] sin redondear.
     */
    fun cut(firstRow: Int, rows: Int, columns: Int, values: DoubleArray, valueType: RasterValueType): List<RasterTileCut> {
        val edge = RasterTile.EDGE
        require(firstRow >= 0 && firstRow % edge == 0) { "Una banda empieza en una fila múltiplo de $edge" }
        require(rows in 1..edge) { "Una banda tiene de 1 a $edge filas" }
        require(columns > 0 && values.size >= rows * columns) { "La banda tiene menos celdas que $rows × $columns" }
        val across = (columns + edge - 1) / edge
        return List(across) { tileColumn ->
            val x0 = tileColumn * edge
            val width = minOf(edge, columns - x0)
            val tile = if (valueType == RasterValueType.FLOAT32) {
                val cells = FloatArray(RasterTile.CELLS) { Float.NaN }
                for (row in 0 until rows) for (col in 0 until width) cells[row * edge + col] = values[row * columns + x0 + col].toFloat()
                RasterTile.ofFloats(cells)
            } else {
                val cells = DoubleArray(RasterTile.CELLS) { Double.NaN }
                for (row in 0 until rows) for (col in 0 until width) cells[row * edge + col] = values[row * columns + x0 + col]
                RasterTile.ofDoubles(cells)
            }
            RasterTileCut(tileColumn, firstRow / edge, tile)
        }
    }
}

/** La extensión de un ráster en el sistema del proyecto, o por qué no entra (S045/RF-3, RF-11). */
sealed interface RasterFootprint {
    data class Inside(val extent: SurfaceExtent) : RasterFootprint

    /** Alguna esquina cae fuera del ámbito del sistema del proyecto o de la rejilla de ED50. */
    data class Outside(val reason: CadIgnoredReason) : RasterFootprint

    companion object {
        /**
         * El recuadro, en Este y Norte del proyecto en metros, de las cuatro esquinas de los centros de celda de un ráster en el
         * sistema [source], con el centro de la celda (0, 0) en ([originX], [originY]) y celdas de [stepX] × [stepY] (RF-11). Es el
         * recuadro de las esquinas convertidas: con otro sistema es aproximado a unos metros, y la pantalla lo da como extensión.
         */
        fun of(
            crs: ProjectCrs,
            source: CadSourceCrs,
            originX: Double,
            originY: Double,
            stepX: Double,
            stepY: Double,
            columns: Int,
            rows: Int,
        ): RasterFootprint {
            val x0 = originX
            val x1 = originX + (columns - 1) * stepX
            val y0 = originY - (rows - 1) * stepY
            val y1 = originY
            val e = doubleArrayOf(x0, x1, x1, x0)
            val n = doubleArrayOf(y0, y0, y1, y1)
            return when (val projection = SurfaceProjector(crs, source, LinearUnit.METRE).convert(e, n, DoubleArray(4))) {
                is SurfaceProjection.Rejected -> Outside(projection.reason)
                is SurfaceProjection.Converted ->
                    Inside(
                        SurfaceExtent(
                            projection.easting.min(),
                            projection.easting.max(),
                            projection.northing.min(),
                            projection.northing.max(),
                        ),
                    )
            }
        }
    }
}
