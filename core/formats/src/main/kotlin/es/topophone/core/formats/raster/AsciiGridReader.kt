package es.topophone.core.formats.raster

import es.topophone.core.model.surface.RasterValueType
import java.io.IOException

/**
 * Lee un ASCII Grid de Esri por flujo (S045/RF-2, RF-3, RF-7): la cabecera (`ncols`, `nrows`, `xllcorner` o `xllcenter`, `yllcorner` o
 * `yllcenter`, `cellsize` y `NODATA_value`) y las filas de valores separados por espacios. Los números llevan punto decimal
 * (RNF-T9); lo que no es un número es una celda sin dato (RF-7). No declara sistema de coordenadas.
 */
object AsciiGridReader {
    private const val HEADER_BYTES = 8192
    private const val DEFAULT_NODATA = -9999.0
    private const val MAX_TOKEN = 64
    private const val CHUNK = 64 * 1024
    private val KEYS = setOf("ncols", "nrows", "xllcorner", "xllcenter", "yllcorner", "yllcenter", "cellsize", "nodata_value")

    private class Rejection(val reason: RasterRejection) : RuntimeException(reason.name)

    private class Parsed(val header: RasterHeader, val dataOffset: Int)

    /** Si [source] empieza como un ASCII Grid: `ncols`, sin distinguir mayúsculas y tras un BOM o espacios opcionales. */
    internal fun looksLikeAsciiGrid(head: ByteArray): Boolean {
        var at = if (head.size >= 3 && head[0] == 0xEF.toByte() && head[1] == 0xBB.toByte() && head[2] == 0xBF.toByte()) 3 else 0
        while (at < head.size &&
            (
                head[at] == ' '.code.toByte() ||
                    head[at] == '\t'.code.toByte() ||
                    head[at] == '\r'.code.toByte() ||
                    head[at] == '\n'.code.toByte()
                )
        ) {
            at++
        }
        if (head.size - at < 5) return false
        return String(head, at, 5, Charsets.US_ASCII).equals("ncols", ignoreCase = true)
    }

    fun scan(source: RasterSource): RasterScan = try {
        RasterScan.Found(parse(source).header)
    } catch (e: Rejection) {
        RasterScan.Rejected(e.reason)
    } catch (e: IOException) {
        RasterScan.Rejected(RasterRejection.NOT_A_RASTER)
    }

    private fun parse(source: RasterSource): Parsed {
        if (source.size == 0L) throw Rejection(RasterRejection.EMPTY)
        if (source.size > RasterLimits.MAX_FILE_BYTES) throw Rejection(RasterRejection.TOO_LARGE)
        val head = source.readBytes(0, minOf(source.size, HEADER_BYTES.toLong()).toInt())
        if (!looksLikeAsciiGrid(head)) throw Rejection(RasterRejection.NOT_A_RASTER)

        val values = HashMap<String, String>()
        var lineStart = if (head[0] == 0xEF.toByte()) 3 else 0
        var dataOffset = -1
        while (lineStart < head.size) {
            var lineEnd = lineStart
            while (lineEnd < head.size && head[lineEnd] != '\n'.code.toByte()) lineEnd++
            // La cabecera no acaba dentro de los primeros 8 kB.
            if (lineEnd == head.size && source.size > head.size) throw Rejection(RasterRejection.NOT_A_RASTER)
            val tokens = String(head, lineStart, lineEnd - lineStart, Charsets.US_ASCII).trim().split(WHITESPACE).filter { it.isNotEmpty() }
            if (tokens.isNotEmpty()) {
                val key = tokens[0].lowercase()
                if (key !in KEYS) {
                    dataOffset = lineStart
                    break
                }
                values[key] = tokens.getOrElse(1) { "" }
            }
            lineStart = lineEnd + 1
        }
        if (dataOffset < 0) dataOffset = head.size

        val columns = values["ncols"]?.toIntOrNull()?.takeIf { it > 0 } ?: throw Rejection(RasterRejection.NOT_A_RASTER)
        val rows = values["nrows"]?.toIntOrNull()?.takeIf { it > 0 } ?: throw Rejection(RasterRejection.NOT_A_RASTER)
        if (columns.toLong() * rows > RasterLimits.MAX_CELLS) throw Rejection(RasterRejection.TOO_LARGE)
        if (columns > RasterLimits.MAX_COLUMNS) throw Rejection(RasterRejection.TOO_WIDE)

        val step =
            values["cellsize"]?.toDoubleOrNull()?.takeIf { it > 0.0 && it.isFinite() } ?: throw Rejection(RasterRejection.NOT_GEOREFERENCED)
        val half = step / 2
        val x = (values["xllcenter"]?.toDoubleOrNull() ?: values["xllcorner"]?.toDoubleOrNull()?.let { it + half })
            ?: throw Rejection(RasterRejection.NOT_GEOREFERENCED)
        val yBottom = (values["yllcenter"]?.toDoubleOrNull() ?: values["yllcorner"]?.toDoubleOrNull()?.let { it + half })
            ?: throw Rejection(RasterRejection.NOT_GEOREFERENCED)
        val noData =
            values["nodata_value"]?.let { if (it.equals("nan", ignoreCase = true)) Double.NaN else it.toDoubleOrNull() } ?: DEFAULT_NODATA

        val header = RasterHeader(
            format = RasterFormat.ASCII_GRID,
            columns = columns,
            rows = rows,
            originX = x,
            originY = yBottom + (rows - 1) * step,
            stepX = step,
            stepY = step,
            epsg = null,
            geographic = false,
            verticalUnit = null,
            bandCount = 1,
            noDataValue = noData,
            valueType = RasterValueType.FLOAT64,
        )
        return Parsed(header, dataOffset)
    }

    private val WHITESPACE = Regex("\\s+")

    /**
     * Entrega las filas de [bandRows] en [bandRows], de norte a sur, con `NaN` en las celdas sin dato; el recuento al final. Si el fichero
     * trae menos valores que celdas, lanza [RasterFormatException]; los que sobran se ignoran.
     */
    fun readBands(source: RasterSource, sink: RasterSink, bandRows: Int = RasterLimits.BAND_ROWS): RasterReadStats {
        require(bandRows > 0) { "Una banda tiene al menos una fila" }
        val parsed = try {
            parse(source)
        } catch (e: Rejection) {
            throw RasterFormatException("El fichero no se puede leer: ${e.reason}")
        } catch (e: IOException) {
            throw RasterFormatException("El fichero no se puede leer", e)
        }
        val header = parsed.header
        val columns = header.columns
        val counter = RasterCellCounter(header.noDataValue, float32 = false)
        val band = DoubleArray(minOf(bandRows, header.rows) * columns)
        var cell = 0 // celda dentro de la banda
        var rowsDone = 0
        val token = ByteArray(MAX_TOKEN)
        var length = 0
        var position = parsed.dataOffset.toLong()
        val chunk = ByteArray(CHUNK)

        fun finishToken() {
            if (length == 0) return
            band[cell++] =
                if (length > MAX_TOKEN) Double.NaN else String(token, 0, length, Charsets.US_ASCII).toDoubleOrNull() ?: Double.NaN
            length = 0
            val rowsInBand = minOf(bandRows, header.rows - rowsDone)
            if (cell == rowsInBand * columns) {
                counter.apply(band, cell)
                sink.accept(rowsDone, rowsInBand, band)
                rowsDone += rowsInBand
                cell = 0
            }
        }

        while (rowsDone < header.rows && position < source.size) {
            val n = minOf(CHUNK.toLong(), source.size - position).toInt()
            source.read(position, chunk, 0, n)
            position += n
            var k = 0
            while (k < n && rowsDone < header.rows) {
                val b = chunk[k++]
                if (b <= ' '.code.toByte()) {
                    finishToken()
                } else {
                    if (length < MAX_TOKEN) token[length] = b
                    length = minOf(length + 1, MAX_TOKEN + 1)
                }
            }
        }
        if (rowsDone < header.rows) finishToken()
        if (rowsDone < header.rows) throw RasterFormatException("El fichero tiene menos valores que celdas")
        return counter.stats()
    }
}
