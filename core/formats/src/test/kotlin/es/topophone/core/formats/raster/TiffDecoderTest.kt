package es.topophone.core.formats.raster

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.Deflater
import kotlin.random.Random
import org.junit.jupiter.api.Test

/** El descodificador de bloques TIFF: sin comprimir, LZW, Deflate y los predictores 2 y 3 (S045/RF-2). */
class TiffDecoderTest {

    private val random = Random(45)

    /** LZW de TIFF como lo escribe libtiff, para fabricar los bloques de prueba: 9 a 12 bits, MSB primero, cambio de anchura temprano. */
    private fun lzwEncode(data: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        var acc = 0L
        var bits = 0

        fun emit(code: Int, width: Int) {
            acc = (acc shl width) or code.toLong()
            bits += width
            while (bits >= 8) {
                bits -= 8
                out.write(((acc shr bits) and 0xFF).toInt())
            }
            acc = acc and ((1L shl bits) - 1)
        }

        var table = HashMap<List<Byte>, Int>().also { t -> for (i in 0 until 256) t[listOf(i.toByte())] = i }
        var next = 258
        var width = 9
        emit(256, width)
        var current = emptyList<Byte>()
        for (b in data) {
            val candidate = current + b
            if (candidate in table) {
                current = candidate
                continue
            }
            emit(table.getValue(current), width)
            table[candidate] = next++
            if (next == 4094) {
                emit(256, width)
                table = HashMap<List<Byte>, Int>().also { t -> for (i in 0 until 256) t[listOf(i.toByte())] = i }
                next = 258
                width = 9
            } else if (next > (1 shl width) - 1) {
                width++
            }
            current = listOf(b)
        }
        if (current.isNotEmpty()) {
            emit(table.getValue(current), width)
            next++
            if (next > (1 shl width) - 1 && width < 12) width++
        }
        emit(257, width)
        if (bits > 0) emit(0, 8 - bits)
        return out.toByteArray()
    }

    private fun deflate(data: ByteArray): ByteArray {
        val deflater = Deflater()
        deflater.setInput(data)
        deflater.finish()
        val out = ByteArrayOutputStream()
        val chunk = ByteArray(4096)
        while (!deflater.finished()) out.write(chunk, 0, deflater.deflate(chunk))
        deflater.end()
        return out.toByteArray()
    }

    private fun shorts(values: IntArray, order: ByteOrder): ByteArray =
        ByteBuffer.allocate(values.size * 2).order(order).also { b -> values.forEach { b.putShort(it.toShort()) } }.array()

    private fun ints(values: IntArray, order: ByteOrder): ByteArray =
        ByteBuffer.allocate(values.size * 4).order(order).also { b -> values.forEach { b.putInt(it) } }.array()

    private fun floats(values: FloatArray, order: ByteOrder): ByteArray =
        ByteBuffer.allocate(values.size * 4).order(order).also { b -> values.forEach { b.putFloat(it) } }.array()

    private fun doubles(values: DoubleArray, order: ByteOrder): ByteArray =
        ByteBuffer.allocate(values.size * 8).order(order).also { b -> values.forEach { b.putDouble(it) } }.array()

    @Test
    fun `S045 RF-2 el LZW de la especificacion de TIFF se descodifica`() {
        // TIFF 6.0, sección 13: 7 7 7 8 8 7 7 6 6 se codifica 256 7 258 8 8 258 6 6 257 con códigos de 9 bits.
        val bits = listOf(256, 7, 258, 8, 8, 258, 6, 6, 257).joinToString("") { it.toString(2).padStart(9, '0') }.padEnd(88, '0')
        val bytes = ByteArray(bits.length / 8) { bits.substring(it * 8, it * 8 + 8).toInt(2).toByte() }

        TiffDecoder.decompress(TiffDecoder.LZW, bytes, 9) shouldBe byteArrayOf(7, 7, 7, 8, 8, 7, 7, 6, 6)
    }

    @Test
    fun `S045 RF-2 el LZW vuelve igual con datos repetidos y con datos al azar que reinician la tabla`() {
        val inputs = listOf(
            ByteArray(0),
            byteArrayOf(1),
            ByteArray(100_000) { (it % 7).toByte() },
            ByteArray(40_000) { random.nextInt(256).toByte() },
            ByteArray(40_000) { random.nextInt(2).toByte() },
        )
        for (data in inputs) TiffDecoder.decompress(TiffDecoder.LZW, lzwEncode(data), data.size) shouldBe data
    }

    @Test
    fun `S045 RF-2 Deflate en sus dos codigos y sin comprimir`() {
        val data = ByteArray(20_000) { (it * 31 % 251).toByte() }

        TiffDecoder.decompress(TiffDecoder.DEFLATE, deflate(data), data.size) shouldBe data
        TiffDecoder.decompress(TiffDecoder.DEFLATE_ADOBE, deflate(data), data.size) shouldBe data
        TiffDecoder.decompress(TiffDecoder.NONE, data, data.size) shouldBe data
    }

    @Test
    fun `S045 RF-4 una compresion que no se lee o un bloque corrupto no se descomprime`() {
        shouldThrow<TiffFormatException> { TiffDecoder.decompress(7, ByteArray(10), 10) }
        shouldThrow<TiffFormatException> { TiffDecoder.decompress(TiffDecoder.DEFLATE, ByteArray(10) { 1 }, 10) }
        shouldThrow<TiffFormatException> { TiffDecoder.decompress(TiffDecoder.DEFLATE, deflate(ByteArray(5)), 10) }
        shouldThrow<TiffFormatException> { TiffDecoder.decompress(TiffDecoder.NONE, ByteArray(5), 10) }
        TiffDecoder.isSupported(TiffDecoder.NONE) shouldBe true
        TiffDecoder.isSupported(TiffDecoder.LZW) shouldBe true
        TiffDecoder.isSupported(TiffDecoder.DEFLATE) shouldBe true
        TiffDecoder.isSupported(TiffDecoder.DEFLATE_ADOBE) shouldBe true
        TiffDecoder.isSupported(7) shouldBe false
        TiffDecoder.isSupported(32773) shouldBe false
    }

    @Test
    fun `S045 RF-2 los enteros de 16 bits en little y big endian`() {
        val values = intArrayOf(600, 601, 0, -9999, 32767, -32768, 12)
        for (order in listOf(ByteOrder.LITTLE_ENDIAN, ByteOrder.BIG_ENDIAN)) {
            val back = TiffDecoder.toValues(shorts(values, order), TiffSampleType.INT16, order, values.size)
            back.toList() shouldBe values.map { it.toDouble() }
        }
        val unsigned = TiffDecoder.toValues(
            shorts(intArrayOf(65535, 1, 600), ByteOrder.LITTLE_ENDIAN),
            TiffSampleType.UINT16,
            ByteOrder.LITTLE_ENDIAN,
            3,
        )
        unsigned.toList() shouldBe listOf(65535.0, 1.0, 600.0)
    }

    @Test
    fun `S045 RF-2 los enteros de 32 bits con y sin signo`() {
        val values = intArrayOf(600_000, -9999, Int.MAX_VALUE, Int.MIN_VALUE)
        val bytes = ints(values, ByteOrder.LITTLE_ENDIAN)

        TiffDecoder.toValues(bytes, TiffSampleType.INT32, ByteOrder.LITTLE_ENDIAN, 4).toList() shouldBe values.map { it.toDouble() }
        TiffDecoder.toValues(ints(intArrayOf(-1), ByteOrder.BIG_ENDIAN), TiffSampleType.UINT32, ByteOrder.BIG_ENDIAN, 1)[0] shouldBe
            4294967295.0
    }

    @Test
    fun `S045 RF-2 los reales de 32 y de 64 bits sin perder ningun valor`() {
        val f = floatArrayOf(600.123f, Float.NaN, -9999f, 0.5f)
        val d = doubleArrayOf(600.123456789012, Double.NaN, -9999.0, 1e-9)
        for (order in listOf(ByteOrder.LITTLE_ENDIAN, ByteOrder.BIG_ENDIAN)) {
            val back32 = TiffDecoder.toValues(floats(f, order), TiffSampleType.FLOAT32, order, 4)
            back32[0] shouldBe 600.123f.toDouble()
            back32[1].isNaN() shouldBe true
            val back64 = TiffDecoder.toValues(doubles(d, order), TiffSampleType.FLOAT64, order, 4)
            back64[0] shouldBe 600.123456789012
            back64[3] shouldBe 1e-9
        }
    }

    @Test
    fun `S045 RF-2 el predictor 2 deshace la diferencia horizontal de cada fila en enteros`() {
        val width = 5
        val rows = 3
        val original = intArrayOf(600, 601, 603, 600, 595, 10, 9, 8, 7, 6, -9999, -9999, 3, 4, 5)
        val diffs = IntArray(original.size) { k -> if (k % width == 0) original[k] else original[k] - original[k - 1] }
        val raw = shorts(diffs, ByteOrder.LITTLE_ENDIAN)

        TiffDecoder.undoPredictor(raw, 2, width, rows, TiffSampleType.INT16, 1, ByteOrder.LITTLE_ENDIAN)

        TiffDecoder.toValues(raw, TiffSampleType.INT16, ByteOrder.LITTLE_ENDIAN, original.size).toList() shouldBe
            original.map { it.toDouble() }
    }

    @Test
    fun `S045 RF-2 el predictor 2 con enteros de 32 bits y big endian`() {
        val width = 4
        val original = intArrayOf(100_000, 100_005, 99_990, 100_000, 1, 2, 3, 4)
        val diffs = IntArray(original.size) { k -> if (k % width == 0) original[k] else original[k] - original[k - 1] }
        val raw = ints(diffs, ByteOrder.BIG_ENDIAN)

        TiffDecoder.undoPredictor(raw, 2, width, 2, TiffSampleType.INT32, 1, ByteOrder.BIG_ENDIAN)

        TiffDecoder.toValues(raw, TiffSampleType.INT32, ByteOrder.BIG_ENDIAN, original.size).toList() shouldBe
            original.map { it.toDouble() }
    }

    /** El predictor 3 de TIFF (TechNote 3): por fila, los bytes en planos de más a menos significativo y la diferencia byte a byte. */
    private fun floatPredict(values: DoubleArray, width: Int, bytesPerSample: Int): ByteArray {
        val out = ByteArrayOutputStream()
        for (row in values.indices step width) {
            val be = (row until row + width).map { k ->
                if (bytesPerSample ==
                    4
                ) {
                    ByteBuffer.allocate(4).putFloat(values[k].toFloat()).array()
                } else {
                    ByteBuffer.allocate(8).putDouble(values[k]).array()
                }
            }
            val planes = ByteArray(width * bytesPerSample) { n -> be[n % width][n / width] }
            for (k in planes.size - 1 downTo 1) planes[k] = (planes[k] - planes[k - 1]).toByte()
            out.write(planes)
        }
        return out.toByteArray()
    }

    @Test
    fun `S045 RF-2 el predictor 3 de coma flotante en 32 y en 64 bits`() {
        val width = 6
        val values = DoubleArray(width * 3) { if (it == 4) Double.NaN else 600.0 + it * 0.37 }
        for (order in listOf(ByteOrder.LITTLE_ENDIAN, ByteOrder.BIG_ENDIAN)) {
            val v32 = DoubleArray(values.size) { values[it].toFloat().toDouble() }
            val raw32 = floatPredict(v32, width, 4)
            TiffDecoder.undoPredictor(raw32, 3, width, 3, TiffSampleType.FLOAT32, 1, order)
            val back32 = TiffDecoder.toValues(raw32, TiffSampleType.FLOAT32, order, values.size)
            back32.indices.forEach { k -> if (v32[k].isNaN()) back32[k].isNaN() shouldBe true else back32[k] shouldBe v32[k] }

            val raw64 = floatPredict(values, width, 8)
            TiffDecoder.undoPredictor(raw64, 3, width, 3, TiffSampleType.FLOAT64, 1, order)
            val back64 = TiffDecoder.toValues(raw64, TiffSampleType.FLOAT64, order, values.size)
            back64.indices.forEach { k -> if (values[k].isNaN()) back64[k].isNaN() shouldBe true else back64[k] shouldBe values[k] }
        }
    }

    @Test
    fun `S045 RF-4 un predictor que no se lee no se deshace`() {
        shouldThrow<TiffFormatException> {
            TiffDecoder.undoPredictor(ByteArray(8), 4, 2, 1, TiffSampleType.INT16, 1, ByteOrder.LITTLE_ENDIAN)
        }
        shouldThrow<TiffFormatException> {
            TiffDecoder.undoPredictor(ByteArray(8), 3, 2, 1, TiffSampleType.INT16, 1, ByteOrder.LITTLE_ENDIAN)
        }
        TiffDecoder.isSupportedPredictor(1) shouldBe true
        TiffDecoder.isSupportedPredictor(2) shouldBe true
        TiffDecoder.isSupportedPredictor(3) shouldBe true
        TiffDecoder.isSupportedPredictor(4) shouldBe false
    }

    @Test
    fun `S045 RF-2 el tipo de dato sale de los bits y del formato de la muestra`() {
        TiffSampleType.of(16, 1) shouldBe TiffSampleType.UINT16
        TiffSampleType.of(16, 2) shouldBe TiffSampleType.INT16
        TiffSampleType.of(32, 1) shouldBe TiffSampleType.UINT32
        TiffSampleType.of(32, 2) shouldBe TiffSampleType.INT32
        TiffSampleType.of(32, 3) shouldBe TiffSampleType.FLOAT32
        TiffSampleType.of(64, 3) shouldBe TiffSampleType.FLOAT64
        TiffSampleType.of(8, 1) shouldBe null
        TiffSampleType.of(64, 1) shouldBe null
        TiffSampleType.of(16, 3) shouldBe null
        TiffSampleType.FLOAT64.isFloat shouldBe true
        TiffSampleType.INT16.isFloat shouldBe false
    }
}
