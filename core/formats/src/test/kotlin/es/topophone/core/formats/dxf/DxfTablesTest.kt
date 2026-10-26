package es.topophone.core.formats.dxf

import es.topophone.core.model.cad.CadColor
import io.kotest.matchers.shouldBe
import java.io.ByteArrayInputStream
import org.junit.jupiter.api.Test

/** Las capas del DXF con su color y su estado, la paleta ACI y el color de un elemento (S040/RF-24, RF-25, RF-33). */
class DxfTablesTest {

    /** Las capas de un fichero de ejemplo: cabecera, y luego la sección TABLES. */
    private fun tablesOf(name: String): DxfTables {
        val tags = DxfTagReader(ByteArrayInputStream(DxfFixtures.bytes(name)))
        tags.next() shouldBe DxfTag(0, "SECTION")
        tags.next() shouldBe DxfTag(2, "HEADER")
        DxfHeaderParser.parse(tags)
        tags.next() shouldBe DxfTag(0, "SECTION")
        tags.next() shouldBe DxfTag(2, "TABLES")
        return DxfTables.parse(tags)
    }

    private fun color(text: String) = if (text == "AUTO") CadColor.AUTO_CONTRAST else CadColor(text.toLong(16).toInt())

    @Test
    fun `S040 RF-24 la paleta ACI da los colores conocidos de AutoCAD`() {
        val known = mapOf(
            1 to 0xFF0000, 2 to 0xFFFF00, 3 to 0x00FF00, 4 to 0x00FFFF, 5 to 0x0000FF, 6 to 0xFF00FF, 7 to 0xFFFFFF,
            8 to 0x414141, 9 to 0x808080, 10 to 0xFF0000, 11 to 0xFF7F7F, 12 to 0xCC0000, 13 to 0xCC6666, 14 to 0x990000,
            15 to 0x994C4C, 16 to 0x7F0000, 17 to 0x7F3F3F, 18 to 0x4C0000, 19 to 0x4C2626, 20 to 0xFF3F00, 21 to 0xFF9F7F,
            30 to 0xFF7F00, 40 to 0xFFBF00, 50 to 0xFFFF00, 60 to 0xBFFF00, 90 to 0x00FF00, 91 to 0x7FFF7F, 130 to 0x00FFFF,
            170 to 0x0000FF, 210 to 0xFF00FF, 240 to 0xFF003F, 250 to 0x333333, 251 to 0x505050, 252 to 0x696969,
            253 to 0x828282, 254 to 0xBEBEBE, 255 to 0xFFFFFF,
        )

        known.forEach { (index, rgb) -> AciPalette.rgb(index) shouldBe rgb }
    }

    @Test
    fun `S040 RF-24 BYBLOCK BYLAYER y los indices fuera de la paleta no tienen color propio`() {
        listOf(0, 256, -1, 257, 1000).forEach { AciPalette.rgb(it) shouldBe null }
    }

    @Test
    fun `S040 RF-25 el ACI 7 y el color verdadero blanco o negro se marcan para el contraste`() {
        DxfColors.aci(7) shouldBe CadColor.AUTO_CONTRAST
        DxfColors.trueColor(0xFFFFFF) shouldBe CadColor.AUTO_CONTRAST
        DxfColors.trueColor(0x000000) shouldBe CadColor.AUTO_CONTRAST
        DxfColors.trueColor(0x336699) shouldBe CadColor.rgb(0x336699)
        DxfColors.aci(1) shouldBe CadColor.rgb(0xFF0000)
        DxfColors.aci(250) shouldBe CadColor.rgb(0x333333)
    }

    @Test
    fun `S040 RF-33 una capa con color negativo esta apagada y una con el bit 1 esta congelada`() {
        DxfLayer("a", aci = -3, flags = 0, trueColor = null).hidden shouldBe true
        DxfLayer("b", aci = 5, flags = 1, trueColor = null).hidden shouldBe true
        DxfLayer("c", aci = 5, flags = 4, trueColor = null).hidden shouldBe false
        DxfLayer("d", aci = 5, flags = 0, trueColor = null).hidden shouldBe false
    }

    @Test
    fun `S040 RF-24 y RF-33 las capas del fichero de capas son las esperadas`() {
        val tables = tablesOf("capas.dxf")
        val expected = DxfFixtures.expected("esperado-capas.csv").filter { it["archivo"] == "capas.dxf" && it["tipo_capa"] == "LINES" }

        expected.size shouldBe 6
        expected.forEach { row ->
            val layer = tables.layer(row.getValue("capa"))!!
            layer.color shouldBe color(row.getValue("color"))
            layer.hidden shouldBe (row.getValue("visible") == "NO")
        }
    }

    @Test
    fun `S040 RF-24 el color de una capa con color verdadero manda sobre su ACI`() {
        tablesOf("capas.dxf").layer("Verdadera")!!.color shouldBe CadColor.rgb(0x336699)
    }

    @Test
    fun `S040 RF-2 los nombres con acentos salen bien en Windows-1252 y en UTF-8`() {
        listOf("r12.dxf", "r2000.dxf", "r2010.dxf", "r2018.dxf").forEach { name ->
            val tables = tablesOf(name)

            tables.layer("Acera ñandú")!!.color shouldBe CadColor.rgb(0x0000FF)
            tables.layer("Eje-Zanja")!!.color shouldBe CadColor.rgb(0xFF0000)
            tables.layer("0")!!.color shouldBe CadColor.AUTO_CONTRAST
            tables.layers.size shouldBe 5
        }
    }

    @Test
    fun `S040 RF-24 una capa que no esta en la tabla se pinta de contraste`() {
        tablesOf("r2000.dxf").layer("No existe") shouldBe null
        DxfTables(emptyMap()).colorOf("No existe") shouldBe CadColor.AUTO_CONTRAST
    }

    @Test
    fun `S040 RF-24 el color de un elemento es el propio, el de su capa o el del bloque`() {
        val red = CadColor.rgb(0xFF0000)
        val green = CadColor.rgb(0x00FF00)
        val insert = CadColor.rgb(0x0000FF)

        DxfColors.entity(aci = 2, trueColor = null, layerColor = red, insertColor = null) shouldBe CadColor.rgb(0xFFFF00)
        DxfColors.entity(aci = 256, trueColor = null, layerColor = red, insertColor = null) shouldBe red
        DxfColors.entity(aci = null, trueColor = null, layerColor = red, insertColor = null) shouldBe red
        DxfColors.entity(aci = 0, trueColor = null, layerColor = red, insertColor = insert) shouldBe insert
        DxfColors.entity(aci = 0, trueColor = null, layerColor = red, insertColor = null) shouldBe red
        DxfColors.entity(aci = 1, trueColor = 0x00AA55, layerColor = green, insertColor = null) shouldBe CadColor.rgb(0x00AA55)
        DxfColors.entity(aci = 7, trueColor = null, layerColor = red, insertColor = null) shouldBe CadColor.AUTO_CONTRAST
        DxfColors.entity(aci = -3, trueColor = null, layerColor = red, insertColor = null) shouldBe CadColor.rgb(0x00FF00)
    }

    @Test
    fun `S040 RF-24 una tabla de otro tipo y una cabecera sin capas no dan capas`() {
        val bytes = "  0\nTABLE\n  2\nLTYPE\n  0\nLTYPE\n  2\nCONTINUOUS\n  0\nENDTAB\n  0\nENDSEC\n".toByteArray()

        DxfTables.parse(DxfTagReader(ByteArrayInputStream(bytes))).layers shouldBe emptyMap()
    }
}
