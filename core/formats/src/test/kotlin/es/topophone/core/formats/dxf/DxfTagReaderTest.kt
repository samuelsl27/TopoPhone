package es.topophone.core.formats.dxf

import es.topophone.core.model.project.LinearUnit
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import java.io.ByteArrayInputStream
import java.nio.charset.Charset
import java.util.Locale
import org.junit.jupiter.api.Test

/** Los pares código/valor por flujo, la codificación y la cabecera del DXF (S040/RF-2, RF-7, RNF-T9). */
class DxfTagReaderTest {

    private fun reader(bytes: ByteArray) = DxfTagReader(ByteArrayInputStream(bytes))

    private fun reader(name: String) = reader(DxfFixtures.bytes(name))

    private fun all(reader: DxfTagReader): List<DxfTag> = generateSequence { reader.next() }.toList()

    /** Salta hasta el final de la sección HEADER y devuelve la cabecera, como hará el lector completo. */
    private fun header(reader: DxfTagReader): DxfHeader {
        reader.next() shouldBe DxfTag(0, "SECTION")
        reader.next() shouldBe DxfTag(2, "HEADER")
        return DxfHeaderParser.parse(reader)
    }

    @Test
    fun `S040 RF-2 lee los pares de un fichero con CRLF`() {
        val tags = reader("r12.dxf")

        tags.next() shouldBe DxfTag(0, "SECTION")
        tags.next() shouldBe DxfTag(2, "HEADER")
        tags.next() shouldBe DxfTag(9, "\$ACADVER")
        tags.next() shouldBe DxfTag(1, "AC1009")
    }

    @Test
    fun `S040 RF-2 lee igual con LF o con CR solos y tolera el espacio del codigo`() {
        val lf = "  0\nSECTION\n  2\nHEADER\n".toByteArray()
        val cr = "  0\rSECTION\r  2\rHEADER\r".toByteArray()
        val crlf = "0\r\nSECTION\r\n2\r\nHEADER\r\n".toByteArray()

        listOf(lf, cr, crlf).forEach { all(reader(it)) shouldBe listOf(DxfTag(0, "SECTION"), DxfTag(2, "HEADER")) }
    }

    @Test
    fun `S040 RF-2 da tantos pares como lineas hay entre dos en los ficheros de ejemplo sin contar los comentarios`() {
        listOf("r12.dxf", "r2000.dxf", "r2010.dxf", "r2018.dxf", "bloques.dxf", "capas.dxf").forEach { name ->
            val lines = DxfFixtures.bytes(name).toString(Charsets.ISO_8859_1).lines().dropLastWhile { it.isEmpty() }
            val pairs = lines.chunked(2).filter { it.first().trim() != "999" }
            all(reader(name)).size shouldBe pairs.size
        }
    }

    @Test
    fun `S040 RNF-T9 un numero se lee con punto decimal aunque la region use coma`() {
        val before = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("es-ES"))

            DxfTag(10, "440000.5").double() shouldBe 440000.5
            DxfTag(10, " 4.5E-3 ").double() shouldBe 0.0045
            DxfTag(10, "-12").double() shouldBe -12.0
            DxfTag(10, "1,5").double() shouldBe null
            DxfTag(10, "abc").double() shouldBe null
            DxfTag(70, " 6 ").int() shouldBe 6
            DxfTag(70, "6.5").int() shouldBe null
        } finally {
            Locale.setDefault(before)
        }
    }

    @Test
    fun `S040 RF-2 un codigo que no es un numero da un error con su linea`() {
        val broken = "  0\nSECTION\nxx\nHEADER\n".toByteArray()
        val tags = reader(broken)
        tags.next() shouldBe DxfTag(0, "SECTION")

        val error = shouldThrow<DxfFormatException> { tags.next() }

        error.line shouldBe 3
    }

    @Test
    fun `S040 RF-13 un fichero cortado a mitad de un valor acaba sin error y se puede seguir pidiendo`() {
        val tags = reader("cortado.dxf")
        val read = all(tags)

        read.last() shouldBe DxfTag(10, "44")
        tags.next() shouldBe null
    }

    @Test
    fun `S040 RF-13 un codigo sin valor al final del fichero cuenta como cortado`() {
        val tags = reader("  0\nSECTION\n  2".toByteArray())

        all(tags) shouldBe listOf(DxfTag(0, "SECTION"))
        tags.truncated shouldBe true
    }

    @Test
    fun `S040 RF-2 los comentarios con el codigo 999 se saltan`() {
        val bytes = "999\nunos comentarios\n  0\nSECTION\n999\notro\n  2\nHEADER\n".toByteArray()

        all(reader(bytes)) shouldBe listOf(DxfTag(0, "SECTION"), DxfTag(2, "HEADER"))
    }

    @Test
    fun `S040 RF-2 se puede devolver un par para leerlo otra vez`() {
        val tags = reader("  0\nSECTION\n  2\nHEADER\n".toByteArray())

        val first = tags.next()!!
        tags.unread(first)

        tags.next() shouldBe first
        tags.next() shouldBe DxfTag(2, "HEADER")
    }

    @Test
    fun `S040 RF-2 R2000 se lee en Windows-1252 y R2010 y R2018 en UTF-8 aunque su pagina de codigos diga 1252`() {
        listOf("r12.dxf", "r2000.dxf", "r2010.dxf", "r2018.dxf").forEach { name ->
            val tags = reader(name)
            header(tags)
            val read = all(tags)

            read.contains(DxfTag(2, "Acera ñandú")) shouldBe true
        }
    }

    @Test
    fun `S040 RF-2 la cabecera da la version y la pagina de codigos`() {
        val h12 = header(reader("r12.dxf"))
        val h2018 = header(reader("r2018.dxf"))

        h12.version shouldBe "AC1009"
        h12.codePage shouldBe "ANSI_1252"
        h2018.version shouldBe "AC1032"
        h2018.usesUtf8 shouldBe true
        h12.usesUtf8 shouldBe false
        header(reader("r2000.dxf")).usesUtf8 shouldBe false
    }

    @Test
    fun `S040 RF-7 la unidad declarada sale de INSUNITS`() {
        header(reader("r12.dxf")).declaredUnit shouldBe null
        header(reader("r2000.dxf")).declaredUnit shouldBe LinearUnit.METRE
        header(reader("origen-etrs89-utm30-pies.dxf")).declaredUnit shouldBe LinearUnit.INTERNATIONAL_FOOT
        header(reader("origen-etrs89-utm30-pies-us.dxf")).declaredUnit shouldBe LinearUnit.US_SURVEY_FOOT
    }

    @Test
    fun `S040 RF-7 las unidades que la app no ofrece quedan sin declarar`() {
        listOf(0, 1, 3, 4, 5, 7, 14, 99).forEach { DxfHeader(insUnits = it).declaredUnit shouldBe null }
    }

    @Test
    fun `S040 RF-2 la extension de la cabecera no se usa y una cabecera sin variables sirve`() {
        val bytes = "  0\nSECTION\n  2\nHEADER\n  0\nENDSEC\n  0\nSECTION\n".toByteArray()
        val tags = reader(bytes)
        tags.next()
        tags.next()

        val header = DxfHeaderParser.parse(tags)

        header shouldBe DxfHeader()
        tags.next() shouldBe DxfTag(0, "SECTION")
    }

    @Test
    fun `S040 RF-2 las paginas de codigos conocidas y la de por defecto`() {
        DxfCodePage.charset("ANSI_1250") shouldBe Charset.forName("windows-1250")
        DxfCodePage.charset("ansi_1252") shouldBe Charset.forName("windows-1252")
        DxfCodePage.charset("ANSI_1251") shouldBe Charset.forName("windows-1251")
        DxfCodePage.charset("desconocida") shouldBe Charset.forName("windows-1252")
        DxfCodePage.charset(null) shouldBe Charset.forName("windows-1252")
    }
}
