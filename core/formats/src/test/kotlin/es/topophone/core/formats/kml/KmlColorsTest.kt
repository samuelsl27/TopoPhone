package es.topophone.core.formats.kml

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class KmlColorsTest {

    @Test
    fun `S031 RF-12 el color de un KML va de aabbggrr a ARGB opaco`() {
        KmlColors.parse("ff0000ff") shouldBe 0xFFFF0000.toInt() // rojo
        KmlColors.parse("ffff0000") shouldBe 0xFF0000FF.toInt() // azul
        KmlColors.parse("ff00ff00") shouldBe 0xFF00FF00.toInt() // verde
        KmlColors.parse("ff00ffff") shouldBe 0xFFFFFF00.toInt() // amarillo
    }

    @Test
    fun `S031 RF-12 la transparencia se ignora`() {
        KmlColors.parse("80112233") shouldBe 0xFF332211.toInt()
        KmlColors.parse("00112233") shouldBe 0xFF332211.toInt()
    }

    @Test
    fun `S031 RF-12 las mayusculas y los espacios de los extremos no importan`() {
        KmlColors.parse("FF0000FF") shouldBe 0xFFFF0000.toInt()
        KmlColors.parse("  ff0000ff\n") shouldBe 0xFFFF0000.toInt()
    }

    @Test
    fun `S031 RF-12 un color que no son ocho cifras hexadecimales no vale`() {
        KmlColors.parse("rojo") shouldBe null
        KmlColors.parse("ff0000") shouldBe null
        KmlColors.parse("ff0000ff00") shouldBe null
        KmlColors.parse("gg0000ff") shouldBe null
        KmlColors.parse("") shouldBe null
        KmlColors.parse(null) shouldBe null
    }

    @Test
    fun `S031 RF-23 un color ARGB se escribe como aabbggrr`() {
        KmlColors.toKml(0xFFFF0000.toInt()) shouldBe "ff0000ff"
        KmlColors.toKml(0xFF0000FF.toInt()) shouldBe "ffff0000"
        KmlColors.toKml(0xFF332211.toInt()) shouldBe "ff112233"
    }

    @Test
    fun `S031 RF-23 la transparencia que se pide sustituye a la del color`() {
        KmlColors.toKml(0xFFFF0000.toInt(), alpha = 0x80) shouldBe "800000ff"
        KmlColors.toKml(0x00123456, alpha = 0xFF) shouldBe "ff563412"
    }

    @Test
    fun `S031 RF-23 lo que se escribe se vuelve a leer igual`() {
        for (rgb in listOf(0x000000, 0xFFFFFF, 0x123456, 0xABCDEF, 0x010203)) {
            KmlColors.parse(KmlColors.toKml(0xFF000000.toInt() or rgb)) shouldBe (0xFF000000.toInt() or rgb)
        }
    }
}
