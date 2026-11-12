package es.topophone.core.formats.kml.export

import io.kotest.matchers.shouldBe
import java.util.Locale
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class KmlNumberTest {

    @Test
    fun `S031 RF-24 la longitud y la latitud llevan siempre 9 decimales`() {
        KmlNumber.coordinate(-3.7038) shouldBe "-3.703800000"
        KmlNumber.coordinate(40.0) shouldBe "40.000000000"
        KmlNumber.coordinate(0.5) shouldBe "0.500000000"
        KmlNumber.coordinate(-16.25) shouldBe "-16.250000000"
    }

    @Test
    fun `S031 RF-24 se redondea el valor exacto del double a la mitad par`() {
        KmlNumber.coordinate(40.416800000049) shouldBe "40.416800000"
        KmlNumber.coordinate(0.0009765625) shouldBe "0.000976562" // 2^-10 es exacto y está justo en la mitad: al par
        KmlNumber.coordinate(0.0029296875) shouldBe "0.002929688" // 3 · 2^-10, también en la mitad: al par
    }

    @Test
    fun `S031 RF-24 lo que se redondea a cero no lleva signo`() {
        KmlNumber.coordinate(-0.0) shouldBe "0.000000000"
        KmlNumber.coordinate(-1e-12) shouldBe "0.000000000"
        KmlNumber.coordinate(1e-12) shouldBe "0.000000000"
    }

    @Test
    fun `S031 RNF-T9 la configuracion regional no cambia los numeros`() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("es-ES"))
            KmlNumber.coordinate(-3.7038) shouldBe "-3.703800000"
            KmlNumber.height(646.377) shouldBe "646.3770"
            KmlNumber.plain(0.25) shouldBe "0.25"
            Locale.setDefault(Locale.forLanguageTag("ar-EG"))
            KmlNumber.coordinate(12.5) shouldBe "12.500000000"
        } finally {
            Locale.setDefault(previous)
        }
    }

    @Test
    fun `S031 RF-26 la altura lleva 4 decimales`() {
        KmlNumber.height(646.377) shouldBe "646.3770"
        KmlNumber.height(100.0) shouldBe "100.0000"
        KmlNumber.height(0.03125) shouldBe "0.0312" // 2^-5 es exacto y está justo en la mitad: al par
        KmlNumber.height(0.09375) shouldBe "0.0938"
        KmlNumber.height(-0.03125) shouldBe "-0.0312"
        KmlNumber.height(-0.00001) shouldBe "0.0000"
    }

    @Test
    fun `S031 RF-21 un valor se escribe sin redondear y sin notacion cientifica`() {
        KmlNumber.plain(440290.4580539461) shouldBe "440290.4580539461"
        KmlNumber.plain(100.0) shouldBe "100"
        KmlNumber.plain(0.00001) shouldBe "0.00001"
        KmlNumber.plain(1.5e20) shouldBe "150000000000000000000"
        KmlNumber.plain(-0.5) shouldBe "-0.5"
        KmlNumber.plain(0.1 + 0.2) shouldBe "0.30000000000000004"
        KmlNumber.plain(0.0) shouldBe "0"
    }

    @Test
    fun `S031 RF-21 un numero que no es finito no se escribe`() {
        assertThrows<IllegalArgumentException> { KmlNumber.coordinate(Double.NaN) }
        assertThrows<IllegalArgumentException> { KmlNumber.height(Double.POSITIVE_INFINITY) }
        assertThrows<IllegalArgumentException> { KmlNumber.plain(Double.NEGATIVE_INFINITY) }
    }

    @Test
    fun `S031 RF-24 lo que se escribe se vuelve a leer a menos de 1 mm`() {
        for (x in listOf(-3.703812345678, 40.416812345678, -16.2512345, 0.000123456789)) {
            val back = KmlNumber.coordinate(x).toDouble()

            (kotlin.math.abs(back - x) <= 5e-10 + 1e-15) shouldBe true
        }
    }
}
