package es.topophone.core.model.point

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** La lectura de un número con el signo decimal elegido (S028/RF-8, RF-23). */
class ImportNumberTest {

    private fun point(text: String) = ImportNumber.parse(text, ImportDecimal.POINT)

    private fun comma(text: String) = ImportNumber.parse(text, ImportDecimal.COMMA)

    @Test
    fun `S028 RF-8 lee un numero con punto o con coma segun el signo elegido`() {
        point("440297.988") shouldBe 440297.988
        comma("440297,988") shouldBe 440297.988
        point("-3.70371") shouldBe -3.70371
        comma("-3,70371") shouldBe -3.70371
    }

    @Test
    fun `S028 RF-8 lee enteros, con signo mas y con espacios alrededor`() {
        point("5") shouldBe 5.0
        point("+5") shouldBe 5.0
        point("0") shouldBe 0.0
        point("  12.5 ") shouldBe 12.5
        comma("\t7,25") shouldBe 7.25
    }

    @Test
    fun `S028 RF-8 acepta un signo decimal al principio o al final`() {
        point(".5") shouldBe 0.5
        point("5.") shouldBe 5.0
        comma(",5") shouldBe 0.5
    }

    @Test
    fun `S028 RF-23 el otro signo decimal no es un numero`() {
        point("1,5").shouldBeNull()
        comma("1.5").shouldBeNull()
    }

    @Test
    fun `S028 RF-23 un numero con separador de miles no es un numero`() {
        comma("1.234,56").shouldBeNull()
        point("1,234.56").shouldBeNull()
        comma("440.290,458").shouldBeNull()
        point("1 000").shouldBeNull()
    }

    @Test
    fun `S028 RF-23 las letras, la notacion cientifica y lo vacio no son numeros`() {
        listOf("", "  ", "abc", "12abc", "1e3", "1E-2", "0x10", "NaN", "Infinity", "-", "+", ".", "--5", "1.2.3", "5-", "1-2").forEach {
            point(it).shouldBeNull()
        }
        comma("1,2,3").shouldBeNull()
    }

    @Test
    fun `S028 RF-23 solo valen los digitos ASCII`() {
        point("١٢٣").shouldBeNull() // dígitos arábigos
        point("１２３").shouldBeNull() // dígitos de ancho completo
    }

    @Test
    fun `S028 RF-23 el valor no se redondea`() {
        point("4474244.00123456789") shouldBe 4474244.00123456789
        point("0.000000001") shouldBe 1e-9
    }
}
