package es.topophone.core.formats.dxf

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** Qué es un fichero por su contenido: DXF, DWG, DXF binario, vacío o ajeno, y el límite de tamaño (S040/RF-2 a RF-5). */
class DxfFileKindTest {

    private fun probe(name: String): DxfProbe {
        val size = DxfFixtures.size(name)
        val head = DxfFixtures.bytes(name).copyOf(minOf(size, DxfFileKind.HEAD_BYTES.toLong()).toInt())
        return DxfFileKind.probe(head, size)
    }

    @Test
    fun `S040 RF-2 los DXF de texto de R12 a R2018 se reconocen`() {
        listOf("r12.dxf", "r2000.dxf", "r2010.dxf", "r2018.dxf", "capas.dxf", "bloques.dxf", "cortado.dxf", "sin-elementos.dxf")
            .forEach { probe(it) shouldBe DxfProbe.Dxf }
    }

    @Test
    fun `S040 RF-2 un comentario antes de la primera seccion no impide reconocerlo`() {
        DxfFixtures.bytes("r2018.dxf").take(4).map { it.toInt().toChar() }.joinToString("") shouldBe "999\n"
        probe("r2018.dxf") shouldBe DxfProbe.Dxf
    }

    @Test
    fun `S040 RF-2 con BOM de UTF-8 o con otro fin de linea tambien`() {
        val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())

        DxfFileKind.probe(bom + "  0\nSECTION\n  2\nHEADER\n".toByteArray(), 100) shouldBe DxfProbe.Dxf
        DxfFileKind.probe("0\r\nSECTION\r\n2\r\nENTITIES\r\n".toByteArray(), 100) shouldBe DxfProbe.Dxf
        DxfFileKind.probe("0\rSECTION\r".toByteArray(), 100) shouldBe DxfProbe.Dxf
    }

    @Test
    fun `S040 RF-3 un DWG se reconoce por su contenido aunque se llame dxf`() {
        probe("dibujo.dwg") shouldBe DxfProbe.Dwg("AC1032")
        probe("dwg-como-dxf.dxf") shouldBe DxfProbe.Dwg("AC1027")
    }

    @Test
    fun `S040 RF-4 un DXF binario no se importa`() {
        probe("binario.dxf") shouldBe DxfProbe.BinaryDxf
    }

    @Test
    fun `S040 RF-4 un fichero vacio o que no es un DXF no se importa`() {
        probe("vacio.dxf") shouldBe DxfProbe.Empty
        probe("texto.dxf") shouldBe DxfProbe.NotDxf
        DxfFileKind.probe(byteArrayOf(1, 2, 3, 0, 5), 5) shouldBe DxfProbe.NotDxf
        DxfFileKind.probe("0\nSECTIONS\n".toByteArray(), 11) shouldBe DxfProbe.NotDxf
        DxfFileKind.probe("SECTION\n0\n".toByteArray(), 10) shouldBe DxfProbe.NotDxf
    }

    @Test
    fun `S040 RF-5 mas de 100 MB no se importa y justo 100 MB si`() {
        val head = DxfFixtures.bytes("r2000.dxf").copyOf(1024)

        DxfFileKind.MAX_BYTES shouldBe 100L * 1024 * 1024
        DxfFileKind.probe(head, DxfFileKind.MAX_BYTES) shouldBe DxfProbe.Dxf
        DxfFileKind.probe(head, DxfFileKind.MAX_BYTES + 1) shouldBe DxfProbe.TooLarge
    }

    @Test
    fun `S040 RF-3 un DWG de mas de 100 MB sigue siendo un DWG`() {
        val head = DxfFixtures.bytes("dibujo.dwg")

        DxfFileKind.probe(head, DxfFileKind.MAX_BYTES + 1) shouldBe DxfProbe.Dwg("AC1032")
    }

    @Test
    fun `S040 RF-3 una cabecera AC10 sin version de cuatro cifras no es un DWG`() {
        DxfFileKind.probe("AC10\u0000\u0000".toByteArray(), 6) shouldBe DxfProbe.NotDxf
    }
}
