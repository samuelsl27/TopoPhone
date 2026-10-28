package es.topophone.core.formats.raster

import es.topophone.core.model.project.LinearUnit
import es.topophone.core.model.surface.RasterValueType
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

/**
 * Lee un GeoTIFF de cotas por flujo (S045/RF-2 a RF-7, RF-9): TIFF clásico, sin comprimir, LZW o Deflate, por tiras o por teselas,
 * con enteros de 16 o 32 bits o reales de 32 o 64, y la primera banda. [scan] solo lee la cabecera; [readBands] entrega las filas de
 * 128 en 128 sin tener nunca más de una banda y un bloque del fichero en memoria (plan D5).
 */
object GeoTiffRasterReader {
    private const val TAG_WIDTH = 256
    private const val TAG_HEIGHT = 257
    private const val TAG_BITS = 258
    private const val TAG_COMPRESSION = 259
    private const val TAG_PHOTOMETRIC = 262
    private const val TAG_STRIP_OFFSETS = 273
    private const val TAG_SAMPLES = 277
    private const val TAG_ROWS_PER_STRIP = 278
    private const val TAG_STRIP_COUNTS = 279
    private const val TAG_PLANAR = 284
    private const val TAG_PREDICTOR = 317
    private const val TAG_TILE_WIDTH = 322
    private const val TAG_TILE_LENGTH = 323
    private const val TAG_TILE_OFFSETS = 324
    private const val TAG_TILE_COUNTS = 325
    private const val TAG_SAMPLE_FORMAT = 339
    private const val TAG_PIXEL_SCALE = 33550
    private const val TAG_TIEPOINT = 33922
    private const val TAG_TRANSFORMATION = 34264
    private const val TAG_GEO_KEYS = 34735
    private const val TAG_NODATA = 42113

    private const val KEY_MODEL_TYPE = 1024
    private const val KEY_RASTER_TYPE = 1025
    private const val KEY_GEOGRAPHIC_TYPE = 2048
    private const val KEY_PROJECTED_TYPE = 3072
    private const val KEY_VERTICAL_UNITS = 4099
    private const val MODEL_GEOGRAPHIC = 2
    private const val RASTER_POINT = 2
    private const val USER_DEFINED = 32767

    private const val PLANAR_SEPARATE = 2
    private const val ROTATION_EPS = 1e-12

    /** Lo que dice la cabecera de [source], o por qué no se lee. */
    fun scan(source: RasterSource): RasterScan = try {
        RasterScan.Found(Layout.of(source).header)
    } catch (e: Rejection) {
        RasterScan.Rejected(e.reason)
    } catch (e: IOException) {
        RasterScan.Rejected(RasterRejection.NOT_A_RASTER)
    } catch (e: IndexOutOfBoundsException) {
        RasterScan.Rejected(RasterRejection.NOT_A_RASTER)
    }

    /**
     * Entrega a [sink] las filas del ráster de [bandRows] en [bandRows], de norte a sur, con `NaN` en las celdas sin dato (las del valor
     * declarado y las que no son un número), y devuelve el recuento. El `DoubleArray` que recibe [sink] se reutiliza en la banda
     * siguiente: sus primeras `rows × columns` celdas son las de la banda.
     */
    fun readBands(source: RasterSource, sink: RasterSink, bandRows: Int = RasterLimits.BAND_ROWS): RasterReadStats {
        require(bandRows > 0) { "Una banda tiene al menos una fila" }
        val layout = try {
            Layout.of(source)
        } catch (e: Rejection) {
            throw TiffFormatException("El fichero no se puede leer: ${e.reason}")
        } catch (e: IOException) {
            throw TiffFormatException("El fichero no se puede leer", e)
        }
        return Reader(source, layout).read(sink, bandRows)
    }

    private class Rejection(val reason: RasterRejection) : RuntimeException(reason.name)

    private class Entry(val type: Int, val count: Int, val inline: ByteArray, val offset: Long)

    /** Todo lo que se necesita para leer las celdas: la cabecera ya validada y la disposición de los bloques. */
    private class Layout(
        val header: RasterHeader,
        val order: ByteOrder,
        val type: TiffSampleType,
        val compression: Int,
        val predictor: Int,
        val samples: Int,
        val separatePlanes: Boolean,
        val tileWidth: Int,
        val tileHeight: Int,
        val rowsPerStrip: Int,
        val offsets: LongArray,
        val counts: LongArray,
    ) {
        val tiled: Boolean get() = tileWidth > 0

        companion object {
            fun of(source: RasterSource): Layout {
                if (source.size == 0L) throw Rejection(RasterRejection.EMPTY)
                if (source.size > RasterLimits.MAX_FILE_BYTES) throw Rejection(RasterRejection.TOO_LARGE)
                if (source.size < 8) throw Rejection(RasterRejection.NOT_A_RASTER)
                val head = source.readBytes(0, 8)
                val order = when {
                    head[0] == 'I'.code.toByte() && head[1] == 'I'.code.toByte() -> ByteOrder.LITTLE_ENDIAN
                    head[0] == 'M'.code.toByte() && head[1] == 'M'.code.toByte() -> ByteOrder.BIG_ENDIAN
                    else -> throw Rejection(RasterRejection.NOT_A_RASTER)
                }
                val headBuffer = ByteBuffer.wrap(head).order(order)
                when (headBuffer.getShort(2).toInt()) {
                    42 -> Unit
                    43 -> throw Rejection(RasterRejection.BIGTIFF)
                    else -> throw Rejection(RasterRejection.NOT_A_RASTER)
                }
                val ifdOffset = headBuffer.getInt(4).toLong() and 0xFFFFFFFFL
                return Tags(source, order, ifdOffset).layout()
            }
        }
    }

    /** Las etiquetas de la primera IFD (las demás son vistas reducidas). */
    private class Tags(private val source: RasterSource, private val order: ByteOrder, ifdOffset: Long) {
        private val entries = HashMap<Int, Entry>()

        init {
            if (ifdOffset + 2 > source.size) throw Rejection(RasterRejection.NOT_A_RASTER)
            val count = ByteBuffer.wrap(source.readBytes(ifdOffset, 2)).order(order).getShort(0).toInt() and 0xFFFF
            if (count == 0 || ifdOffset + 2 + count * 12L > source.size) throw Rejection(RasterRejection.NOT_A_RASTER)
            val table = ByteBuffer.wrap(source.readBytes(ifdOffset + 2, count * 12)).order(order)
            for (k in 0 until count) {
                val at = k * 12
                val tag = table.getShort(at).toInt() and 0xFFFF
                val inline = ByteArray(4) { table.get(at + 8 + it) }
                entries[tag] = Entry(
                    type = table.getShort(at + 2).toInt() and 0xFFFF,
                    count = table.getInt(at + 4),
                    inline = inline,
                    offset = table.getInt(at + 8).toLong() and 0xFFFFFFFFL,
                )
            }
        }

        private fun typeSize(type: Int) = when (type) {
            1, 2, 6, 7 -> 1
            3, 8 -> 2
            4, 9, 11 -> 4
            5, 10, 12 -> 8
            else -> throw Rejection(RasterRejection.NOT_A_RASTER)
        }

        private fun data(entry: Entry): ByteBuffer {
            val size = typeSize(entry.type).toLong() * entry.count
            if (entry.count < 0) throw Rejection(RasterRejection.NOT_A_RASTER)
            val bytes = if (size <= 4) {
                entry.inline.copyOf(size.toInt())
            } else {
                if (entry.offset + size > source.size) throw Rejection(RasterRejection.NOT_A_RASTER)
                source.readBytes(entry.offset, size.toInt())
            }
            return ByteBuffer.wrap(bytes).order(order)
        }

        fun has(tag: Int) = tag in entries

        fun longs(tag: Int): LongArray? {
            val entry = entries[tag] ?: return null
            val buffer = data(entry)
            return when (entry.type) {
                3 -> LongArray(entry.count) { (buffer.getShort(it * 2).toLong() and 0xFFFF) }
                4 -> LongArray(entry.count) { (buffer.getInt(it * 4).toLong() and 0xFFFFFFFFL) }
                else -> throw Rejection(RasterRejection.NOT_A_RASTER)
            }
        }

        fun long(tag: Int): Long? = longs(tag)?.firstOrNull()

        fun doubles(tag: Int): DoubleArray? {
            val entry = entries[tag] ?: return null
            if (entry.type != 12) throw Rejection(RasterRejection.NOT_A_RASTER)
            val buffer = data(entry)
            return DoubleArray(entry.count) { buffer.getDouble(it * 8) }
        }

        fun ascii(tag: Int): String? {
            val entry = entries[tag] ?: return null
            val buffer = data(entry)
            return String(ByteArray(entry.count).also { buffer.get(it) }, Charsets.US_ASCII).trimEnd('\u0000').trim()
        }

        fun layout(): Layout {
            val width = long(TAG_WIDTH)?.toInt() ?: throw Rejection(RasterRejection.NOT_A_RASTER)
            val height = long(TAG_HEIGHT)?.toInt() ?: throw Rejection(RasterRejection.NOT_A_RASTER)
            if (width <= 0 || height <= 0) throw Rejection(RasterRejection.NOT_A_RASTER)
            if (width.toLong() * height > RasterLimits.MAX_CELLS) throw Rejection(RasterRejection.TOO_LARGE)

            val compression = long(TAG_COMPRESSION)?.toInt() ?: TiffDecoder.NONE
            val predictor = long(TAG_PREDICTOR)?.toInt() ?: 1
            if (!TiffDecoder.isSupported(compression) || !TiffDecoder.isSupportedPredictor(predictor)) {
                throw Rejection(RasterRejection.UNSUPPORTED_COMPRESSION)
            }

            val samples = long(TAG_SAMPLES)?.toInt() ?: 1
            val bits = longs(TAG_BITS)?.firstOrNull()?.toInt() ?: 1
            val photometric = long(TAG_PHOTOMETRIC)?.toInt() ?: 1
            if (photometric in COLOUR_MODELS || (bits == 8 && samples >= 3)) throw Rejection(RasterRejection.COLOUR)
            val format = longs(TAG_SAMPLE_FORMAT)?.firstOrNull()?.toInt() ?: 1
            val type = TiffSampleType.of(bits, format) ?: throw Rejection(RasterRejection.UNSUPPORTED_DATA_TYPE)
            if (predictor == 2 && type.isFloat) throw Rejection(RasterRejection.UNSUPPORTED_COMPRESSION)
            if (predictor == 3 && !type.isFloat) throw Rejection(RasterRejection.UNSUPPORTED_COMPRESSION)
            val separatePlanes = long(TAG_PLANAR)?.toInt() == PLANAR_SEPARATE

            val geoKeys = geoKeys()
            val (originX, originY, stepX, stepY) = georeference(geoKeys[KEY_RASTER_TYPE] == RASTER_POINT)

            if (width > RasterLimits.MAX_COLUMNS) throw Rejection(RasterRejection.TOO_WIDE)
            val channels = if (separatePlanes) 1 else samples
            val tiled = has(TAG_TILE_WIDTH)
            val tileWidth: Int
            val tileHeight: Int
            val rowsPerStrip: Int
            val offsets: LongArray
            val counts: LongArray
            if (tiled) {
                tileWidth = long(TAG_TILE_WIDTH)?.toInt() ?: 0
                tileHeight = long(TAG_TILE_LENGTH)?.toInt() ?: 0
                if (tileWidth <= 0 || tileHeight <= 0) throw Rejection(RasterRejection.NOT_A_RASTER)
                if (tileWidth.toLong() * tileHeight * channels * type.bytes >
                    RasterLimits.MAX_BLOCK_BYTES
                ) {
                    throw Rejection(RasterRejection.TOO_WIDE)
                }
                rowsPerStrip = 0
                offsets = longs(TAG_TILE_OFFSETS) ?: throw Rejection(RasterRejection.NOT_A_RASTER)
                counts = longs(TAG_TILE_COUNTS) ?: throw Rejection(RasterRejection.NOT_A_RASTER)
            } else {
                tileWidth = 0
                tileHeight = 0
                rowsPerStrip = minOf(long(TAG_ROWS_PER_STRIP) ?: Int.MAX_VALUE.toLong(), height.toLong()).toInt()
                if (rowsPerStrip <= 0) throw Rejection(RasterRejection.NOT_A_RASTER)
                if (width.toLong() * rowsPerStrip * channels * type.bytes >
                    RasterLimits.MAX_BLOCK_BYTES
                ) {
                    throw Rejection(RasterRejection.TOO_WIDE)
                }
                offsets = longs(TAG_STRIP_OFFSETS) ?: throw Rejection(RasterRejection.NOT_A_RASTER)
                counts = longs(TAG_STRIP_COUNTS) ?: throw Rejection(RasterRejection.NOT_A_RASTER)
            }

            val epsg = (geoKeys[KEY_PROJECTED_TYPE] ?: geoKeys[KEY_GEOGRAPHIC_TYPE])?.takeIf { it != 0 && it != USER_DEFINED }
            val geographic = geoKeys[KEY_MODEL_TYPE] == MODEL_GEOGRAPHIC
            val unit = when (geoKeys[KEY_VERTICAL_UNITS]) {
                9001 -> LinearUnit.METRE
                9002 -> LinearUnit.INTERNATIONAL_FOOT
                9003 -> LinearUnit.US_SURVEY_FOOT
                else -> null
            }
            val noData = ascii(TAG_NODATA)?.let { if (it.equals("nan", ignoreCase = true)) Double.NaN else it.toDoubleOrNull() }
            val header = RasterHeader(
                format = RasterFormat.GEOTIFF,
                columns = width,
                rows = height,
                originX = originX,
                originY = originY,
                stepX = stepX,
                stepY = stepY,
                epsg = epsg,
                geographic = geographic,
                verticalUnit = unit,
                bandCount = samples,
                noDataValue = noData,
                valueType = if (type == TiffSampleType.FLOAT64) RasterValueType.FLOAT64 else RasterValueType.FLOAT32,
            )
            return Layout(
                header, order, type, compression, predictor, samples, separatePlanes, tileWidth, tileHeight, rowsPerStrip, offsets, counts,
            )
        }

        /** Las claves GeoTIFF que se usan, por número de clave y con su valor cuando va en la propia entrada. */
        private fun geoKeys(): Map<Int, Int> {
            val raw = longs(TAG_GEO_KEYS) ?: return emptyMap()
            if (raw.size < 4) return emptyMap()
            val result = HashMap<Int, Int>()
            for (k in 0 until minOf(raw[3].toInt(), (raw.size - 4) / 4)) {
                val base = 4 + 4 * k
                if (raw[base + 1] == 0L) result[raw[base].toInt()] = raw[base + 3].toInt()
            }
            return result
        }

        /** El centro de la celda (0, 0) y el paso, de la escala y el punto de enlace (o de una matriz sin giro). */
        private fun georeference(pixelIsPoint: Boolean): DoubleArray {
            val matrix = doubles(TAG_TRANSFORMATION)
            val originX: Double
            val originY: Double
            val stepX: Double
            val stepY: Double
            if (matrix != null) {
                if (matrix.size < 16) throw Rejection(RasterRejection.NOT_A_RASTER)
                if (abs(matrix[1]) > ROTATION_EPS * abs(matrix[0]) || abs(matrix[4]) > ROTATION_EPS * abs(matrix[5])) {
                    throw Rejection(RasterRejection.ROTATED)
                }
                stepX = matrix[0]
                stepY = -matrix[5]
                val half = if (pixelIsPoint) 0.0 else 0.5
                originX = matrix[3] + half * stepX
                originY = matrix[7] - half * stepY
            } else {
                val scale = doubles(TAG_PIXEL_SCALE)
                val tie = doubles(TAG_TIEPOINT)
                if (scale == null || tie == null || scale.size < 2 || tie.size != 6) throw Rejection(RasterRejection.NOT_GEOREFERENCED)
                stepX = scale[0]
                stepY = scale[1]
                val half = if (pixelIsPoint) 0.0 else 0.5
                originX = tie[3] + (half - tie[0]) * stepX
                originY = tie[4] - (half - tie[1]) * stepY
            }
            if (!(stepX > 0.0 && stepY > 0.0) || !originX.isFinite() || !originY.isFinite()) throw Rejection(RasterRejection.ROTATED)
            return doubleArrayOf(originX, originY, stepX, stepY)
        }
    }

    private val COLOUR_MODELS = setOf(2, 3, 5, 6)

    /** Descodifica los bloques que tocan cada banda y los copia a la banda. */
    private class Reader(private val source: RasterSource, private val layout: Layout) {
        private val header = layout.header
        private val width = header.columns
        private val height = header.rows
        private val channels = if (layout.separatePlanes) 1 else layout.samples
        private val counter = RasterCellCounter(header.noDataValue, layout.type == TiffSampleType.FLOAT32)

        private var cachedBlock = -1
        private var cachedValues = DoubleArray(0)

        fun read(sink: RasterSink, bandRows: Int): RasterReadStats {
            val band = DoubleArray(minOf(bandRows, height) * width)
            var first = 0
            while (first < height) {
                val rows = minOf(bandRows, height - first)
                if (layout.tiled) fillFromTiles(band, first, rows) else fillFromStrips(band, first, rows)
                counter.apply(band, rows * width)
                sink.accept(first, rows, band)
                first += rows
            }
            return counter.stats()
        }

        private fun fillFromStrips(band: DoubleArray, first: Int, rows: Int) {
            val rps = layout.rowsPerStrip
            for (strip in first / rps..(first + rows - 1) / rps) {
                val stripRow = strip * rps
                val stripRows = minOf(rps, height - stripRow)
                val values = block(strip, width, stripRows)
                for (y in maxOf(first, stripRow) until minOf(first + rows, stripRow + stripRows)) {
                    copyRow(values, (y - stripRow) * width, width, band, (y - first) * width)
                }
            }
        }

        private fun fillFromTiles(band: DoubleArray, first: Int, rows: Int) {
            val tw = layout.tileWidth
            val th = layout.tileHeight
            val across = (width + tw - 1) / tw
            for (ty in first / th..(first + rows - 1) / th) {
                for (tx in 0 until across) {
                    val values = block(ty * across + tx, tw, th)
                    val x0 = tx * tw
                    val columns = minOf(tw, width - x0)
                    for (y in maxOf(first, ty * th) until minOf(first + rows, ty * th + th, height)) {
                        copyRow(values, (y - ty * th) * tw, columns, band, (y - first) * width + x0)
                    }
                }
            }
        }

        /** Los valores de la primera banda de un bloque, como [blockWidth] × [blockRows] celdas; el último bloque reutilizado se guarda. */
        private fun block(index: Int, blockWidth: Int, blockRows: Int): DoubleArray {
            if (index == cachedBlock) return cachedValues
            if (index >= layout.offsets.size || index >= layout.counts.size) throw TiffFormatException("Falta el bloque $index")
            val length = layout.counts[index]
            val offset = layout.offsets[index]
            if (length < 0 ||
                length > Int.MAX_VALUE ||
                offset + length > source.size
            ) {
                throw TiffFormatException("El bloque $index sale del fichero")
            }
            val expected = blockWidth * blockRows * channels * layout.type.bytes
            val raw = TiffDecoder.decompress(layout.compression, source.readBytes(offset, length.toInt()), expected)
            TiffDecoder.undoPredictor(raw, layout.predictor, blockWidth, blockRows, layout.type, channels, layout.order)
            val all = TiffDecoder.toValues(raw, layout.type, layout.order, blockWidth * blockRows * channels)
            val values = if (channels == 1) all else DoubleArray(blockWidth * blockRows) { all[it * channels] }
            cachedBlock = index
            cachedValues = values
            return values
        }

        private fun copyRow(from: DoubleArray, fromAt: Int, columns: Int, to: DoubleArray, toAt: Int) {
            System.arraycopy(from, fromAt, to, toAt, columns)
        }
    }
}
