package es.topophone.core.gnss.nmea

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** Entramado NMEA a partir del flujo de bytes del receptor (S002/RF-11). */
class NmeaFramerTest {

    private val gga = withChecksum("GNGGA,101530.20,4025.1234567,N,00342.7654321,W,4,24,0.6,650.123,M,51.234,M,1.0,0001")
    private val gst = withChecksum("GNGST,101530.20,0.010,0.012,0.008,45.0,0.009,0.011,0.020")

    private fun NmeaFramer.feedText(text: String) = feed(text.toByteArray(Charsets.US_ASCII))

    @Test
    fun `S002 RF-11 una frase con suma de control correcta se acepta con su talker, tipo y campos`() {
        val sentences = NmeaFramer().feedText("$gga\r\n")

        sentences shouldHaveSize 1
        with(sentences.single()) {
            talker shouldBe "GN"
            type shouldBe "GGA"
            fields.first() shouldBe "101530.20"
            fields.size shouldBe 14
            raw shouldBe gga
        }
    }

    @Test
    fun `S002 RF-11 una frase con suma de control incorrecta se descarta`() {
        val corrupted = gga.dropLast(2) + if (gga.endsWith("00")) "01" else "00"

        NmeaFramer().feedText("$corrupted\r\n").shouldBeEmpty()
    }

    @Test
    fun `S002 RF-11 la suma de control se acepta en minusculas`() {
        NmeaFramer().feedText(gga.lowercaseChecksum() + "\r\n") shouldHaveSize 1
    }

    @Test
    fun `S002 RF-11 una frase sin suma de control se descarta`() {
        NmeaFramer().feedText("\$GNGGA,101530.20,,,,,0,00,,,M,,M,,\r\n").shouldBeEmpty()
    }

    @Test
    fun `S002 RF-11 se aceptan otros talkers como GP y GL`() {
        val gp = withChecksum("GPGGA,101530.20,4025.1234567,N,00342.7654321,W,1,08,1.2,650.1,M,51.2,M,,")
        val gl = withChecksum("GLGSV,1,1,00")

        NmeaFramer().feedText("$gp\r\n$gl\r\n").map { it.talker to it.type } shouldBe listOf("GP" to "GGA", "GL" to "GSV")
    }

    @Test
    fun `S002 RF-11 una frase partida entre varios bloques se reconstruye`() {
        val framer = NmeaFramer()
        val bytes = "$gga\r\n".toByteArray(Charsets.US_ASCII)

        framer.feed(bytes.copyOfRange(0, 17)).shouldBeEmpty()
        framer.feed(bytes.copyOfRange(17, 40)).shouldBeEmpty()
        framer.feed(bytes.copyOfRange(40, bytes.size)).map { it.raw } shouldBe listOf(gga)
    }

    @Test
    fun `S002 RF-11 varias frases en un bloque y fin de linea solo con LF`() {
        NmeaFramer().feedText("$gga\n$gst\n").map { it.type } shouldBe listOf("GGA", "GST")
    }

    @Test
    fun `S002 RF-11 los datos binarios intercalados (RTCM) no impiden leer las frases siguientes`() {
        val rtcm = byteArrayOf(0xD3.toByte(), 0x00, 0x13, 0x3E, 0xD0.toByte(), 0x00, 0x03, 0x8A.toByte(), 0x24, 0x0A, 0x7F)
        val stream = "$gga\r\n".toByteArray() + rtcm + "$gst\r\n".toByteArray()

        NmeaFramer().feed(stream).map { it.type } shouldBe listOf("GGA", "GST")
    }

    @Test
    fun `S002 RF-11 una frase con bytes no ASCII en medio se descarta`() {
        val bytes = "$gga\r\n".toByteArray()
        bytes[20] = 0xC3.toByte()

        NmeaFramer().feed(bytes).shouldBeEmpty()
    }

    @Test
    fun `S002 RF-11 un dolar a mitad de frase empieza una frase nueva`() {
        NmeaFramer().feedText("\$GNGGA,101530.20,4025$gga\r\n").map { it.raw } shouldBe listOf(gga)
    }

    @Test
    fun `S002 RF-11 una linea demasiado larga se descarta sin bloquear las siguientes`() {
        val tooLong = "\$GNTXT," + "X".repeat(400)

        NmeaFramer().feedText("$tooLong\r\n$gga\r\n").map { it.type } shouldBe listOf("GGA")
    }

    @Test
    fun `S002 RF-11 la suma de control coincide con frases reales de referencia`() {
        // Frase de ejemplo del estándar NMEA 0183 (suma de control conocida *47).
        NmeaChecksum.isValid("\$GPGGA,123519,4807.038,N,01131.000,E,1,08,0.9,545.4,M,46.9,M,,*47") shouldBe true
        NmeaChecksum.isValid("\$GPGGA,123519,4807.038,N,01131.000,E,1,08,0.9,545.4,M,46.9,M,,*48") shouldBe false
    }

    private fun String.lowercaseChecksum() = substringBeforeLast('*') + "*" + substringAfterLast('*').lowercase()
}

/** Construye una frase NMEA completa calculando su suma de control. */
fun withChecksum(body: String): String = "$" + body + "*" + "%02X".format(NmeaChecksum.compute(body))
