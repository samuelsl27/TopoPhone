package es.topophone.core.gnss.signal

import es.topophone.core.gnss.nmea.Constellation
import es.topophone.core.gnss.rtcm.MsmDecoder
import es.topophone.core.gnss.rtcm.RtcmFixtures
import es.topophone.core.gnss.rtcm.RtcmSystem
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** Banda de frecuencia de una señal del rover (NMEA 4.11) y de la base (MSM), para comparar sus SNR (S015/RF-7, RF-8). */
class BandTest {

    @Test
    fun `S015 RF-7 la senal de la GSV da su banda`() {
        val expected = mapOf(
            (Constellation.GPS to 1) to Band.L1,
            (Constellation.GPS to 5) to Band.L2,
            (Constellation.GPS to 8) to Band.L5,
            (Constellation.GLONASS to 1) to Band.G1,
            (Constellation.GLONASS to 3) to Band.G2,
            (Constellation.GALILEO to 1) to Band.E5A,
            (Constellation.GALILEO to 2) to Band.E5B,
            (Constellation.GALILEO to 3) to Band.E5,
            (Constellation.GALILEO to 5) to Band.E6,
            (Constellation.GALILEO to 7) to Band.E1,
            (Constellation.BEIDOU to 1) to Band.B1,
            (Constellation.BEIDOU to 3) to Band.B1C,
            (Constellation.BEIDOU to 5) to Band.B2A,
            (Constellation.BEIDOU to 6) to Band.B2B,
            (Constellation.BEIDOU to 8) to Band.B3,
            (Constellation.BEIDOU to 11) to Band.B2,
            (Constellation.QZSS to 1) to Band.L1,
            (Constellation.QZSS to 6) to Band.L2,
            (Constellation.QZSS to 7) to Band.L5,
            (Constellation.NAVIC to 1) to Band.L5,
        )

        expected.forEach { (key, band) -> Band.fromNmea(key.first, key.second) shouldBe band }
    }

    @Test
    fun `S015 RF-7 sin identificador de senal o con uno desconocido no hay banda`() {
        Band.fromNmea(Constellation.GPS, null).shouldBeNull()
        Band.fromNmea(Constellation.GPS, 9).shouldBeNull()
        Band.fromNmea(Constellation.BEIDOU, 7).shouldBeNull()
        Band.fromNmea(Constellation.QZSS, 9).shouldBeNull()
        Band.fromNmea(Constellation.MULTIPLE, 1).shouldBeNull()
        Band.fromNmea(Constellation.UNKNOWN, 1).shouldBeNull()
    }

    @Test
    fun `S015 RF-8 la senal del MSM da su banda`() {
        val expected = mapOf(
            (RtcmSystem.GPS to 2) to Band.L1,
            (RtcmSystem.GPS to 10) to Band.L2,
            (RtcmSystem.GPS to 15) to Band.L2,
            (RtcmSystem.GPS to 23) to Band.L5,
            (RtcmSystem.GPS to 31) to Band.L1,
            (RtcmSystem.GLONASS to 2) to Band.G1,
            (RtcmSystem.GLONASS to 9) to Band.G2,
            (RtcmSystem.GALILEO to 2) to Band.E1,
            (RtcmSystem.GALILEO to 8) to Band.E6,
            (RtcmSystem.GALILEO to 15) to Band.E5B,
            (RtcmSystem.GALILEO to 19) to Band.E5,
            (RtcmSystem.GALILEO to 23) to Band.E5A,
            (RtcmSystem.BEIDOU to 2) to Band.B1,
            (RtcmSystem.BEIDOU to 8) to Band.B3,
            (RtcmSystem.BEIDOU to 14) to Band.B2,
            (RtcmSystem.BEIDOU to 23) to Band.B2A,
            (RtcmSystem.BEIDOU to 25) to Band.B2B,
            (RtcmSystem.BEIDOU to 31) to Band.B1C,
            (RtcmSystem.QZSS to 2) to Band.L1,
            (RtcmSystem.QZSS to 16) to Band.L2,
            (RtcmSystem.QZSS to 24) to Band.L5,
            (RtcmSystem.NAVIC to 22) to Band.L5,
        )

        expected.forEach { (key, band) -> Band.fromRtcm(key.first, key.second) shouldBe band }
        Band.fromRtcm(RtcmSystem.SBAS, 2).shouldBeNull()
        Band.fromRtcm(RtcmSystem.QZSS, 10).shouldBeNull()
        Band.fromRtcm(RtcmSystem.GPS, 1).shouldBeNull()
    }

    @Test
    fun `S015 RF-8 todas las senales de la grabacion real tienen banda`() {
        val cells = RtcmFixtures.payloads.mapNotNull { MsmDecoder.decode(it.second) }
            .flatMap { msm -> msm.cells.map { msm.system to it.signal } }
            .toSet()

        cells.forEach { (system, signal) -> Band.fromRtcm(system, signal).shouldNotBeNull() }
    }

    @Test
    fun `S015 RF-7 cada banda tiene el nombre que ve el usuario`() {
        Band.entries.map { it.label } shouldBe
            listOf("L1", "L2", "L5", "G1", "G2", "E1", "E5a", "E5b", "E5", "E6", "B1", "B1C", "B2", "B2a", "B2b", "B3")
    }
}
