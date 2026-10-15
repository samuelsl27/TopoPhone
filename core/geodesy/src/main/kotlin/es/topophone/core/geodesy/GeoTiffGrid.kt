package es.topophone.core.geodesy

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.Inflater
import kotlin.math.floor

/**
 * Rejilla geográfica de una banda float32 leída de un GeoTIFF del CDN de PROJ (ADR-4).
 *
 * Admite lo que usan esas rejillas: orden de bytes II/MM, teselas o tiras, sin compresión o
 * DEFLATE, predictor 1 o 3 (coma flotante), PixelIsPoint/PixelIsArea y GDAL_NODATA.
 * Los nodos van de oeste a este (`i`) y de norte a sur (`j`), como las filas del fichero.
 */
class GeoTiffGrid private constructor(
    val width: Int,
    val height: Int,
    val firstNodeLongitudeDeg: Double,
    val firstNodeLatitudeDeg: Double,
    val stepLongitudeDeg: Double,
    val stepLatitudeDeg: Double,
    private val values: FloatArray,
    private val noData: Float?,
) {

    fun node(i: Int, j: Int): Float = values[j * width + i]

    /**
     * Interpolación bilineal como la de PROJ (S002/RF-38). `null` fuera de la rejilla o si algún
     * nodo de la celda no tiene datos: nunca se extrapola (S002/RF-39, ADR-5).
     */
    fun interpolate(latitudeDeg: Double, longitudeDeg: Double): Double? {
        val x = (longitudeDeg - firstNodeLongitudeDeg) / stepLongitudeDeg
        val y = (firstNodeLatitudeDeg - latitudeDeg) / stepLatitudeDeg
        if (x.isNaN() || y.isNaN() || x < -EDGE_EPS || y < -EDGE_EPS || x > width - 1 + EDGE_EPS || y > height - 1 + EDGE_EPS) return null

        val (i, fx) = cell(x, width)
        val (j, fy) = cell(y, height)
        val corners = floatArrayOf(node(i, j), node(i + 1, j), node(i, j + 1), node(i + 1, j + 1))
        if (corners.any { it.isNaN() || (noData != null && it == noData) }) return null

        return (1 - fx) * (1 - fy) * corners[0] + fx * (1 - fy) * corners[1] + (1 - fx) * fy * corners[2] + fx * fy * corners[3]
    }

    /** Índice de la celda y fracción; el último nodo exacto usa la celda anterior con fracción 1. */
    private fun cell(position: Double, size: Int): Pair<Int, Double> {
        if (size == 1) return 0 to 0.0
        val clamped = position.coerceIn(0.0, size - 1.0)
        val index = minOf(floor(clamped).toInt(), size - 2)
        return index to (clamped - index)
    }

    companion object {
        private const val EDGE_EPS = 1e-9

        private const val TAG_WIDTH = 256
        private const val TAG_HEIGHT = 257
        private const val TAG_BITS = 258
        private const val TAG_COMPRESSION = 259
        private const val TAG_STRIP_OFFSETS = 273
        private const val TAG_SAMPLES = 277
        private const val TAG_ROWS_PER_STRIP = 278
        private const val TAG_STRIP_COUNTS = 279
        private const val TAG_PREDICTOR = 317
        private const val TAG_TILE_WIDTH = 322
        private const val TAG_TILE_LENGTH = 323
        private const val TAG_TILE_OFFSETS = 324
        private const val TAG_TILE_COUNTS = 325
        private const val TAG_SAMPLE_FORMAT = 339
        private const val TAG_PIXEL_SCALE = 33550
        private const val TAG_TIEPOINT = 33922
        private const val TAG_GEO_KEYS = 34735
        private const val TAG_NODATA = 42113

        private const val KEY_RASTER_TYPE = 1025
        private const val RASTER_PIXEL_IS_AREA = 1

        fun read(bytes: ByteArray): GeoTiffGrid = Reader(bytes).read()
    }

    private class Entry(val type: Int, val count: Int, val valueOffset: Int, val inline: ByteArray)

    private class Reader(private val bytes: ByteArray) {
        private val order: ByteOrder = when {
            bytes.size > 8 && bytes[0] == 'I'.code.toByte() && bytes[1] == 'I'.code.toByte() -> ByteOrder.LITTLE_ENDIAN
            bytes.size > 8 && bytes[0] == 'M'.code.toByte() && bytes[1] == 'M'.code.toByte() -> ByteOrder.BIG_ENDIAN
            else -> throw IllegalArgumentException("No es un fichero TIFF")
        }
        private val buffer: ByteBuffer = ByteBuffer.wrap(bytes).order(order)
        private val tags = HashMap<Int, Entry>()

        fun read(): GeoTiffGrid {
            require(buffer.getShort(2).toInt() == 42) { "Solo se admite TIFF clásico (no BigTIFF)" }
            readIfd(buffer.getInt(4))

            val width = int(TAG_WIDTH)
            val height = int(TAG_HEIGHT)
            require(int(TAG_BITS) == 32 && int(TAG_SAMPLE_FORMAT, default = 1) == 3) { "Solo se admiten muestras float32" }
            require(int(TAG_SAMPLES, default = 1) == 1) { "Solo se admite una banda" }
            val compression = int(TAG_COMPRESSION, default = 1)
            require(compression == 1 || compression == 8 || compression == 32946) { "Compresión $compression no admitida" }
            val predictor = int(TAG_PREDICTOR, default = 1)
            require(predictor == 1 || predictor == 3) { "Predictor $predictor no admitido" }

            val values = FloatArray(width * height)
            if (TAG_TILE_WIDTH in tags) {
                val tileWidth = int(TAG_TILE_WIDTH)
                val tileHeight = int(TAG_TILE_LENGTH)
                val offsets = ints(TAG_TILE_OFFSETS)
                val counts = ints(TAG_TILE_COUNTS)
                val tilesAcross = (width + tileWidth - 1) / tileWidth
                offsets.indices.forEach { t ->
                    val block = decode(offsets[t], counts[t], tileWidth, tileHeight, compression, predictor)
                    copy(block, tileWidth, tileHeight, (t % tilesAcross) * tileWidth, (t / tilesAcross) * tileHeight, width, height, values)
                }
            } else {
                val rowsPerStrip = int(TAG_ROWS_PER_STRIP, default = height)
                val offsets = ints(TAG_STRIP_OFFSETS)
                val counts = ints(TAG_STRIP_COUNTS)
                offsets.indices.forEach { s ->
                    val rows = minOf(rowsPerStrip, height - s * rowsPerStrip)
                    val block = decode(offsets[s], counts[s], width, rows, compression, predictor)
                    copy(block, width, rows, 0, s * rowsPerStrip, width, height, values)
                }
            }

            val scale = doubles(TAG_PIXEL_SCALE)
            val tie = doubles(TAG_TIEPOINT)
            val area = rasterType() == RASTER_PIXEL_IS_AREA
            val lon0 = tie[3] - tie[0] * scale[0] + if (area) scale[0] / 2 else 0.0
            val lat0 = tie[4] + tie[1] * scale[1] - if (area) scale[1] / 2 else 0.0
            val noData = tags[TAG_NODATA]?.let { ascii(it).trim().toFloatOrNull() }
            return GeoTiffGrid(width, height, lon0, lat0, scale[0], scale[1], values, noData)
        }

        private fun readIfd(offset: Int) {
            val count = buffer.getShort(offset).toInt() and 0xFFFF
            for (k in 0 until count) {
                val at = offset + 2 + 12 * k
                val tag = buffer.getShort(at).toInt() and 0xFFFF
                val type = buffer.getShort(at + 2).toInt() and 0xFFFF
                val n = buffer.getInt(at + 4)
                tags[tag] = Entry(type, n, buffer.getInt(at + 8), bytes.copyOfRange(at + 8, at + 12))
            }
        }

        private fun typeSize(type: Int) = when (type) {
            1, 2, 6, 7 -> 1
            3, 8 -> 2
            4, 9, 11 -> 4
            5, 10, 12 -> 8
            else -> throw IllegalArgumentException("Tipo TIFF $type no admitido")
        }

        private fun valueBuffer(entry: Entry): ByteBuffer {
            val size = typeSize(entry.type) * entry.count
            return if (size <= 4) {
                ByteBuffer.wrap(entry.inline).order(order)
            } else {
                ByteBuffer.wrap(bytes, entry.valueOffset, size).slice().order(order)
            }
        }

        fun ints(tag: Int): IntArray {
            val entry = tags[tag] ?: throw IllegalArgumentException("Falta la etiqueta $tag")
            val values = valueBuffer(entry)
            return IntArray(entry.count) { if (entry.type == 3) values.short.toInt() and 0xFFFF else values.int }
        }

        fun int(tag: Int, default: Int? = null): Int = if (tag in
            tags
        ) {
            ints(tag)[0]
        } else {
            default ?: throw IllegalArgumentException("Falta la etiqueta $tag")
        }

        fun doubles(tag: Int): DoubleArray {
            val entry = tags[tag] ?: throw IllegalArgumentException("Falta la etiqueta $tag")
            val values = valueBuffer(entry)
            return DoubleArray(entry.count) { values.double }
        }

        fun ascii(entry: Entry): String {
            val raw = ByteArray(entry.count)
            valueBuffer(entry).get(raw)
            return String(raw, Charsets.US_ASCII).trimEnd('\u0000')
        }

        /** Tipo de ráster de la clave GeoTIFF 1025; por defecto, área (valor por omisión del estándar). */
        fun rasterType(): Int {
            if (TAG_GEO_KEYS !in tags) return RASTER_PIXEL_IS_AREA
            val keys = ints(TAG_GEO_KEYS)
            for (k in 0 until keys[3]) {
                val base = 4 + 4 * k
                if (keys[base] == KEY_RASTER_TYPE && keys[base + 1] == 0) return keys[base + 3]
            }
            return RASTER_PIXEL_IS_AREA
        }

        /** Descomprime un bloque y deshace el predictor; devuelve las muestras del bloque completo. */
        fun decode(offset: Int, length: Int, blockWidth: Int, blockHeight: Int, compression: Int, predictor: Int): FloatArray {
            val expected = blockWidth * blockHeight * 4
            val raw = if (compression == 1) bytes.copyOfRange(offset, offset + length) else inflate(offset, length, expected)
            require(raw.size >= expected) { "Bloque incompleto: ${raw.size} de $expected bytes" }
            val samples = FloatArray(blockWidth * blockHeight)
            if (predictor == 3) {
                val rowBytes = blockWidth * 4
                for (row in 0 until blockHeight) {
                    val start = row * rowBytes
                    for (k in 1 until rowBytes) raw[start + k] = (raw[start + k] + raw[start + k - 1]).toByte()
                    // Planos de bytes: el primero contiene los bytes más significativos (TIFF TechNote 3).
                    for (s in 0 until blockWidth) {
                        val bits = ((raw[start + s].toInt() and 0xFF) shl 24) or
                            ((raw[start + blockWidth + s].toInt() and 0xFF) shl 16) or
                            ((raw[start + 2 * blockWidth + s].toInt() and 0xFF) shl 8) or
                            (raw[start + 3 * blockWidth + s].toInt() and 0xFF)
                        samples[row * blockWidth + s] = Float.fromBits(bits)
                    }
                }
            } else {
                val view = ByteBuffer.wrap(raw).order(order)
                for (k in samples.indices) samples[k] = view.getFloat(4 * k)
            }
            return samples
        }

        private fun inflate(offset: Int, length: Int, expected: Int): ByteArray {
            val inflater = Inflater()
            try {
                inflater.setInput(bytes, offset, length)
                val out = ByteArrayOutputStream(expected)
                val chunk = ByteArray(64 * 1024)
                while (!inflater.finished()) {
                    val n = inflater.inflate(chunk)
                    if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) break
                    out.write(chunk, 0, n)
                }
                return out.toByteArray()
            } finally {
                inflater.end()
            }
        }

        /** Copia la parte válida de un bloque (los de borde vienen rellenos) a la rejilla. */
        fun copy(block: FloatArray, blockWidth: Int, blockHeight: Int, x0: Int, y0: Int, width: Int, height: Int, target: FloatArray) {
            for (row in 0 until blockHeight) {
                val y = y0 + row
                if (y >= height) break
                val columns = minOf(blockWidth, width - x0)
                System.arraycopy(block, row * blockWidth, target, y * width + x0, columns)
            }
        }
    }
}
