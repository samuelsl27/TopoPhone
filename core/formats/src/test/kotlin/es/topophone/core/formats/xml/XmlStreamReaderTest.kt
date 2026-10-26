package es.topophone.core.formats.xml

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.io.ByteArrayInputStream
import org.junit.jupiter.api.Test

/** El tokenizador XML por streaming con el que se lee un LandXML (S042/RF-2, RF-3). */
class XmlStreamReaderTest {

    private fun reader(xml: String, charset: java.nio.charset.Charset = Charsets.UTF_8) =
        XmlStreamReader(ByteArrayInputStream(xml.toByteArray(charset)))

    /** Todos los eventos como texto: `<a b=1>`, `"texto"`, `</a>`. */
    private fun events(r: XmlStreamReader): List<String> {
        val out = mutableListOf<String>()
        while (true) {
            when (r.next()) {
                XmlStreamReader.Event.START -> out += "<${r.name}>"
                XmlStreamReader.Event.END -> out += "</${r.name}>"
                XmlStreamReader.Event.TEXT -> out += "\"${r.text}\""
                XmlStreamReader.Event.EOF -> return out
            }
        }
    }

    @Test
    fun `S042 RF-2 lee elementos anidados con texto y atributos`() {
        val r = reader("<?xml version=\"1.0\"?>\n<a x=\"1\" y='dos'>\n  <b>hola</b>\n  <c/>\n</a>\n")

        r.next() shouldBe XmlStreamReader.Event.START
        r.name shouldBe "a"
        r.attribute("x") shouldBe "1"
        r.attribute("y") shouldBe "dos"
        r.attribute("z") shouldBe null
        r.depth shouldBe 1
        r.next() shouldBe XmlStreamReader.Event.START
        r.name shouldBe "b"
        r.depth shouldBe 2
        r.next() shouldBe XmlStreamReader.Event.TEXT
        r.text.toString() shouldBe "hola"
        r.next() shouldBe XmlStreamReader.Event.END
        r.name shouldBe "b"
        r.next() shouldBe XmlStreamReader.Event.START
        r.name shouldBe "c"
        r.next() shouldBe XmlStreamReader.Event.END
        r.next() shouldBe XmlStreamReader.Event.END
        r.name shouldBe "a"
        r.depth shouldBe 0
        r.next() shouldBe XmlStreamReader.Event.EOF
    }

    @Test
    fun `S042 RF-2 el texto de solo espacios entre elementos no se entrega`() {
        events(reader("<a>\n   <b>1</b>\n\t<b>2</b>\n</a>")) shouldBe listOf("<a>", "<b>", "\"1\"", "</b>", "<b>", "\"2\"", "</b>", "</a>")
    }

    @Test
    fun `S042 RF-2 resuelve las cinco entidades y las numericas`() {
        val r = reader("<a t=\"1 &lt; 2 &amp;&amp; 3\">&lt;&gt;&amp;&quot;&apos; &#65;&#x42;&#x1F600;</a>")

        r.next()
        r.attribute("t") shouldBe "1 < 2 && 3"
        r.next()
        r.text.toString() shouldBe "<>&\"' AB😀"
    }

    @Test
    fun `S042 RF-2 un CDATA entra como texto sin resolver nada`() {
        events(reader("<a><![CDATA[1 < 2 & ]] ]]]></a>")) shouldBe listOf("<a>", "\"1 < 2 & ]] ]\"", "</a>")
    }

    @Test
    fun `S042 RF-2 salta comentarios instrucciones y el DOCTYPE con su subconjunto interno`() {
        val xml = "<?xml version=\"1.0\"?><!DOCTYPE a [ <!ENTITY e \"x>y\"> ]><!-- antes -->" +
            "<a><?pi dato?><b>1</b><!-- - -- --></a><!-- fin -->"

        events(reader(xml)) shouldBe listOf("<a>", "<b>", "\"1\"", "</b>", "</a>")
    }

    @Test
    fun `S042 RF-2 ignora los prefijos de espacio de nombres`() {
        val r = reader("<l:LandXML xmlns:l=\"http://x\" l:version=\"1.2\"><l:Surfaces/></l:LandXML>")

        events(r) shouldBe listOf("<LandXML>", "<Surfaces>", "</Surfaces>", "</LandXML>")
        reader("<l:A l:v=\"3\"/>").also { it.next() }.attribute("v") shouldBe "3"
    }

    @Test
    fun `S042 RF-2 un BOM de UTF-8 se salta y UTF-16 con BOM se lee`() {
        val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
        events(XmlStreamReader(ByteArrayInputStream(bom + "<a>ñ</a>".toByteArray()))) shouldBe listOf("<a>", "\"ñ\"", "</a>")
        val utf16 = byteArrayOf(0xFE.toByte(), 0xFF.toByte()) + "<a>ñ</a>".toByteArray(Charsets.UTF_16BE)
        events(XmlStreamReader(ByteArrayInputStream(utf16))) shouldBe listOf("<a>", "\"ñ\"", "</a>")
    }

    @Test
    fun `S042 RF-2 usa la codificacion declarada y UTF-8 si no hay ninguna`() {
        val latin = "<?xml version=\"1.0\" encoding=\"ISO-8859-1\"?><a>señal</a>"
        events(reader(latin, Charsets.ISO_8859_1)) shouldBe listOf("<a>", "\"señal\"", "</a>")
        val windows = "<?xml version='1.0' encoding='windows-1252'?><a>€</a>"
        events(reader(windows, charset("windows-1252"))) shouldBe listOf("<a>", "\"€\"", "</a>")
        events(reader("<a>señal</a>")) shouldBe listOf("<a>", "\"señal\"", "</a>")
    }

    @Test
    fun `S042 RF-3 una codificacion que no existe se rechaza`() {
        shouldThrow<XmlFormatException> { reader("<?xml version=\"1.0\" encoding=\"klingon-9\"?><a/>") }.message shouldContain "klingon-9"
    }

    @Test
    fun `S042 RF-3 un fichero que no es XML o esta vacio se rechaza`() {
        shouldThrow<XmlFormatException> { events(reader("")) }
        shouldThrow<XmlFormatException> { events(reader("   \n  ")) }
        shouldThrow<XmlFormatException> { events(reader("hola, esto no es XML")) }
        shouldThrow<XmlFormatException> { events(reader("{\"json\": true}")) }
        shouldThrow<XmlFormatException> { events(XmlStreamReader(ByteArrayInputStream(ByteArray(300) { (it * 7).toByte() }))) }
    }

    @Test
    fun `S042 RF-3 un XML cortado dice que falta cerrar y en que linea`() {
        val e = shouldThrow<XmlFormatException> { events(reader("<a>\n<b>\n1</b>\n<c>")) }

        e.message shouldContain "<c>"
        e.line shouldBe 4
    }

    @Test
    fun `S042 RF-3 una etiqueta de cierre que no cierra lo abierto se rechaza`() {
        shouldThrow<XmlFormatException> { events(reader("<a><b></a></b>")) }.message shouldContain "</a>"
        shouldThrow<XmlFormatException> { events(reader("<a></a></b>")) }
    }

    @Test
    fun `S042 RF-3 un XML mal formado en otros sitios se rechaza`() {
        shouldThrow<XmlFormatException> { events(reader("<a x=1/>")) }
        shouldThrow<XmlFormatException> { events(reader("<a x=\"1/>")) }
        shouldThrow<XmlFormatException> { events(reader("<a>&nada;</a>")) }
        shouldThrow<XmlFormatException> { events(reader("<a>&#xZZ;</a>")) }
        shouldThrow<XmlFormatException> { events(reader("<a/><b/>")) }
        shouldThrow<XmlFormatException> { events(reader("<a/>texto")) }
        shouldThrow<XmlFormatException> { events(reader("<a x=\"<\"/>")) }
        shouldThrow<XmlFormatException> { events(reader("<a><!- mal --></a>")) }
    }

    @Test
    fun `S042 RF-2 skipElement salta un elemento entero con lo que contiene`() {
        val r = reader("<a><skip><x><y/>texto</x></skip><b>1</b></a>")

        r.next()
        r.next()
        r.name shouldBe "skip"
        r.skipElement()
        r.depth shouldBe 1
        r.next() shouldBe XmlStreamReader.Event.START
        r.name shouldBe "b"
    }

    @Test
    fun `S042 RF-2 skipElement tambien salta un elemento vacio`() {
        val r = reader("<a><e/><b/></a>")

        r.next()
        r.next()
        r.skipElement()
        r.next()
        r.name shouldBe "b"
    }

    @Test
    fun `S042 RF-2 un fichero grande se lee por flujo sin quedarse sin memoria`() {
        val rows = 300_000
        val stream = object : java.io.InputStream() {
            private val head = "<a>".toByteArray()
            private val tail = "</a>".toByteArray()
            private val row = "<p id=\"1\">4474257.38189 440290.45805 600.00000</p>\n".toByteArray()
            private var index = 0L
            private val total = head.size + rows.toLong() * row.size + tail.size

            override fun read(): Int {
                if (index >= total) return -1
                val i = index++
                return when {
                    i < head.size -> head[i.toInt()].toInt()
                    i < total - tail.size -> row[((i - head.size) % row.size).toInt()].toInt()
                    else -> tail[(i - (total - tail.size)).toInt()].toInt()
                }
            }
        }
        val r = XmlStreamReader(stream)
        var starts = 0
        while (true) {
            when (r.next()) {
                XmlStreamReader.Event.START -> starts++
                XmlStreamReader.Event.EOF -> break
                else -> Unit
            }
        }

        starts shouldBe rows + 1
    }
}
