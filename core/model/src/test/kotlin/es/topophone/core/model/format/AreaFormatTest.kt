package es.topophone.core.model.format

import es.topophone.core.model.project.LinearUnit
import io.kotest.matchers.shouldBe
import java.util.Locale
import org.junit.jupiter.api.Test

/** Formato del área y de la pendiente (S026/RF-12, RNF-T9). */
class AreaFormatTest {

    private val es = Locale.forLanguageTag("es-ES")
    private val en = Locale.US

    @Test
    fun `S026 RF-12 area en metros cuadrados con la regional`() {
        AreaFormat.inUnit(1234.5678, LinearUnit.METRE, es) shouldBe "1.234,568"
        AreaFormat.inUnit(1234.5678, LinearUnit.METRE, en) shouldBe "1,234.568"
    }

    @Test
    fun `S026 RF-12 area en pies cuadrados internacionales`() {
        val expected = 100.0 / (0.3048 * 0.3048)
        AreaFormat.inUnit(100.0, LinearUnit.INTERNATIONAL_FOOT, en) shouldBe
            String.format(Locale.US, "%,.3f", expected)
    }

    @Test
    fun `S026 RF-12 area en pies cuadrados topograficos de EE UU`() {
        val foot = 1200.0 / 3937.0
        AreaFormat.inUnit(100.0, LinearUnit.US_SURVEY_FOOT, en) shouldBe
            String.format(Locale.US, "%,.3f", 100.0 / (foot * foot))
    }

    @Test
    fun `S026 RF-12 la unidad de area sale de la lineal`() {
        AreaFormat.unitSymbol(LinearUnit.METRE) shouldBe "m²"
        AreaFormat.unitSymbol(LinearUnit.INTERNATIONAL_FOOT) shouldBe "ft²"
        AreaFormat.unitSymbol(LinearUnit.US_SURVEY_FOOT) shouldBe "usft²"
    }

    @Test
    fun `S026 RF-12 la pendiente va siempre en porcentaje con 2 decimales`() {
        SlopeFormat.percent(12.3456, es) shouldBe "12,35"
        SlopeFormat.percent(-0.004, en) shouldBe "0.00"
        SlopeFormat.percent(-3.5, en) shouldBe "-3.50"
    }
}
