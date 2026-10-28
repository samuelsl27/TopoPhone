package es.topophone.core.formats.raster

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.DataFormatException
import java.util.zip.Inflater

/** Un fichero TIFF que no se puede leer: el bloque está corrupto o usa algo que la app no admite (S045/RF-3, RF-4). */
class TiffFormatException(message: String, cause: Throwable? = null) : IllegalArgumentException(message, cause)

/** El tipo de las muestras de un TIFF que la app lee (S045/RF-2): enteros de 16 y 32 bits y reales de 32 y 64. */
enum class TiffSampleType(val bits: Int, val isFloat: Boolean) {
    UINT16(16, false),
    INT16(16, false),
    UINT32(32, false),
    INT32(32, false),
    FLOAT32(32, true),
    FLOAT64(64, true),
    ;

    val bytes: Int get() = bits / 8

    companion object {
        /** El tipo para unos bits por muestra y un `SampleFormat` de TIFF (1 sin signo, 2 con signo, 3 real); null si no se lee. */
        fun of(bits: Int, sampleFormat: Int): TiffSampleType? = when (sampleFormat to bits) {
            1 to 16 -> UINT16
            2 to 16 -> INT16
            1 to 32 -> UINT32
            2 to 32 -> INT32
            3 to 32 -> FLOAT32
            3 to 64 -> FLOAT64
            else -> null
        }
    }
}

/**
 * Descodifica un bloque (tira o tesela) de un TIFF clásico (S045/RF-2): lo descomprime (sin comprimir, LZW o Deflate), deshace el
 * predictor (2, diferencia horizontal en enteros; 3, coma flotante) y pasa las muestras a `Double`. Sin librerías (RNF-T14).
 */
object TiffDecoder {
    const val NONE = 1
    const val LZW = 5
    const val DEFLATE = 8
    const val DEFLATE_ADOBE = 32946

    fun isSupported(compression: Int): Boolean =
        compression == NONE || compression == LZW || compression == DEFLATE || compression == DEFLATE_ADOBE

    fun isSupportedPredictor(predictor: Int): Boolean = predictor == 1 || predictor == 2 || predictor == 3

    /** Los [expected] bytes de un bloque comprimido con [compression]; [TiffFormatException] si no se lee o está incompleto. */
    fun decompress(compression: Int, data: ByteArray, expected: Int): ByteArray {
        val raw = when (compression) {
            NONE -> data
            LZW -> lzw(data, expected)
            DEFLATE, DEFLATE_ADOBE -> inflate(data, expected)
            else -> throw TiffFormatException("Compresión $compression no admitida")
        }
        if (raw.size < expected) throw TiffFormatException("Bloque incompleto: ${raw.size} de $expected bytes")
        return raw
    }

    private fun inflate(data: ByteArray, expected: Int): ByteArray {
        val inflater = Inflater()
        try {
            inflater.setInput(data)
            val out = ByteArrayOutputStream(expected)
            val chunk = ByteArray(64 * 1024)
            while (!inflater.finished()) {
                val n = inflater.inflate(chunk)
                if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) break
                out.write(chunk, 0, n)
            }
            return out.toByteArray()
        } catch (e: DataFormatException) {
            throw TiffFormatException("Bloque Deflate corrupto", e)
        } finally {
            inflater.end()
        }
    }

    /** LZW de TIFF: códigos de 9 a 12 bits de más a menos significativo y cambio de anchura una entrada antes (`early change`). */
    private fun lzw(data: ByteArray, expected: Int): ByteArray {
        val prefix = IntArray(TABLE_SIZE)
        val first = ByteArray(TABLE_SIZE)
        val suffix = ByteArray(TABLE_SIZE)
        val length = IntArray(TABLE_SIZE)
        for (i in 0 until 256) {
            first[i] = i.toByte()
            suffix[i] = i.toByte()
            length[i] = 1
            prefix[i] = -1
        }
        val out = ByteArray(expected)
        var outPos = 0
        var acc = 0
        var bits = 0
        var pos = 0
        var width = 9
        var next = 258
        var previous = -1

        fun write(code: Int) {
            val n = length[code]
            var at = outPos + n - 1
            var c = code
            while (c >= 0) {
                if (at < expected) out[at] = suffix[c]
                at--
                c = prefix[c]
            }
            outPos = minOf(outPos + n, expected)
        }

        while (outPos < expected) {
            while (bits < width) {
                if (pos >= data.size) return out.copyOf(outPos)
                acc = (acc shl 8) or (data[pos++].toInt() and 0xFF)
                bits += 8
            }
            bits -= width
            val code = (acc shr bits) and ((1 shl width) - 1)
            acc = acc and ((1 shl bits) - 1)
            if (code == 257) break
            if (code == 256) {
                width = 9
                next = 258
                previous = -1
                continue
            }
            if (previous == -1) {
                if (code >= 256) throw TiffFormatException("Bloque LZW corrupto")
                write(code)
                previous = code
                continue
            }
            if (next >= TABLE_SIZE) throw TiffFormatException("Bloque LZW corrupto")
            if (code < next) {
                prefix[next] = previous
                first[next] = first[previous]
                suffix[next] = first[code]
                length[next] = length[previous] + 1
                write(code)
            } else if (code == next) {
                prefix[next] = previous
                first[next] = first[previous]
                suffix[next] = first[previous]
                length[next] = length[previous] + 1
                write(code)
            } else {
                throw TiffFormatException("Bloque LZW corrupto")
            }
            next++
            previous = code
            if (next + 1 >= (1 shl width) && width < 12) width++
        }
        return out.copyOf(outPos)
    }

    private const val TABLE_SIZE = 4096

    /**
     * Deshace en el sitio el [predictor] de un bloque de [rows] filas de [width] píxeles con [samples] muestras cada uno, ya
     * descomprimido y con la muestra de [type] en el orden de bytes [order].
     */
    fun undoPredictor(raw: ByteArray, predictor: Int, width: Int, rows: Int, type: TiffSampleType, samples: Int, order: ByteOrder) {
        when (predictor) {
            1 -> Unit
            2 -> {
                if (type.isFloat) throw TiffFormatException("El predictor 2 es de enteros")
                undoHorizontal(raw, width, rows, type, samples, order)
            }
            3 -> {
                if (!type.isFloat) throw TiffFormatException("El predictor 3 es de coma flotante")
                undoFloat(raw, width, rows, type, samples, order)
            }
            else -> throw TiffFormatException("Predictor $predictor no admitido")
        }
    }

    private fun undoHorizontal(raw: ByteArray, width: Int, rows: Int, type: TiffSampleType, samples: Int, order: ByteOrder) {
        val buffer = ByteBuffer.wrap(raw).order(order)
        val rowBytes = width * samples * type.bytes
        for (row in 0 until rows) {
            val base = row * rowBytes
            for (x in 1 until width) {
                for (c in 0 until samples) {
                    val at = base + (x * samples + c) * type.bytes
                    val before = at - samples * type.bytes
                    if (type.bytes == 2) {
                        buffer.putShort(at, (buffer.getShort(at) + buffer.getShort(before)).toShort())
                    } else {
                        buffer.putInt(at, buffer.getInt(at) + buffer.getInt(before))
                    }
                }
            }
        }
    }

    /**
     * TechNote 3: por fila, se acumula byte a byte y los bytes están en planos del más al menos significativo; se devuelven a las
     * muestras en el orden de bytes [order] del fichero.
     */
    private fun undoFloat(raw: ByteArray, width: Int, rows: Int, type: TiffSampleType, samples: Int, order: ByteOrder) {
        val perRow = width * samples
        val rowBytes = perRow * type.bytes
        val line = ByteArray(rowBytes)
        for (row in 0 until rows) {
            val base = row * rowBytes
            for (k in 1 until rowBytes) raw[base + k] = (raw[base + k] + raw[base + k - 1]).toByte()
            System.arraycopy(raw, base, line, 0, rowBytes)
            for (s in 0 until perRow) {
                for (b in 0 until type.bytes) {
                    val plane = if (order == ByteOrder.BIG_ENDIAN) b else type.bytes - 1 - b
                    raw[base + s * type.bytes + b] = line[plane * perRow + s]
                }
            }
        }
    }

    /** Las [count] primeras muestras de [raw] como `Double`, sin redondear, en el orden de bytes [order]. */
    fun toValues(raw: ByteArray, type: TiffSampleType, order: ByteOrder, count: Int): DoubleArray {
        val buffer = ByteBuffer.wrap(raw).order(order)
        return DoubleArray(count) { k ->
            when (type) {
                TiffSampleType.UINT16 -> (buffer.getShort(k * 2).toInt() and 0xFFFF).toDouble()
                TiffSampleType.INT16 -> buffer.getShort(k * 2).toDouble()
                TiffSampleType.UINT32 -> (buffer.getInt(k * 4).toLong() and 0xFFFFFFFFL).toDouble()
                TiffSampleType.INT32 -> buffer.getInt(k * 4).toDouble()
                TiffSampleType.FLOAT32 -> buffer.getFloat(k * 4).toDouble()
                TiffSampleType.FLOAT64 -> buffer.getDouble(k * 8)
            }
        }
    }
}
