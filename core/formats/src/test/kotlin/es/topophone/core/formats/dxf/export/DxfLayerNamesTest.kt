package es.topophone.core.formats.dxf.export

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** El nombre de capa de cada código, limpio para el CAD y sin repetir (S029/RF-4, RF-5). */
class DxfLayerNamesTest {
    @Test
    fun `S029 RF-5 mayusculas y solo letras cifras guion bajo guion y dolar`() {
        val names = DxfLayerNames()

        names.layerOf("arbol") shouldBe "ARBOL"
        names.layerOf("borde-1") shouldBe "BORDE-1"
        names.layerOf("a\$b_c") shouldBe "A\$B_C"
        names.layerOf("farola 2/b") shouldBe "FAROLA_2_B"
    }

    @Test
    fun `S029 RF-5 los acentos se quitan y la enie es una N`() {
        val names = DxfLayerNames()

        names.layerOf("Árbol") shouldBe "ARBOL"
        names.layerOf("Cañería") shouldBe "CANERIA"
    }

    @Test
    fun `S029 RF-5 hasta 31 caracteres`() {
        val names = DxfLayerNames()

        names.layerOf("a".repeat(40)) shouldBe "A".repeat(31)
    }

    @Test
    fun `S029 RF-5 el mismo codigo es la misma capa y dos que dan el mismo nombre no se repiten`() {
        val names = DxfLayerNames()

        names.layerOf("Arbol") shouldBe "ARBOL"
        names.layerOf("Arbol") shouldBe "ARBOL"
        names.layerOf("Árbol") shouldBe "ARBOL_2"
        names.layerOf("ARBOL x") shouldBe "ARBOL_X"
        names.layerOf("arbol") shouldBe "ARBOL_3"
        names.layerOf("Árbol") shouldBe "ARBOL_2"
    }

    @Test
    fun `S029 RF-5 el sufijo no hace pasar de 31 caracteres`() {
        val names = DxfLayerNames()
        val long = "x".repeat(35)

        names.layerOf(long) shouldBe "X".repeat(31)
        names.layerOf(long + "y") shouldBe "X".repeat(29) + "_2"
    }

    @Test
    fun `S029 RF-4 sin codigo o sin ningun caracter valido va a SIN_CODIGO`() {
        val names = DxfLayerNames()

        names.layerOf(null) shouldBe "SIN_CODIGO"
        names.layerOf("") shouldBe "SIN_CODIGO"
        names.layerOf("   ") shouldBe "SIN_CODIGO"
        names.layerOf("***") shouldBe "SIN_CODIGO"
        names.layerOf("日本") shouldBe "SIN_CODIGO"
    }

    @Test
    fun `S029 RF-5 un codigo no puede usar el nombre de una capa de etiquetas ni la cero`() {
        val names = DxfLayerNames()

        names.layerOf("nombres") shouldBe "NOMBRES_2"
        names.layerOf("cotas") shouldBe "COTAS_2"
        names.layerOf("Descripciones") shouldBe "DESCRIPCIONES_2"
        names.layerOf("0") shouldBe "0_2"
        names.layerOf("sin_codigo") shouldBe "SIN_CODIGO_2"
    }

    @Test
    fun `S029 RF-4 las capas se dan en el orden en que aparecen`() {
        val names = DxfLayerNames()
        names.layerOf("B")
        names.layerOf(null)
        names.layerOf("A")
        names.layerOf("B")

        names.used shouldBe listOf("B", "SIN_CODIGO", "A")
    }
}
