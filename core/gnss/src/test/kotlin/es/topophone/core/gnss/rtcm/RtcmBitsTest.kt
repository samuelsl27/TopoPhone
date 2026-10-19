package es.topophone.core.gnss.rtcm

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import kotlin.random.Random
import org.junit.jupiter.api.Test

/** Lectura de los campos de bits de una carga RTCM, la base de los mensajes 1005/1006 y MSM (S015/RF-8, RF-12). */
class RtcmBitsTest {

    @Test
    fun `S015 RF-12 lee campos sin signo que cruzan los bytes`() {
        val bits = RtcmBits(byteArrayOf(0xD3.toByte(), 0x00, 0x13, 0x3E))

        bits.unsigned(4) shouldBe 0xD
        bits.unsigned(12) shouldBe 0x300
        bits.unsigned(12) shouldBe 0x133
        bits.remaining shouldBe 4
        bits.unsigned(4) shouldBe 0xE
        bits.remaining shouldBe 0
    }

    @Test
    fun `S015 RF-12 lee en complemento a dos los campos con signo de 38 bits`() {
        val payload = RtcmBitWriter().signed(-4_769_307_239L, 38).signed(46_048_891_789L, 38).bytes()

        val bits = RtcmBits(payload)

        bits.signed(38) shouldBe -4_769_307_239L
        bits.signed(38) shouldBe 46_048_891_789L
    }

    @Test
    fun `S015 RF-8 lee mascaras de 64 bits y salta campos`() {
        val mask = 0x8000_0000_0000_0001UL.toLong()
        val bits = RtcmBits(RtcmBitWriter().unsigned(5, 3).unsigned(mask, 64).unsigned(1, 1).bytes())

        bits.skip(3)
        bits.unsigned(64) shouldBe mask
        bits.unsigned(1) shouldBe 1L
    }

    @Test
    fun `S015 RF-12 vuelve a leer lo que se escribio, con cualquier ancho`() {
        val random = Random(20261019)
        val fields = List(500) {
            val width = random.nextInt(1, 64)
            val value = random.nextLong() shr (64 - width)
            width to value
        }
        val writer = RtcmBitWriter()
        fields.forEach { (width, value) -> writer.signed(value, width) }

        val bits = RtcmBits(writer.bytes())

        fields.forEach { (width, value) -> bits.signed(width) shouldBe value }
    }

    @Test
    fun `S015 RF-12 no lee mas alla del final`() {
        val bits = RtcmBits(byteArrayOf(0x01))
        bits.skip(4)

        shouldThrow<IllegalStateException> { bits.unsigned(5) }
    }
}
