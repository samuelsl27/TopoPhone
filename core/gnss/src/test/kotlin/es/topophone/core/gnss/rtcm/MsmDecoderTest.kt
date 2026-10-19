package es.topophone.core.gnss.rtcm

import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** SNR (CNR) de la base en los mensajes MSM 4 a 7; los MSM 1 a 3 no la traen (S015/RF-8, RF-10). */
class MsmDecoderTest {

    /**
     * Un MSM con [cnr] por celda (satélite, señal), en unidades del mensaje: 1 dB-Hz en MSM4 y MSM5, 1/16 dB-Hz en MSM6 y
     * MSM7. Los demás datos van a cero.
     */
    private fun msm(type: Int, sats: List<Int>, sigs: List<Int>, cnr: Map<Pair<Int, Int>, Long>): ByteArray {
        val level = (type - 1071) % 10 + 1
        val writer = RtcmBitWriter()
            .unsigned(type, 12)
            .unsigned(6, 12)
            .unsigned(0, 30 + 1 + 3 + 7 + 2 + 2 + 1 + 3)
            .unsigned(sats.fold(0L) { mask, s -> mask or (1L shl (64 - s)) }, 64)
            .unsigned(sigs.fold(0L) { mask, g -> mask or (1L shl (32 - g)) }, 32)
        val cells = sats.flatMap { s -> sigs.map { g -> s to g } }
        cells.forEach { writer.unsigned(if (it in cnr) 1 else 0, 1) }
        val present = cells.filter { it in cnr }
        val n = sats.size
        val c = present.size
        writer.unsigned(0, if (level == 4 || level == 6) 18 * n else 36 * n)
        writer.unsigned(0, if (level == 4 || level == 5) 42 * c else 55 * c)
        present.forEach { writer.unsigned(cnr.getValue(it), if (level == 4 || level == 5) 6 else 10) }
        if (level == 5 || level == 7) writer.unsigned(0, 15 * c)
        return writer.bytes()
    }

    @Test
    fun `S015 RF-8 el MSM5 real da la CNR de cada satelite y senal`() {
        val gps = MsmDecoder.decode(RtcmFixtures.payloadsOf(1075).first()).shouldNotBeNull()
        val glonass = MsmDecoder.decode(RtcmFixtures.payloadsOf(1085).first()).shouldNotBeNull()
        val galileo = MsmDecoder.decode(RtcmFixtures.payloadsOf(1095).first()).shouldNotBeNull()
        val beidou = MsmDecoder.decode(RtcmFixtures.payloadsOf(1125).first()).shouldNotBeNull()

        gps.system shouldBe RtcmSystem.GPS
        gps.stationId shouldBe 6
        gps.cells shouldHaveSize 38
        gps.cells shouldContainAll listOf(MsmCell(5, 2, 35.0), MsmCell(5, 10, 34.0), MsmCell(16, 2, 51.0))
        glonass.system shouldBe RtcmSystem.GLONASS
        glonass.cells shouldHaveSize 21
        glonass.cells shouldContainAll listOf(MsmCell(7, 9, 39.0), MsmCell(15, 2, 54.0))
        galileo.system shouldBe RtcmSystem.GALILEO
        galileo.cells shouldHaveSize 50
        galileo.cells shouldContainAll listOf(MsmCell(2, 2, 41.0), MsmCell(2, 19, 48.0), MsmCell(3, 2, 35.0))
        beidou.system shouldBe RtcmSystem.BEIDOU
        beidou.cells shouldHaveSize 48
        beidou.cells shouldContainAll listOf(MsmCell(6, 23, 44.0), MsmCell(6, 31, 36.0), MsmCell(9, 14, 41.0))
    }

    @Test
    fun `S015 RF-8 todos los MSM5 del flujo real se leen enteros`() {
        val types = listOf(1075, 1085, 1095, 1125)
        val decoded = RtcmFixtures.payloads.filter { it.first in types }.map { MsmDecoder.decode(it.second) }

        decoded shouldHaveSize 99
        decoded.forEach { msm ->
            msm.shouldNotBeNull()
            msm.cells.forEach { (it.cnrDbHz in 20.0..63.0) shouldBe true }
        }
    }

    @Test
    fun `S015 RF-8 el MSM4 da la CNR en dB-Hz enteros`() {
        val payload = msm(1094, sats = listOf(2, 36), sigs = listOf(2, 22), cnr = mapOf((2 to 2) to 41L, (36 to 22) to 47L))

        MsmDecoder.decode(payload) shouldBe MsmCnr(RtcmSystem.GALILEO, 6, listOf(MsmCell(2, 2, 41.0), MsmCell(36, 22, 47.0)))
    }

    @Test
    fun `S015 RF-8 el MSM6 y el MSM7 dan la CNR con 1 16 de dB-Hz`() {
        val cnr = mapOf((1 to 2) to 712L, (64 to 32) to 641L)

        val msm6 = MsmDecoder.decode(msm(1076, sats = listOf(1, 64), sigs = listOf(2, 32), cnr = cnr)).shouldNotBeNull()
        val msm7 = MsmDecoder.decode(msm(1127, sats = listOf(1, 64), sigs = listOf(2, 32), cnr = cnr)).shouldNotBeNull()

        msm6.cells shouldBe listOf(MsmCell(1, 2, 44.5), MsmCell(64, 32, 40.0625))
        msm7.system shouldBe RtcmSystem.BEIDOU
        msm7.cells shouldBe msm6.cells
    }

    @Test
    fun `S015 RF-8 una celda con la CNR a cero no tiene valor`() {
        val payload = msm(1075, sats = listOf(3), sigs = listOf(2, 15), cnr = mapOf((3 to 2) to 0L, (3 to 15) to 40L))

        MsmDecoder.decode(payload).shouldNotBeNull().cells shouldBe listOf(MsmCell(3, 15, 40.0))
    }

    @Test
    fun `S015 RF-10 los MSM 1 a 3, otros tipos y los datos cortos no dan CNR`() {
        val msm5 = msm(1075, sats = listOf(3, 9), sigs = listOf(2), cnr = mapOf((3 to 2) to 40L, (9 to 2) to 41L))

        MsmDecoder.decode(msm(1071, sats = listOf(3), sigs = listOf(2), cnr = mapOf((3 to 2) to 40L))).shouldBeNull()
        MsmDecoder.decode(msm(1083, sats = listOf(3), sigs = listOf(2), cnr = mapOf((3 to 2) to 40L))).shouldBeNull()
        MsmDecoder.decode(RtcmFixtures.payloadsOf(1006).first()).shouldBeNull()
        MsmDecoder.decode(msm5.copyOf(msm5.size - 4)).shouldBeNull()
        MsmDecoder.decode(byteArrayOf(0x43, 0x30)).shouldBeNull()
    }
}
