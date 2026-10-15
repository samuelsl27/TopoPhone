package es.topophone.core.gnss.nmea

import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

/** Frases GGA y GST (S002/RF-12, S002/RF-13, S002/RF-14, S002/RF-15). */
class GgaGstParserTest {

    private fun sentence(body: String) = NmeaFramer().feed((withChecksum(body) + "\r\n").toByteArray()).single()

    @Test
    fun `S002 RF-12 S002 RF-13 una GGA fija da posicion, calidad, satelites, HDOP y edad de correcciones`() {
        val fix = GgaParser.parse(sentence("GNGGA,101530.20,4025.1234567,N,00342.7654321,W,4,24,0.6,650.123,M,51.234,M,1.0,0001"))

        fix.shouldNotBeNull()
        fix.utcTime shouldBe "101530.20"
        fix.latitudeDeg shouldBe ((40 + 25.1234567 / 60) plusOrMinus 1e-12)
        fix.longitudeDeg shouldBe (-(3 + 42.7654321 / 60) plusOrMinus 1e-12)
        fix.status shouldBe SolutionStatus.FIX
        fix.satellites shouldBe 24
        fix.hdop shouldBe 0.6
        fix.altitudeM shouldBe 650.123
        fix.geoidSeparationM shouldBe 51.234
        fix.correctionAgeS shouldBe 1.0
    }

    @Test
    fun `S002 RF-12 latitud sur y longitud este dan signos correctos`() {
        val fix = GgaParser.parse(sentence("GPGGA,000001.00,3352.1234,S,15112.5678,E,1,10,0.9,10.0,M,20.0,M,,"))

        fix.shouldNotBeNull()
        (fix.latitudeDeg < 0) shouldBe true
        (fix.longitudeDeg > 0) shouldBe true
        fix.correctionAgeS.shouldBeNull()
    }

    @ParameterizedTest
    @CsvSource("0, NONE", "1, SINGLE", "2, DGPS", "4, FIX", "5, FLOAT", "6, ESTIMATED", "3, NONE", "7, NONE", "9, NONE")
    fun `S002 RF-12 cada indicador de calidad GGA se traduce a su estado de solucion`(quality: Int, expected: SolutionStatus) {
        SolutionStatus.fromGgaQuality(quality) shouldBe expected
    }

    @Test
    fun `S002 RF-12 los estados de solucion son Sin solucion, Autonoma, Diferencial, Flotante, Fija y Estimada`() {
        SolutionStatus.entries.map { it.name } shouldBe listOf("NONE", "SINGLE", "DGPS", "FLOAT", "FIX", "ESTIMATED")
    }

    @Test
    fun `S002 RF-12 una GGA sin solucion con campos vacios se interpreta sin posicion`() {
        val fix = GgaParser.parse(sentence("GNGGA,101530.20,,,,,0,00,99.99,,M,,M,,"))

        fix.shouldNotBeNull()
        fix.status shouldBe SolutionStatus.NONE
        fix.hasPosition shouldBe false
        fix.satellites shouldBe 0
    }

    @Test
    fun `S002 RF-13 una GGA con campos numericos corruptos no se acepta`() {
        GgaParser.parse(sentence("GNGGA,101530.20,40XX.12,N,00342.76,W,4,24,0.6,650.1,M,51.2,M,,")).shouldBeNull()
    }

    @Test
    fun `S002 RF-12 una frase que no es GGA no se interpreta como GGA`() {
        GgaParser.parse(sentence("GNGST,101530.20,0.010,0.012,0.008,45.0,0.009,0.011,0.020")).shouldBeNull()
    }

    @Test
    fun `S002 RF-14 S002 RF-15 la GST da la precision horizontal combinada y la vertical`() {
        val accuracy = GstParser.parse(sentence("GNGST,101530.20,0.010,0.012,0.008,45.0,0.009,0.012,0.020"))

        accuracy.shouldNotBeNull()
        accuracy.horizontalM shouldBe (0.015 plusOrMinus 1e-12) // √(0,009² + 0,012²)
        accuracy.verticalM shouldBe 0.020
    }

    @Test
    fun `S002 RF-14 una GST con desviaciones vacias no da precision`() {
        val accuracy = GstParser.parse(sentence("GNGST,101530.20,,,,,,,"))

        accuracy.shouldNotBeNull()
        accuracy.horizontalM.shouldBeNull()
        accuracy.verticalM.shouldBeNull()
    }
}
