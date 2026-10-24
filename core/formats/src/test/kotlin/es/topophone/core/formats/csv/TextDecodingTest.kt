package es.topophone.core.formats.csv

import es.topophone.core.model.point.ImportEncoding
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import java.io.ByteArrayInputStream
import org.junit.jupiter.api.Test

/** La codificación del fichero que se importa: UTF-8 con y sin BOM, Windows-1252, binario y vacío (S028/RF-2, RF-4). */
class TextDecodingTest {

    private fun probe(bytes: ByteArray, truncated: Boolean = false) = TextDecoding.probe(bytes, truncated)

    @Test
    fun `S028 RF-4 un fichero UTF-8 con BOM se lee como UTF-8 y se sabe que lleva BOM`() {
        val bytes = ImportFixtures.bytes("etrs89-utm30-utf8-bom-coma.csv")

        probe(bytes) shouldBe TextProbe.Text(ImportEncoding.UTF8, hasBom = true)
        TextDecoding.decode(bytes, ImportEncoding.UTF8) shouldStartWith "Name,Easting"
    }

    @Test
    fun `S028 RF-4 un fichero UTF-8 sin BOM y con acentos se lee como UTF-8`() {
        val bytes = ImportFixtures.bytes("etrs89-utm30-tabulador-sin-cabecera.txt")

        probe(bytes) shouldBe TextProbe.Text(ImportEncoding.UTF8, hasBom = false)
        TextDecoding.decode(bytes, ImportEncoding.UTF8) shouldContain "Árbol ñandú"
    }

    @Test
    fun `S028 RF-4 un fichero de Windows-1252 con acentos no es UTF-8 valido y se lee como Windows-1252`() {
        val bytes = ImportFixtures.bytes("etrs89-utm30-windows1252-puntoycoma.txt")

        probe(bytes) shouldBe TextProbe.Text(ImportEncoding.WINDOWS_1252, hasBom = false)
        val text = TextDecoding.decode(bytes, ImportEncoding.WINDOWS_1252)
        text shouldContain "Descripción"
        text shouldContain "Árbol ñandú"
        text shouldContain "Línea \"\"eje\"\""
    }

    @Test
    fun `S028 RF-4 un fichero solo con ASCII se toma como UTF-8`() {
        probe("a,b\r\n1,2\r\n".toByteArray(Charsets.US_ASCII)) shouldBe TextProbe.Text(ImportEncoding.UTF8, hasBom = false)
    }

    @Test
    fun `S028 RF-4 todos los ficheros de ejemplo se leen con la codificacion con la que se escribieron`() {
        ImportFixtures.samples.forEach { name ->
            val expected = if (name == "etrs89-utm30-windows1252-puntoycoma.txt") ImportEncoding.WINDOWS_1252 else ImportEncoding.UTF8
            (probe(ImportFixtures.bytes(name)) as TextProbe.Text).encoding shouldBe expected
        }
    }

    @Test
    fun `S028 RF-4 el CSV que escribe S012 se lee como UTF-8 sin BOM`() {
        probe(ImportFixtures.s012("todas_coma_punto.csv")) shouldBe TextProbe.Text(ImportEncoding.UTF8, hasBom = false)
    }

    @Test
    fun `S028 RF-2 un fichero vacio no es un fichero de puntos`() {
        probe(ByteArray(0)) shouldBe TextProbe.Empty
    }

    @Test
    fun `S028 RF-2 un fichero con bytes nulos es binario`() {
        probe(byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 0x0D, 0x0A, 0x1A, 0x0A, 0x00)) shouldBe
            TextProbe.Binary
        probe("a,b\u0000c".toByteArray()) shouldBe TextProbe.Binary
        // UTF-16 lleva un byte nulo por carácter: tampoco es un CSV legible.
        probe("a,b\r\n1,2\r\n".toByteArray(Charsets.UTF_16LE)) shouldBe TextProbe.Binary
    }

    @Test
    fun `S028 RF-4 una muestra cortada en mitad de un caracter de dos bytes sigue siendo UTF-8`() {
        val bytes = "Árbol ñandú".toByteArray(Charsets.UTF_8)
        val cut = bytes.copyOf(bytes.indexOfFirst { it == 0xC3.toByte() } + 1) // solo el primer byte de la Á

        probe(cut, truncated = true) shouldBe TextProbe.Text(ImportEncoding.UTF8, hasBom = false)
        probe(cut, truncated = false) shouldBe TextProbe.Text(ImportEncoding.WINDOWS_1252, hasBom = false)
    }

    @Test
    fun `S028 RF-4 una muestra cortada en mitad de un caracter de tres o cuatro bytes sigue siendo UTF-8`() {
        val euro = "€".toByteArray(Charsets.UTF_8) // E2 82 AC
        probe(euro.copyOf(1), truncated = true) shouldBe TextProbe.Text(ImportEncoding.UTF8, hasBom = false)
        probe(euro.copyOf(2), truncated = true) shouldBe TextProbe.Text(ImportEncoding.UTF8, hasBom = false)
        val emoji = "😀".toByteArray(Charsets.UTF_8) // F0 9F 98 80
        probe(emoji.copyOf(3), truncated = true) shouldBe TextProbe.Text(ImportEncoding.UTF8, hasBom = false)
    }

    @Test
    fun `S028 RF-4 un byte suelto no valido en medio del texto, aunque la muestra este cortada, es Windows-1252`() {
        probe(byteArrayOf('a'.code.toByte(), 0xE1.toByte(), ' '.code.toByte(), 'b'.code.toByte()), truncated = true) shouldBe
            TextProbe.Text(ImportEncoding.WINDOWS_1252, hasBom = false)
    }

    @Test
    fun `S028 RF-4 el BOM solo se quita al leer como UTF-8`() {
        val bytes = ImportFixtures.bytes("etrs89-utm30-utf8-bom-coma.csv")

        TextDecoding.decode(bytes, ImportEncoding.UTF8).first() shouldBe 'N'
        TextDecoding.decode(bytes, ImportEncoding.WINDOWS_1252).startsWith("ï»¿") shouldBe true
    }

    @Test
    fun `S028 RF-4 Windows-1252 lee los caracteres que no estan en Latin-1`() {
        TextDecoding.decode(byteArrayOf(0x80.toByte(), 0x93.toByte(), 0x94.toByte()), ImportEncoding.WINDOWS_1252) shouldBe "€“”"
    }

    @Test
    fun `S028 RF-4 un byte no valido leyendo como UTF-8 no rompe la lectura, se sustituye`() {
        TextDecoding.decode(byteArrayOf('a'.code.toByte(), 0xE1.toByte(), 'b'.code.toByte()), ImportEncoding.UTF8) shouldBe "a�b"
    }

    @Test
    fun `S028 RF-4 el lector por flujo da el mismo texto y quita el BOM`() {
        val bytes = ImportFixtures.bytes("etrs89-utm30-utf8-bom-coma.csv")

        TextDecoding.reader(ByteArrayInputStream(bytes), ImportEncoding.UTF8).readText() shouldBe
            TextDecoding.decode(bytes, ImportEncoding.UTF8)
        val windows = ImportFixtures.bytes("etrs89-utm30-windows1252-puntoycoma.txt")
        TextDecoding.reader(ByteArrayInputStream(windows), ImportEncoding.WINDOWS_1252).readText() shouldBe
            TextDecoding.decode(windows, ImportEncoding.WINDOWS_1252)
    }

    @Test
    fun `S028 RF-4 la muestra son los primeros 64 kB y dice si hay mas`() {
        val big = TextDecoding.readSample(ByteArrayInputStream(ByteArray(70_000) { 'a'.code.toByte() }))
        val small = TextDecoding.readSample(ByteArrayInputStream("a,b".toByteArray()))
        val exact = TextDecoding.readSample(ByteArrayInputStream(ByteArray(TextDecoding.SAMPLE_BYTES) { 'a'.code.toByte() }))

        big.bytes.size shouldBe TextDecoding.SAMPLE_BYTES
        big.truncated shouldBe true
        small.bytes.size shouldBe 3
        small.truncated shouldBe false
        exact.truncated shouldBe false
    }

    @Test
    fun `S028 RF-4 el juego de caracteres de cada codificacion`() {
        TextDecoding.charset(ImportEncoding.UTF8) shouldBe Charsets.UTF_8
        TextDecoding.charset(ImportEncoding.WINDOWS_1252).name() shouldBe "windows-1252"
    }
}
