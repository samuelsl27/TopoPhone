package es.topophone.core.model.format

import io.kotest.matchers.shouldBe
import java.util.Locale
import org.junit.jupiter.api.Test

/** Formatos de coordenadas y longitudes (S002/RF-34, S002/RF-35, S002/RF-36, S002/RF-38). */
class AngleFormatTest {

    private val es = Locale.forLanguageTag("es-ES")
    private val en = Locale.US

    @Test
    fun `S002 RF-34 latitud en grados, minutos y segundos con 5 decimales de segundo`() {
        AngleFormat.latitudeDms(40.418678912, es) shouldBe "40° 25′ 07,24408″ N"
        AngleFormat.latitudeDms(40.418678912, en) shouldBe "40° 25′ 07.24408″ N"
        AngleFormat.latitudeDms(-33.5, es) shouldBe "33° 30′ 00,00000″ S"
    }

    @Test
    fun `S002 RF-34 longitud con tres cifras de grados y hemisferio este u oeste`() {
        AngleFormat.longitudeDms(-3.703790123, es) shouldBe "003° 42′ 13,64444″ W"
        AngleFormat.longitudeDms(2.6502, es) shouldBe "002° 39′ 00,72000″ E"
    }

    @Test
    fun `S002 RF-34 el redondeo de los segundos arrastra a minutos y grados`() {
        AngleFormat.latitudeDms(40.999999999, es) shouldBe "41° 00′ 00,00000″ N"
        AngleFormat.longitudeDms(-0.999999999999, es) shouldBe "001° 00′ 00,00000″ W"
    }

    @Test
    fun `S002 RF-34 grados decimales con 9 decimales y signo`() {
        AngleFormat.decimalDegrees(40.418678912345, es) shouldBe "40,418678912"
        AngleFormat.decimalDegrees(-3.7037901234, en) shouldBe "-3.703790123"
    }

    @Test
    fun `S002 RF-35 S002 RF-36 S002 RF-38 longitudes en metros con 3 decimales y separador de miles`() {
        LengthFormat.metres(699.4234, es) shouldBe "699,423 m"
        LengthFormat.metres(4_474_123.4567, es) shouldBe "4.474.123,457 m"
        LengthFormat.metres(4_474_123.4567, en) shouldBe "4,474,123.457 m"
        LengthFormat.metres(-0.0004, es) shouldBe "0,000 m"
    }
}
