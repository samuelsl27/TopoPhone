package es.topophone.core.formats.raster

import es.topophone.core.model.project.LinearUnit
import es.topophone.core.model.surface.RasterValueType

/** Los formatos de ráster que la app lee (S045/RF-2). */
enum class RasterFormat { GEOTIFF, ASCII_GRID }

/** Por qué un fichero no se importa como ráster (S045/RF-3 a RF-5, plan D6). */
enum class RasterRejection {
    /** No es un GeoTIFF ni un ASCII Grid. */
    NOT_A_RASTER,

    /** El fichero no tiene ningún byte. */
    EMPTY,

    /** No trae escala ni punto de enlace (o un ASCII Grid sin cabecera completa). */
    NOT_GEOREFERENCED,

    /** TIFF de más de 4 GB (cabecera `II+`). */
    BIGTIFF,

    /** Una compresión o un predictor que la app no lee. */
    UNSUPPORTED_COMPRESSION,

    /** Girado, inclinado o con el eje Y hacia el norte. */
    ROTATED,

    /** Color: 8 bits con tres o más bandas, o paleta. */
    COLOUR,

    /** Muestras que no son enteros de 16 o 32 bits ni reales de 32 o 64. */
    UNSUPPORTED_DATA_TYPE,

    /** Más de [RasterLimits.MAX_FILE_BYTES] bytes o de [RasterLimits.MAX_CELLS] celdas (RF-5). */
    TOO_LARGE,

    /** Una banda de 128 filas o un bloque del fichero no caben en la memoria que se les reserva (plan D6). */
    TOO_WIDE,
}

/** Los límites de tamaño de un ráster (S045/RF-5, plan D5 y D6). */
object RasterLimits {
    const val MAX_FILE_BYTES = 250L * 1024 * 1024
    const val MAX_CELLS = 250_000_000L

    /** Lo más que ocupa en memoria una banda de [BAND_ROWS] filas en `Double`: 64 MB, es decir, 65.536 celdas de ancho. */
    const val MAX_BAND_BYTES = 64L * 1024 * 1024
    const val BAND_ROWS = 128
    const val MAX_COLUMNS = (MAX_BAND_BYTES / (BAND_ROWS * Double.SIZE_BYTES)).toInt()

    /** Lo más que ocupa un bloque (tira o tesela) del fichero ya descomprimido. */
    const val MAX_BLOCK_BYTES = 16L * 1024 * 1024
}

/**
 * Lo que dice la cabecera de un ráster (S045/RF-6 a RF-9), sin haber leído ninguna celda: [originX] y [originY] son el centro de la
 * celda (0, 0), arriba a la izquierda, en el sistema [epsg] (null si el fichero no lo declara); [stepY] es positivo hacia el sur.
 * [noDataValue] es el valor declarado como sin dato (el de un ASCII Grid sin él es -9999); [verticalUnit] la unidad que declara el
 * GeoTIFF para las cotas, o null. [valueType] es float64 solo si el fichero trae reales de 64 bits.
 */
data class RasterHeader(
    val format: RasterFormat,
    val columns: Int,
    val rows: Int,
    val originX: Double,
    val originY: Double,
    val stepX: Double,
    val stepY: Double,
    val epsg: Int?,
    val geographic: Boolean,
    val verticalUnit: LinearUnit?,
    val bandCount: Int,
    val noDataValue: Double?,
    val valueType: RasterValueType,
) {
    val cellCount: Long get() = columns.toLong() * rows
}

/** Lo que se ve de un fichero antes de leer sus celdas. */
sealed interface RasterScan {
    data class Found(val header: RasterHeader) : RasterScan

    data class Rejected(val reason: RasterRejection) : RasterScan
}

/** Quien recibe las filas de un ráster, de norte a sur, por bandas (S045, plan A2). */
fun interface RasterSink {
    /** [values] son [rows] filas de `columns` celdas desde la fila [firstRow]; `NaN` es una celda sin dato. Es un `Double` por celda, sin redondear. */
    fun accept(firstRow: Int, rows: Int, values: DoubleArray)
}

/** Lo que salió de leer un ráster entero (S045/RF-16): celdas con y sin dato y las cotas mínima y máxima, en la unidad del fichero. */
data class RasterReadStats(val dataCells: Long, val noDataCells: Long, val minValue: Double, val maxValue: Double)
