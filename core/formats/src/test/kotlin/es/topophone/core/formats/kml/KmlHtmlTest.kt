package es.topophone.core.formats.kml

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class KmlHtmlTest {

    @Test
    fun `S031 RF-11 las etiquetas se quitan y queda el texto`() {
        KmlHtml.toText("<b>Hola</b> mundo") shouldBe "Hola mundo"
        KmlHtml.toText("<a href=\"http://x/y\">enlace</a><img src=\"f.jpg\"/>") shouldBe "enlace"
    }

    @Test
    fun `S031 RF-11 los saltos de linea de HTML son saltos de linea`() {
        KmlHtml.toText("Uno<br>Dos<br/>Tres<BR />Cuatro<br  />Cinco") shouldBe "Uno\nDos\nTres\nCuatro\nCinco"
        KmlHtml.toText("<p>Uno</p><p>Dos</p>") shouldBe "Uno\nDos"
        KmlHtml.toText("<div>A</div><ul><li>B</li><li>C</li></ul><table><tr><td>D</td></tr></table>") shouldBe "A\nB\nC\nD"
    }

    @Test
    fun `S031 RF-11 las entidades se resuelven despues de quitar las etiquetas`() {
        KmlHtml.toText("a &amp; b &lt; c &gt; d &quot;e&quot; &apos;f&apos;") shouldBe "a & b < c > d \"e\" 'f'"
        KmlHtml.toText("&lt;b&gt;negrita&lt;/b&gt;") shouldBe "<b>negrita</b>"
    }

    @Test
    fun `S031 RF-11 las entidades numericas y las de las letras del espanol se resuelven`() {
        KmlHtml.toText("&#241;and&#xFA; &eacute;&ntilde;&Ntilde;&uuml; &iquest;qu&eacute;?") shouldBe "ñandú éñÑü ¿qué?"
        KmlHtml.toText("5&nbsp;m") shouldBe "5 m"
        KmlHtml.toText("20&deg; &euro;") shouldBe "20° €"
    }

    @Test
    fun `S031 RF-11 una entidad que no se conoce se deja como esta`() {
        KmlHtml.toText("a &desconocida; b") shouldBe "a &desconocida; b"
        KmlHtml.toText("Tom & Jerry") shouldBe "Tom & Jerry"
        KmlHtml.toText("&#99999999999;") shouldBe "&#99999999999;"
    }

    @Test
    fun `S031 RF-11 se quitan las lineas vacias y los espacios de los extremos de cada linea`() {
        KmlHtml.toText("  hueco  \n\n  otra  ") shouldBe "hueco\notra"
        KmlHtml.toText("Uno<br><br><br>Dos") shouldBe "Uno\nDos"
        KmlHtml.toText("Uno\r\nDos\rTres") shouldBe "Uno\nDos\nTres"
    }

    @Test
    fun `S031 RF-11 una descripcion sin nada es el texto vacio`() {
        KmlHtml.toText("") shouldBe ""
        KmlHtml.toText("   \n ") shouldBe ""
        KmlHtml.toText("<br/><p></p>") shouldBe ""
    }

    @Test
    fun `S031 RF-11 el ejemplo de los ficheros de prueba da lo esperado`() {
        val raw = "<b>Poste</b> de <i>hormigón</i><br/>Línea 2<br>Línea 3 &amp; fin<img src=\"foto.jpg\"/>"

        KmlHtml.toText(raw) shouldBe "Poste de hormigón\nLínea 2\nLínea 3 & fin"
    }

    @Test
    fun `S031 RF-11 un texto sin etiquetas ni entidades no cambia`() {
        KmlHtml.toText("Tapa rota") shouldBe "Tapa rota"
    }

    @Test
    fun `S031 RF-11 un menor que suelto no se confunde con una etiqueta`() {
        KmlHtml.toText("a < b y c > d") shouldBe "a < b y c > d"
    }
}
