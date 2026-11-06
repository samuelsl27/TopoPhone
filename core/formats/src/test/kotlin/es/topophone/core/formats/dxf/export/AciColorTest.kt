package es.topophone.core.formats.dxf.export

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** La tabla ACI de 255 colores y el color más cercano (S029/RF-6), contra la tabla que calcula `tools`. */
class AciColorTest {
    private val rows = DxfExportFixtures.csv("aci.csv")

    @Test
    fun `S029 RF-6 los 255 colores de la tabla son los de tools`() {
        rows.size shouldBe 255
        for (row in rows) {
            val index = row.getValue("indice").toInt()
            val expected = (row.getValue("r").toInt() shl 16) or (row.getValue("g").toInt() shl 8) or row.getValue("b").toInt()
            AciColor.rgb(index) shouldBe expected
        }
    }

    @Test
    fun `S029 RF-6 colores conocidos de la tabla publicada`() {
        AciColor.rgb(1) shouldBe 0xFF0000
        AciColor.rgb(7) shouldBe 0xFFFFFF
        AciColor.rgb(11) shouldBe 0xFF7F7F
        AciColor.rgb(21) shouldBe 0xFF9F7F
        AciColor.rgb(130) shouldBe 0x00FFFF
        AciColor.rgb(250) shouldBe 0x333333
    }

    @Test
    fun `S029 RF-6 el color exacto gana y en un empate el indice menor`() {
        AciColor.nearest(0xFF0000) shouldBe 1 // también es el 10: gana el menor
        AciColor.nearest(0xFFFFFF) shouldBe 7
        AciColor.nearest(0x333333) shouldBe 250
        AciColor.nearest(0x000000) shouldBe 18 // (76,0,0) y (0,0,76) empatan
    }

    @Test
    fun `S029 RF-6 el mas cercano coincide con el calculo de tools para colores repartidos`() {
        var checked = 0
        for (r in 0..255 step 51) {
            for (g in 0..255 step 51) {
                for (b in 0..255 step 51) {
                    val rgb = (r shl 16) or (g shl 8) or b
                    val best = rows.minWith(compareBy({ distance(it, r, g, b) }, { it.getValue("indice").toInt() }))
                    AciColor.nearest(rgb) shouldBe best.getValue("indice").toInt()
                    checked++
                }
            }
        }
        checked shouldBe 216
    }

    @Test
    fun `S029 RF-6 sin color el 7`() {
        AciColor.nearestOrDefault(null) shouldBe AciColor.DEFAULT
        AciColor.DEFAULT shouldBe 7
    }

    private fun distance(row: Map<String, String>, r: Int, g: Int, b: Int): Int {
        val dr = row.getValue("r").toInt() - r
        val dg = row.getValue("g").toInt() - g
        val db = row.getValue("b").toInt() - b
        return dr * dr + dg * dg + db * db
    }
}
