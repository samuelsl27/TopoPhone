package es.topophone.core.model.surface

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlin.random.Random
import org.junit.jupiter.api.Test

/** El bloque binario de una tesela de la superficie (S042/RF-14, plan A3). */
class SurfaceTileCodecTest {

    private fun tile() = SurfaceTile(
        doubleArrayOf(440290.123456789, 440310.987654321, 440300.5),
        doubleArrayOf(4474257.000000001, 4474257.25, 4474277.5),
        doubleArrayOf(600.0, 602.458, 601.0000000001),
        intArrayOf(0, 1, 2),
    )

    @Test
    fun `S042 RF-14 las coordenadas y las cotas vuelven bit a bit sin redondear`() {
        val original = tile()

        val back = SurfaceTileCodec.decode(SurfaceTileCodec.encode(original))

        back.easting.map { it.toRawBits() } shouldBe original.easting.map { it.toRawBits() }
        back.northing.map { it.toRawBits() } shouldBe original.northing.map { it.toRawBits() }
        back.height.map { it.toRawBits() } shouldBe original.height.map { it.toRawBits() }
        back.triangles.toList() shouldBe listOf(0, 1, 2)
        back shouldBe original
    }

    @Test
    fun `S042 RF-14 una tesela vacia vuelve vacia y sin recuadro`() {
        val empty = SurfaceTile(DoubleArray(0), DoubleArray(0), DoubleArray(0), IntArray(0))

        val back = SurfaceTileCodec.decode(SurfaceTileCodec.encode(empty))

        back.vertexCount shouldBe 0
        back.triangleCount shouldBe 0
        back.bounds.shouldBeNull()
    }

    @Test
    fun `S042 RF-14 el recuadro es el de sus vertices`() {
        tile().bounds shouldBe SurfaceExtent(440290.123456789, 440310.987654321, 4474257.000000001, 4474277.5)
    }

    @Test
    fun `S042 RF-14 una tesela con muchos triangulos vuelve igual`() {
        val random = Random(42)
        val n = 6_000
        val big = SurfaceTile(
            DoubleArray(n) { random.nextDouble(440000.0, 441000.0) },
            DoubleArray(n) { random.nextDouble(4474000.0, 4475000.0) },
            DoubleArray(n) { random.nextDouble(500.0, 700.0) },
            IntArray(3 * 4_000) { random.nextInt(n) },
        )

        val bytes = SurfaceTileCodec.encode(big)

        bytes.size shouldBe SurfaceTileCodec.encodedSize(big)
        SurfaceTileCodec.decode(bytes) shouldBe big
    }

    @Test
    fun `S042 RF-14 una tesela de justo el limite se codifica y una de mas no`() {
        val perVertex = 3 * Double.SIZE_BYTES
        val fits = (SurfaceTileCodec.MAX_TILE_BYTES - 8) / perVertex
        val atLimit = SurfaceTile(DoubleArray(fits), DoubleArray(fits), DoubleArray(fits), IntArray(0))
        val over = SurfaceTile(DoubleArray(fits + 1), DoubleArray(fits + 1), DoubleArray(fits + 1), IntArray(0))

        (SurfaceTileCodec.encode(atLimit).size <= SurfaceTileCodec.MAX_TILE_BYTES) shouldBe true
        shouldThrow<IllegalArgumentException> { SurfaceTileCodec.encode(over) }
    }

    @Test
    fun `S042 RF-14 un bloque cortado o de otro formato se rechaza`() {
        val bytes = SurfaceTileCodec.encode(tile())

        shouldThrow<IllegalArgumentException> { SurfaceTileCodec.decode(bytes.copyOf(bytes.size - 3)) }
        shouldThrow<IllegalArgumentException> { SurfaceTileCodec.decode(bytes + byteArrayOf(0)) }
        shouldThrow<IllegalArgumentException> { SurfaceTileCodec.decode(bytes.also { it[0] = 99 }) }
        shouldThrow<IllegalArgumentException> { SurfaceTileCodec.decode(byteArrayOf()) }
    }

    @Test
    fun `S042 RF-14 un triangulo que cita un vertice que no existe no se construye`() {
        shouldThrow<IllegalArgumentException> {
            SurfaceTile(doubleArrayOf(1.0), doubleArrayOf(1.0), doubleArrayOf(1.0), intArrayOf(0, 0, 1))
        }
        shouldThrow<IllegalArgumentException> { SurfaceTile(doubleArrayOf(1.0), doubleArrayOf(1.0), doubleArrayOf(1.0), intArrayOf(0, 0)) }
        shouldThrow<IllegalArgumentException> { SurfaceTile(doubleArrayOf(1.0, 2.0), doubleArrayOf(1.0), doubleArrayOf(1.0), IntArray(0)) }
    }
}
