package es.topophone.core.gnss.signal

import es.topophone.core.gnss.nmea.Constellation
import es.topophone.core.gnss.rtcm.RtcmSystem
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * Número propio de cada satélite en su constelación, para unir las GSA, las GSV y los MSM (S015/RF-6, RF-8), y su letra
 * (S015/RF-5).
 */
class SatelliteNumberTest {

    @Test
    fun `S015 RF-6 los numeros NMEA pasan al numero propio de su constelacion`() {
        SatelliteNumber.native(Constellation.GPS, 5) shouldBe 5
        SatelliteNumber.native(Constellation.GLONASS, 70) shouldBe 6
        SatelliteNumber.native(Constellation.GLONASS, 96) shouldBe 32
        SatelliteNumber.native(Constellation.GLONASS, 7) shouldBe 7
        SatelliteNumber.native(Constellation.GALILEO, 36) shouldBe 36
        SatelliteNumber.native(Constellation.GALILEO, 302) shouldBe 2
        SatelliteNumber.native(Constellation.BEIDOU, 39) shouldBe 39
        SatelliteNumber.native(Constellation.BEIDOU, 211) shouldBe 11
        SatelliteNumber.native(Constellation.BEIDOU, 401) shouldBe 1
        SatelliteNumber.native(Constellation.QZSS, 193) shouldBe 1
        SatelliteNumber.native(Constellation.QZSS, 3) shouldBe 3
        SatelliteNumber.native(Constellation.NAVIC, 14) shouldBe 14
    }

    @Test
    fun `S015 RF-6 los numeros fuera de rango o sin constelacion no se unen`() {
        SatelliteNumber.native(Constellation.GPS, 40).shouldBeNull() // SBAS en una GSV de GPS
        SatelliteNumber.native(Constellation.GLONASS, 50).shouldBeNull()
        SatelliteNumber.native(Constellation.GALILEO, 0).shouldBeNull()
        SatelliteNumber.native(Constellation.QZSS, 203).shouldBeNull()
        SatelliteNumber.native(Constellation.MULTIPLE, 5).shouldBeNull()
        SatelliteNumber.native(Constellation.UNKNOWN, 5).shouldBeNull()
    }

    @Test
    fun `S015 RF-8 cada sistema RTCM es su constelacion, salvo SBAS`() {
        SatelliteNumber.constellation(RtcmSystem.GPS) shouldBe Constellation.GPS
        SatelliteNumber.constellation(RtcmSystem.GLONASS) shouldBe Constellation.GLONASS
        SatelliteNumber.constellation(RtcmSystem.GALILEO) shouldBe Constellation.GALILEO
        SatelliteNumber.constellation(RtcmSystem.BEIDOU) shouldBe Constellation.BEIDOU
        SatelliteNumber.constellation(RtcmSystem.QZSS) shouldBe Constellation.QZSS
        SatelliteNumber.constellation(RtcmSystem.NAVIC) shouldBe Constellation.NAVIC
        SatelliteNumber.constellation(RtcmSystem.SBAS).shouldBeNull()
    }

    @Test
    fun `S015 RF-5 cada constelacion tiene su letra y cada satelite su etiqueta`() {
        Constellation.entries.associateWith { SatelliteId(it, 5).label } shouldBe mapOf(
            Constellation.GPS to "G05",
            Constellation.GLONASS to "R05",
            Constellation.GALILEO to "E05",
            Constellation.BEIDOU to "C05",
            Constellation.QZSS to "J05",
            Constellation.NAVIC to "I05",
            Constellation.MULTIPLE to "?05",
            Constellation.UNKNOWN to "?05",
        )
        SatelliteId(Constellation.BEIDOU, 39).label shouldBe "C39"
    }
}
