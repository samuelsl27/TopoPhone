package es.topophone.core.model.format

import es.topophone.core.model.project.AngularUnit
import io.kotest.matchers.shouldBe
import java.util.Locale
import org.junit.jupiter.api.Test

/** El azimut de COGO en pantalla, con la resolución del cálculo: 0,01″ y 0,000001° (S038/RF-6, RNF-T9). */
class AzimuthFormatTest {

    private val es = Locale.forLanguageTag("es-ES")

    @Test
    fun `S038 RF-6 en DMS el azimut lleva dos decimales de segundo y la coma del idioma`() {
        val rad = Math.toRadians(123.0 + 45.0 / 60 + 6.78 / 3600)

        AngleFormat.azimuth(rad, AngularUnit.DMS, es) shouldBe "123° 45′ 06,78″"
        AngleFormat.azimuth(rad, AngularUnit.DMS, Locale.US) shouldBe "123° 45′ 06.78″"
    }

    @Test
    fun `S038 RF-6 en grados decimales lleva seis decimales y en gon seis`() {
        AngleFormat.azimuth(Math.toRadians(45.1234567), AngularUnit.DECIMAL_DEGREES, es) shouldBe "45,123457°"
        AngleFormat.azimuth(Math.toRadians(90.0), AngularUnit.GON, es) shouldBe "100,000000 gon"
    }

    @Test
    fun `S038 RF-6 el cero y el medio giro se escriben tal cual`() {
        AngleFormat.azimuth(0.0, AngularUnit.DMS, es) shouldBe "0° 00′ 00,00″"
        AngleFormat.azimuth(Math.PI, AngularUnit.DECIMAL_DEGREES, es) shouldBe "180,000000°"
    }
}
