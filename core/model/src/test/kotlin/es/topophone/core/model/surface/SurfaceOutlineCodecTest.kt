package es.topophone.core.model.surface

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import kotlin.random.Random
import org.junit.jupiter.api.Test

/** Los bloques binarios del contorno de una superficie (S061/RF-6, plan «Formato del contorno»). */
class SurfaceOutlineCodecTest {

    private fun ring(exterior: Boolean, n: Int, seed: Int = n): SurfaceRing {
        val r = Random(seed)
        return SurfaceRing(
            exterior,
            DoubleArray(n) { 440_000.0 + r.nextDouble() * 1_000.0 },
            DoubleArray(n) { 4_474_000.0 + r.nextDouble() * 1_000.0 },
        )
    }

    @Test
    fun `S061 RF-6 un exterior y un hueco vuelven bit a bit sin redondear`() {
        val outline = SurfaceOutline(
            listOf(
                SurfaceRing(
                    true,
                    doubleArrayOf(440000.123456789, 440010.0, 440010.0),
                    doubleArrayOf(4474000.000000001, 4474000.0, 4474010.5),
                ),
                SurfaceRing(false, doubleArrayOf(440002.0, 440003.0, 440003.0), doubleArrayOf(4474002.0, 4474003.0, 4474002.0)),
            ),
        )

        val parts = SurfaceOutlineCodec.encode(outline)
        val back = SurfaceOutlineCodec.decode(parts)

        parts.size shouldBe 1
        back shouldBe outline
        back.rings[0].easting.map { it.toRawBits() } shouldBe outline.rings[0].easting.map { it.toRawBits() }
        back.rings.map { it.exterior } shouldBe listOf(true, false)
    }

    @Test
    fun `S061 RF-6 un contorno sin anillos da un bloque y vuelve vacio`() {
        val parts = SurfaceOutlineCodec.encode(SurfaceOutline(emptyList()))

        parts.size shouldBe 1
        SurfaceOutlineCodec.decode(parts).rings shouldBe emptyList()
    }

    @Test
    fun `S061 RF-6 un anillo de 40000 vertices se parte en bloques de 256 kB y vuelve entero`() {
        val outline = SurfaceOutline(listOf(ring(true, 3), ring(true, 40_000), ring(false, 7)))

        val parts = SurfaceOutlineCodec.encode(outline)

        parts.size shouldBeGreaterThan 2
        parts.forEach { it.size shouldBeLessThanOrEqual SurfaceOutlineCodec.MAX_PART_BYTES }
        SurfaceOutlineCodec.decode(parts) shouldBe outline
    }

    @Test
    fun `S061 RF-6 con bloques pequenos muchos anillos se reparten sin perder ninguno`() {
        val outline = SurfaceOutline((1..50).map { ring(it % 3 == 0, it * 7, seed = it) })

        val parts = SurfaceOutlineCodec.encode(outline, maxPartBytes = 1_000)

        parts.forEach { it.size shouldBeLessThanOrEqual 1_000 }
        SurfaceOutlineCodec.decode(parts) shouldBe outline
    }

    @Test
    fun `S061 RF-6 un bloque cortado o de otro formato se rechaza`() {
        val parts = SurfaceOutlineCodec.encode(SurfaceOutline(listOf(ring(true, 10))))

        shouldThrow<IllegalArgumentException> { SurfaceOutlineCodec.decode(listOf(parts[0].copyOf(parts[0].size - 3))) }
        shouldThrow<IllegalArgumentException> { SurfaceOutlineCodec.decode(listOf(byteArrayOf(9, 0))) }
    }

    @Test
    fun `S061 RF-6 un anillo partido sin su final se rechaza`() {
        val parts = SurfaceOutlineCodec.encode(SurfaceOutline(listOf(ring(true, 200))), maxPartBytes = 500)

        shouldThrow<IllegalArgumentException> { SurfaceOutlineCodec.decode(parts.dropLast(1)) }
    }
}
