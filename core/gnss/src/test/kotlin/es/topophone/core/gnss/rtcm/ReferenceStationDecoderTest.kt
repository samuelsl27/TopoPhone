package es.topophone.core.gnss.rtcm

import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** Posición de la estación de referencia en los mensajes RTCM 1005/1006 (S015/RF-12, RF-13). */
class ReferenceStationDecoderTest {

    /** Un 1005 o un 1006 montado campo a campo (RTCM 10403.3, DF002 a DF028). */
    private fun message(type: Int, station: Int, x: Long, y: Long, z: Long, antennaHeight: Long = 0) = RtcmBitWriter()
        .unsigned(type, 12)
        .unsigned(station, 12)
        .unsigned(0, 6) // año ITRF
        .unsigned(0b1110, 4) // GPS, GLONASS, Galileo y estación de referencia
        .signed(x, 38)
        .unsigned(0, 1)
        .unsigned(0, 1)
        .signed(y, 38)
        .unsigned(0, 2)
        .signed(z, 38)
        .apply { if (type == 1006) unsigned(antennaHeight, 16) }
        .bytes()

    @Test
    fun `S015 RF-12 el 1006 real da la estacion 6 de Aviles con su ARP y su altura de antena`() {
        val stations = RtcmFixtures.payloadsOf(1006).map { ReferenceStationDecoder.decode(it) }

        stations.size shouldBe 2
        stations.forEach { station ->
            station.shouldNotBeNull()
            station.stationId shouldBe 6
            station.x shouldBe (4_604_889.1789 plusOrMinus 1e-6)
            station.y shouldBe (-476_930.7239 plusOrMinus 1e-6)
            station.z shouldBe (4_372_677.1220 plusOrMinus 1e-6)
            station.antennaHeightM shouldBe 0.0
        }
    }

    @Test
    fun `S015 RF-13 el 1006 trae la altura de antena`() {
        val station = ReferenceStationDecoder.decode(message(1006, 4095, 1, -2, 3, antennaHeight = 18_000))

        station.shouldNotBeNull()
        station.stationId shouldBe 4095
        station.antennaHeightM shouldBe (1.8 plusOrMinus 1e-9)
    }

    @Test
    fun `S015 RF-12 el 1005 no trae altura de antena`() {
        val station = ReferenceStationDecoder.decode(message(1005, 2003, -12_345_678_901L, 4_769_307_239L, -43_726_771_220L))

        station.shouldNotBeNull()
        station.stationId shouldBe 2003
        station.x shouldBe (-1_234_567.8901 plusOrMinus 1e-6)
        station.y shouldBe (476_930.7239 plusOrMinus 1e-6)
        station.z shouldBe (-4_372_677.1220 plusOrMinus 1e-6)
        station.antennaHeightM.shouldBeNull()
    }

    @Test
    fun `S015 RF-12 otro tipo o unos datos cortos no dan estacion`() {
        val full = message(1006, 6, 1, 2, 3, antennaHeight = 1)

        ReferenceStationDecoder.decode(full.copyOf(full.size - 1)).shouldBeNull()
        ReferenceStationDecoder.decode(RtcmFixtures.payloadsOf(1075).first()).shouldBeNull()
        ReferenceStationDecoder.decode(byteArrayOf(0x3E)).shouldBeNull()
    }
}
