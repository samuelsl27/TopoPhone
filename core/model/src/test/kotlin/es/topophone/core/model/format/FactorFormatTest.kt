package es.topophone.core.model.format

import io.kotest.matchers.shouldBe
import java.util.Locale
import org.junit.jupiter.api.Test

/** Factores de la proyección en la interfaz (S008/RF-13, RF-15). */
class FactorFormatTest {

    @Test
    fun `S008 RF-13 RF-15 los factores se muestran con 8 decimales y el separador del idioma`() {
        FactorFormat.factor(0.999612345678, Locale.forLanguageTag("es-ES")) shouldBe "0,99961235"
        FactorFormat.factor(1.0002, Locale.US) shouldBe "1.00020000"
    }
}
