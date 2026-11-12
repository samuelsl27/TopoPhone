package es.topophone.core.formats.kml

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class KmlStyleTableTest {
    private val red = 0xFFFF0000.toInt()
    private val blue = 0xFF0000FF.toInt()
    private val green = 0xFF00FF00.toInt()
    private val yellow = 0xFFFFFF00.toInt()

    private fun table() = KmlStyleTable().apply {
        addStyle("ptRojo", KmlStyle(iconColor = red))
        addStyle("lnAzul", KmlStyle(lineColor = blue))
        addStyle("ambos", KmlStyle(iconColor = green, lineColor = yellow))
        addStyle("sinColor", KmlStyle())
        addStyleMap("mapa", KmlStyleMapNormal.Reference("#ptRojo"))
        addStyleMap("mapaInline", KmlStyleMapNormal.Inline(KmlStyle(iconColor = green)))
    }

    private fun point(inline: KmlStyle? = null, url: String? = null) = table().resolve(inline, url).colorFor(KmlColorTarget.POINT)

    private fun line(inline: KmlStyle? = null, url: String? = null) = table().resolve(inline, url).colorFor(KmlColorTarget.LINE)

    @Test
    fun `S031 RF-12 un punto toma el color de IconStyle y una linea el de LineStyle`() {
        point(url = "#ptRojo") shouldBe red
        line(url = "#lnAzul") shouldBe blue
    }

    @Test
    fun `S031 RF-12 un punto no toma el color de LineStyle ni una linea el de IconStyle`() {
        point(url = "#lnAzul") shouldBe null
        line(url = "#ptRojo") shouldBe null
    }

    @Test
    fun `S031 RF-12 un estilo con los dos da a cada tipo el suyo`() {
        point(url = "#ambos") shouldBe green
        line(url = "#ambos") shouldBe yellow
    }

    @Test
    fun `S031 RF-12 un StyleMap da su estilo normal, por referencia o en linea`() {
        point(url = "#mapa") shouldBe red
        point(url = "#mapaInline") shouldBe green
    }

    @Test
    fun `S031 RF-12 el estilo en la marca gana al styleUrl`() {
        point(inline = KmlStyle(iconColor = yellow), url = "#ptRojo") shouldBe yellow
    }

    @Test
    fun `S031 RF-12 un estilo en la marca sin color no deja pasar el del styleUrl`() {
        point(inline = KmlStyle(), url = "#ptRojo") shouldBe null
    }

    @Test
    fun `S031 RF-12 sin estilo, con un estilo sin color o que no existe no hay color`() {
        point() shouldBe null
        point(url = "#sinColor") shouldBe null
        point(url = "#noExiste") shouldBe null
        point(url = "") shouldBe null
    }

    @Test
    fun `S031 RF-12 un estilo de otro fichero no se resuelve`() {
        point(url = "otro.kml#ptRojo") shouldBe null
        point(url = "http://example.org/estilos.kml#ptRojo") shouldBe null
    }

    @Test
    fun `S031 RF-12 un estilo declarado despues de resolver la marca no se aplica a ella pero si a las siguientes`() {
        val table = KmlStyleTable()
        table.resolve(null, "#tarde").colorFor(KmlColorTarget.POINT) shouldBe null

        table.addStyle("tarde", KmlStyle(iconColor = red))

        table.resolve(null, "#tarde").colorFor(KmlColorTarget.POINT) shouldBe red
    }

    @Test
    fun `S031 RF-12 un StyleMap puede apuntar a un estilo declarado despues de el pero antes de la marca`() {
        val table = KmlStyleTable()
        table.addStyleMap("mapa", KmlStyleMapNormal.Reference("#destino"))
        table.addStyle("destino", KmlStyle(iconColor = blue))

        table.resolve(null, "#mapa").colorFor(KmlColorTarget.POINT) shouldBe blue
    }

    @Test
    fun `S031 RF-12 la ultima declaracion de un identificador gana`() {
        val table = KmlStyleTable()
        table.addStyle("x", KmlStyle(iconColor = red))
        table.addStyle("x", KmlStyle(iconColor = blue))

        table.resolve(null, "#x").colorFor(KmlColorTarget.POINT) shouldBe blue
    }

    @Test
    fun `S031 RF-12 una cadena de StyleMap que se muerde la cola no se cuelga`() {
        val table = KmlStyleTable()
        table.addStyleMap("a", KmlStyleMapNormal.Reference("#b"))
        table.addStyleMap("b", KmlStyleMapNormal.Reference("#a"))

        table.resolve(null, "#a").colorFor(KmlColorTarget.POINT) shouldBe null
    }

    @Test
    fun `S031 RF-12 una cadena de StyleMap de hasta cuatro saltos se resuelve y mas no`() {
        val table = KmlStyleTable()
        table.addStyle("fin", KmlStyle(iconColor = red))
        table.addStyleMap("m4", KmlStyleMapNormal.Reference("#fin"))
        table.addStyleMap("m3", KmlStyleMapNormal.Reference("#m4"))
        table.addStyleMap("m2", KmlStyleMapNormal.Reference("#m3"))
        table.addStyleMap("m1", KmlStyleMapNormal.Reference("#m2"))
        table.addStyleMap("m0", KmlStyleMapNormal.Reference("#m1"))

        table.resolve(null, "#m1").colorFor(KmlColorTarget.POINT) shouldBe red
        table.resolve(null, "#m0").colorFor(KmlColorTarget.POINT) shouldBe null
    }

    @Test
    fun `S031 RF-12 sin estilo el color es null`() {
        (null as KmlStyle?).colorFor(KmlColorTarget.LINE) shouldBe null
    }
}
