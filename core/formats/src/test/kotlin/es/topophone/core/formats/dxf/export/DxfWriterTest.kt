package es.topophone.core.formats.dxf.export

import io.kotest.matchers.shouldBe
import java.io.ByteArrayOutputStream
import org.junit.jupiter.api.Test

/** El texto del DXF R12: estructura, polilíneas 3D y Windows-1252 (S029/RF-3, RF-4, RF-15), contra el fichero que genera `tools`. */
class DxfWriterTest {
    private fun write(document: DxfDocument): Pair<String, Int> {
        val out = ByteArrayOutputStream()
        val replaced = DxfWriter.write(document, out)
        return String(out.toByteArray(), DxfCharset.CHARSET) to replaced
    }

    private val layers = listOf(
        DxfLayerDef("0", 7),
        DxfLayerDef("ARBOL", AciColor.nearest(0x008000)),
        DxfLayerDef("BORDE_1", AciColor.nearest(0xFF0000)),
        DxfLayerDef("SIN_CODIGO", 7),
        DxfLayerDef("NOMBRES", 7),
        DxfLayerDef("COTAS", 7),
        DxfLayerDef("DESCRIPCIONES", 7),
    )

    private val p1 = Triple(440000.1234567, 4474000.7654321, 650.1236)
    private val p2 = Triple(440010.5, 4474010.25, 0.0)
    private val p3 = Triple(440020.0, 4474000.0, 651.0)
    private val h = 0.5

    private fun point(layer: String, p: Triple<Double, Double, Double>) = DxfPointEntity(layer, p.first, p.second, p.third)

    private fun text(layer: String, p: Triple<Double, Double, Double>, dy: Double, text: String) =
        DxfTextEntity(layer, p.first + 0.5 * h, p.second + dy, p.third, h, text)

    private val referenceEntities: List<DxfEntity> = listOf(
        point("ARBOL", p1),
        point("BORDE_1", p2),
        point("SIN_CODIGO", p3),
        text("NOMBRES", p1, 0.5 * h, "P1"),
        text("COTAS", p1, -1.5 * h, "650.124"),
        text("DESCRIPCIONES", p1, -3 * h, "Roble"),
        text("NOMBRES", p2, 0.5 * h, "P2"),
        text("NOMBRES", p3, 0.5 * h, "P3"),
        text("COTAS", p3, -1.5 * h, "651.000"),
        text("DESCRIPCIONES", p3, -3 * h, "Esquina"),
        DxfPolylineEntity("BORDE_1", false, listOf(p1, p2, p3).map { DxfVertex(it.first, it.second, it.third) }),
        DxfPolylineEntity("BORDE_1", true, listOf(p1, p2, p3).map { DxfVertex(it.first, it.second, it.third) }),
    )

    @Test
    fun `S029 RF-3 RF-4 el fichero es byte a byte el de referencia de tools`() {
        val out = ByteArrayOutputStream()

        val replaced = DxfWriter.write(DxfDocument(DxfCoordinates.LOCAL, layers, referenceEntities), out)

        replaced shouldBe 0
        out.toByteArray().toList() shouldBe DxfExportFixtures.bytes("referencia-locales.dxf").toList()
    }

    @Test
    fun `S029 RF-4 un punto es un POINT en su capa con X Y y Z`() {
        val (text, _) = write(DxfDocument(DxfCoordinates.LOCAL, listOf(DxfLayerDef("0", 7)), listOf(DxfPointEntity("0", 1.0, 2.0, 3.0))))

        text.contains("  0\r\nPOINT\r\n  8\r\n0\r\n 10\r\n1.0000\r\n 20\r\n2.0000\r\n 30\r\n3.0000\r\n") shouldBe true
    }

    @Test
    fun `S029 RF-3 una polilinea abierta lleva el 8 y un poligono el 9 y cada una acaba en SEQEND`() {
        val v = listOf(DxfVertex(0.0, 0.0, 0.0), DxfVertex(1.0, 0.0, 0.0), DxfVertex(1.0, 1.0, 0.0))
        val (text, _) = write(
            DxfDocument(
                DxfCoordinates.LOCAL,
                listOf(DxfLayerDef("0", 7)),
                listOf(DxfPolylineEntity("0", false, v), DxfPolylineEntity("0", true, v)),
            ),
        )

        text.contains("POLYLINE\r\n  8\r\n0\r\n 66\r\n1\r\n 70\r\n8\r\n  0\r\nVERTEX") shouldBe true
        text.contains("POLYLINE\r\n  8\r\n0\r\n 66\r\n1\r\n 70\r\n9\r\n  0\r\nVERTEX") shouldBe true
        Regex("SEQEND").findAll(text).count() shouldBe 2
        Regex(" 70\r\n32\r\n").findAll(text).count() shouldBe 6
    }

    @Test
    fun `S029 RF-10 las coordenadas globales van con nueve decimales`() {
        val (text, _) = write(
            DxfDocument(
                DxfCoordinates.GLOBAL,
                listOf(DxfLayerDef("0", 7)),
                listOf(DxfPointEntity("0", -3.7037901234, 40.4167754321, 650.5)),
            ),
        )

        text.contains(" 10\r\n-3.703790123\r\n 20\r\n40.416775432\r\n 30\r\n650.500000000\r\n") shouldBe true
    }

    @Test
    fun `S029 RF-15 el fichero va en Windows-1252 y la enie y el euro caben`() {
        val out = ByteArrayOutputStream()
        val entity = DxfTextEntity("0", 0.0, 0.0, 0.0, 1.0, "Año 5€")

        val replaced = DxfWriter.write(DxfDocument(DxfCoordinates.LOCAL, listOf(DxfLayerDef("0", 7)), listOf(entity)), out)

        replaced shouldBe 0
        val bytes = out.toByteArray()
        val at = bytes.toList().windowed(3).indexOfFirst { it == listOf('A'.code.toByte(), 0xF1.toByte(), 'o'.code.toByte()) }
        (at >= 0) shouldBe true
        bytes.contains(0x80.toByte()) shouldBe true
    }

    @Test
    fun `S029 RF-15 lo que no cabe pasa a interrogacion y se cuenta`() {
        val entity = DxfTextEntity("0", 0.0, 0.0, 0.0, 1.0, "日本 ok 😀")

        val (text, replaced) = write(DxfDocument(DxfCoordinates.LOCAL, listOf(DxfLayerDef("0", 7)), listOf(entity)))

        replaced shouldBe 3
        text.contains("  1\r\n?? ok ?\r\n") shouldBe true
    }

    @Test
    fun `S029 RF-15 un salto de linea dentro de un texto no parte los pares`() {
        val entity = DxfTextEntity("0", 0.0, 0.0, 0.0, 1.0, "uno\r\ndos\tx")

        val (text, replaced) = write(DxfDocument(DxfCoordinates.LOCAL, listOf(DxfLayerDef("0", 7)), listOf(entity)))

        replaced shouldBe 0
        text.contains("  1\r\nuno  dos x\r\n") shouldBe true
        (text.split("\r\n").size % 2) shouldBe 1 // pares completos y un último vacío
    }

    @Test
    fun `S029 RF-4 la tabla de capas cuenta las capas y lleva el color de cada una`() {
        val (text, _) = write(DxfDocument(DxfCoordinates.LOCAL, listOf(DxfLayerDef("0", 7), DxfLayerDef("ARBOL", 94)), emptyList()))

        text.contains("  2\r\nLAYER\r\n 70\r\n2\r\n") shouldBe true
        text.contains("  0\r\nLAYER\r\n  2\r\nARBOL\r\n 70\r\n0\r\n 62\r\n94\r\n  6\r\nCONTINUOUS\r\n") shouldBe true
        text.endsWith("  0\r\nEOF\r\n") shouldBe true
    }
}
