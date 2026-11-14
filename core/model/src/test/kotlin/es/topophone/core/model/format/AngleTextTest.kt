package es.topophone.core.model.format

import es.topophone.core.model.project.AngularUnit
import es.topophone.core.model.project.LinearUnit
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import java.util.Random
import org.junit.jupiter.api.Test

/** Los valores del borrador de COGO vuelven a la pantalla como texto que se lee de nuevo igual (S038/RF-27). */
class AngleTextTest {

    @Test
    fun `S038 RF-27 grados decimales y gon salen como numero con punto y sin exponente`() {
        AngleText.of(Math.toRadians(45.5), AngularUnit.DECIMAL_DEGREES).toDouble() shouldBe (45.5 plusOrMinus 1e-9)
        AngleText.of(Math.PI / 2, AngularUnit.GON).toDouble() shouldBe (100.0 plusOrMinus 1e-9)
        AngleText.of(1e-12, AngularUnit.DECIMAL_DEGREES).contains('E') shouldBe false
    }

    @Test
    fun `S038 RF-27 en DMS sale grados, minutos y segundos separados por espacios`() {
        AngleText.of(Math.toRadians(40.0 + 25.0 / 60 + 7.24 / 3600), AngularUnit.DMS) shouldBe "40 25 7.24"
        AngleText.of(0.0, AngularUnit.DMS) shouldBe "0 0 0"
        AngleText.of(Math.toRadians(10.0 + 59.0 / 60 + 59.9999999 / 3600), AngularUnit.DMS) shouldBe "11 0 0"
    }

    @Test
    fun `S038 RF-27 el texto de cada unidad se lee de nuevo con el mismo valor`() {
        val random = Random(38)
        for (unit in AngularUnit.entries) {
            repeat(200) {
                val rad = random.nextDouble() * 2 * Math.PI * 0.999
                val text = AngleText.of(rad, unit)

                DirectionInput.parse(text, unit, DirectionKind.AZIMUTH)!! shouldBe (rad plusOrMinus 1e-9)
            }
        }
    }

    @Test
    fun `S038 RF-27 las longitudes vuelven a la unidad del proyecto sin perder digitos`() {
        LengthText.of(4_474_000.5, LinearUnit.METRE) shouldBe "4474000.5"
        LengthText.of(0.3048 * 100, LinearUnit.INTERNATIONAL_FOOT).toDouble() shouldBe (100.0 plusOrMinus 1e-9)
        LengthText.of(-1.25, LinearUnit.METRE) shouldBe "-1.25"
        DistanceInput.parseSigned(LengthText.of(12_345_678.123456, LinearUnit.METRE), LinearUnit.METRE)!! shouldBe
            (12_345_678.123456 plusOrMinus 1e-9)
        LengthText.of(1.0E-7, LinearUnit.METRE).contains('E') shouldBe false
    }
}
