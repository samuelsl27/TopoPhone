package es.topophone.core.geodesy

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import org.junit.jupiter.api.Test

/** Geoide EGM08-REDNAP y lector GeoTIFF (S002/RF-38, S002/RF-39; ≤ 1 mm frente a PROJ). */
class GeoidModelTest {

    @Test
    fun `S002 RF-38 la ondulacion EGM08-REDNAP coincide con PROJ a menos de 1 mm`() {
        val vectors = GeodesyFixtures.rows("geoide_egm08_rednap.csv").filter { it[2].isNotEmpty() }
        vectors.size shouldBeGreaterThan 150

        val failures = vectors.mapNotNull { (lat, lon, expected) ->
            val n = GeoidModels.egm08RednapPeninsula.undulationM(lat.toDouble(), lon.toDouble())
            when {
                n == null -> "sin valor en ($lat, $lon)"
                abs(n - expected.toDouble()) > 0.001 -> "($lat, $lon): ${n - expected.toDouble()} m"
                else -> null
            }
        }

        failures.shouldBeEmpty()
    }

    @Test
    fun `S002 RF-39 fuera de la cobertura de la Peninsula y Baleares no hay ondulacion y no se extrapola`() {
        val outside = GeodesyFixtures.rows("geoide_egm08_rednap.csv").filter { it[2].isEmpty() }
        outside.size shouldBeGreaterThan 3

        outside.forEach { (lat, lon) -> GeoidModels.egm08RednapPeninsula.undulationM(lat.toDouble(), lon.toDouble()).shouldBeNull() }
    }

    @Test
    fun `S002 RF-38 la rejilla de la app tiene la geometria esperada`() {
        val grid = GeoidModels.egm08RednapPeninsula.grid

        grid.width shouldBe 841
        grid.height shouldBe 541
        grid.firstNodeLongitudeDeg shouldBe -9.5
        grid.firstNodeLatitudeDeg shouldBe 43.9999964
        grid.stepLongitudeDeg shouldBe (1.0 / 60 plusOrMinus 1e-7)
    }

    // --- Lector GeoTIFF con rejillas sintéticas (tiras, sin compresión, orden de bytes, área, sin datos) ---

    @Test
    fun `S002 RF-38 interpolacion bilineal en una rejilla sintetica de tiras sin comprimir`() {
        // 3 x 2 nodos: fila norte 0, 1, 2; fila sur 10, 11, 12. Paso 1°, primer nodo en (lon 0, lat 1).
        val grid = GeoTiffGrid.read(SyntheticTiff.build(3, 2, floatArrayOf(0f, 1f, 2f, 10f, 11f, 12f)))

        grid.interpolate(1.0, 0.0) shouldBe 0.0 // nodo noroeste
        grid.interpolate(0.0, 2.0) shouldBe 12.0 // último nodo exacto (sureste)
        grid.interpolate(0.5, 0.5) shouldBe (5.5 plusOrMinus 1e-12)
        grid.interpolate(0.25, 1.75) shouldBe (9.25 plusOrMinus 1e-12)
        grid.interpolate(1.000001, 0.0).shouldBeNull()
        grid.interpolate(0.5, 2.000001).shouldBeNull()
    }

    @Test
    fun `S002 RF-38 el orden de bytes big-endian se lee igual`() {
        val values = floatArrayOf(1.5f, -2.25f, 3f, 4f)

        val grid = GeoTiffGrid.read(SyntheticTiff.build(2, 2, values, order = ByteOrder.BIG_ENDIAN))

        grid.interpolate(1.0, 0.0) shouldBe 1.5
        grid.interpolate(1.0, 1.0) shouldBe -2.25
    }

    @Test
    fun `S002 RF-38 con PixelIsArea los nodos estan en el centro de cada pixel`() {
        val grid = GeoTiffGrid.read(SyntheticTiff.build(2, 2, floatArrayOf(1f, 2f, 3f, 4f), pixelIsArea = true))

        // El anclaje está en la esquina noroeste del primer píxel (lon 0, lat 2): su centro es (0,5; 1,5).
        grid.firstNodeLongitudeDeg shouldBe 0.5
        grid.firstNodeLatitudeDeg shouldBe 1.5
        grid.interpolate(1.5, 0.5) shouldBe 1.0
        grid.interpolate(0.5, 1.5) shouldBe 4.0
    }

    @Test
    fun `S002 RF-39 un nodo sin datos hace que su celda no tenga valor, como en PROJ`() {
        // Fila norte: 1, 2, sin datos; fila sur: 3, 4, 5.
        val grid = GeoTiffGrid.read(SyntheticTiff.build(3, 2, floatArrayOf(1f, 2f, -9999f, 3f, 4f, 5f), noData = "-9999"))

        grid.interpolate(0.5, 0.5) shouldBe (2.5 plusOrMinus 1e-12) // celda oeste: completa
        grid.interpolate(0.5, 1.5).shouldBeNull() // celda este: un nodo sin datos
        grid.interpolate(0.0, 2.0).shouldBeNull() // nodo válido, pero su celda incluye el nodo sin datos
    }

    @Test
    fun `S002 RF-38 un fichero que no es TIFF o con muestras no flotantes se rechaza`() {
        runCatching { GeoTiffGrid.read("no es un tiff".toByteArray()) }.isFailure shouldBe true
        runCatching { GeoTiffGrid.read(SyntheticTiff.build(1, 1, floatArrayOf(0f), sampleFormat = 1)) }.isFailure shouldBe true
    }
}

/** Escribe GeoTIFF mínimos (una tira, float32 sin comprimir) para probar el lector. */
object SyntheticTiff {
    fun build(
        width: Int,
        height: Int,
        values: FloatArray,
        order: ByteOrder = ByteOrder.LITTLE_ENDIAN,
        pixelIsArea: Boolean = false,
        noData: String? = null,
        sampleFormat: Int = 3,
    ): ByteArray {
        data class Entry(val tag: Int, val type: Int, val count: Int, val payload: ByteArray)

        fun shorts(vararg v: Int) = ByteBuffer.allocate(2 * v.size).order(order).apply { v.forEach { putShort(it.toShort()) } }.array()
        fun longs(vararg v: Int) = ByteBuffer.allocate(4 * v.size).order(order).apply { v.forEach { putInt(it) } }.array()
        fun doubles(vararg v: Double) = ByteBuffer.allocate(8 * v.size).order(order).apply { v.forEach { putDouble(it) } }.array()

        val pixels = ByteBuffer.allocate(4 * values.size).order(order).apply { values.forEach { putFloat(it) } }.array()
        val entries = mutableListOf(
            Entry(256, 3, 1, shorts(width)),
            Entry(257, 3, 1, shorts(height)),
            Entry(258, 3, 1, shorts(32)),
            Entry(259, 3, 1, shorts(1)),
            Entry(273, 4, 1, longs(0)), // se corrige al final
            Entry(277, 3, 1, shorts(1)),
            Entry(278, 3, 1, shorts(height)),
            Entry(279, 4, 1, longs(pixels.size)),
            Entry(339, 3, 1, shorts(sampleFormat)),
            Entry(33550, 12, 3, doubles(1.0, 1.0, 0.0)),
            Entry(33922, 12, 6, doubles(0.0, 0.0, 0.0, 0.0, height - 1.0 + if (pixelIsArea) 1.0 else 0.0, 0.0)),
            Entry(34735, 3, 8, shorts(1, 1, 0, 1, 1025, 0, 1, if (pixelIsArea) 1 else 2)),
        )
        noData?.let { entries += Entry(42113, 2, it.length + 1, (it + "\u0000").toByteArray()) }

        val ifdOffset = 8
        val ifdSize = 2 + 12 * entries.size + 4
        var dataOffset = ifdOffset + ifdSize
        val extra = mutableListOf<ByteArray>()
        val pixelsOffset = dataOffset + entries.filter { it.payload.size > 4 }.sumOf { it.payload.size }
        val ifd = ByteBuffer.allocate(ifdSize).order(order)
        ifd.putShort(entries.size.toShort())
        for (entry in entries.sortedBy { it.tag }) {
            val payload = if (entry.tag == 273) longs(pixelsOffset) else entry.payload
            ifd.putShort(entry.tag.toShort()).putShort(entry.type.toShort()).putInt(entry.count)
            if (payload.size <= 4) {
                ifd.put(payload.copyOf(4))
            } else {
                ifd.putInt(dataOffset)
                extra += payload
                dataOffset += payload.size
            }
        }
        ifd.putInt(0)
        val header = ByteBuffer.allocate(8).order(order).apply {
            put(if (order == ByteOrder.LITTLE_ENDIAN) "II".toByteArray() else "MM".toByteArray())
            putShort(42)
            putInt(ifdOffset)
        }.array()
        return header + ifd.array() + extra.fold(ByteArray(0)) { acc, bytes -> acc + bytes } + pixels
    }
}
