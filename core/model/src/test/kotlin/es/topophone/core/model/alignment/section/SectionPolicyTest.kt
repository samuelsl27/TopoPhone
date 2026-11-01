package es.topophone.core.model.alignment.section

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** Reglas de los códigos y las pendientes de sección (S050/RF-2, RF-5, RF-7). */
class SectionPolicyTest {
    @Test
    fun `S050 RF-2 un codigo admite hasta 12 caracteres`() {
        SectionCode.isValid("BORDE_DER") shouldBe true
        SectionCode.isValid("A".repeat(12)) shouldBe true
        SectionCode.isValid("A".repeat(13)) shouldBe false
    }

    @Test
    fun `S050 RF-2 un codigo no es vacio ni lleva espacios en los extremos`() {
        SectionCode.isValid("") shouldBe false
        SectionCode.isValid("   ") shouldBe false
        SectionCode.isValid(" BD") shouldBe false
        SectionCode.isValid("B\nD") shouldBe false
    }

    @Test
    fun `S050 RF-5 los puntos sin nombre se llaman P1 P2 en orden`() {
        SectionCode.generated(0) shouldBe "P1"
        SectionCode.generated(2) shouldBe "P3"
    }

    @Test
    fun `S050 RF-7 una pendiente de modulo mayor de 100 por ciento no es valida`() {
        SectionSlope.isValid(1.0) shouldBe true
        SectionSlope.isValid(-1.0) shouldBe true
        SectionSlope.isValid(1.0000001) shouldBe false
        SectionSlope.isValid(Double.NaN) shouldBe false
        SectionSlope.isValid(Double.POSITIVE_INFINITY) shouldBe false
    }

    @Test
    fun `S050 RNF-T9 la pendiente se guarda en fraccion y se muestra en porcentaje`() {
        SectionSlope.fromPercent(2.0) shouldBe 0.02
        SectionSlope.toPercent(0.06) shouldBe 6.0
    }
}
