package es.topophone.core.formats.dxf.export

import es.topophone.core.formats.GroundTransform
import io.kotest.matchers.shouldBe
import java.io.ByteArrayOutputStream
import org.junit.jupiter.api.Test

/** El DXF de un proyecto en terreno: coordenadas de terreno y comentario de cabecera con el origen y el factor (S058/RF-14). */
class TerrainDxfTest {
    private val p1 = DxfTestData.point("P1", 440_300.0, 4_474_250.0, 650.0, null, "", ellipsoidal = 700.0)
    private val p2 = DxfTestData.point("P2", 440_400.0, 4_474_350.0, 651.0, null, "", ellipsoidal = 701.0)
    private val points = listOf(p1, p2)
    private val lines = listOf(DxfTestData.line("L1", false, null, p1, p2))
    private val ground = GroundTransform(440_000.0, 4_474_000.0, 1.0004)
    private val options = DxfExportOptions(labels = emptySet())

    private fun text(build: DxfBuild): String =
        ByteArrayOutputStream().also { DxfWriter.write(build.document, it) }.toByteArray().toString(Charsets.ISO_8859_1)

    private fun build(withGround: GroundTransform?, opts: DxfExportOptions = options) =
        DxfDocumentBuilder.build(points, lines, points, DxfTestData.project(), opts, withGround)

    @Test
    fun `S058 RF-14 sin plano el DXF es el de siempre y no lleva comentario`() {
        val result = build(null)

        result.document.comment shouldBe null
        text(result).startsWith("  0\r\nSECTION\r\n") shouldBe true
    }

    @Test
    fun `S058 RF-14 con plano el comentario es lo primero del fichero y lleva el origen y el factor`() {
        val file = text(build(ground))

        file.startsWith(
            "999\r\nTopoPhone ground coordinates origin easting=440000.0000 northing=4474000.0000 grid units=m factor=1.0004000000\r\n  0\r\nSECTION",
        ) shouldBe true
    }

    @Test
    fun `S058 RF-14 con plano los puntos y los vertices van en terreno y la cota no cambia`() {
        val file = text(build(ground))

        // 440000 + 1,0004·300 = 440300,12; 4474000 + 1,0004·250 = 4474250,1.
        file.contains(" 10\r\n440300.1200\r\n 20\r\n4474250.1000\r\n 30\r\n650.0000\r\n") shouldBe true
        // El vértice de la polilínea es el mismo punto.
        file.split(" 10\r\n440300.1200\r\n").size shouldBe 3
    }

    @Test
    fun `S058 RF-14 en globales no hay terreno ni comentario`() {
        val result = build(ground, options.copy(coordinates = DxfCoordinates.GLOBAL))

        result.document.comment shouldBe null
    }
}
