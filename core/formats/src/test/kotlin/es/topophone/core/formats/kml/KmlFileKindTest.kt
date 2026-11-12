package es.topophone.core.formats.kml

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class KmlFileKindTest {

    private fun kind(path: String) = KmlFileKind.of(KmlFixtures.head(path), KmlFixtures.file(path).length())

    private fun of(text: String) = KmlFileKind.of(text.toByteArray(), text.length.toLong())

    @Test
    fun `S031 RF-2 un KML se reconoce por su contenido en cualquiera de sus versiones`() {
        for (name in listOf("carpetas-anidadas.kml", "ns20-bom.kml", "ns22-ogc.kml", "sin-ns.kml", "geometrias.kml", "qgis/qgis-kml.kml")) {
            kind(name) shouldBe KmlFileKind.Kind.KML
        }
    }

    @Test
    fun `S031 RF-2 un KMZ se reconoce por su cabecera de ZIP`() {
        for (name in KmlFixtures.files.keys.filter { it.endsWith(".kmz") }) kind(name) shouldBe KmlFileKind.Kind.KMZ
        kind("qgis/qgis-libkml.kmz") shouldBe KmlFileKind.Kind.KMZ
    }

    @Test
    fun `S031 RF-3 un fichero vacio o de solo espacios es vacio`() {
        kind("vacio.kml") shouldBe KmlFileKind.Kind.EMPTY
        kind("espacios.kml") shouldBe KmlFileKind.Kind.EMPTY
        KmlFileKind.of(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()), 3) shouldBe KmlFileKind.Kind.EMPTY
    }

    @Test
    fun `S031 RF-3 un texto, un XML de otra raiz o un KML 3 no son un KML`() {
        kind("texto.kml") shouldBe KmlFileKind.Kind.NOT_KML
        kind("no-es-kml.kml") shouldBe KmlFileKind.Kind.NOT_KML
        kind("ns30.kml") shouldBe KmlFileKind.Kind.NOT_KML
    }

    @Test
    fun `S031 RF-3 un KML cortado sigue siendo un KML y se descubre al leerlo`() {
        kind("danado.kml") shouldBe KmlFileKind.Kind.KML
    }

    @Test
    fun `S031 RF-2 el resultado de cada fichero de ejemplo no contradice lo esperado`() {
        for ((name, row) in KmlFixtures.files) {
            val expected = row.getValue("resultado")
            val actual = kind(name)
            when {
                name.endsWith(".kmz") -> actual shouldBe KmlFileKind.Kind.KMZ
                expected == "EMPTY" -> actual shouldBe KmlFileKind.Kind.EMPTY
                expected == "NOT_KML" -> actual shouldBe KmlFileKind.Kind.NOT_KML
                else -> actual shouldBe KmlFileKind.Kind.KML
            }
        }
    }

    @Test
    fun `S031 RF-2 se salta la declaracion, los comentarios y el DOCTYPE antes de la raiz`() {
        val long = "<?xml version=\"1.0\"?>\n<!-- hecho a mano -->\n<!DOCTYPE kml>\n" +
            "<kml xmlns=\"http://www.opengis.net/kml/2.2\"><Document/></kml>"
        of(long) shouldBe KmlFileKind.Kind.KML
        of("  \n\t<kml><Document/></kml>") shouldBe KmlFileKind.Kind.KML
    }

    @Test
    fun `S031 RF-2 la raiz puede llevar prefijo y su espacio de nombres declarado con el prefijo`() {
        of("<k:kml xmlns:k=\"http://www.opengis.net/kml/2.2\"><k:Document/></k:kml>") shouldBe KmlFileKind.Kind.KML
        of("<k:kml xmlns:k=\"http://example.org/otro\"><k:Document/></k:kml>") shouldBe KmlFileKind.Kind.NOT_KML
    }

    @Test
    fun `S031 RF-2 la raiz distingue mayusculas y no acepta una raiz que solo empieza por kml`() {
        of("<KML xmlns=\"http://www.opengis.net/kml/2.2\"/>") shouldBe KmlFileKind.Kind.NOT_KML
        of("<kmlx><Document/></kmlx>") shouldBe KmlFileKind.Kind.NOT_KML
        of("<gpx><wpt/></gpx>") shouldBe KmlFileKind.Kind.NOT_KML
    }

    @Test
    fun `S031 RF-2 un KML en UTF-16 con BOM se reconoce`() {
        val text = "<?xml version=\"1.0\" encoding=\"UTF-16\"?><kml xmlns=\"http://www.opengis.net/kml/2.2\"><Document/></kml>"
        val bytes = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + text.toByteArray(Charsets.UTF_16LE)
        KmlFileKind.of(bytes, bytes.size.toLong()) shouldBe KmlFileKind.Kind.KML
    }

    @Test
    fun `S031 RF-2 un binario cualquiera no es un KML`() {
        val bytes = ByteArray(64) { it.toByte() }
        KmlFileKind.of(bytes, bytes.size.toLong()) shouldBe KmlFileKind.Kind.NOT_KML
    }

    @Test
    fun `S031 RF-2 el KML principal de un KMZ es doc kml o el primero de la raiz por orden alfabetico`() {
        for ((name, row) in KmlFixtures.files.filter { it.key.endsWith(".kmz") && it.value.getValue("resultado") == "OK" }) {
            KmlFileKind.mainEntry(KmlFixtures.zipNames(name)) shouldBe row.getValue("principal")
        }
    }

    @Test
    fun `S031 RF-3 un KMZ sin KML en la raiz no tiene principal`() {
        for (name in listOf("sin-kml.kmz", "kml-en-subcarpeta.kmz", "kmz-sin-entradas.kmz")) {
            KmlFileKind.mainEntry(KmlFixtures.zipNames(name)) shouldBe null
        }
    }

    @Test
    fun `S031 RF-2 los KML de las subcarpetas no cuentan y se elige sin distinguir mayusculas`() {
        KmlFileKind.mainEntry(listOf("b.kml", "sub/doc.kml", "a.kml")) shouldBe "a.kml"
        KmlFileKind.mainEntry(listOf("Zeta.KML", "alfa.kml")) shouldBe "alfa.kml"
        KmlFileKind.mainEntry(listOf("z.kml", "Doc.Kml")) shouldBe "Doc.Kml"
        KmlFileKind.mainEntry(listOf("files/foto.jpg", "icono.png")) shouldBe null
    }

    @Test
    fun `S031 RF-4 los limites son 100 MB y 500 MB`() {
        KmlFileKind.MAX_FILE_BYTES shouldBe 100L * 1024 * 1024
        KmlFileKind.MAX_UNCOMPRESSED_BYTES shouldBe 500L * 1024 * 1024
    }
}
