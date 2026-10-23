package es.topophone.core.model.format

import es.topophone.core.model.project.AngularUnit
import io.kotest.matchers.doubles.shouldBeLessThan
import io.kotest.matchers.shouldBe
import java.util.Locale
import org.junit.jupiter.api.Test

/** Formatos de coordenadas y longitudes (S002/RF-34, S002/RF-35, S002/RF-36, S002/RF-38, S008/RF-6, RF-14). */
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

    @Test
    fun `S008 RF-6 latitud y longitud del proyecto en sus unidades con resolucion de 0,1 mm o mejor`() {
        AngleFormat.latitude(40.4168, AngularUnit.DMS, es) shouldBe "40° 25′ 00,480000″ N"
        AngleFormat.longitude(-3.7038, AngularUnit.DMS, es) shouldBe "003° 42′ 13,680000″ W"
        AngleFormat.latitude(40.4168, AngularUnit.DECIMAL_DEGREES, es) shouldBe "40,4168000000°"
        AngleFormat.longitude(-3.7038, AngularUnit.DECIMAL_DEGREES, es) shouldBe "-3,7038000000°"
        AngleFormat.latitude(40.4168, AngularUnit.GON, es) shouldBe "44,9075555556 gon"
        AngleFormat.latitude(40.4168, AngularUnit.GON, en) shouldBe "44.9075555556 gon"
    }

    @Test
    fun `S008 RF-6 el ultimo digito vale menos de 0,1 mm en el terreno`() {
        val metresPerDegree = 111_320.0
        (1e-6 / 3600 * metresPerDegree) shouldBeLessThan 0.0001 // DMS: 6 decimales de segundo
        (1e-10 * metresPerDegree) shouldBeLessThan 0.0001 // DD: 10 decimales de grado
        (1e-10 * 0.9 * metresPerDegree) shouldBeLessThan 0.0001 // gon: 10 decimales
        AngleFormat.latitude(40.4168 + 1e-10, AngularUnit.DECIMAL_DEGREES, es) shouldBe "40,4168000001°"
    }

    @Test
    fun `S008 RF-14 la convergencia con su signo en grados, minutos y segundos, grados decimales o gon`() {
        val gamma = Math.toRadians(-2.5847081380079753)

        AngleFormat.convergence(gamma, AngularUnit.DMS, es) shouldBe "-2° 35′ 04,95″"
        AngleFormat.convergence(gamma, AngularUnit.DECIMAL_DEGREES, es) shouldBe "-2,584708°"
        AngleFormat.convergence(gamma, AngularUnit.GON, es) shouldBe "-2,871898 gon"
        AngleFormat.convergence(Math.toRadians(0.5), AngularUnit.DMS, es) shouldBe "0° 30′ 00,00″"
    }

    @Test
    fun `S016 RF-11 la inclinacion, la direccion y el rumbo en DMS, grados o gon`() {
        val direction = Math.toRadians(210.5)

        AngleFormat.direction(direction, AngularUnit.DMS, es) shouldBe "210° 30′ 00″"
        AngleFormat.direction(direction, AngularUnit.DECIMAL_DEGREES, es) shouldBe "210,500°"
        AngleFormat.direction(direction, AngularUnit.DECIMAL_DEGREES, en) shouldBe "210.500°"
        AngleFormat.direction(direction, AngularUnit.GON, es) shouldBe "233,889 gon"
        AngleFormat.direction(Math.toRadians(15.25), AngularUnit.DMS, es) shouldBe "15° 15′ 00″"
        AngleFormat.direction(Math.toRadians(359.9999), AngularUnit.DMS, es) shouldBe "360° 00′ 00″"
    }
}
