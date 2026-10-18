package es.topophone.core.model.format

import es.topophone.core.model.project.LinearUnit
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import java.util.Locale
import org.junit.jupiter.api.Test

/** Números escritos por el usuario: coma o punto decimal y separadores de miles (S011/RF-8, RF-17). */
class NumberInputTest {

    @Test
    fun `S011 RF-17 acepta coma o punto decimal y signo`() {
        NumberInput.parse("440297,988") shouldBe 440297.988
        NumberInput.parse("440297.988") shouldBe 440297.988
        NumberInput.parse("  -12,5 ") shouldBe -12.5
        NumberInput.parse("+3") shouldBe 3.0
        NumberInput.parse("0") shouldBe 0.0
        NumberInput.parse(",5") shouldBe 0.5
    }

    @Test
    fun `S011 RF-17 con los dos signos el ultimo es el decimal y el otro separa los miles`() {
        NumberInput.parse("4.474.244,001") shouldBe 4474244.001
        NumberInput.parse("4,474,244.001") shouldBe 4474244.001
        NumberInput.parse("440.297,988") shouldBe 440297.988
    }

    @Test
    fun `S011 RF-17 un mismo signo repetido o los espacios separan los miles`() {
        NumberInput.parse("4.474.244") shouldBe 4474244.0
        NumberInput.parse("1,234,567") shouldBe 1234567.0
        NumberInput.parse("4 474 244,001") shouldBe 4474244.001
        NumberInput.parse("4 474 244,001") shouldBe 4474244.001
    }

    @Test
    fun `S011 RF-8 lee lo que escribe la interfaz en cualquier idioma`() {
        for (locale in listOf(Locale.forLanguageTag("es-ES"), Locale.US)) {
            val text = LengthFormat.inUnit(4_474_244.0011, LinearUnit.METRE, locale)
            NumberInput.parse(text).shouldNotBeNull() shouldBe (4_474_244.001 plusOrMinus 1e-9)
        }
    }

    @Test
    fun `S011 RF-17 lo que no es un numero no vale`() {
        listOf("", "  ", "abc", "12-3", "1e5", "--1", "+", ".", "1,2,3.4.5", "1.234,5,6", "12.34.5", "1,23,456", "4.47.244")
            .forEach { NumberInput.parse(it).shouldBeNull() }
    }

    @Test
    fun `S011 RF-17 las longitudes pasan a metros desde la unidad del proyecto`() {
        LinearUnit.METRE.toMetres(12.5) shouldBe 12.5
        LinearUnit.INTERNATIONAL_FOOT.toMetres(100.0) shouldBe (30.48 plusOrMinus 1e-12)
        LinearUnit.US_SURVEY_FOOT.toMetres(3937.0) shouldBe (1200.0 plusOrMinus 1e-9)
    }
}
