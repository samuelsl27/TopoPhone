package es.topophone.core.gnss.nmea

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

/** Frases GSA y GSV (S003/RF-1, S003/RF-2, S003/RF-7). */
class GsaGsvParserTest {

    private fun sentence(body: String) =
        NmeaFramer().feed(("$" + body + "*" + "%02X".format(NmeaChecksum.compute(body)) + "\r\n").toByteArray()).single()

    @Test
    fun `S003 RF-1 una GSA por constelacion da modo, satelites usados y DOP`() {
        val gsa = GsaParser.parse(sentence("GPGSA,A,3,05,12,13,15,18,20,24,25,,,,,1.2,0.6,1.0,1")).shouldNotBeNull()

        gsa.mode shouldBe FixMode.FIX_3D
        gsa.constellation shouldBe Constellation.GPS
        gsa.satellitesUsed shouldBe listOf(5, 12, 13, 15, 18, 20, 24, 25)
        gsa.pdop shouldBe 1.2
        gsa.hdop shouldBe 0.6
        gsa.vdop shouldBe 1.0
    }

    @Test
    fun `S003 RF-1 en una GSA combinada la constelacion sale del identificador de sistema`() {
        GsaParser.parse(sentence("GNGSA,A,3,02,07,11,,,,,,,,,,1.5,0.8,1.3,3")).shouldNotBeNull().constellation shouldBe
            Constellation.GALILEO
        GsaParser.parse(sentence("GNGSA,A,2,02,07,11,,,,,,,,,,1.5,0.8,1.3")).shouldNotBeNull().let {
            it.constellation shouldBe Constellation.MULTIPLE
            it.mode shouldBe FixMode.FIX_2D
        }
    }

    @Test
    fun `S003 RF-1 una GSA sin posicion no tiene satelites ni DOP`() {
        val gsa = GsaParser.parse(sentence("GLGSA,A,1,,,,,,,,,,,,,,,,2")).shouldNotBeNull()

        gsa.mode shouldBe FixMode.NONE
        gsa.constellation shouldBe Constellation.GLONASS
        gsa.satellitesUsed.shouldBeEmpty()
        gsa.pdop.shouldBeNull()
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "GPGSA,A,3,05,12,,,,,,,,,,,1.x,0.6,1.0,1", // PDOP no numérico
            "GPGSA,A,4,05,12,,,,,,,,,,,1.2,0.6,1.0,1", // modo imposible
            "GPGSA,A,3,0a,12,,,,,,,,,,,1.2,0.6,1.0,1", // satélite no numérico
        ],
    )
    fun `S003 RF-7 una GSA con un campo que no se puede interpretar se descarta`(body: String) {
        GsaParser.parse(sentence(body)).shouldBeNull()
    }

    @Test
    fun `S003 RF-2 una GSV de NMEA 4_11 da los satelites en vista con elevacion, azimut y SNR por senal`() {
        val gsv = GsvParser.parse(sentence("GPGSV,3,1,11,05,62,045,48,12,35,301,44,13,22,089,40,15,10,190,35,1")).shouldNotBeNull()

        gsv.constellation shouldBe Constellation.GPS
        gsv.signalId shouldBe 1
        gsv.totalMessages shouldBe 3
        gsv.messageNumber shouldBe 1
        gsv.satellitesInView shouldBe 11
        gsv.satellites.map { it.prn } shouldBe listOf(5, 12, 13, 15)
        val first = gsv.satellites.first()
        first.elevationRad.shouldNotBeNull() shouldBe (Math.toRadians(62.0) plusOrMinus 1e-12)
        first.azimuthRad.shouldNotBeNull() shouldBe (Math.toRadians(45.0) plusOrMinus 1e-12)
        first.snrDbHz shouldBe 48
    }

    @Test
    fun `S003 RF-2 una GSV sin senal y con menos de cuatro satelites tambien vale`() {
        val gsv = GsvParser.parse(sentence("GLGSV,3,3,11,24,05,150,,25,40,060,42,29,15,320,30")).shouldNotBeNull()

        gsv.constellation shouldBe Constellation.GLONASS
        gsv.signalId.shouldBeNull()
        gsv.satellites.map { it.prn } shouldBe listOf(24, 25, 29)
        gsv.satellites.first().snrDbHz.shouldBeNull()
    }

    @Test
    fun `S003 RF-2 el identificador de senal hexadecimal se interpreta`() {
        GsvParser.parse(sentence("GAGSV,1,1,02,04,50,100,41,09,20,200,38,7")).shouldNotBeNull().let {
            it.constellation shouldBe Constellation.GALILEO
            it.signalId shouldBe 7
        }
        GsvParser.parse(sentence("GBGSV,1,1,01,30,45,180,40,B")).shouldNotBeNull().signalId shouldBe 11
    }

    @Test
    fun `S003 RF-2 los grupos de satelite vacios se ignoran`() {
        GsvParser.parse(sentence("GPGSV,1,1,02,05,62,045,48,12,35,301,44,,,,,,,,")).shouldNotBeNull().satellites.size shouldBe 2
    }

    @ParameterizedTest
    @ValueSource(strings = ["GPGSV,3,1,11,05,6x,045,48", "GPGSV,2,3,11,05,62,045,48", "GPGSV,0,0,00", "GPGSV,1,1,02,05,62,045,48,12,35"])
    fun `S003 RF-7 una GSV con un campo que no se puede interpretar se descarta`(body: String) {
        GsvParser.parse(sentence(body)).shouldBeNull()
    }
}
