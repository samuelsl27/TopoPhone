package es.topophone.core.model.alignment

import es.topophone.core.model.project.LinearUnit
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import java.util.Locale
import org.junit.jupiter.api.Test

/** El PK y el desplazamiento con el formato del proyecto (S049/RNF-T9, RNF-T8). */
class ChainageFormatTest {
    private val es = Locale.forLanguageTag("es-ES")
    private val en = Locale.US

    @Test
    fun `S049 RNF-T9 en metros el PK lleva kilometros y metros`() {
        ChainageFormat.format(1240.5, LinearUnit.METRE, es) shouldBe "1+240,50"
        ChainageFormat.format(1240.5, LinearUnit.METRE, en) shouldBe "1+240.50"
        ChainageFormat.format(0.0, LinearUnit.METRE, es) shouldBe "0+000,00"
        ChainageFormat.format(12.345, LinearUnit.METRE, es, decimals = 3) shouldBe "0+012,345"
    }

    @Test
    fun `S049 RNF-T9 el redondeo se hace una vez y arrastra al kilometro`() {
        ChainageFormat.format(999.996, LinearUnit.METRE, es) shouldBe "1+000,00"
        ChainageFormat.format(2999.994, LinearUnit.METRE, es) shouldBe "2+999,99"
    }

    @Test
    fun `S049 RNF-T9 un PK negativo lleva el signo delante`() {
        ChainageFormat.format(-12.5, LinearUnit.METRE, es) shouldBe "-0+012,50"
    }

    @Test
    fun `S049 RNF-T9 en pies la estacion son 100 pies`() {
        // 1240,5 m son 4069,88 ft: estación 40 + 69,88
        ChainageFormat.format(1240.5, LinearUnit.INTERNATIONAL_FOOT, es) shouldBe "40+69,88"
        ChainageFormat.format(100.0 * 0.3048, LinearUnit.INTERNATIONAL_FOOT, es) shouldBe "1+00,00"
    }

    @Test
    fun `S049 RNF-T8 el desplazamiento tiene lado ademas del signo`() {
        OffsetFormat.side(-3.5) shouldBe OffsetSide.LEFT
        OffsetFormat.side(3.5) shouldBe OffsetSide.RIGHT
        OffsetFormat.side(0.0) shouldBe OffsetSide.ON_AXIS
        OffsetFormat.side(0.0004) shouldBe OffsetSide.ON_AXIS
    }

    @Test
    fun `S049 RNF-T8 el desplazamiento se escribe en valor absoluto y en la unidad del proyecto`() {
        OffsetFormat.magnitude(-3.5, LinearUnit.METRE, es) shouldBe "3,500"
        OffsetFormat.magnitude(3.5, LinearUnit.METRE, en) shouldBe "3.500"
        OffsetFormat.magnitude(0.3048, LinearUnit.INTERNATIONAL_FOOT, es) shouldBe "1,000"
    }

    @Test
    fun `S049 RF-20 un PK se escribe con kilometros y metros o como un numero suelto`() {
        ChainageInput.parse("1+240,5", LinearUnit.METRE) shouldBe 1240.5
        ChainageInput.parse("1+240.5", LinearUnit.METRE) shouldBe 1240.5
        ChainageInput.parse("0+012", LinearUnit.METRE) shouldBe 12.0
        ChainageInput.parse("1240,5", LinearUnit.METRE) shouldBe 1240.5
        ChainageInput.parse("  12 ", LinearUnit.METRE) shouldBe 12.0
        ChainageInput.parse("-0+012,5", LinearUnit.METRE) shouldBe -12.5
    }

    @Test
    fun `S049 RF-20 en pies las estaciones son de 100 pies y se pasa a metros`() {
        ChainageInput.parse("12+34", LinearUnit.INTERNATIONAL_FOOT) shouldBe (1234 * 0.3048)
        ChainageInput.parse("1234", LinearUnit.INTERNATIONAL_FOOT) shouldBe (1234 * 0.3048)
    }

    @Test
    fun `S049 RF-20 lo que no es un PK da null`() {
        ChainageInput.parse("", LinearUnit.METRE) shouldBe null
        ChainageInput.parse("abc", LinearUnit.METRE) shouldBe null
        ChainageInput.parse("1+", LinearUnit.METRE) shouldBe null
        ChainageInput.parse("+5", LinearUnit.METRE) shouldBe 5.0
        ChainageInput.parse("1+-5", LinearUnit.METRE) shouldBe null
        ChainageInput.parse("1+2+3", LinearUnit.METRE) shouldBe null
    }

    @Test
    fun `S049 RF-20 el PK escrito con el formato del proyecto vuelve al mismo valor`() {
        for (value in listOf(0.0, 12.5, 999.99, 1240.5, 15_000.25)) {
            val text = ChainageFormat.format(value, LinearUnit.METRE, Locale.US)
            ChainageInput.parse(text, LinearUnit.METRE) shouldBe (value plusOrMinus 0.005)
        }
    }
}
