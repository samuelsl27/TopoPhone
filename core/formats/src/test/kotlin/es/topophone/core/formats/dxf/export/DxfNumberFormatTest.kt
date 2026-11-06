package es.topophone.core.formats.dxf.export

import io.kotest.matchers.shouldBe
import java.util.Locale
import org.junit.jupiter.api.Test

/** Los números del DXF: punto decimal, 4 decimales en unidades lineales y 9 en grados, sin la configuración regional (S029/RF-10). */
class DxfNumberFormatTest {
    @Test
    fun `S029 RF-10 cuatro decimales en la unidad lineal y nueve en grados`() {
        DxfNumberFormat.linear(440000.1234567) shouldBe "440000.1235"
        DxfNumberFormat.linear(4474000.0) shouldBe "4474000.0000"
        DxfNumberFormat.degrees(-3.703790123456) shouldBe "-3.703790123"
        DxfNumberFormat.degrees(40.0) shouldBe "40.000000000"
    }

    @Test
    fun `S029 RF-10 sin notacion cientifica ni miles aunque el movil este en aleman`() {
        val previous = Locale.getDefault()
        Locale.setDefault(Locale.GERMANY)
        try {
            DxfNumberFormat.linear(1234567.5) shouldBe "1234567.5000"
            DxfNumberFormat.linear(0.00001) shouldBe "0.0000"
            DxfNumberFormat.linear(1e10) shouldBe "10000000000.0000"
            DxfNumberFormat.degrees(1e-9) shouldBe "0.000000001"
        } finally {
            Locale.setDefault(previous)
        }
    }

    @Test
    fun `S029 RF-10 un cero con signo se escribe sin signo`() {
        DxfNumberFormat.linear(-0.00001) shouldBe "0.0000"
        DxfNumberFormat.linear(-0.0) shouldBe "0.0000"
        DxfNumberFormat.linear(-12.5) shouldBe "-12.5000"
    }

    @Test
    fun `S029 RF-10 el error de escribir es menor de 0 05 mm`() {
        val values = listOf(440000.12344999, 4474000.76545001, -0.00004999, 123456.78905)
        for (value in values) {
            (kotlin.math.abs(DxfNumberFormat.linear(value).toDouble() - value) < 5e-5 + 1e-9) shouldBe true
        }
    }

    @Test
    fun `S029 RF-7 la cota se escribe con tres decimales`() {
        DxfNumberFormat.elevation(650.1236) shouldBe "650.124"
        DxfNumberFormat.elevation(651.0) shouldBe "651.000"
    }

    @Test
    fun `S029 RF-10 un valor que no es finito no se puede escribir`() {
        runCatching { DxfNumberFormat.linear(Double.NaN) }.isFailure shouldBe true
        runCatching { DxfNumberFormat.degrees(Double.POSITIVE_INFINITY) }.isFailure shouldBe true
    }
}
