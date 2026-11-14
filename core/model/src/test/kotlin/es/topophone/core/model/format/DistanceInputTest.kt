package es.topophone.core.model.format

import es.topophone.core.model.project.LinearUnit
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** Distancias y desniveles escritos en la unidad lineal del proyecto (S038/RF-4, RF-11). */
class DistanceInputTest {

    @Test
    fun `S038 RF-4 una distancia con punto o coma decimal sale en metros`() {
        DistanceInput.parse("12.345", LinearUnit.METRE) shouldBe 12.345
        DistanceInput.parse("12,345", LinearUnit.METRE) shouldBe 12.345
        DistanceInput.parse("0", LinearUnit.METRE) shouldBe 0.0
    }

    @Test
    fun `S038 RF-4 una distancia en pies se pasa a metros`() {
        DistanceInput.parse("100", LinearUnit.INTERNATIONAL_FOOT)!! shouldBe (30.48 plusOrMinus 1e-12)
        DistanceInput.parse("100", LinearUnit.US_SURVEY_FOOT)!! shouldBe (100 * 1200.0 / 3937.0 plusOrMinus 1e-12)
    }

    @Test
    fun `S038 RF-4 una distancia negativa o que no es un numero no vale`() {
        DistanceInput.parse("-1", LinearUnit.METRE).shouldBeNull()
        DistanceInput.parse("", LinearUnit.METRE).shouldBeNull()
        DistanceInput.parse("abc", LinearUnit.METRE).shouldBeNull()
        DistanceInput.parse("12 m", LinearUnit.METRE).shouldBeNull()
        DistanceInput.parse("NaN", LinearUnit.METRE).shouldBeNull()
    }

    @Test
    fun `S038 RF-11 un desnivel admite signo`() {
        DistanceInput.parseSigned("-0,4", LinearUnit.METRE) shouldBe -0.4
        DistanceInput.parseSigned("0,4", LinearUnit.METRE) shouldBe 0.4
        DistanceInput.parseSigned("-10", LinearUnit.INTERNATIONAL_FOOT)!! shouldBe (-3.048 plusOrMinus 1e-12)
        DistanceInput.parseSigned("x", LinearUnit.METRE).shouldBeNull()
        DistanceInput.parseSigned("", LinearUnit.METRE).shouldBeNull()
    }
}
