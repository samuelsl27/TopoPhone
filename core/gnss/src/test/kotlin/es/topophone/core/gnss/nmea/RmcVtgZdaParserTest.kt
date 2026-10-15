package es.topophone.core.gnss.nmea

import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import java.time.LocalDate
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource

/** Frases RMC, VTG y ZDA, hora NMEA y constelaciones (S003/RF-3, S003/RF-4, S003/RF-7). */
class RmcVtgZdaParserTest {

    private fun sentence(body: String) = NmeaFramer().feed((nmea(body) + "\r\n").toByteArray()).single()

    private fun nmea(body: String) = "$" + body + "*" + "%02X".format(NmeaChecksum.compute(body))

    private val knot = 1852.0 / 3600.0

    @Test
    fun `S003 RF-3 S003 RF-4 una RMC valida da hora, fecha, validez, velocidad y rumbo`() {
        val rmc = RmcParser.parse(sentence("GNRMC,101530.20,A,4025.0080000,N,00342.2228000,W,0.025,123.4,151026,,,R,V")).shouldNotBeNull()

        rmc.utcMillisOfDay shouldBe 36_930_200
        rmc.date shouldBe LocalDate.of(2026, 10, 15)
        rmc.valid shouldBe true
        rmc.speedMps.shouldNotBeNull() shouldBe (0.025 * knot plusOrMinus 1e-12)
        rmc.courseRad.shouldNotBeNull() shouldBe (Math.toRadians(123.4) plusOrMinus 1e-12)
    }

    @Test
    fun `S003 RF-3 una RMC no valida conserva hora y fecha y deja vacios velocidad y rumbo`() {
        val rmc = RmcParser.parse(sentence("GNRMC,000001.00,V,,,,,,,010126,,,N")).shouldNotBeNull()

        rmc.utcMillisOfDay shouldBe 1_000
        rmc.date shouldBe LocalDate.of(2026, 1, 1)
        rmc.valid shouldBe false
        rmc.speedMps.shouldBeNull()
        rmc.courseRad.shouldBeNull()
    }

    @Test
    fun `S003 RF-3 una RMC de NMEA 2_0 sin modo tambien vale`() {
        val rmc = RmcParser.parse(sentence("GPRMC,123519,A,4807.038,N,01131.000,E,022.4,084.4,230394,003.1,W")).shouldNotBeNull()

        rmc.utcMillisOfDay shouldBe (12 * 3600 + 35 * 60 + 19) * 1000
        rmc.date shouldBe LocalDate.of(2094, 3, 23)
        rmc.speedMps.shouldNotBeNull() shouldBe (22.4 * knot plusOrMinus 1e-12)
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "GNRMC,101530.20,A,4025.0080000,N,00342.2228000,W,0.025,123.4,321026,,,R,V", // día imposible
            "GNRMC,101530.20,A,4025.0080000,N,00342.2228000,W,abc,123.4,151026,,,R,V", // velocidad no numérica
            "GNRMC,256030.20,A,4025.0080000,N,00342.2228000,W,0.025,123.4,151026,,,R,V", // hora imposible
            "GNRMC,101530.20,A,4025.0080000,N,00342.2228000,W,0.025,123.4,1510,,,R,V", // fecha cortada
        ],
    )
    fun `S003 RF-7 una RMC con un campo que no se puede interpretar se descarta`(body: String) {
        RmcParser.parse(sentence(body)).shouldBeNull()
    }

    @Test
    fun `S003 RF-4 una VTG da el rumbo y la velocidad en kilometros por hora`() {
        val vtg = VtgParser.parse(sentence("GNVTG,123.4,T,,M,0.025,N,0.046,K,R")).shouldNotBeNull()

        vtg.courseRad.shouldNotBeNull() shouldBe (Math.toRadians(123.4) plusOrMinus 1e-12)
        vtg.speedMps.shouldNotBeNull() shouldBe (0.046 / 3.6 plusOrMinus 1e-12)
    }

    @Test
    fun `S003 RF-4 una VTG sin kilometros por hora usa los nudos y sin rumbo lo deja vacio`() {
        val vtg = VtgParser.parse(sentence("GPVTG,,T,,M,5.5,N,,K")).shouldNotBeNull()

        vtg.courseRad.shouldBeNull()
        vtg.speedMps.shouldNotBeNull() shouldBe (5.5 * knot plusOrMinus 1e-12)
    }

    @Test
    fun `S003 RF-7 una VTG con un campo que no se puede interpretar se descarta`() {
        VtgParser.parse(sentence("GNVTG,12x.4,T,,M,0.025,N,0.046,K,R")).shouldBeNull()
    }

    @Test
    fun `S003 RF-3 una ZDA da la fecha y la hora UTC`() {
        val zda = ZdaParser.parse(sentence("GNZDA,101530.20,15,10,2026,00,00")).shouldNotBeNull()

        zda.utcMillisOfDay shouldBe 36_930_200
        zda.date shouldBe LocalDate.of(2026, 10, 15)
    }

    @ParameterizedTest
    @ValueSource(strings = ["GNZDA,,,,,,", "GNZDA,101530.20,15,13,2026,00,00", "GNZDA,101530.20,1x,10,2026,00,00"])
    fun `S003 RF-7 una ZDA sin fecha o con un campo imposible no da fecha`(body: String) {
        ZdaParser.parse(sentence(body)).shouldBeNull()
    }

    @Test
    fun `S003 RF-3 otros tipos de frase no se interpretan como RMC, VTG ni ZDA`() {
        val gga = sentence("GNGGA,101530.20,4025.0080000,N,00342.2228000,W,4,24,0.6,650.123,M,51.234,M,1.0,0001")

        RmcParser.parse(gga).shouldBeNull()
        VtgParser.parse(gga).shouldBeNull()
        ZdaParser.parse(gga).shouldBeNull()
    }

    @ParameterizedTest
    @CsvSource("000000,0", "000000.5,500", "101530.20,36930200", "235959.99,86399990", "120000.123,43200123")
    fun `S003 RF-3 la hora NMEA se convierte en milisegundos del dia`(text: String, millis: Int) {
        NmeaTime.parse(text) shouldBe millis
    }

    @ParameterizedTest
    @ValueSource(strings = ["", "1015", "246000", "106000", "101560", "10153a.20"])
    fun `S003 RF-7 una hora NMEA vacia o imposible no se convierte`(text: String) {
        NmeaTime.parse(text).shouldBeNull()
    }

    @ParameterizedTest
    @CsvSource("GP,GPS", "GL,GLONASS", "GA,GALILEO", "GB,BEIDOU", "BD,BEIDOU", "GQ,QZSS", "GI,NAVIC", "GN,MULTIPLE", "XX,UNKNOWN")
    fun `S003 RF-1 cada identificador de constelacion se reconoce`(talker: String, constellation: Constellation) {
        Constellation.fromTalker(talker) shouldBe constellation
    }

    @ParameterizedTest
    @CsvSource("1,GPS", "2,GLONASS", "3,GALILEO", "4,BEIDOU", "5,QZSS", "6,NAVIC", "9,UNKNOWN")
    fun `S003 RF-1 el identificador de sistema de NMEA 4_11 se reconoce`(systemId: Int, constellation: Constellation) {
        Constellation.fromSystemId(systemId) shouldBe constellation
    }
}
